# ServiceTag 1.1.0 — A Local Automation API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a workstation a way to drive this phone's records without a screen, without a backend and without widening the app's attack surface by more than a socket that only exists while the owner is looking at it. Three parts, one MINOR release. **(1)** A loopback JSON API inside the app, alive only while a new Settings › Utilities screen — **Developer API** — is on top and the activity is resumed: 127.0.0.1, port 17337, a fresh 8-character pairing code per visit, `Authorization: Bearer <code>` on every request, one `/v1` endpoint per existing use case and nothing that bypasses one. **(2)** A workstation MCP server at `tools/servicetag-mcp/` that sets up its own `adb forward`, takes the code once through a `pair` tool, and exposes one tool per endpoint. **(3)** An *additive*, **conflict-safe** merge import in `:core`, to issue [#44](https://github.com/GonzRon/ServiceTag/issues/44)'s semantics: the archive is **planned before anything is written**, a row is INSERTed only when its id is absent, an id that is already here is a no-op when its canonical content matches and a **CONFLICT** when it does not, NFC payload identity is checked independently of the row id, and **any** unresolved conflict makes the apply refuse with the destination byte-for-byte unchanged. Ship it as `versionName` **1.1.0** / `versionCode` **12**.

**Architecture:** Six tasks, single lane, in order. **Task 1** is pure `:core` and is the release's hardest thinking: `BuildBackupMergePlan` reads the archive plus one consistent destination snapshot and produces a deterministic `MergePlan`; `ApplyBackupMergePlan` applies an accepted, conflict-free plan in one transaction and refuses anything else; `ImportBackupMerge` is the façade that runs both. The planner never chooses by `updatedAt`, never overwrites a local row — 1.1.0's merge **only inserts** — and checks every uniqueness constraint the schema actually has: **five** unique indices *and* the four aggregate child-row primary keys, plus every foreign key and the parent-tree cycle guard. Forty-two JVM cases: **thirty-three** on the pure planner, with no ports at all, and **nine** end to end over the in-memory fakes. **Task 2** is the API in `:app`, in a new package `com.loosecannon.servicetag.api` whose every file is plain JVM Kotlin with no Android import and no Compose — a hand-rolled loopback `ServerSocket` (the dependency decision is argued below), a wire parser with hard ceilings, a router that authenticates and maps paths to handlers, and handlers that take the same `AppGraph` members the view models take, so `FakeGraph` builds them and `:app:testDebugUnitTest` runs the whole thing against a real socket with no Robolectric. **Task 3** is the screen, the route, the Settings entry and the lifecycle binding, plus one connected case on `emulator-5554`. **Task 4** is the Python MCP server, its pytest suite against a stdlib fake server, its CI job and its README. **Task 5** is the version, the README section, `docs/api/v1.md` and the `docs/versioning.md` history row. **Task 6** is the controller-run proof set, ending in an end-to-end round trip on the emulator.

**Tech Stack:** the estate's pins — AGP 9.4.0, Kotlin 2.4.20, Gradle 9.7.1, JDK 17, compileSdk 37 / minSdk 26 / targetSdk 36; `nfc-tag-core` at `nfc-tag-core-v0.1.0` (`7e0377a`) as a pinned submodule under `libs/`, **untouched**; Room 3 with `AppDatabase` at schema version **5, unchanged**; `BackupCodec.FORMAT_VERSION` **5, unchanged**; Compose + Material 3 + Navigation 3; kotlinx-serialization-json 1.9.0, already on `:app`'s classpath (`app/build.gradle.kts:123`) with the plugin already applied (`:8`); JUnit 5 + `kotlin.test` in `:core` (`useJUnitPlatform()`, `core/build.gradle.kts:28`–`30`), JUnit 4 + Compose test in `:app`; **no new Gradle dependency in any module** (see the dependency decision); Python 3.12 + `uv` + the `mcp` SDK 2.x + `httpx` + `pytest` for the workstation half; `apksigner`/`aapt2` from build-tools 36.0.0 for `tools/release-dry-run.sh`.

**Spec:** GitHub issue [#46](https://github.com/GonzRon/ServiceTag/issues/46) — *"[NEXT] Local automation API: loopback JSON API in the app, a workstation MCP server, an additive backup import"* — and the owner's approved design quoted in Global Constraints below. **Task 1's semantics are issue [#44](https://github.com/GonzRon/ServiceTag/issues/44)'s** (*"Merge an incoming ServiceTag backup set into an existing non-empty install"*), which is binding: its identity precedence, its plan-before-write flow, its same-UUID and NFC-collision rules, and its acceptance criteria 1–7, 10, 11 and 14 are what Task 1 implements. #44's slices **B** (owner-driven conflict resolution and asset-id remapping), **D** (the artifacts-archive classification) and **E** (the Backup screen's Replace-vs-Merge UI) are explicitly **out of scope** for 1.1.0; slice **A** (the pure planner) and the conflict-free-union half of **C** (the transactional apply) are in. The house example for shape, depth and voice is the previous release's plan, `docs/superpowers/plans/2026-09-18-servicetag-2.8-restore-prompt-and-inspect.md` (shipped as 2.7.1). Versioning policy: `docs/versioning.md`.

---

## The three parts, as scoped

**(1) The loopback API.** ServiceTag has no way in but a finger. Every capability it has is a use case in `:core` wired once in `di/AppGraph.kt` and called from a view model; there is no CLI, no content provider, no intent API. That is the right default for a local-first product, and it makes the one thing the owner actually wants — "load these forty events from a spreadsheet", "rename every component of the hot tub", "check what this phone thinks it holds" — a manual afternoon. So: a JSON API over the *same* use cases, reachable only from this phone's own loopback address, alive only while a screen that says so is in front of the owner, and gated by a code that is regenerated every time that screen opens. Nothing is added to the domain; the API is a second caller of the calls the UI already makes.

**(2) The workstation MCP server.** `adb forward` is what turns "this phone's own loopback address" into something a workstation can reach, and an MCP server is what turns it into something an agent can drive. It lives in this repository because its contract is this repository's — one tool per endpoint, and the endpoints are version 1 of a contract written down in `docs/api/v1.md`.

**(3) The additive, conflict-safe import.** `ImportBackupReplace` is the only import there is, and it is a wipe-and-load (`ImportBackupReplace.kt:53`–`64` deletes all seven tables before it inserts anything). That is correct for "restore this phone from a backup" and useless for "add these rows to the phone I am holding" — which is issue #44's whole subject: phone A tracks one group of assets, phone B another, and the owner wants the union without losing either.

#44 is explicit that the dangerous way to do this is the obvious way. **"Incoming row wins" is forbidden**, and so is choosing by `updatedAt`: *"Backup merge is not live synchronization; clock skew and legitimate edits on both phones make timestamp-only last-write-wins too risky."* So the merge is **planned before it writes**. A pure planner reads the decoded archive and one consistent snapshot of this install and decides, per row: **INSERT** (the id is not here), **IDENTICAL** (the id is here and the canonical content matches — nothing to do), **SKIPPED** (the plan declines to write it, and says why), or **CONFLICT** (the id is here and the content differs, or a unique index or a foreign key would be violated, or an NFC payload identity is claimed by a different asset). One conflict anywhere means the apply refuses and **nothing at all is written**; the report names every conflict so the owner can see what overlaps.

1.1.0 therefore ships exactly #44's *first useful milestone*: **the automatic conflict-free union, and a report**. It never updates a local row and never deletes one — the only write it can make is an insert. The owner-driven resolution UI, asset-id remapping and the artifacts-archive merge are #44's later slices. It has no UI at all in 1.1.0: it is reachable over the API and through the MCP, and nowhere else.

## Global Constraints

- **Ratification before execution.** The owner ratifies every new user-visible string before this plan is executed. They are listed once, byte-for-byte, in the block below this section, and **no other user-visible string changes anywhere in the app**. If the owner changes a word, it is changed in the ratification block and then in exactly the places the block names.
- **`libs/` is untouched.** No file under `libs/nfc-tag-core` is modified or re-pinned; the gitlink stays at `7e0377a` (`git ls-tree HEAD libs/nfc-tag-core` → `160000 commit 7e0377ac99d7a4fee95ca6b88551daaa6330e52f`). `tools/check-submodule-pin.sh` must pass.
- **No schema and no backup-format bump.** `AppDatabase` stays at 5, `AppGraph.SCHEMA_VERSION` stays 5 (`AppGraph.kt:219`), `BackupCodec.FORMAT_VERSION` stays 5 (`BackupCodec.kt:47`), `app/schemas/` is not re-exported, no migration is added or edited. `ImportBackupMerge` **reads** format 5 exactly as the codec already decodes it and writes no new field.
- **The 2.6 tombstones are untouched.** `LinkRepository` keeps exactly `upsert`, `get`, `all`, `deleteAll` (`core/ports/Repositories.kt:57`–`62`); `external_link` is still exported and restored byte-for-byte; nothing above the DAO gains a way to *display* a link. The merge import inserts a new `externalLinks` row like any other table's and never touches one that is already here, and the API has **no link endpoint**: a tombstone is data a backup carries, not a thing this product offers.
- **No repository port gains a member.** Every query the merge and the API need already exists: `all()` on all seven repositories, `forAsset` on `definitions`/`profiles`/`events`, `get` on each, `count()` on `attachments`, and `open(locator)` plus `StoredBytes` on `AttachmentStore` (`core/ports/AttachmentStore.kt:18`, `:9`) — which is what lets the planner verify an attachment's size and sha256 without a new port member. `core/ports/**` is not edited by any task.
- **The security minimums, non-negotiable, each with the test that pins it.**
  1. The listener binds the literal `127.0.0.1` and nothing else, and additionally refuses any accepted connection whose peer is not a loopback address. — `LoopbackApiServerTest.itBindsTheLoopbackAddressAndNothingElse` for the bind, and `…onlyALoopbackPeerIsAnswered` for the second check, which is a predicate precisely because it cannot be driven through a loopback-bound socket
  2. Every request carries `Authorization: Bearer <code>`, compared with `java.security.MessageDigest.isEqual` (the JDK's own non-short-circuiting array compare). — `PairingCodeTest`, `ApiRouterTest.aRequestWithNoTokenIs401WithAnEmptyBody`, `…aWrongTokenIs401WithAnEmptyBody`
  3. Anything that fails that check gets **401 with a zero-byte body** — no code, no message, no hint. — the same two router cases, and `LoopbackApiServerTest.anUnauthorisedRequestGetsAnEmptyBodyOverTheWire`
  4. The code is fresh every time the screen opens, 8 characters from a 32-character unambiguous alphabet, from `SecureRandom`. — `PairingCodeTest.twoCodesAreNotTheSame`, `…isEightCharactersOfTheUnambiguousAlphabet`
  5. Bodies are capped: 64 KiB everywhere, 4 MiB on the two `/v1/import-merge/*` paths alone. Over the cap is 413 and the body is never read. — `HttpWireTest.aBodyOverTheCapIs413`, `…theImportPathsHaveTheirOwnCap`
  6. `Transfer-Encoding` is refused outright; a body needs a `Content-Length`; a request line, the header block (in **bytes**) and the header count all have ceilings; a `GET` or `DELETE` with a body is refused; and every refusal the parser raises — before authentication has happened — answers with an **empty body**, so it names no path and no cap. — `HttpWireTest`, 15 cases
  7. Wipe, replace-import, export, NFC write, NFC bind and attachment bytes are **not reachable**: no route matches them and no handler calls them. — `ApiRouterTest.theDestructiveUseCasesHaveNoRoute`, and Task 6's structural greps
  8. Nothing sensitive is logged. The only log line the API produces is a bind failure, with no token, no path and no body; the owner is told about that failure on the screen, in the ratified words of **S6**.
  9. **No outbound networking of any kind is introduced in 1.1.0.** The one socket this release adds is a *listening* socket bound to the loopback address. There is no `HttpURLConnection`, no `OkHttp`, no `URL(…)`, no `openConnection`, no client `Socket(…)` and no `WebView` load anywhere in `app/src/main`. Task 6 Step 3 greps for each of those and expects nothing.
- **The listener's lifetime is the screen's.** `LifecycleResumeEffect` starts it on resume and stops it on pause or dispose — the same primitive `ServiceTagRoot.kt:75`–`78` already uses for reader mode. There is **no `Service`**, no `WorkManager`, no foreground notification, no `BroadcastReceiver` and no boot hook. Leaving the screen, locking the phone, switching apps and killing the activity all stop it.
- **One manifest line, approved, and the sentence that goes with it.** `app/src/main/AndroidManifest.xml` gains exactly `<uses-permission android:name="android.permission.INTERNET" />` and **nothing else** — no component, no `<service>`, no `<receiver>`, no exported anything, no new `<intent-filter>`, no new scheme. Android gates the *creation* of a TCP socket on the group that permission grants, whatever address the socket is later bound to, so a loopback listener cannot exist without it (note **D1**). **The owner approved it** on the reasoning that ServiceTag has a cloud roadmap that will need the permission anyway, so a permanent manifest-level no-network guarantee is not a goal worth an Android-only transport for. The distinction is therefore documented rather than engineered away, in **three places**, in the owner's own sentence, **verbatim**:

  > `INTERNET` gives the process network capability, but ServiceTag 1.1.0 introduces no outbound networking and the Developer API listens only on localhost; future cloud features may make intentional outbound use of the permission under their own designs.

  (a) the manifest comment beside the new line (Task 3 Step 6); (b) `docs/api/v1.md` (Task 5 Step 4); (c) the README subsection (Task 5 Step 3). Task 6 Step 3 proves the engineering half: `uses-permission` is exactly 2 (`NFC` and `INTERNET`, nothing else), `android:exported` is unchanged at 3, no component was added, and there are **zero outbound call sites** in `app/src/main`.
- **Commits.** One commit per task, on `master`. Single casual subject line; **no body, no trailers, no attribution of any kind** — no `Co-Authored-By`, no `Generated-with`, no `Signed-off-by`. Author **GonzRon**, with the owner's own git identity, read out of `master` rather than written down here, so no e-mail address is stored in this plan (every commit on `master` already carries exactly one, and `git log -1 --format='%ae' master` at `0a582f8` returns it):
  ```bash
  AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
  git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "<subject>"
  ```
  Every commit step below repeats those two lines; `AUTHOR_EMAIL` is re-derived each time, because a task's shell does not outlive the task.
- **Device rule.** `ANDROID_SERIAL=emulator-5554` goes on the **same command line** as every `adb` and every Gradle device command. Instrumented suites wipe app data (`clearInstall()`, `AppSmokeTest.kt:56`–`79`), which is why. **The phone is never addressed**: it holds the owner's real records, `adb devices` is not consulted for it, and nothing in this change installs on, reads from or writes to it. The MCP's own `adb forward` is never run against it during execution either — the only serial any command in this plan carries is `emulator-5554`.
- **Test-fixture convention for Room-backed view-model tests.** Quoted exactly as the tree does it. `BackupViewModelTest.kt:63`–`69`:
  ```kotlin
  private val scheduler = TestCoroutineScheduler()
  private lateinit var graph: FakeGraph

  @Before fun setUp() {
      Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
      graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
  }
  ```
  and the reason, from `TagWriteControllerTest.kt`'s KDoc (lines 39–42): *"One dispatcher carries the controller's scope, its io hop AND Room's query context, so provision, inspect, write and complete all land on the single test scheduler and `advanceUntilIdle()` is a real settle rather than a hope (`inMemoryDb()` puts Room on `Dispatchers.Default`, which would leave the row write racing the assertion)."* `TestDb.kt:9`–`15` says the same from the other side: *"A ViewModel or controller fixture that put a test dispatcher on `Dispatchers.Main` should pass a `StandardTestDispatcher` on that *same* `TestCoroutineScheduler` instead."* So: Main is `UnconfinedTestDispatcher(scheduler)`, Room is `StandardTestDispatcher(scheduler)` on that same scheduler, the graph is `FakeGraph(queryContext = …)`, and **no `runBlocking` in setup** — every suspending fixture call happens inside `runTest`.
  **This release's API tests are the documented exception, and the exception is argued rather than assumed.** `ApiRouter.handle` is a *blocking* function called from a socket thread: it wraps its suspending work in `runBlocking(Dispatchers.IO)` because that is what the caller is — a thread with nothing else to do. A virtual-clock fixture cannot drive it, because `runBlocking` on a `TestCoroutineScheduler` that the test thread also owns would deadlock. So `ApiRouterTest` and `LoopbackApiServerTest` use `FakeGraph()` with its **default** `queryContext` (`Dispatchers.Default`, `FakeGraph.kt:74`) and no `Dispatchers.setMain`: there is no view model, no `Main` dispatcher and no flow to settle, and every call the tests make is synchronous from the test thread's point of view because `runBlocking` does not return until Room has answered. That is the whole reason the API layer was built with no `ViewModel` in it.
- **Robolectric is not used, and is not in the tree.** `app/build.gradle.kts:127`–`131` declares JUnit 4, `kotlin-test`, `kotlinx-coroutines-test`, `room3-testing` and `sqlite-bundled-jvm` for `testDebugUnitTest`, and nothing else. Every file under `app/src/main/kotlin/com/loosecannon/servicetag/api/` is therefore written with **no Android import at all** — `java.net`, `java.io`, `java.security`, `kotlinx.coroutines` and `kotlinx.serialization` only — so `testDebugUnitTest` runs the parser, the router, the handlers and a real `ServerSocket` on the JVM. The one Android-touching file, `ui/api/DeveloperApiViewModel.kt`, does nothing but mint a code, own a server and log a bind failure, and is proved on the emulator.
- **Hygiene.** No e-mail address, no absolute home path (write `~`), no device id other than `emulator-5554`, no tag UID, no note id, and no pairing code in any file this change touches — source, test or document. **This plan writes no absolute home path.** `/home/` and `/Users/` appear in it only as hygiene grep *patterns* — Task 4 Step 12's `grep -rn 'emulator-\|/home/\|/Users/' tools/servicetag-mcp` and Task 6 Step 6's `git diff HEAD~5 | grep -nE '/home/|/Users/'` — plus this sentence and the self-review row that account for them. They are the checks, not paths: every path this plan gives for real uses `~`, including Task 6 Step 2's `SET=~/Documents/...`, whose expansion for a non-owner shell is derived in the shell and never written down. Test fixtures use invented names (`Hot tub`, `Generator`, `Filter`, `Mower`, `Round trip`) and short invented ids (`a1`, `t1`, `d1`, `pf1`, `m-shared`) rather than UUID-shaped ones, so nothing in this release even resembles a real id; the invented canonical UUID `123e4567-e89b-12d3-a456-426614174000` that `ResolveTagTest.kt:30` and `PreSplitLinkTagSheetTest.kt:35` use is the one to reach for if a future fixture needs a UUID shape, and no fixture here does.
- **Versioning.** `docs/versioning.md`'s rubric classifies this first: a new user-facing capability is a **MINOR**. So `versionName` "1.0.0" → **"1.1.0"**, `versionCode` 11 → **12**. PATCH resets to 0 by the same document's rule. Task 5 Step 4 also corrects that document's forward-looking history rows, because they currently reserve 1.1.0 for schedules — see Deviations note **D2**.
- **Per-task gate**, all six tasks: `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain`. `:app` is never knowingly red between tasks. From Task 4 on, the gate also includes `cd tools/servicetag-mcp && uv run pytest`.
- **The evidence file is not touched by any task.** `docs/architecture/product-split-evidence.md` is the controller's to write **after** Task 6's proofs. Its 1.0.0 entry is history and is not rewritten.
- **Not in this change:** no `git push`, no tag, no `gh` mutation, no phone install, no physical NFC, no edit to `docs/architecture/product-split-evidence.md`, no edit under `docs/design/**` (history — never rewritten), no edit to `docs/architecture/product-split-target.md` or `product-split-migration.md` (1.1.0 changes no identity, no filter, no division of labour and adds no physical runbook row — a loopback socket is not an NFC behaviour), no schedules work (still the next phase), no UI for the merge import, no change to `ImportBackupReplace`, no change to `.github/workflows/release.yml`, and no re-pinning of `ci.yml`'s existing action versions (see Deviations note **D3**).

## Ratified before execution

**The six new user-visible strings, as ratified, verbatim and in full.** These are the only user-visible strings 1.1.0 adds, and nothing else in the app changes:

| # | string | where |
|---|---|---|
| S1 | `Developer API` | the Settings › Utilities row label (Task 3 Step 4) **and** the new screen's `TopAppBar` title (Task 3 Step 3). One string, two uses, byte-identical. |
| S2 | `While this screen is open, ServiceTag accepts commands on this phone's loopback address only. Pair with the code below; leave the screen to stop.` | the screen's one explanatory sentence, in a `QuietLine` (Task 3 Step 3) |
| S3 | `Port` | a `LabelValue` label; `LabelValue` renders a label through `label.uppercase()` (`LabelValue.kt:27`), so the pixels read `PORT` and the source string is `Port` (Task 3 Step 3) |
| S4 | `Pairing code` | a `LabelValue` label; the pixels read `PAIRING CODE` (Task 3 Step 3) |
| S5 | `Requests this session` | a `LabelValue` label; the pixels read `REQUESTS THIS SESSION` (Task 3 Step 3) |
| S6 | `The Developer API could not start. Leave this screen and open it again.` | the screen's failure line, shown when the listener cannot bind its port (Task 3 Steps 3 and 5). It replaces the silent log-only behaviour an earlier draft of this plan proposed. |

Note S2's wording: `this phone's loopback address`, **not** "this phone's own loopback address". The string is the ratified one and appears byte-identically in `DeveloperApiScreen.kt` and in `DeveloperApiListenerTest`.

**The two open decisions, both now settled:**

- **R1 — APPROVED as drafted: TCP loopback on `127.0.0.1:17337` plus `android.permission.INTERNET`.** The owner's reasoning: ServiceTag has a cloud/SaaS roadmap that will need the permission anyway, so a permanent manifest-level no-network guarantee is not a goal worth an Android-only transport. What is kept, all of it binding: the bind to the literal `127.0.0.1` and nothing else; the fixed port 17337; the accepted-peer loopback check as defence in depth; `adb forward tcp:17337 tcp:17337` as the only way in from a workstation; the listener alive only while the Developer API screen is resumed, and stopped on leave, pause, lock and destroy; a fresh pairing code every session; a bearer token on every request, compared in constant time, with a 401 and an empty body otherwise; no background service, boot receiver, `WorkManager`, exported component or LAN binding; and **no outbound networking of any kind introduced in 1.1.0**. The `INTERNET`/no-outbound distinction is documented in the owner's sentence in three places — see the Global Constraints item above, Task 3 Step 6, Task 5 Step 3 and Task 5 Step 4 — and proved by Task 6 Step 3.
- **R2 — APPROVED: the MCP mirrors every API operation.** `archive_definition` and `archive_profile` become tools, so the tool count is **21** (`pair` plus twenty operations). Task 4's `TOOL_NAMES`, its `EXPECTED_TOOLS` literal, two pytest cases, the README's tool list and Task 6's tool-count grep all say 21.

## The dependency decision: a hand-rolled loopback server, not NanoHTTPD

**Decision: hand-rolled, over `java.net.ServerSocket`, with hard ceilings and a test per malformed shape. No new Gradle dependency in any module.**

The owner's rule is that a dependency is acceptable when it is *small and maintained*. NanoHTTPD 2.3.1 is small — one jar, BSD-3 — and it is **not maintained**: 2.3.1 is its last release, from March 2017, and there has been no release in the nine years since. It fails half the rule outright, and the half it passes it passes by less than it looks: NanoHTTPD is a *general* HTTP server, and what it brings that this API must not have is exactly what makes it general — chunked transfer decoding, multipart form parsing, request bodies spooled to temporary files on disk, a session abstraction, a thread-per-connection executor. Every one of those is either attack surface for a listener whose whole threat model is "only the owner's own workstation should be able to say anything to it", or a place where a 4 MiB zip lands on disk outside the app's control on its way to a use case that wanted it in memory. Adopting it would mean adding a nine-year-old artifact to the *production* classpath of a release APK and then spending the plan's test budget configuring away its features.

What is actually needed is 200 lines: accept one connection at a time, read a request line and a header block with ceilings, refuse anything that is not `GET`/`POST`/`PATCH`/`DELETE` with a `Content-Length` body, read exactly that many bytes, hand a `(method, path, headers, body)` value to a router, write a response with `Connection: close`. That is small enough to hold in one file, and — decisively — it is **pure JVM**, so `:app:testDebugUnitTest` can drive every malformed shape through the real parser and a real socket with no emulator, no Robolectric and no new test dependency. The strict limits the owner asked for as the condition of a hand-rolled server are therefore not a promise in prose; they are `HttpWireTest`, fifteen cases, in the JVM gate CI already runs.

**One connection at a time is a deliberate limit, not an oversight.** There is one client, the MCP server, and it makes one call at a time. Serialising connections means no thread pool, no shared mutable state between requests, and writes that cannot interleave — the concurrency story is "there is none". A second client waits in the accept backlog (4) or is refused. A connection that goes quiet is dropped after 5 seconds, so a half-open socket cannot hold the only worker.

**What was rejected and why, in one line each.** NanoHTTPD 2.3.1 — unmaintained since 2017, and general where this must be narrow. Ktor's `CIO` server — a new dependency tree on the production classpath of an app that has no server in it, for a feature that must not survive the screen. `com.sun.net.httpserver.HttpServer` — present in the JDK but **not in the Android runtime**, so it compiles and fails at class load on a device. An `AF_UNIX` `LocalServerSocket` — genuinely better on Android (no `INTERNET` permission, and `adb forward … localabstract:` speaks it natively), and rejected only because the approved design binds "127.0.0.1, fixed port 17337"; it is written up as the alternative under Deviations note **D1** for the owner to take if they would rather not grant the permission.

## Where the external facts in this plan were read

Two claims in this plan are about things outside the repository, and a reviewer should be able to
check them without taking this document's word for it. Both were read on **2026-09-21**:

- **`astral-sh/setup-uv` v10.2.0 = `c18668ad3cf93ea998bef934396af7bb5c839dc7`.** Read from the
  action's own repository: the latest release is `v10.2.0` (published 2026-09-21), and the git ref
  `refs/tags/v10.2.0` resolves to that commit. Verify with
  `gh api repos/astral-sh/setup-uv/releases/latest --jq .tag_name` and
  `gh api repos/astral-sh/setup-uv/git/refs/tags/v10.2.0 --jq .object.sha`. The fallback named in
  Task 4 Step 9, `v10.1.0`, resolves to `bec219d24cd3e171d82865faccec33120bb574f4` by the same
  query. Because v10.2.0 was published the same day this plan was written, the executor should
  re-run those two commands and use the fallback if the fresh release has since been yanked.
- **The `mcp` Python SDK is at 2.x, and its high-level server class is `MCPServer`, not `FastMCP`.**
  Read from `modelcontextprotocol/python-sdk`: the latest release is `v2.2.0` (published
  2026-09-07). Its `docs/whats-new.md` carries the heading `### `FastMCP` is now `MCPServer`` and
  the migration line `from mcp.server import MCPServer  # v1: from mcp.server.fastmcp import
  FastMCP`, and says that everything under `mcp.server.fastmcp.*` now lives under
  `mcp.server.mcpserver.*`. Those two lines are quoted; the rest of this bullet is a summary of
  them and is not presented as the file's words. `docs/troubleshooting.md` is where the
  `@mcp.tool()` parentheses requirement comes from. Verify with
  `gh api repos/modelcontextprotocol/python-sdk/releases/latest --jq .tag_name` and by reading
  `docs/whats-new.md` in that repository. If the reviewer finds the 1.x line is what this estate
  should pin, the change is `pyproject.toml`'s `mcp>=2.2,<3` and the two lines in `server.py` that
  name `MCPServer` — nothing else in Task 4 depends on which it is.

## File map

```
core/src/main/kotlin/com/loosecannon/servicetag/core/
  merge/MergePlan.kt                     NEW   MergeTable, MergeVerdict, MergeReason, MergeHint,
                                               MergeDecision, DuplicateCandidate, MergeTally,
                                               MergeReport, MergeWrites, MergeSnapshot, MergePlan
  merge/MergePlanner.kt                  NEW   mergePlanOf(backup, snapshot) — the pure planner
  usecase/BuildBackupMergePlan.kt        NEW   the snapshot read + the pure call
  usecase/ApplyBackupMergePlan.kt        NEW   rebuild-inside-the-transaction, then one write
  usecase/ImportBackupMerge.kt           NEW   the façade: plan(bytes), run(bytes)
  usecase/MergeErrors.kt                 NEW   MergeRefused, MergePlanStale
  usecase/ImportBackupReplace.kt         UNTOUCHED  the wipe-and-load import is not edited
  backup/**, ports/**, model/**          UNTOUCHED  every query the planner needs already exists

core/src/test/kotlin/com/loosecannon/servicetag/core/
  merge/MergePlannerTest.kt              NEW   33 cases on the pure planner, no ports at all
  usecase/ImportBackupMergeTest.kt       NEW   9 cases end to end: fakes, real codec, the apply
  testing/InMemoryRepositories.kt        UNTOUCHED
  testing/InMemoryAttachmentStore.kt     UNTOUCHED  `open`, `files` and `sha256Hex` are enough

app/src/main/kotlin/com/loosecannon/servicetag/
  api/HttpWire.kt                        NEW   ApiRequest, ApiResponse, MalformedRequest,
                                               parseRequest, writeResponse, bearerToken, the ceilings
  api/PairingCode.kt                     NEW   PAIRING_ALPHABET, newPairingCode, tokenMatches
  api/ApiJson.kt                         NEW   API_VERSION, ApiJson, ApiFailure, errorResponse,
                                               mapDomainFailure
  api/ApiDtos.kt                         NEW   the request and response shapes (Task 2 Step 5)
  api/ApiHandlers.kt                     NEW   class ApiHandlers — one method per endpoint
  api/ApiRouter.kt                       NEW   class ApiRouter — auth, path matching, body caps
  api/LoopbackApiServer.kt               NEW   the socket, the thread, start/stop, the request count
  ui/api/DeveloperApiViewModel.kt        NEW   the per-visit code, the server, failedToStart
  ui/api/DeveloperApiScreen.kt           NEW   the screen: S1–S6 and the lifecycle binding
  di/AppGraph.kt                         MODIFY  3 imports, 3 vals (build/apply/importBackupMerge),
                                                 and `private companion` -> `internal companion`
                                                 so ApiHandlers can read SCHEMA_VERSION
  ui/nav/Route.kt                        MODIFY  one @Serializable data object: DeveloperApi
  ui/nav/ServiceTagRoot.kt               MODIFY  one import, one entry, one Settings callback
  ui/settings/SettingsScreen.kt          MODIFY  one parameter, one UtilityRow, one KDoc sentence
  ui/backup/**, ui/scan/**, ui/asset/**  UNTOUCHED
  MainActivity.kt, nfc/NfcDispatchActivity.kt   UNTOUCHED  no deep link, no new intent
  AndroidManifest.xml                    MODIFY  one <uses-permission> line; no component (D1)

app/src/test/kotlin/com/loosecannon/servicetag/
  api/PairingCodeTest.kt                 NEW   4 cases
  api/HttpWireTest.kt                    NEW   15 cases
  api/ApiRouterTest.kt                   NEW   23 cases, over FakeGraph
  api/LoopbackApiServerTest.kt           NEW   7 cases, over a real loopback socket
  ui/api/DeveloperApiViewModelTest.kt    NEW   3 cases, incl. S6 by occupying a port
  ui/nav/RouteTest.kt                    MODIFY  one assertion in onlyTheTagScreensHoldReaderMode
  testing/FakeGraph.kt                   MODIFY  3 imports, 3 vals (build/apply/importBackupMerge)

app/src/androidTest/kotlin/com/loosecannon/servicetag/
  ui/api/DeveloperApiListenerTest.kt     NEW   3 cases on emulator-5554
  ui/settings/SettingsBackupEntryTest.kt MODIFY  the new row's case; the existing call gains a lambda
  every other class                      UNTOUCHED

tools/servicetag-mcp/
  pyproject.toml                         NEW
  .python-version                        NEW   3.12
  uv.lock                                NEW   generated by `uv lock`, committed
  README.md                              NEW   the Claude Code MCP configuration snippet
  src/servicetag_mcp/__init__.py         NEW
  src/servicetag_mcp/client.py           NEW   Device, ApiError, NotPaired, the adb forward
  src/servicetag_mcp/server.py           NEW   MCPServer + 21 tools (pair + one per operation)
  tests/conftest.py                      NEW   a stdlib fake API on 127.0.0.1, no device
  tests/test_client.py                   NEW   8 cases
  tests/test_tools.py                    NEW   16 cases

.github/workflows/ci.yml                 MODIFY  one new job: mcp
app/build.gradle.kts                     MODIFY  versionCode 12, versionName "1.1.0" — nothing else
gradle/libs.versions.toml                UNTOUCHED  no new dependency (see the decision above)
README.md                                MODIFY  one bullet, the Settings bullet, one subsection
docs/api/v1.md                           NEW   the contract
docs/versioning.md                       MODIFY  the history table's rows below 1.0.0
libs/**, app/schemas/**, core/ports/**,
docs/design/**, docs/architecture/**,
.github/workflows/release.yml            UNTOUCHED
```

---

### Task 1: the conflict-safe merge planner, and the apply that refuses

**Files:**
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt`
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlanner.kt`
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/MergeErrors.kt`
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/BuildBackupMergePlan.kt`
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ApplyBackupMergePlan.kt`
- Create: `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ImportBackupMerge.kt`
- Create test: `core/src/test/kotlin/com/loosecannon/servicetag/core/merge/MergePlannerTest.kt` (33 cases)
- Create test: `core/src/test/kotlin/com/loosecannon/servicetag/core/usecase/ImportBackupMergeTest.kt` (9 cases)
- **Untouched, and checked at Step 12:** `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ImportBackupReplace.kt`, `core/src/main/kotlin/com/loosecannon/servicetag/core/ports/**`, `core/src/main/kotlin/com/loosecannon/servicetag/core/backup/**`, `core/src/test/kotlin/com/loosecannon/servicetag/core/testing/**`, `app/**`, `libs/**`, `app/schemas/**`

**Interfaces:**
- Consumes (all present at `0a582f8`): `BackupCodec.decode(bytes): Backup` (`BackupCodec.kt:138`); `Backup(manifest, data)` (`BackupFormat.kt:243`); `BackupManifest` (`:47`); `BackupData` (`:232`); the seven `…Dto.toDomain()` and the seven `…toDto()` mappers (`BackupFormat.kt:251`–`605`); `AssetTree.parentsFirst`, `AssetTree.wouldCycle` (`model/AssetTree.kt:51`, `:16`); `DefinitionKind.ENTERED` (`model/Journal.kt:5`); `all()`/`upsert()` on all seven repository ports (`ports/Repositories.kt`); `AttachmentStorage.store()`, `AttachmentStore.open(locator)` and `StoredBytes(sha256, sizeBytes)` (`ports/AttachmentStore.kt:32`, `:18`, `:9`); `UnitOfWork.read`/`write` (`ports/UnitOfWork.kt:7`, `:16`).
- Produces (Tasks 2, 4, 5 and 6 rely on these names; package `com.loosecannon.servicetag.core.merge` unless stated):
  - `enum class MergeTable { ASSETS, DEFINITIONS, PROFILES, LINKS, TAGS, EVENTS, ATTACHMENTS }` — declaration order **is** write order and the first sort key.
  - `enum class MergeVerdict { INSERT, IDENTICAL, CONFLICT, SKIPPED }` — there is deliberately no `UPDATE`.
  - `enum class MergeReason` — **fifteen** stable codes: `NONE`, `CONTENT_DIFFERS`, `PAYLOAD_BOUND_TO_ANOTHER_ASSET`, `PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG`, `PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG`, `PAYLOAD_DUPLICATED_IN_ARCHIVE`, `DEFINITION_KEY_TAKEN`, `EVENT_SOURCE_REF_TAKEN`, `ATTACHMENT_LOCATOR_TAKEN`, `PROFILE_FIELD_DEFINITION_TAKEN`, `CHILD_ROW_ID_TAKEN`, `OWNER_NOT_AVAILABLE`, `PARENT_CYCLE`, `ATTACHMENT_BYTES_ABSENT`, `ATTACHMENT_BYTES_DIFFER`, `ATTACHMENT_STORE_NOT_CONFIGURED`. (That list is sixteen names including `NONE`; **fifteen** are reasons a row can carry.)
  - `enum class MergeHint { SAME_MANUFACTURER_MODEL_SERIAL }`.
  - `data class MergeDecision(table, id, verdict, reason = MergeReason.NONE, detail = "")`.
  - `data class DuplicateCandidate(incomingAssetId, localAssetId, hint)`.
  - `data class MergeTally(insert, identical, conflict, skipped)`.
  - `data class MergeWrites(assets, definitions, profiles, links, tags, events, attachments)`.
  - `data class MergeSnapshot(assets, tags, links, definitions, profiles, events, attachments, storedBytes, attachmentStoreConfigured)`.
  - `data class MergeReport(formatVersion, backupSetId, applicable, assets, definitions, profiles, links, tags, events, attachments, conflicts, duplicateCandidates)` — the shape the API and the MCP report, for both the plan and the apply.
  - `class MergePlan` with `backup`, `decisions`, `writes`, `duplicateCandidates`, `conflicts`, `applicable`, `fingerprint`, `tally(table)`, `report()`. Its constructor is `internal`: only the planner makes one.
  - `internal fun mergePlanOf(backup: Backup, snapshot: MergeSnapshot): MergePlan` — the pure planner.
  - `internal suspend fun storedBytesOf(backup, storage): Map<String, StoredBytes>` and `internal suspend fun mergeSnapshotOf(assets, tags, links, definitions, profiles, events, attachments, storedBytes, attachmentStoreConfigured): MergeSnapshot`.
  - In `com.loosecannon.servicetag.core.usecase`: `class MergeRefused(val report: MergeReport)`, `class MergePlanStale(val report: MergeReport)`, `class BuildBackupMergePlan(assets, tags, links, definitions, profiles, events, attachments, storage, uow)` with `suspend fun run(bytes): MergePlan`, `class ApplyBackupMergePlan(` the same nine `)` with `suspend fun run(plan: MergePlan): MergeReport`, and `class ImportBackupMerge(build, apply)` with `suspend fun plan(bytes): MergeReport` and `suspend fun run(bytes): MergeReport`.

**#44's rules, and where each one lives**

| #44 | this task |
|---|---|
| "Merge must be planned before it writes" | `mergePlanOf` is a pure function of (archive, snapshot); `ApplyBackupMergePlan` is the only thing that writes, and it takes a plan |
| Identity 1: same record UUID → same lineage | every pass keys on the row's own id first |
| Identity 2: same `(payloadFormat, payloadKey)` → same logical tag | the tag pass indexes local tags by payload identity **independently of the row id** |
| Identity 3: manufacturer + model + serial → duplicate *candidate* only | `duplicateCandidates`, which never affects `applicable` |
| Identity 4: name/category/description → never identity | no pass reads them; only `==` over the whole DTO does, and that is content, not identity |
| "physical_uid is never authoritative identity" | `physicalUid` appears in no index and in no key; it is one field of the content comparison and nothing else |
| Same UUID + identical canonical content → IDENTICAL | `incoming == local.toDto()` |
| Same UUID + different content → CONFLICT, never resolved by `updatedAt` | `CONTENT_DIFFERS`; no line of the planner reads `updatedAt` for anything but whole-row equality |
| NFC: same payload + equivalent binding → no-op | verdict `IDENTICAL`, reason `PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG` |
| NFC: same payload, different asset → required conflict | `PAYLOAD_BOUND_TO_ANOTHER_ASSET` |
| "same payload_format + payload_key cannot create two live tag identities" | the unique index `nfc_tag(payload_format, payload_key)` (`NfcTagEntity.kt:26`) is checked in the plan, and a duplicate inside the archive is caught too |
| Uniqueness and FK failures found before mutation | the plan checks **all five** unique indices the schema declares, **the four aggregate child-row primary keys**, and every foreign key |
| "Do not deduplicate events because they have the same date, title, type or measurement values" | the event pass keys on the id and on `(source, sourceRef)`, and on nothing else |
| `source` + `sourceRef` as an extra collision detector | `EVENT_SOURCE_REF_TAKEN`, from the unique index `asset_event(source, source_ref)` (`JournalEntities.kt:167`) |
| Attachments: same id + different owner or sha256 → conflict | `CONTENT_DIFFERS` covers both, because the owner columns and `sha256` are fields of `AttachmentDto` |
| "Never overwrite different local bytes merely because a locator collides" | `ATTACHMENT_LOCATOR_TAKEN`, from the unique index `attachment(storage_provider, storage_locator)` (`AttachmentEntity.kt:43`) |
| "Verify size + SHA-256 before writing" (acceptance 12) | `storedBytesOf` hashes and sizes what the store holds; a mismatch is `ATTACHMENT_BYTES_DIFFER`, a conflict |
| Apply is one transaction and rolls back on failure | one `uow.write`; every refusal is raised inside it or before it |
| Importing the same backup twice is idempotent | all-IDENTICAL, `writes` empty, nothing applied |

**Out of scope for 1.1.0, from #44 itself, each with the reason:**

- **Slice B — owner-driven conflict resolution and asset-id remapping.** 1.1.0 ships #44's own "first useful milestone": the automatic conflict-free union. Every overlap that is not provably identical is refused and named.
- **Slice D's artifact *writing*.** The API takes a *data* archive only: attachment **rows** merge here and their bytes are **verified** here, but no byte is ever written — restoring bytes stays the Backup screen's `RestoreArtifacts`, and so does the `WRITE`/`MISSING_FROM_ARCHIVE`/`REFERENCE_ONLY` classification of a paired artifacts archive.
- **Slice E — the Backup screen's Replace-vs-Merge control.** No UI in 1.1.0 at all.
- **#44's pre-merge safety snapshot.** Its purpose is undo, and in 1.1.0 there is nothing to undo: the merge cannot update or delete a pre-existing row, and a conflicted plan writes nothing. It becomes required the moment slice B adds remapping, and is written down here so the omission is legible as a consequence of the scope rather than an oversight.

**Decisions, and what lost:**

1. **Plan and apply are separate types, and the plan is a pure function.** `mergePlanOf(backup, snapshot)` takes no ports, does no IO and returns the same plan for the same inputs — which is what makes thirty-three of this task's forty-two cases plain value assertions with no fakes at all. A single `ImportBackupMerge.run(bytes)` that decided as it wrote lost: #44 forbids it in as many words, and it would put every one of those cases behind a repository.
2. **There is no `UPDATE` verdict, so 1.1.0's merge can only insert.** An id that is already here either matches (no-op) or conflicts (refuse). That is #44's same-UUID rule read literally, and its consequence is the strongest safety property this release has: **no pre-existing row can be modified by a merge**, whatever the archive says. An `UPDATE` verdict lost because choosing a winner is exactly what #44 forbids without the owner in the loop.
3. **Canonical content is every backup-format field, `createdAt` and `updatedAt` included.** The comparison is `incoming == local.toDto()` — one expression, the whole row, the format's own field set, and no exclusions to argue about. A row that differs only in `updatedAt` is a **CONFLICT**, not a no-op: two installs that both touched a row have both touched it, and 1.1.0's answer to that is to say so rather than to guess. Excluding `updatedAt` (an earlier draft of this plan) lost: it invites exactly the "it's only a timestamp" reasoning that ends in last-write-wins. Every pass compares in the same direction and the same shape — `dto == local.toDto()` — including assets, where an earlier draft round-tripped the incoming row through `toDomain().toDto()` for no reason. Two footnotes on the comparison, because "one expression, no exclusions" is a claim worth qualifying honestly: the two aggregate tables' child lists are compared in `(sortOrder, id)` order (decision 4), and `Double` fields compare with `Double.equals`, so `0.0 != -0.0` and `NaN == NaN` — neither value is reachable through the app's own writers, and a hand-built archive that carried one would be reported as a conflict, which is the safe direction.
4. **Child lists are ordered by `sortOrder` **then `id`**, because the format does not promise `sortOrder` is unique.** `sortedBy` is stable, so a tie keeps each side's input order — and the two sides' input orders come from different places: `BackupCodec.encode`'s own sort (`BackupCodec.kt:85`–`96`) on one side, and `JournalMappers`' per-read sort on the other, whose comment says `@Relation` returns children in no particular order (`JournalMappers.kt:33`). Two stable sorts over two undefined bases agree only when the key is total, so the key is made total with the child's id. Without it a `sortOrder` tie would make IDENTICAL-versus-CONFLICT depend on storage order, which would contradict decisions 1 and 13 in the same breath they are made.
5. **No coalescing and no remapping, by ruling.** Same payload identity under different row ids is *noticed and reported* every time: `IDENTICAL` when the two rows are equivalent, `CONFLICT` otherwise. Nothing is rewritten, no id is remapped, no alias is recorded. The contract is: disjoint merges automatically, identical overlap is a no-op, ambiguous or diverged overlap is refused safely.
6. **The plan checks every uniqueness constraint the schema actually has — five indices and four child-row primary keys — and no invented ones.** The five unique indices, read from the entities and confirmed in `app/schemas/*/5.json`: `nfc_tag(payload_format, payload_key)` (`NfcTagEntity.kt:26`), `measurement_definition(asset_id, key)` (`JournalEntities.kt:45`), **`profile_field(profile_id, definition_id)`** (`JournalEntities.kt:113`), `asset_event(source, source_ref)` (`JournalEntities.kt:167`), `attachment(storage_provider, storage_locator)` (`AttachmentEntity.kt:43`). The four child-row primary keys — `profile_field.id`, `profile_consumable.id`, `measurement.id`, `consumable_usage.id` — are part of uniqueness too, and `core/model/Journal.kt:28`–`30` says why: *"Child rows carry durable ids of their own … they survive backup verbatim … Import never generates replacement ids."* `BackupCodec.decode` checks those four are unique **within the file** (`BackupCodec.kt:324`, `:325`, `:381`, `:382`) and nothing checks them against the destination, so a local profile's field id and an incoming profile's field id can collide even though both aggregate roots are distinct. Every one of these nine is checked against the destination **and** against the archive's own accepted rows, because a constraint does not care which side a duplicate came from.
   Two of the nine deserve their exact reachability written down rather than implied. **`profile_field(profile_id, definition_id)`**: the key contains the aggregate root's id, and a merge only ever inserts a *new* root, so a collision with the destination is unreachable — the live case is an archive whose profile lists one definition twice, which `decode` accepts (it checks field-*id* uniqueness and that each field's definition belongs to the asset, `BackupCodec.kt:308`–`324`, and not the pair) and which the product itself guards against at `SaveProfile.kt:67` (`!seen.add(input.definitionId) -> "listed twice"`). The check is still written against the union, because the cost is one map lookup and a loosened key would otherwise go unnoticed. **The four child-row primary keys**: those *are* reachable from the destination, which is why they get a code of their own.
   What is deliberately **not** checked: a profile's name, which `SaveProfile` keeps unique per asset as a product rule but which the schema does not index (`JournalEntities.kt:81` has only `Index("asset_id")`) — so a merged profile may share a name with a local one on the same asset. That is a display oddity with no constraint behind it, and inventing a rule for it here would be inventing a rule the product does not have. Stated as a known limit in `docs/api/v1.md`.
7. **`(source, source_ref)` collisions are a flat CONFLICT.** SQLite treats NULLs as distinct in a unique index, so only a non-null `sourceRef` can collide; when one does, #44 asks for "semantically identical → coalesce candidate, materially different → conflict". 1.1.0 takes the conservative half of that and conflicts on both, because the coalesce half needs slice B's resolution step to be useful. Over-refusing costs the owner a message; under-refusing costs them a failed transaction.
8. **The owner-availability rule is one rule applied six times, and its live job is the cascade.** A row may be inserted only when every non-null reference it makes resolves to something that is either already local or an `INSERT` in this same plan — parent assets, definition sources, profile fields' definitions, event profiles and measurement definitions, attachment owners, tag targets and link owners. A reference that resolves to neither is `OWNER_NOT_AVAILABLE`: **a conflict, not an orphan**. Note what that means in practice: `BackupCodec.decode` already refuses a *genuinely* dangling reference for every one of those edges (`BackupCodec.kt:200`, `:256`, `:261`, `:274`, `:302`, `:310`, `:331`, `:338`, `:352`, `:393`), so through the API the rule can only fire as a **cascade** — a referenced row that *is* in the archive but was not accepted because a sibling rule conflicted it. That cascade is the shape the owner's ruling is about, and it has its own test; the flat case keeps a test too, labelled as the planner-level guard it is.
9. **Assets are decided in `AssetTree.parentsFirst` order**, so a child is always decided after its parent and can see whether the parent was accepted. `parentsFirst` gives indegree 0 to an asset whose parent is not in the collection (`AssetTree.kt:54`–`56`), which is exactly right for a parent that lives in the destination. The `PARENT_CYCLE` guard beside it is kept and is **unreachable against an FK-valid destination in 1.1.0**: `asset.parent_asset_id` is a RESTRICT self-FK, so every local ancestor chain stays local and terminates at a local root, and the walk can only reach the incoming row's own id if that id is already local — in which case the pass returned `IDENTICAL` or `CONTENT_DIFFERS` two branches earlier. An archive-internal cycle is refused by `decode` (`BackupCodec.kt:209`–`213`) and would otherwise be thrown by `parentsFirst` (`AssetTree.kt:72`). The guard becomes live with slice B's remapping, and its test says so in its name.
10. **`mergePlanOf` is not total, and that is a property of a *hand-built* `Backup` only.** It propagates `IllegalStateException` from `parentsFirst` on a cyclic asset set, `BackupCorrupt` from a `toDomain()` that cannot name a value, and an NPE from the `dto.eventId!!` in the attachment pass if an attachment row names neither owner. Every one of those is refused by `BackupCodec.decode` before a plan exists, so no caller of `BuildBackupMergePlan` can reach them — only a test constructing a `Backup` directly can. The API maps them anyway, because a 500 is not an acceptable answer to a request: `BackupCorrupt` → **400** `archive_corrupt` (Task 2 Step 3's `mapDomainFailure`), `IllegalStateException` and anything else → 500 with the class name. Named here so the next reader does not have to rediscover it.
11. **The attachment pass asks four questions in one fixed order, and the order is the point.** Is the locator already claimed by a different local row → `ATTACHMENT_LOCATOR_TAKEN`. Is there an attachment folder at all → `SKIPPED ATTACHMENT_STORE_NOT_CONFIGURED`. Are the bytes there → `SKIPPED ATTACHMENT_BYTES_ABSENT`. Do the bytes *match the row's own size and sha256* → if not, `CONFLICT ATTACHMENT_BYTES_DIFFER`. The locator check must come first: when a local row already claims a locator its bytes are of course present, so asking "are the bytes here?" first would answer yes and produce an `INSERT` the unique index refuses. And the last question is #44 acceptance 12 — *"Verify size + SHA-256 before writing"* — which an earlier draft of this plan left out: it wrote the row on `exists(locator)` alone, so a diverged file at the same locator produced a destination row whose recorded hash did not describe the stored bytes. A diverged file is a diverged overlap, so it is a conflict and not a skip.
12. **`ATTACHMENT_STORE_NOT_CONFIGURED` is a `SKIPPED` reason of its own, and `applicable` stays true.** A phone whose owner has not picked an attachments folder can still merge the other six tables, so the merge is not refused — but "the bytes were never looked for" and "the bytes are missing" are different facts and a client should be able to tell them apart. Reusing `ATTACHMENT_BYTES_ABSENT` for both lost, because it would report a configuration state as a data state.
13. **A plan with any conflict carries no writes at all.** `MergeWrites` is empty whenever a conflict exists, so "no partial merge" is a property of the value and not a discipline the apply has to remember. `ApplyBackupMergePlan` still checks `applicable` — twice, once before the transaction and once on the rebuilt plan inside it — because a belt that is also a brace costs two lines.
14. **TOCTOU is closed by rebuilding the plan inside the apply's own transaction, and a conflict is named before staleness.** The rebuild is the mechanism: `ApplyBackupMergePlan` re-reads the snapshot with the repositories *inside* `uow.write` — not through a nested `uow.read`, which `UnitOfWork`'s own KDoc calls illegal to write inside — re-runs the pure planner, and applies only that fresh result. So there is never a plan executed against a destination it was not just checked against. On the rebuilt plan the **`applicable` check comes first and the fingerprint check second**, which matters: `fingerprintOf` digests every decision, so any destination change that introduces a conflict *necessarily* changes a verdict and therefore the fingerprint. Checking the fingerprint first would make `MergeRefused`-from-rebuild unreachable and would answer a genuine conflict with a stale-plan report that carries no conflict list — the opposite of the ruling. In this order `MergeRefused` is the answer whenever the fresh plan has conflicts, and `MergePlanStale` is reserved for its one real case: a destination that changed, is *still* conflict-free, and no longer matches what was accepted.
15. **The fingerprint covers the decisions and not the hints.** `duplicateCandidates` is outside it, so a destination change that alters only the review hints is not named stale. Harmless by construction — hints never affect `applicable` and nothing is written differently — and stated so the omission reads as a choice.
16. **The façade keeps the name `ImportBackupMerge`**, because that is what the API and the MCP call and what #46 named; its semantics are the plan's, and it is six lines. #44 says the names are not normative and plan-before-write is.

- [ ] **Step 1: Write the failing pure-planner test first.**

Create `core/src/test/kotlin/com/loosecannon/servicetag/core/merge/MergePlannerTest.kt`. It builds `Backup` and `MergeSnapshot` values by hand — no repositories, no transaction, no zip — because the planner is a function. **33 cases.**

```kotlin
package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.BackupManifest
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.StoredBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.1.0 (#46), semantics from #44 — the merge planner, as a function.
 *
 * Every case here is (archive, snapshot) in and a plan out. No repository, no transaction, no zip:
 * the planner is pure precisely so that the rules #44 spends two pages on can be asserted as
 * values, one rule per case, and so that a future reader can see what each rule *is* rather than
 * what a database did.
 *
 * Two cases are deliberate **negative controls** — `the physical uid is never identity` and
 * `a matching name alone is neither identity nor a hint` — and they are labelled so, because a
 * reader should not mistake them for behaviour tests. Two more assert snapshots the schema or the
 * codec would not allow, and are labelled as planner guards for the same reason.
 *
 * The end-to-end half — the real codec, the fakes, one transaction, the refusals — is
 * `ImportBackupMergeTest`.
 */
class MergePlannerTest {

    // --- fixtures ---------------------------------------------------------------------------

    private fun asset(
        id: String,
        name: String,
        parent: String? = null,
        manufacturer: String = "",
        model: String = "",
        serial: String = "",
        updatedAt: Long = 2L,
    ) = Asset(
        id = AssetId(id), name = name, createdAt = 1L, updatedAt = updatedAt,
        parentAssetId = parent?.let(::AssetId),
        manufacturer = manufacturer, model = model, serialNumber = serial,
    )

    private fun tag(
        id: String,
        key: String,
        assetId: String?,
        linkId: String? = null,
        physicalUid: String? = null,
        label: String? = null,
    ) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = key,
        target = when {
            assetId != null -> TagTarget.AssetTarget(AssetId(assetId))
            linkId != null -> TagTarget.LinkTarget(LinkId(linkId))
            else -> TagTarget.None
        },
        label = label,
        physicalUid = physicalUid,
        createdAt = 3L,
        updatedAt = 4L,
    )

    private fun link(id: String, assetId: String? = null) = ExternalLink(
        id = LinkId(id), assetId = assetId?.let(::AssetId), kind = LinkKind.JOPLIN,
        label = "note $id", uri = "joplin://$id", createdAt = 5L, updatedAt = 6L,
    )

    private fun definition(
        id: String,
        assetId: String,
        key: String = "key_$id",
        derived: DerivedSpec? = null,
    ) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = key, label = "Label $id",
        unit = "", valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null,
        isMeter = false, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        kind = if (derived == null) DefinitionKind.ENTERED else DefinitionKind.DERIVED,
        derived = derived,
    )

    private fun field(id: String, definitionId: String, sortOrder: Int = 0) =
        ProfileField(id = id, definitionId = DefinitionId(definitionId), required = false, sortOrder = sortOrder)

    private fun profile(
        id: String,
        assetId: String,
        fields: List<ProfileField> = emptyList(),
    ) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Action $id",
        eventKind = EventKind.MEASUREMENT, defaultTitle = "Action $id", templateKey = null,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        fields = fields, consumables = emptyList(),
    )

    private fun measurement(id: String, definitionId: String) = Measurement(
        id = id, definitionId = DefinitionId(definitionId), valueNum = 7.4, valueText = null,
        unit = "", sortOrder = 0,
    )

    private fun event(
        id: String,
        assetId: String,
        title: String = "Filter change",
        sourceRef: String? = null,
        measurements: List<Measurement> = emptyList(),
        consumables: List<ConsumableUsage> = emptyList(),
    ) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MAINTENANCE, title = title,
        profileId = null, occurredOn = "2026-09-21", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = sourceRef, createdAt = 7L, updatedAt = 8L,
        measurements = measurements, consumables = consumables,
    )

    /** Four bytes, and the sha256 the row must carry for them. */
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val bytesSha = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    private val storedFour = StoredBytes(sha256 = bytesSha, sizeBytes = 4L)

    private fun attachment(
        id: String,
        assetId: String,
        locator: String = "assets/$assetId/$id.pdf",
        sha256: String = bytesSha,
        sizeBytes: Long = 4L,
    ) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId(assetId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf",
        mimeType = "application/pdf", sizeBytes = sizeBytes, sha256 = sha256,
        storageLocator = locator, capturedOn = null, createdAt = 9L, updatedAt = 10L,
    )

    private fun backupOf(
        assets: List<Asset> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
    ) = Backup(
        manifest = BackupManifest(
            formatVersion = 5, appVersion = "1.1.0", schemaVersion = 5, createdAt = 1L,
            counts = emptyMap(), dataSha256 = "a".repeat(64), backupSetId = "set-incoming",
        ),
        data = BackupData(
            assets = assets.map { it.toDto() },
            nfcTags = tags.map { it.toDto() },
            externalLinks = links.map { it.toDto() },
            measurementDefinitions = definitions.map { it.toDto() },
            eventProfiles = profiles.map { it.toDto() },
            assetEvents = events.map { it.toDto() },
            attachments = attachments.map { it.toDto() },
        ),
    )

    private fun snapshotOf(
        assets: List<Asset> = emptyList(),
        tags: List<TagBinding> = emptyList(),
        links: List<ExternalLink> = emptyList(),
        definitions: List<MeasurementDefinition> = emptyList(),
        profiles: List<EventProfile> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        storedBytes: Map<String, StoredBytes> = emptyMap(),
        attachmentStoreConfigured: Boolean = true,
    ) = MergeSnapshot(
        assets, tags, links, definitions, profiles, events, attachments,
        storedBytes, attachmentStoreConfigured,
    )

    /** The one decision the plan reached about [id] in [table]. */
    private fun MergePlan.decision(table: MergeTable, id: String): MergeDecision =
        decisions.single { it.table == table && it.id == id }

    // --- the conflict-free union ------------------------------------------------------------

    /** #44 acceptance 1 and 2: disjoint graphs merge, and every imported UUID survives. */
    @Test
    fun `a disjoint archive is all INSERT and applicable`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Filter", parent = "a1")),
                tags = listOf(tag("t1", "key-1", "a1")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
                events = listOf(event("e1", "a1")),
            ),
            snapshotOf(assets = listOf(asset("z9", "Generator"))),
        )

        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.conflicts)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), plan.tally(MergeTable.ASSETS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.TAGS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.DEFINITIONS))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.PROFILES))
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.EVENTS))
        assertEquals(listOf("a1", "a2"), plan.writes.assets.map { it.id.value })
        assertEquals(listOf("t1"), plan.writes.tags.map { it.id.value })
    }

    /** Parents before children, and ENTERED definitions before the DERIVED ones that read them. */
    @Test
    fun `writes are ordered so that every reference resolves as it is applied`() {
        val plan = mergePlanOf(
            backupOf(
                // Ids chosen so plain id order is the wrong order in both tables.
                assets = listOf(asset("a2", "Filter", parent = "a9"), asset("a9", "Hot tub")),
                definitions = listOf(
                    definition("d1", "a9", derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d2"), DefinitionId("d3"))),
                    definition("d2", "a9"),
                    definition("d3", "a9"),
                ),
            ),
            snapshotOf(),
        )

        assertTrue(plan.applicable)
        assertEquals(listOf("a9", "a2"), plan.writes.assets.map { it.id.value })
        assertEquals(listOf("d2", "d3", "d1"), plan.writes.definitions.map { it.id.value })
    }

    // --- the same id ------------------------------------------------------------------------

    /** #44 acceptance 4: the same archive twice is a no-op. */
    @Test
    fun `rows already here with identical content are IDENTICAL and nothing is written`() {
        val rows = listOf(asset("a1", "Hot tub"))
        val plan = mergePlanOf(backupOf(assets = rows), snapshotOf(assets = rows))

        assertTrue(plan.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), plan.tally(MergeTable.ASSETS))
        assertEquals(emptyList(), plan.writes.assets)
    }

    /** #44 acceptance 5: divergence is a conflict, and `updatedAt` never picks a winner. */
    @Test
    fun `the same id with different content is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub"))),
            snapshotOf(assets = listOf(asset("a1", "Spa"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(MergeTable.ASSETS, "a1", MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, "a1"),
            plan.decision(MergeTable.ASSETS, "a1"),
        )
        assertEquals(emptyList(), plan.writes.assets)
    }

    /**
     * The strict reading of canonical content, ratified: every backup-format field is compared,
     * `createdAt` and `updatedAt` included. A row that differs only in a timestamp is a conflict,
     * because two installs that both touched it have both touched it.
     */
    @Test
    fun `a row that differs only in updatedAt is still a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub", updatedAt = 50L))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub", updatedAt = 99L))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.CONTENT_DIFFERS, plan.decision(MergeTable.ASSETS, "a1").reason)
    }

    /** #44 acceptance 10: different event ids are different records, however alike they look. */
    @Test
    fun `events with different ids are both kept even when every visible field matches`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-import", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-local", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.EVENTS))
        assertEquals(listOf("e-import"), plan.writes.events.map { it.id.value })
    }

    /**
     * Decision 4: a `sortOrder` tie is broken by the child's id, so two sides that stored the same
     * two fields in opposite order still compare equal. Without the tie-break this is
     * `CONTENT_DIFFERS` on one machine and `IDENTICAL` on another.
     */
    @Test
    fun `a sortOrder tie in a child list is broken by the child id`() {
        val incoming = profile("p1", "a1", listOf(field("pf-a", "d1", sortOrder = 0), field("pf-b", "d2", sortOrder = 0)))
        val local = profile("p1", "a1", listOf(field("pf-b", "d2", sortOrder = 0), field("pf-a", "d1", sortOrder = 0)))
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), profiles = listOf(incoming)),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1"), definition("d2", "a1")),
                profiles = listOf(local),
            ),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.IDENTICAL, plan.decision(MergeTable.PROFILES, "p1").verdict)
    }

    // --- NFC payload identity ---------------------------------------------------------------

    /** #44 case 1 / acceptance 6: the same tag under a different row id, bound the same way. */
    @Test
    fun `the same payload identity under a different row id and the same asset is IDENTICAL`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-import", "key-1", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-local", "key-1", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t-import", MergeVerdict.IDENTICAL,
                MergeReason.PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG, "t-local",
            ),
            plan.decision(MergeTable.TAGS, "t-import"),
        )
        assertEquals(emptyList(), plan.writes.tags)
    }

    /** #44 case 3 / acceptance 6: the same tag pointing at two different things is refused. */
    @Test
    fun `the same payload identity bound to another asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t-import", "key-1", "a1")),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Mower")),
                tags = listOf(tag("t-local", "key-1", "a2")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.TAGS, "t-import", MergeVerdict.CONFLICT,
                MergeReason.PAYLOAD_BOUND_TO_ANOTHER_ASSET, "t-local",
            ),
            plan.decision(MergeTable.TAGS, "t-import"),
        )
    }

    /** Same payload, same asset, some other field diverged: still refused, with its own code. */
    @Test
    fun `the same payload identity on a diverged local tag is a CONFLICT of its own kind`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-import", "key-1", "a1", label = "Lid"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t-local", "key-1", "a1", label = "Side"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeReason.PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG,
            plan.decision(MergeTable.TAGS, "t-import").reason,
        )
    }

    /** The unique index does not care which side the duplicate came from. */
    @Test
    fun `two tags in one archive sharing a payload identity is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t1", "key-1", "a1"), tag("t2", "key-1", "a1")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.TAGS, "t1").verdict)
        assertEquals(
            MergeReason.PAYLOAD_DUPLICATED_IN_ARCHIVE,
            plan.decision(MergeTable.TAGS, "t2").reason,
        )
    }

    /**
     * **Negative control.** #44 acceptance 7: the hardware UID is informational and is never
     * identity. This case passes trivially if no pass reads `physicalUid` — which is the point, and
     * the grep in Task 6 Step 3 is what actually enforces it. It is here so that a future pass that
     * *did* key on the UID would have to delete a test to ship.
     */
    @Test
    fun `the physical uid is never identity`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t1", "key-1", "a1", physicalUid = "04a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), tags = listOf(tag("t9", "key-9", "a1", physicalUid = "04a1"))),
        )

        // Same hardware UID, different payload identity: two different tags, and the import inserts.
        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.TAGS, "t1").verdict)
    }

    // --- the schema's five unique indices ---------------------------------------------------

    @Test
    fun `a definition whose key is taken on the same asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-import", "a1", key = "ph")),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d-import", MergeVerdict.CONFLICT,
                MergeReason.DEFINITION_KEY_TAKEN, "d-local",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d-import"),
        )
    }

    /** Decision 6: the same index, the archive's own two rows. */
    @Test
    fun `two definitions in one archive with the same key on one asset is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1", key = "ph"), definition("d2", "a1", key = "ph")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.DEFINITIONS, "d1").verdict)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d2", MergeVerdict.CONFLICT,
                MergeReason.DEFINITION_KEY_TAKEN, "d1",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d2"),
        )
    }

    /** #44 acceptance 11: a source/sourceRef collision is found before the commit. */
    @Test
    fun `an event whose source and sourceRef are taken is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-import", "a1", sourceRef = "ref-1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e-local", "a1", sourceRef = "ref-1"))),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeReason.EVENT_SOURCE_REF_TAKEN,
            plan.decision(MergeTable.EVENTS, "e-import").reason,
        )
    }

    @Test
    fun `two events in one archive with the same sourceRef is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                events = listOf(event("e1", "a1", sourceRef = "ref-1"), event("e2", "a1", sourceRef = "ref-1")),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.EVENTS, "e1").verdict)
        assertEquals(
            MergeDecision(MergeTable.EVENTS, "e2", MergeVerdict.CONFLICT, MergeReason.EVENT_SOURCE_REF_TAKEN, "e1"),
            plan.decision(MergeTable.EVENTS, "e2"),
        )
    }

    /** A null sourceRef cannot collide: SQLite treats NULLs as distinct in a unique index. */
    @Test
    fun `events with no sourceRef never collide on it`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e1", "a1"), event("e2", "a1"))),
            snapshotOf(assets = listOf(asset("a1", "Hot tub")), events = listOf(event("e9", "a1"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(2, 0, 0, 0), plan.tally(MergeTable.EVENTS))
    }

    /**
     * The fifth unique index, `profile_field(profile_id, definition_id)` (`JournalEntities.kt:113`).
     * `BackupCodec.decode` does not check the pair — it checks field-*id* uniqueness and that each
     * field's definition belongs to the asset — so this archive decodes cleanly and would otherwise
     * die on the insert. The product guards the same shape at `SaveProfile.kt:67`.
     */
    @Test
    fun `a profile listing one definition twice is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1"), field("pf2", "d1", sortOrder = 1)))),
            ),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT,
                MergeReason.PROFILE_FIELD_DEFINITION_TAKEN, "d1",
            ),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    /**
     * The other half of that index, and why the key is the *pair*: two different profiles may each
     * offer the same reading, and must not be refused for it. Without this case a fix for the one
     * above could plausibly key on the definition alone and still look green.
     */
    @Test
    fun `two profiles may each offer the same definition`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(
                    profile("p1", "a1", listOf(field("pf1", "d1"))),
                    profile("p2", "a1", listOf(field("pf2", "d1"))),
                ),
            ),
            snapshotOf(),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(2, 0, 0, 0), plan.tally(MergeTable.PROFILES))
    }

    /** #44: "never overwrite different local bytes merely because a locator collides". */
    @Test
    fun `an attachment whose locator is claimed by another row is a CONFLICT`() {
        val locator = "assets/a1/shared.pdf"
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att-import", "a1", locator)),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att-local", "a1", locator)),
                storedBytes = mapOf(locator to storedFour),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att-import", MergeVerdict.CONFLICT,
                MergeReason.ATTACHMENT_LOCATOR_TAKEN, "att-local",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att-import"),
        )
    }

    // --- the four aggregate child-row primary keys ------------------------------------------

    /**
     * `profile_field.id` is a durable primary key of its own (`core/model/Journal.kt:28`–`30`), and
     * `decode` only checks it is unique *within the file*. Two distinct profiles — one local, one
     * incoming — can therefore carry the same field id, which is a constraint failure inside the
     * transaction unless the plan catches it.
     */
    @Test
    fun `a profile whose field id is held by another local profile is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p-import", "a1", listOf(field("pf-shared", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p-local", "a1", listOf(field("pf-shared", "d1")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "pf-shared",
            ),
            plan.decision(MergeTable.PROFILES, "p-import"),
        )
    }

    @Test
    fun `an event whose measurement id is held by another local event is a CONFLICT`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                events = listOf(event("e-import", "a1", measurements = listOf(measurement("m-shared", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1")),
                events = listOf(event("e-local", "a1", measurements = listOf(measurement("m-shared", "d1")))),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.EVENTS, "e-import", MergeVerdict.CONFLICT,
                MergeReason.CHILD_ROW_ID_TAKEN, "m-shared",
            ),
            plan.decision(MergeTable.EVENTS, "e-import"),
        )
    }

    // --- foreign keys and the tree ----------------------------------------------------------

    @Test
    fun `a contested parent does not blame its child`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub"), asset("a2", "Filter", parent = "a1")),
            ),
            snapshotOf(assets = listOf(asset("a1", "Spa"))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.CONTENT_DIFFERS, plan.decision(MergeTable.ASSETS, "a1").reason)
        // `a1` exists locally, so `a2`'s foreign key does resolve: it is insertable, and it is the
        // parent that is contested. The plan says so instead of blaming the child.
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a2").verdict)
        // And nothing is written, because one conflict empties the whole write set.
        assertEquals(MergeWrites(), plan.writes)
    }

    /**
     * Decision 8's **live** shape: the cascade. `decode` refuses a genuinely dangling reference, so
     * through the API `OWNER_NOT_AVAILABLE` can only fire when the referenced row *is* in the
     * archive and was not accepted. Here the definition's key is taken, so the profile whose field
     * names it has nothing to point at.
     */
    @Test
    fun `a profile whose field names a conflicted definition is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d1", "a1", key = "ph")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
            ),
            snapshotOf(
                assets = listOf(asset("a1", "Hot tub")),
                definitions = listOf(definition("d-local", "a1", key = "ph")),
            ),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.DEFINITION_KEY_TAKEN, plan.decision(MergeTable.DEFINITIONS, "d1").reason)
        assertEquals(
            MergeDecision(
                MergeTable.PROFILES, "p1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "d1",
            ),
            plan.decision(MergeTable.PROFILES, "p1"),
        )
    }

    /**
     * **Planner guard.** The flat form of the same rule, on an archive `BackupCodec.decode` would
     * refuse outright (`BackupCodec.kt:274`–`279`). Kept because the planner must not depend on the
     * codec for its own invariants; not reachable through the API.
     */
    @Test
    fun `a definition on an asset that is nowhere is OWNER_NOT_AVAILABLE`() {
        val plan = mergePlanOf(
            backupOf(definitions = listOf(definition("d1", "a-missing"))),
            snapshotOf(),
        )

        assertFalse(plan.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.DEFINITIONS, "d1", MergeVerdict.CONFLICT,
                MergeReason.OWNER_NOT_AVAILABLE, "a-missing",
            ),
            plan.decision(MergeTable.DEFINITIONS, "d1"),
        )
    }

    /**
     * **Planner guard**, and decision 9 says why: the cycle rule cannot fire against an FK-valid
     * destination in 1.1.0, because a local parent chain stays local and terminates at a local
     * root. This snapshot — a local child whose parent is not local — is one the RESTRICT self-FK
     * makes impossible. The guard is kept for slice B's remapping, where it becomes live.
     */
    @Test
    fun `the cycle rule holds even for a snapshot the schema would not allow`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub", parent = "a2"))),
            snapshotOf(assets = listOf(asset("a2", "Filter", parent = "a1"))),
        )

        assertFalse(plan.applicable)
        assertEquals(MergeReason.PARENT_CYCLE, plan.decision(MergeTable.ASSETS, "a1").reason)
    }

    // --- attachments' bytes -----------------------------------------------------------------

    @Test
    fun `an attachment row is SKIPPED when its bytes are not in the store, and INSERTed when they are`() {
        val here = attachment("att-here", "a1", "assets/a1/here.pdf")
        val gone = attachment("att-gone", "a1", "assets/a1/gone.pdf")
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(here, gone)),
            snapshotOf(storedBytes = mapOf("assets/a1/here.pdf" to storedFour)),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(insert = 1, identical = 0, conflict = 0, skipped = 1), plan.tally(MergeTable.ATTACHMENTS))
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att-gone", MergeVerdict.SKIPPED,
                MergeReason.ATTACHMENT_BYTES_ABSENT, "assets/a1/gone.pdf",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att-gone"),
        )
        assertEquals(listOf("att-here"), plan.writes.attachments.map { it.id.value })
    }

    /**
     * #44 acceptance 12, *"Verify size + SHA-256 before writing"*. Bytes at the locator that are
     * not the row's bytes are a diverged overlap, not a missing file: writing the row would leave a
     * destination whose recorded hash does not describe what is stored.
     */
    @Test
    fun `an attachment whose stored bytes do not match the row is a CONFLICT`() {
        val locator = "assets/a1/att1.pdf"
        val wrongHash = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(attachment("att1", "a1", locator))),
            snapshotOf(storedBytes = mapOf(locator to StoredBytes(sha256 = "b".repeat(64), sizeBytes = 4L))),
        )
        assertFalse(wrongHash.applicable)
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.CONFLICT,
                MergeReason.ATTACHMENT_BYTES_DIFFER, locator,
            ),
            wrongHash.decision(MergeTable.ATTACHMENTS, "att1"),
        )

        val wrongSize = mergePlanOf(
            backupOf(assets = listOf(asset("a1", "Hot tub")), attachments = listOf(attachment("att1", "a1", locator))),
            snapshotOf(storedBytes = mapOf(locator to StoredBytes(sha256 = bytesSha, sizeBytes = 9L))),
        )
        assertFalse(wrongSize.applicable)
        assertEquals(MergeReason.ATTACHMENT_BYTES_DIFFER, wrongSize.decision(MergeTable.ATTACHMENTS, "att1").reason)
    }

    /**
     * Decision 12: no attachment folder is a *configuration* state, not a data state, and it must
     * not be reported as missing bytes. The other six tables still merge and `applicable` stays
     * true.
     */
    @Test
    fun `with no attachment folder every attachment row is SKIPPED with its own reason`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                attachments = listOf(attachment("att1", "a1")),
            ),
            snapshotOf(attachmentStoreConfigured = false),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 1), plan.tally(MergeTable.ATTACHMENTS))
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.SKIPPED,
                MergeReason.ATTACHMENT_STORE_NOT_CONFIGURED, "assets/a1/att1.pdf",
            ),
            plan.decision(MergeTable.ATTACHMENTS, "att1"),
        )
        assertEquals(MergeTally(1, 0, 0, 0), plan.tally(MergeTable.ASSETS))
    }

    // --- hints, and the shape of the report -------------------------------------------------

    /** #44 identity 3 and acceptance 9: a review hint, and no action whatsoever. */
    @Test
    fun `manufacturer model and serial produce a duplicate candidate and change nothing`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a-import", "Mower", manufacturer = "Cub Cadet", model = "XT1", serial = "SN-7"))),
            snapshotOf(assets = listOf(asset("a-local", "Garage mower", manufacturer = "Cub Cadet", model = "XT1", serial = "SN-7"))),
        )

        assertTrue(plan.applicable)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a-import").verdict)
        assertEquals(
            listOf(DuplicateCandidate("a-import", "a-local", MergeHint.SAME_MANUFACTURER_MODEL_SERIAL)),
            plan.duplicateCandidates,
        )
    }

    /**
     * **Negative control**, and a real one: a name-based hint or a name-based identity would fail
     * this. #44 identity 4 and acceptance 9.
     */
    @Test
    fun `a matching name alone is neither identity nor a hint`() {
        val plan = mergePlanOf(
            backupOf(assets = listOf(asset("a-import", "Hot tub"))),
            snapshotOf(assets = listOf(asset("a-local", "Hot tub"))),
        )

        assertTrue(plan.applicable)
        assertEquals(emptyList(), plan.duplicateCandidates)
        assertEquals(MergeVerdict.INSERT, plan.decision(MergeTable.ASSETS, "a-import").verdict)
    }

    /** Deterministic reporting: table order, then id, with a stable code on every conflict. */
    @Test
    fun `conflicts are reported in table order then id, and the plan is reproducible`() {
        val backup = backupOf(
            assets = listOf(asset("a2", "Spa"), asset("a1", "Hot tub")),
            definitions = listOf(definition("d1", "a1", key = "ph")),
            events = listOf(event("e1", "a1", sourceRef = "ref-1")),
        )
        val snapshot = snapshotOf(
            assets = listOf(asset("a1", "Sauna"), asset("a2", "Pool")),
            definitions = listOf(definition("d-local", "a1", key = "ph")),
            events = listOf(event("e-local", "a1", sourceRef = "ref-1")),
        )

        val plan = mergePlanOf(backup, snapshot)
        val again = mergePlanOf(backup, snapshot)

        assertFalse(plan.applicable)
        assertEquals(
            listOf(
                MergeTable.ASSETS to "a1",
                MergeTable.ASSETS to "a2",
                MergeTable.DEFINITIONS to "d1",
                MergeTable.EVENTS to "e1",
            ),
            plan.conflicts.map { it.table to it.id },
        )
        assertTrue(plan.conflicts.none { it.reason == MergeReason.NONE })
        assertEquals(plan.decisions, again.decisions)
        assertEquals(plan.fingerprint, again.fingerprint)
    }

    /** Every table reports a tally, and the seven of them cover every decision exactly once. */
    @Test
    fun `the report accounts for every row exactly once`() {
        val plan = mergePlanOf(
            backupOf(
                assets = listOf(asset("a1", "Hot tub")),
                tags = listOf(tag("t1", "key-1", "a1")),
                links = listOf(link("l1")),
                definitions = listOf(definition("d1", "a1")),
                profiles = listOf(profile("p1", "a1", listOf(field("pf1", "d1")))),
                events = listOf(event("e1", "a1")),
                attachments = listOf(attachment("att1", "a1")),
            ),
            snapshotOf(),
        )
        val report = plan.report()
        val tallies = listOf(
            report.assets, report.definitions, report.profiles, report.links,
            report.tags, report.events, report.attachments,
        )

        assertEquals(
            plan.decisions.size,
            tallies.sumOf { it.insert + it.identical + it.conflict + it.skipped },
        )
        assertEquals(5, report.formatVersion)
        assertEquals("set-incoming", report.backupSetId)
        assertTrue(report.applicable)
        // One attachment, no bytes in the store: SKIPPED, and `applicable` is unaffected.
        assertEquals(MergeTally(0, 0, 0, 1), report.attachments)
    }
}
```

Two notes on the fixtures. `bytesSha` is the real SHA-256 of `byteArrayOf(1, 2, 3, 4)`; an earlier draft of this plan used `"0".repeat(64)` as an attachment row's hash while storing real bytes, which the new `ATTACHMENT_BYTES_DIFFER` rule would now — correctly — call a conflict. Verify it once before relying on it: `printf '\x01\x02\x03\x04' | sha256sum`. And `ProfileField`, `Measurement` and `ConsumableUsage` take plain `String` ids (`core/model/Journal.kt:24`, `:40`, `:46`), not value classes, which is why `field("pf1", "d1")` reads the way it does.

- [ ] **Step 2: Run it and watch it fail to compile.**

Run: `./gradlew :core:test --tests '*MergePlannerTest' --console=plain`
Expected: **FAILURE** — `Unresolved reference: mergePlanOf`, `MergeSnapshot`, `MergePlan`, `MergeTable`, `MergeVerdict`, `MergeReason`, `MergeDecision`, `MergeTally`, `MergeWrites`, `MergeHint`, `DuplicateCandidate`. That is the red.

- [ ] **Step 3: The plan's vocabulary.**

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt`:

```kotlin
package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.ports.StoredBytes
import java.security.MessageDigest

/**
 * The seven canonical tables, **in the order a merge must write them**: every reference a row makes
 * points at a table declared before it (assets first, attachment rows last, when every owner is
 * in). The ordinal is also the first key conflicts are sorted by, which is what makes a report
 * deterministic.
 */
enum class MergeTable { ASSETS, DEFINITIONS, PROFILES, LINKS, TAGS, EVENTS, ATTACHMENTS }

/**
 * What the plan decided about one incoming row.
 *
 * There is deliberately **no `UPDATE`** (#44): an id that is already here either matches, and is a
 * no-op, or differs, and is a conflict for the owner to resolve. So the only write a 1.1.0 merge
 * can make is an insert, and no pre-existing row can be modified by one.
 *
 * `SKIPPED` is the plan declining to write a row it could otherwise have written — in 1.1.0, an
 * attachment row whose bytes are not on this phone, or one on a phone with no attachment folder.
 */
enum class MergeVerdict { INSERT, IDENTICAL, CONFLICT, SKIPPED }

/**
 * A stable code per outcome kind, so a client branches on the code and never on prose. Every
 * `CONFLICT` and every `SKIPPED` carries one; `INSERT` carries [NONE], and so does an `IDENTICAL`
 * that matched on its own id.
 */
enum class MergeReason {
    NONE,

    /** The id is already here and some backup-format field differs. Never resolved automatically. */
    CONTENT_DIFFERS,

    /** A local tag holds this payload identity and binds a different asset (#44 case 3). */
    PAYLOAD_BOUND_TO_ANOTHER_ASSET,

    /** A local tag holds this payload identity, binds the same asset, and differs elsewhere. */
    PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG,

    /** A local tag under a different row id already is this tag, field for field (#44 case 1). */
    PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG,

    /** Two rows claim one payload identity — `nfc_tag(payload_format, payload_key)` is unique. */
    PAYLOAD_DUPLICATED_IN_ARCHIVE,

    /** `measurement_definition(asset_id, key)` is unique and something else holds this one. */
    DEFINITION_KEY_TAKEN,

    /** `asset_event(source, source_ref)` is unique and something else holds this one. */
    EVENT_SOURCE_REF_TAKEN,

    /** `attachment(storage_provider, storage_locator)` is unique and another row claims it. */
    ATTACHMENT_LOCATOR_TAKEN,

    /**
     * `profile_field(profile_id, definition_id)` is unique and this profile offers one reading
     * twice — the fifth unique index, which `BackupCodec.decode` does not check.
     */
    PROFILE_FIELD_DEFINITION_TAKEN,

    /**
     * An aggregate child row's own primary key is already held: `profile_field.id`,
     * `profile_consumable.id`, `measurement.id` or `consumable_usage.id`. Those ids are durable
     * identity (`core/model/Journal.kt:28`–`30`) and `decode` only checks them within one file.
     */
    CHILD_ROW_ID_TAKEN,

    /** A reference this row makes resolves to nothing local and to nothing this plan inserts. */
    OWNER_NOT_AVAILABLE,

    /** The parent it names sits under it once local and incoming rows are put together. */
    PARENT_CYCLE,

    /** The bytes this attachment row names are not in this phone's attachment folder. */
    ATTACHMENT_BYTES_ABSENT,

    /**
     * Bytes *are* at this locator and they are not the row's: the size or the sha256 differs. A
     * diverged file is a diverged overlap, so it is a conflict and not a skip (#44 acceptance 12).
     */
    ATTACHMENT_BYTES_DIFFER,

    /** This phone has no attachment folder, so no attachment row's bytes could be looked for. */
    ATTACHMENT_STORE_NOT_CONFIGURED,
}

/** A review hint (#44: "review hints only, never automatic identity"). It never blocks an apply. */
enum class MergeHint { SAME_MANUFACTURER_MODEL_SERIAL }

/**
 * One row's outcome. [detail] carries what it collided with — a local row's id, a taken key, a
 * child row's id, a locator — and **never** a display field.
 */
data class MergeDecision(
    val table: MergeTable,
    val id: String,
    val verdict: MergeVerdict,
    val reason: MergeReason = MergeReason.NONE,
    val detail: String = "",
)

/** Two asset rows that look like the same physical thing. Reported; never acted on. */
data class DuplicateCandidate(
    val incomingAssetId: String,
    val localAssetId: String,
    val hint: MergeHint,
)

/** One table's outcome, counted. */
data class MergeTally(val insert: Int, val identical: Int, val conflict: Int, val skipped: Int)

/**
 * The rows to insert. **The field order is the write order**, and each list is ordered within
 * itself — assets parents-first, definitions ENTERED-before-DERIVED. Empty in every field when the
 * plan holds a conflict, so "no partial merge" is a property of this value rather than a discipline
 * the apply has to remember.
 */
data class MergeWrites(
    val assets: List<Asset> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)

/**
 * One consistent view of this install, read once.
 *
 * The last two fields are the exception: they come from the attachment *store*, not the database.
 * [storedBytes] maps each locator the archive names — and only those — to the size and sha256 of
 * what is actually there, so the planner can answer #44's *"verify size + SHA-256 before writing"*
 * without opening a file itself. [attachmentStoreConfigured] is false when the owner has picked no
 * folder at all, which is a different fact from "the bytes are missing" and gets its own reason.
 */
data class MergeSnapshot(
    val assets: List<Asset> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val links: List<ExternalLink> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    val profiles: List<EventProfile> = emptyList(),
    val events: List<AssetEvent> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val storedBytes: Map<String, StoredBytes> = emptyMap(),
    val attachmentStoreConfigured: Boolean = true,
)

/**
 * What a plan says — the shape the API and the MCP report, for a plan and for an apply alike.
 *
 * [applicable] is the only thing a client has to read to know whether an apply will do anything:
 * it is true exactly when [conflicts] is empty. [duplicateCandidates] never makes it false, and
 * neither does a `SKIPPED` attachment row.
 */
data class MergeReport(
    val formatVersion: Int,
    val backupSetId: String,
    val applicable: Boolean,
    val assets: MergeTally,
    val definitions: MergeTally,
    val profiles: MergeTally,
    val links: MergeTally,
    val tags: MergeTally,
    val events: MergeTally,
    val attachments: MergeTally,
    /** Deterministic: table order, then id. */
    val conflicts: List<MergeDecision>,
    val duplicateCandidates: List<DuplicateCandidate>,
)

/**
 * A decision about every row in one archive, against one snapshot of this install — and nothing
 * else. Building it writes nothing; [MergeWrites] is what an apply would do, not what it has done.
 *
 * The constructor is `internal` because only [mergePlanOf] may make one: a plan that was not
 * produced by the planner is a plan nobody checked.
 */
class MergePlan internal constructor(
    /** The archive this was planned from, kept so an apply can re-plan against a fresh snapshot. */
    val backup: Backup,
    /** Every row's outcome, sorted by table order then id. */
    val decisions: List<MergeDecision>,
    val writes: MergeWrites,
    val duplicateCandidates: List<DuplicateCandidate>,
) {
    val conflicts: List<MergeDecision> = decisions.filter { it.verdict == MergeVerdict.CONFLICT }

    /** Whether an apply would write the union. One conflict anywhere makes this false. */
    val applicable: Boolean get() = conflicts.isEmpty()

    /**
     * A digest of what this plan *decided*, so an apply can tell "the same answer" from "a
     * different answer" without re-deriving it.
     *
     * It covers the archive's identity — `backupSetId` and the `dataSha256` of its `data.json` —
     * and then every decision's table, id, verdict, reason and detail. So a change to any local row
     * the plan looked at moves the verdict or the reason, and moves this. It deliberately does
     * **not** cover [duplicateCandidates]: a destination change that alters only the review hints
     * is not named stale, which is harmless because hints never affect [applicable] and nothing is
     * written differently for them.
     *
     * It is **not** the safety mechanism: rebuilding the plan inside the apply's transaction is
     * ([com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan]). This is what lets a stale
     * plan be *named* stale instead of quietly producing a different report.
     */
    val fingerprint: String = fingerprintOf(backup, decisions)

    fun tally(table: MergeTable): MergeTally {
        val rows = decisions.filter { it.table == table }
        return MergeTally(
            insert = rows.count { it.verdict == MergeVerdict.INSERT },
            identical = rows.count { it.verdict == MergeVerdict.IDENTICAL },
            conflict = rows.count { it.verdict == MergeVerdict.CONFLICT },
            skipped = rows.count { it.verdict == MergeVerdict.SKIPPED },
        )
    }

    fun report(): MergeReport = MergeReport(
        formatVersion = backup.manifest.formatVersion,
        backupSetId = backup.manifest.backupSetId,
        applicable = applicable,
        assets = tally(MergeTable.ASSETS),
        definitions = tally(MergeTable.DEFINITIONS),
        profiles = tally(MergeTable.PROFILES),
        links = tally(MergeTable.LINKS),
        tags = tally(MergeTable.TAGS),
        events = tally(MergeTable.EVENTS),
        attachments = tally(MergeTable.ATTACHMENTS),
        conflicts = conflicts,
        duplicateCandidates = duplicateCandidates,
    )
}

private fun fingerprintOf(backup: Backup, decisions: List<MergeDecision>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(backup.manifest.backupSetId.toByteArray(Charsets.UTF_8))
    digest.update(backup.manifest.dataSha256.toByteArray(Charsets.UTF_8))
    for (d in decisions) {
        digest.update(
            "${d.table.name}|${d.id}|${d.verdict.name}|${d.reason.name}|${d.detail}\n"
                .toByteArray(Charsets.UTF_8),
        )
    }
    return digest.digest().joinToString("") { b -> "%02x".format(b) }
}
```

- [ ] **Step 4: The planner.**

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlanner.kt`:

```kotlin
package com.loosecannon.servicetag.core.merge

import com.loosecannon.servicetag.core.backup.AssetEventDto
import com.loosecannon.servicetag.core.backup.Backup
import com.loosecannon.servicetag.core.backup.EventProfileDto
import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.ports.TagRepository
import java.io.IOException
import java.security.MessageDigest

/**
 * The merge planner (1.1.0, #46; semantics from #44): a **pure function** of one decoded archive
 * and one snapshot of this install, returning what a merge would do and writing nothing.
 *
 * ### How it decides
 *
 * Table by table, in [MergeTable] order, so that every reference a row makes points at a table
 * already decided. For each incoming row:
 *
 * 1. **Its own id.** Absent here → `INSERT`. Present with identical content → `IDENTICAL`. Present
 *    with any difference → `CONFLICT` / `CONTENT_DIFFERS`.
 * 2. **Its payload identity**, for tags only, and *independently of the row id* — #44's second
 *    identity rule. A local tag holding the same `(payloadFormat, payloadKey)` is the same logical
 *    tag: equivalent field for field → `IDENTICAL`, bound elsewhere → `CONFLICT`, diverged → also
 *    `CONFLICT`. Nothing is coalesced and nothing is remapped; that is #44's later slice.
 * 3. **Every uniqueness constraint the schema has** — the five unique indices *and* the four
 *    aggregate child-row primary keys — each checked against the destination and against the
 *    archive's own accepted rows, because a constraint does not care which side a duplicate came
 *    from.
 * 4. **Its references.** Every non-null one must resolve to a row that is either already here or
 *    an `INSERT` in this same plan; otherwise `OWNER_NOT_AVAILABLE`, which is a conflict and not an
 *    orphan. Plus, for an asset, the parent-tree cycle guard over local and incoming rows together.
 * 5. **Its bytes**, for attachment rows only: no folder → `SKIPPED`
 *    `ATTACHMENT_STORE_NOT_CONFIGURED`; nothing at the locator → `SKIPPED`
 *    `ATTACHMENT_BYTES_ABSENT`; bytes whose size or sha256 is not the row's → `CONFLICT`
 *    `ATTACHMENT_BYTES_DIFFER`.
 *
 * ### What it never does
 *
 * It never writes a row, never removes one, never chooses a winner by any single field, never
 * treats a name or a physical UID as identity, and never produces a partial write set: one conflict
 * anywhere empties [MergePlan.writes].
 *
 * **Canonical content is every backup-format field**, `createdAt` and the last-modified stamp
 * included, compared as `incoming == local.toDto()` in every pass and in the same direction. The
 * only normalisation is that the two aggregate tables' child lists are read in `(sortOrder, id)`
 * order: `sortOrder` is the order the format writes them in (`BackupCodec.kt:85`–`96`) and the id
 * makes the key total, because the format does not promise `sortOrder` is unique within a parent.
 *
 * ### Not total, and only for a hand-built [Backup]
 *
 * This propagates `IllegalStateException` from [AssetTree.parentsFirst] on a cyclic asset set,
 * `BackupCorrupt` from a `toDomain()` that cannot name a value, and an NPE from the attachment
 * pass's `eventId!!` if a row names neither owner. `BackupCodec.decode` refuses all three before a
 * plan exists, so no caller of `BuildBackupMergePlan` can reach them — only a test constructing a
 * `Backup` directly can. See Task 1 decision 10 for what the API maps them to.
 */
internal fun mergePlanOf(backup: Backup, snapshot: MergeSnapshot): MergePlan {
    val data = backup.data
    val decisions = mutableListOf<MergeDecision>()

    val localAssets = snapshot.assets.associateBy { it.id.value }
    val localTags = snapshot.tags.associateBy { it.id.value }
    val localTagsByPayload = snapshot.tags.associateBy { it.payloadFormat.name to it.payloadKey }
    val localLinks = snapshot.links.associateBy { it.id.value }
    val localDefinitions = snapshot.definitions.associateBy { it.id.value }
    val localProfiles = snapshot.profiles.associateBy { it.id.value }
    val localEvents = snapshot.events.associateBy { it.id.value }
    val localAttachments = snapshot.attachments.associateBy { it.id.value }

    // The claim maps unify the two halves of every uniqueness check: each starts with what this
    // install holds and gains what this plan accepts, so a duplicate inside the archive is caught by
    // the same line that catches a collision with a local row. Value = the id that holds the claim.
    val claimedPayloads = snapshot.tags
        .associateTo(mutableMapOf()) { (it.payloadFormat.name to it.payloadKey) to it.id.value }
    val claimedDefinitionKeys = snapshot.definitions
        .associateTo(mutableMapOf()) { (it.assetId.value to it.key) to it.id.value }
    val claimedSourceRefs = snapshot.events
        .filter { it.sourceRef != null }
        .associateTo(mutableMapOf()) { (it.source.name to it.sourceRef!!) to it.id.value }
    val claimedLocators = snapshot.attachments
        .associateTo(mutableMapOf()) { (it.storageProvider.name to it.storageLocator) to it.id.value }
    // The fifth unique index. Seeded from the destination as defence in depth: the key contains the
    // profile's own id and a merge only inserts new profile ids, so the destination arm is
    // unreachable in 1.1.0 (decision 6) — the live case is one profile listing a reading twice.
    val claimedProfileFieldPairs = snapshot.profiles
        .flatMap { p -> p.fields.map { (p.id.value to it.definitionId.value) to p.id.value } }
        .toMap(mutableMapOf())
    // The four aggregate child-row primary keys. These *are* reachable from the destination: two
    // distinct aggregates, one local and one incoming, can carry the same child id.
    val claimedFieldIds = snapshot.profiles
        .flatMap { p -> p.fields.map { it.id to p.id.value } }.toMap(mutableMapOf())
    val claimedProfileConsumableIds = snapshot.profiles
        .flatMap { p -> p.consumables.map { it.id to p.id.value } }.toMap(mutableMapOf())
    val claimedMeasurementIds = snapshot.events
        .flatMap { e -> e.measurements.map { it.id to e.id.value } }.toMap(mutableMapOf())
    val claimedUsageIds = snapshot.events
        .flatMap { e -> e.consumables.map { it.id to e.id.value } }.toMap(mutableMapOf())

    // --- assets -----------------------------------------------------------------------------
    // Decided parents-first, so a child always sees whether its parent was accepted.
    // `parentsFirst` gives indegree 0 to an asset whose parent is outside the collection
    // (`AssetTree.kt:54`–`56`), which is exactly a parent that lives in the destination.
    val assetDtos = data.assets.associateBy { it.id }
    val assetWrites = mutableListOf<Asset>()
    val acceptedAssets = mutableSetOf<String>()
    for (row in AssetTree.parentsFirst(data.assets.map { it.toDomain() })) {
        val id = row.id.value
        val dto = assetDtos.getValue(id)
        val local = localAssets[id]
        val parent = row.parentAssetId?.value
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            parent != null && parent !in localAssets && parent !in acceptedAssets ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, parent)
            parent != null &&
                AssetTree.wouldCycle(localAssets.values + assetWrites, row.id, row.parentAssetId) ->
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.CONFLICT, MergeReason.PARENT_CYCLE, parent)
            else -> {
                assetWrites += row
                acceptedAssets += id
                MergeDecision(MergeTable.ASSETS, id, MergeVerdict.INSERT)
            }
        }
    }

    /** True when a reference to an asset resolves — to a local row, or to one this plan inserts. */
    fun assetAvailable(assetId: String) = assetId in localAssets || assetId in acceptedAssets

    // --- definitions ------------------------------------------------------------------------
    // ENTERED before DERIVED, so a derived row's two sources are already accepted when it is
    // decided — the same order `ImportBackupReplace.kt:73`–`75` writes them in.
    val definitionWrites = mutableListOf<MeasurementDefinition>()
    val acceptedDefinitions = mutableSetOf<String>()
    val (entered, derived) = data.measurementDefinitions
        .partition { it.kind == DefinitionKind.ENTERED.name }
    for (dto in entered + derived) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localDefinitions[id]
        val owner = row.assetId.value
        val keyHolder = claimedDefinitionKeys[owner to row.key]
        val missingSource = row.derived
            ?.let { listOf(it.sourceA.value, it.sourceB.value) }.orEmpty()
            .firstOrNull { it !in localDefinitions && it !in acceptedDefinitions }
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            keyHolder != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.DEFINITION_KEY_TAKEN, keyHolder)
            missingSource != null ->
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingSource)
            else -> {
                definitionWrites += row
                acceptedDefinitions += id
                claimedDefinitionKeys[owner to row.key] = id
                MergeDecision(MergeTable.DEFINITIONS, id, MergeVerdict.INSERT)
            }
        }
    }

    fun definitionAvailable(id: String) = id in localDefinitions || id in acceptedDefinitions

    // --- profiles ---------------------------------------------------------------------------
    val profileWrites = mutableListOf<EventProfile>()
    val acceptedProfiles = mutableSetOf<String>()
    for (dto in data.eventProfiles) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localProfiles[id]
        val owner = row.assetId.value
        val missingField = row.fields.map { it.definitionId.value }.firstOrNull { !definitionAvailable(it) }
        val takenPair = firstTakenPair(
            row.fields.map { id to it.definitionId.value },
            claimedProfileFieldPairs,
        )
        val takenChild = firstTaken(row.fields.map { it.id }, claimedFieldIds)
            ?: firstTaken(row.consumables.map { it.id }, claimedProfileConsumableIds)
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            missingField != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingField)
            takenPair != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.PROFILE_FIELD_DEFINITION_TAKEN, takenPair.second)
            takenChild != null ->
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, takenChild)
            else -> {
                profileWrites += row
                acceptedProfiles += id
                row.fields.forEach {
                    claimedProfileFieldPairs[id to it.definitionId.value] = id
                    claimedFieldIds[it.id] = id
                }
                row.consumables.forEach { claimedProfileConsumableIds[it.id] = id }
                MergeDecision(MergeTable.PROFILES, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- links (2.6 tombstones: carried, never displayed) -----------------------------------
    val linkWrites = mutableListOf<ExternalLink>()
    val acceptedLinks = mutableSetOf<String>()
    for (dto in data.externalLinks) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localLinks[id]
        val owner = dto.assetId
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            owner != null && !assetAvailable(owner) ->
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            else -> {
                linkWrites += row
                acceptedLinks += id
                MergeDecision(MergeTable.LINKS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- tags -------------------------------------------------------------------------------
    // #44's second identity rule, and the `nfc_tag(payload_format, payload_key)` unique index
    // (`NfcTagEntity.kt:26`). The payload lookup is deliberately independent of the row id.
    val tagWrites = mutableListOf<TagBinding>()
    for (dto in data.nfcTags) {
        val row = dto.toDomain()
        val id = dto.id
        val payload = dto.payloadFormat to dto.payloadKey
        val local = localTags[id]
        val samePayload = localTagsByPayload[payload]
        val payloadHolder = claimedPayloads[payload]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            // A local tag under a different row id already *is* this tag, field for field.
            samePayload != null && dto.copy(id = samePayload.id.value) == samePayload.toDto() ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.IDENTICAL,
                    MergeReason.PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG, samePayload.id.value,
                )
            samePayload != null && samePayload.toDto().assetId != dto.assetId ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_BOUND_TO_ANOTHER_ASSET, samePayload.id.value,
                )
            samePayload != null ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG, samePayload.id.value,
                )
            // No local tag holds it, so any holder left is another row of this archive.
            payloadHolder != null ->
                MergeDecision(
                    MergeTable.TAGS, id, MergeVerdict.CONFLICT,
                    MergeReason.PAYLOAD_DUPLICATED_IN_ARCHIVE, payloadHolder,
                )
            dto.assetId != null && !assetAvailable(dto.assetId) ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.assetId)
            dto.linkId != null && dto.linkId !in localLinks && dto.linkId !in acceptedLinks ->
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.linkId)
            else -> {
                tagWrites += row
                claimedPayloads[payload] = id
                MergeDecision(MergeTable.TAGS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- events -----------------------------------------------------------------------------
    // History is append-oriented (#44): nothing here looks at a date, a title or a measurement
    // value. The only identities are the row's own id, its `(source, sourceRef)`, and its children's
    // durable ids.
    val eventWrites = mutableListOf<AssetEvent>()
    val acceptedEvents = mutableSetOf<String>()
    for (dto in data.assetEvents) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localEvents[id]
        val owner = dto.assetId
        val refHolder = dto.sourceRef?.let { claimedSourceRefs[dto.source to it] }
        val missingDefinition = dto.measurements.map { it.definitionId }.firstOrNull { !definitionAvailable(it) }
        val takenChild = firstTaken(row.measurements.map { it.id }, claimedMeasurementIds)
            ?: firstTaken(row.consumables.map { it.id }, claimedUsageIds)
        decisions += when {
            local != null && dto.ordered() == local.toDto().ordered() ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !assetAvailable(owner) ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            dto.profileId != null && dto.profileId !in localProfiles && dto.profileId !in acceptedProfiles ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, dto.profileId)
            refHolder != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.EVENT_SOURCE_REF_TAKEN, refHolder)
            missingDefinition != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, missingDefinition)
            takenChild != null ->
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.CONFLICT, MergeReason.CHILD_ROW_ID_TAKEN, takenChild)
            else -> {
                eventWrites += row
                acceptedEvents += id
                dto.sourceRef?.let { claimedSourceRefs[dto.source to it] = id }
                row.measurements.forEach { claimedMeasurementIds[it.id] = id }
                row.consumables.forEach { claimedUsageIds[it.id] = id }
                MergeDecision(MergeTable.EVENTS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- attachments ------------------------------------------------------------------------
    // Four questions in one fixed order (decision 11). The locator check comes first on purpose:
    // when a local row already claims a locator its bytes are of course present, so asking "are the
    // bytes here?" first would answer yes and produce an INSERT the unique index refuses.
    val attachmentWrites = mutableListOf<Attachment>()
    for (dto in data.attachments) {
        val row = dto.toDomain()
        val id = dto.id
        val local = localAttachments[id]
        val locatorKey = dto.storageProvider to dto.storageLocator
        val locatorHolder = claimedLocators[locatorKey]
        val owner = dto.assetId ?: dto.eventId!!
        val ownerAvailable = if (dto.assetId != null) {
            assetAvailable(dto.assetId)
        } else {
            dto.eventId in localEvents || dto.eventId in acceptedEvents
        }
        val stored = snapshot.storedBytes[dto.storageLocator]
        decisions += when {
            local != null && dto == local.toDto() ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.IDENTICAL)
            local != null ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.CONTENT_DIFFERS, id)
            !ownerAvailable ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.OWNER_NOT_AVAILABLE, owner)
            locatorHolder != null ->
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT, MergeReason.ATTACHMENT_LOCATOR_TAKEN, locatorHolder)
            !snapshot.attachmentStoreConfigured ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.SKIPPED,
                    MergeReason.ATTACHMENT_STORE_NOT_CONFIGURED, dto.storageLocator,
                )
            stored == null ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.SKIPPED,
                    MergeReason.ATTACHMENT_BYTES_ABSENT, dto.storageLocator,
                )
            // #44 acceptance 12: verify size + SHA-256 before writing.
            stored.sha256 != dto.sha256 || stored.sizeBytes != dto.sizeBytes ->
                MergeDecision(
                    MergeTable.ATTACHMENTS, id, MergeVerdict.CONFLICT,
                    MergeReason.ATTACHMENT_BYTES_DIFFER, dto.storageLocator,
                )
            else -> {
                attachmentWrites += row
                claimedLocators[locatorKey] = id
                MergeDecision(MergeTable.ATTACHMENTS, id, MergeVerdict.INSERT)
            }
        }
    }

    // --- review hints, which change nothing (#44 identity 3) --------------------------------
    val localBySignature = snapshot.assets
        .filter { it.manufacturer.isNotBlank() && it.model.isNotBlank() && it.serialNumber.isNotBlank() }
        .groupBy { Triple(it.manufacturer, it.model, it.serialNumber) }
    val candidates = data.assets
        .filter { it.manufacturer.isNotBlank() && it.model.isNotBlank() && it.serialNumber.isNotBlank() }
        .flatMap { dto ->
            localBySignature[Triple(dto.manufacturer, dto.model, dto.serialNumber)].orEmpty()
                .filter { it.id.value != dto.id }
                .map { DuplicateCandidate(dto.id, it.id.value, MergeHint.SAME_MANUFACTURER_MODEL_SERIAL) }
        }
        .sortedWith(compareBy({ it.incomingAssetId }, { it.localAssetId }))

    val ordered = decisions.sortedWith(compareBy({ it.table.ordinal }, { it.id }))
    val hasConflict = ordered.any { it.verdict == MergeVerdict.CONFLICT }
    return MergePlan(
        backup = backup,
        decisions = ordered,
        // No partial merge, made structural rather than remembered.
        writes = if (hasConflict) {
            MergeWrites()
        } else {
            MergeWrites(
                assets = assetWrites,
                definitions = definitionWrites,
                profiles = profileWrites,
                links = linkWrites,
                tags = tagWrites,
                events = eventWrites,
                attachments = attachmentWrites,
            )
        },
        duplicateCandidates = candidates,
    )
}

/** The first id [claimed] already holds, or the first that repeats within [ids]; null if none. */
private fun firstTaken(ids: List<String>, claimed: Map<String, String>): String? {
    val seen = mutableSetOf<String>()
    return ids.firstOrNull { it in claimed || !seen.add(it) }
}

/** The pair form of [firstTaken], for `profile_field(profile_id, definition_id)`. */
private fun firstTakenPair(
    pairs: List<Pair<String, String>>,
    claimed: Map<Pair<String, String>, String>,
): Pair<String, String>? {
    val seen = mutableSetOf<Pair<String, String>>()
    return pairs.firstOrNull { it in claimed || !seen.add(it) }
}

/**
 * Child lists in `(sortOrder, id)`. `sortOrder` is the order the backup format writes them in
 * (`BackupCodec.kt:85`–`96`); the id is the tie-break, because the format does not promise
 * `sortOrder` is unique within a parent and two stable sorts over two undefined bases would
 * otherwise disagree (decision 4).
 */
private fun EventProfileDto.ordered() = copy(
    fields = fields.sortedWith(compareBy({ it.sortOrder }, { it.id })),
    consumables = consumables.sortedWith(compareBy({ it.sortOrder }, { it.id })),
)

private fun AssetEventDto.ordered() = copy(
    measurements = measurements.sortedWith(compareBy({ it.sortOrder }, { it.id })),
    consumables = consumables.sortedWith(compareBy({ it.sortOrder }, { it.id })),
)

/**
 * What the store actually holds for each locator the archive names — **asked outside any
 * transaction**, because `open` is a document-provider round trip and a Room write transaction is
 * not where an IPC belongs.
 *
 * Each file is hashed and sized in one streaming pass with a 64 KiB buffer, exactly as
 * `AttachmentSweep.kt:45`–`65`'s `sha256Of` does and for the same reason: an attachment can be
 * hundreds of megabytes and is never materialised. A locator that is absent, or that opens and then
 * cannot be read, is simply not in the result — which the planner reports as
 * `ATTACHMENT_BYTES_ABSENT`, the same answer `sha256Of` gives an unreadable file.
 *
 * Returns an empty map when there is no attachment folder at all; the caller passes that fact
 * separately, because "no folder" and "no bytes" are different answers.
 */
internal suspend fun storedBytesOf(
    backup: Backup,
    storage: AttachmentStorage,
): Map<String, StoredBytes> {
    val store = storage.store() ?: return emptyMap()
    val answers = LinkedHashMap<String, StoredBytes>()
    for (locator in backup.data.attachments.map { it.storageLocator }.distinct()) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val source = store.open(locator) ?: continue
        val readable = try {
            source.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    size += n
                }
            }
            true
        } catch (e: IOException) {
            false
        }
        if (!readable) continue
        answers[locator] = StoredBytes(
            sha256 = digest.digest().joinToString("") { b -> "%02x".format(b) },
            sizeBytes = size,
        )
    }
    return answers
}

/** Seven reads. **The caller owns the transaction** — see each use case for which one. */
internal suspend fun mergeSnapshotOf(
    assets: AssetRepository,
    tags: TagRepository,
    links: LinkRepository,
    definitions: DefinitionRepository,
    profiles: ProfileRepository,
    events: EventRepository,
    attachments: AttachmentRepository,
    storedBytes: Map<String, StoredBytes>,
    attachmentStoreConfigured: Boolean,
): MergeSnapshot = MergeSnapshot(
    assets = assets.all(),
    tags = tags.all(),
    links = links.all(),
    definitions = definitions.all(),
    profiles = profiles.all(),
    events = events.all(),
    attachments = attachments.all(),
    storedBytes = storedBytes,
    attachmentStoreConfigured = attachmentStoreConfigured,
)
```

- [ ] **Step 5: Run the planner test and watch it pass.**

Run: `./gradlew :core:test --tests '*MergePlannerTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, **33** cases green. Read the class row off `core/build/reports/tests/test/classes/com.loosecannon.servicetag.core.merge.MergePlannerTest.html`.

- [ ] **Step 6: Write the failing end-to-end test.**

Create `core/src/test/kotlin/com/loosecannon/servicetag/core/usecase/ImportBackupMergeTest.kt` (**9 cases**). This half uses the real `BackupCodec` through the production `ExportBackupSet`, the in-memory fakes, and the apply.

```kotlin
package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * 1.1.0 (#46), semantics from #44 — the merge end to end: a real format-5 archive, the in-memory
 * repositories, one transaction, and the two refusals.
 *
 * The rules themselves are `MergePlannerTest`'s, where they are values. What is proved here is what
 * only the wiring can prove: that a plan over real bytes matches a plan over real rows, that an
 * apply writes the union in dependency order, that a refusal leaves the destination **byte for
 * byte** as it was (asserted by snapshotting every fake before and after), and that a plan built
 * against one destination cannot be applied to a different one.
 */
class ImportBackupMergeTest {

    private class Fakes {
        val assets = InMemoryAssetRepository()
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(assets, tags, links, definitions, profiles, events, attachments)

        val build = BuildBackupMergePlan(
            assets, tags, links, definitions, profiles, events, attachments, storage, uow,
        )
        val apply = ApplyBackupMergePlan(
            assets, tags, links, definitions, profiles, events, attachments, storage, uow,
        )
        val merge = ImportBackupMerge(build, apply)

        /** Everything this install holds, as comparable values. */
        fun everything(): List<Any> = runBlocking {
            listOf(
                assets.all().sortedBy { it.id.value },
                tags.all().sortedBy { it.id.value },
                links.all().sortedBy { it.id.value },
                definitions.all().sortedBy { it.id.value },
                profiles.all().sortedBy { it.id.value },
                events.all().sortedBy { it.id.value },
                attachments.all().sortedBy { it.id.value },
            )
        }
    }

    private fun asset(id: String, name: String, parent: String? = null) = Asset(
        id = AssetId(id), name = name, createdAt = 1L, updatedAt = 2L,
        parentAssetId = parent?.let(::AssetId),
    )

    private fun tag(id: String, key: String, assetId: String) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = key,
        target = TagTarget.AssetTarget(AssetId(assetId)), createdAt = 3L, updatedAt = 4L,
    )

    /** Four bytes, and an attachment row that tells the truth about them. */
    private val bytes = byteArrayOf(1, 2, 3, 4)

    private fun attachment(id: String, assetId: String) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId(assetId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf",
        mimeType = "application/pdf",
        sizeBytes = bytes.size.toLong(),
        // The row's hash must describe the bytes, or the merge is right to refuse it — see
        // `MergeReason.ATTACHMENT_BYTES_DIFFER`. The store's own helper computes it in one place.
        sha256 = InMemoryAttachmentStore.sha256Hex(bytes),
        storageLocator = "assets/$assetId/$id.pdf", capturedOn = null,
        createdAt = 9L, updatedAt = 10L,
    )

    /** A real format-5 data archive of [f]'s rows, through the production export. */
    private fun exportOf(f: Fakes): ByteArray = runBlocking {
        ExportBackupSet(
            f.assets, f.tags, f.links, f.definitions, f.profiles, f.events, f.attachments,
            f.uow, IdGenerator { "set-merge" }, Clock { 1_758_400_000_000L },
            appVersion = "1.1.0", schemaVersion = 5,
        ).run().data
    }

    @Test
    fun `a disjoint archive merges into a non-empty install and leaves the local rows alone`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.assets.upsert(asset("a2", "Filter", parent = "a1"))
            source.tags.upsert(tag("t1", "key-1", "a1"))
        }
        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("z9", "Generator"))
            target.tags.upsert(tag("t9", "key-9", "z9"))
        }

        val report = runBlocking { target.merge.run(exportOf(source)) }

        assertTrue(report.applicable)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), report.assets)
        assertEquals(MergeTally(1, 0, 0, 0), report.tags)
        assertEquals(emptyList(), report.conflicts)
        runBlocking {
            assertEquals(listOf("a1", "a2", "z9"), target.assets.all().map { it.id.value }.sorted())
            // #44 acceptance 2: the imported UUIDs are preserved exactly.
            assertEquals("Hot tub", target.assets.get(AssetId("a1"))!!.name)
            assertEquals(AssetId("a1"), target.assets.get(AssetId("a2"))!!.parentAssetId)
            // #44 acceptance 1: the pre-existing rows are untouched.
            assertEquals(asset("z9", "Generator"), target.assets.get(AssetId("z9")))
            assertEquals(tag("t9", "key-9", "z9"), target.tags.get(TagId("t9")))
        }
    }

    /** #44 acceptance 4. */
    @Test
    fun `importing the same archive twice is idempotent`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.tags.upsert(tag("t1", "key-1", "a1"))
        }
        val archive = exportOf(source)
        val target = Fakes()

        runBlocking { target.merge.run(archive) }
        val after = target.everything()
        val second = runBlocking { target.merge.run(archive) }

        assertTrue(second.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), second.assets)
        assertEquals(MergeTally(0, 1, 0, 0), second.tags)
        assertEquals(after, target.everything())
    }

    /** #44 acceptance 5 and 13: a conflict refuses, and mutates nothing at all. */
    @Test
    fun `a conflicting archive is refused and the destination is byte for byte unchanged`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("a1", "Spa"))
            target.assets.upsert(asset("z9", "Generator"))
        }
        val before = target.everything()

        val refused = assertFailsWith<MergeRefused> { runBlocking { target.merge.run(exportOf(source)) } }

        assertFalse(refused.report.applicable)
        assertEquals(1, refused.report.conflicts.size)
        assertEquals(MergeTable.ASSETS, refused.report.conflicts.single().table)
        assertEquals(MergeReason.CONTENT_DIFFERS, refused.report.conflicts.single().reason)
        assertEquals(before, target.everything())
    }

    /** The plan endpoint's whole promise: it decides and writes nothing. */
    @Test
    fun `planning writes nothing, whether the plan is applicable or not`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val archive = exportOf(source)

        val clean = Fakes()
        val cleanBefore = clean.everything()
        val cleanPlan = runBlocking { clean.merge.plan(archive) }
        assertTrue(cleanPlan.applicable)
        assertEquals(cleanBefore, clean.everything())

        val dirty = Fakes()
        runBlocking { dirty.assets.upsert(asset("a1", "Spa")) }
        val dirtyBefore = dirty.everything()
        val dirtyPlan = runBlocking { dirty.merge.plan(archive) }
        assertFalse(dirtyPlan.applicable)
        assertEquals(dirtyBefore, dirty.everything())
    }

    /**
     * TOCTOU, half one: the destination gains a **conflicting** row between the plan and the apply.
     * The apply re-plans inside its own transaction and refuses with `MergeRefused` — not
     * `MergePlanStale` — because a conflict is the more useful answer and is the one that carries a
     * conflict list. Decision 14: the `applicable` check comes before the fingerprint check
     * precisely so that this case cannot be swallowed by the staleness one.
     */
    @Test
    fun `a destination that gained a conflicting row between plan and apply is refused as a conflict`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val target = Fakes()

        val plan = runBlocking { target.build.run(exportOf(source)) }
        assertTrue(plan.applicable)

        runBlocking { target.assets.upsert(asset("a1", "Spa")) }
        val before = target.everything()

        val refused = assertFailsWith<MergeRefused> { runBlocking { target.apply.run(plan) } }

        assertEquals(MergeReason.CONTENT_DIFFERS, refused.report.conflicts.single().reason)
        assertEquals("a1", refused.report.conflicts.single().id)
        assertEquals(before, target.everything())
    }

    /**
     * TOCTOU, half two, and the only case `MergePlanStale` is for: the destination changed in a way
     * that changes a *verdict* **without** creating a conflict — it gained the very row the plan was
     * going to insert. The fresh plan is applicable, so nothing but the fingerprint can tell that it
     * is not what was accepted.
     */
    @Test
    fun `a plan whose verdicts no longer hold is refused as stale and writes nothing`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val target = Fakes()

        val plan = runBlocking { target.build.run(exportOf(source)) }
        assertEquals(MergeTally(1, 0, 0, 0), plan.report().assets)

        runBlocking { target.assets.upsert(asset("a1", "Hot tub")) }
        val before = target.everything()

        val stale = assertFailsWith<MergePlanStale> { runBlocking { target.apply.run(plan) } }

        assertTrue(stale.report.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), stale.report.assets)
        assertEquals(before, target.everything())
    }

    /**
     * The three bytes answers, end to end: no folder → SKIPPED with its own reason; a folder and no
     * bytes → SKIPPED absent; a folder and the row's own bytes → INSERT. The fourth answer, bytes
     * that are *not* the row's, is `MergePlannerTest`'s, where the store does not have to be faked
     * into lying.
     */
    @Test
    fun `an attachment row is written only when the store holds the bytes the row claims`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.attachments.upsert(attachment("att1", "a1"))
        }
        val archive = exportOf(source)

        val noFolder = Fakes()
        noFolder.storage.state = com.loosecannon.servicetag.core.ports.StoreState.NotConfigured
        val skippedNoFolder = runBlocking { noFolder.merge.run(archive) }
        assertTrue(skippedNoFolder.applicable)
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 1), skippedNoFolder.attachments)
        assertEquals(MergeTally(1, 0, 0, 0), skippedNoFolder.assets)

        val noBytes = Fakes()
        val skippedNoBytes = runBlocking { noBytes.merge.run(archive) }
        assertEquals(MergeTally(0, 0, 0, 1), skippedNoBytes.attachments)

        val withBytes = Fakes()
        withBytes.storage.store.files["assets/a1/att1.pdf"] = bytes
        val written = runBlocking { withBytes.merge.run(archive) }
        assertEquals(MergeTally(1, 0, 0, 0), written.attachments)
        runBlocking { assertEquals(1, withBytes.attachments.count()) }
    }

    @Test
    fun `a corrupt archive is refused before a plan exists`() {
        val target = Fakes()
        runBlocking { target.assets.upsert(asset("z9", "Generator")) }
        val before = target.everything()

        assertFailsWith<BackupCorrupt> { runBlocking { target.merge.plan(byteArrayOf(1, 2, 3)) } }
        assertFailsWith<BackupCorrupt> { runBlocking { target.merge.run(byteArrayOf(1, 2, 3)) } }

        assertEquals(before, target.everything())
    }

    @Test
    fun `an archive from a newer format is refused before a plan exists`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val newer = BackupCodec.decode(exportOf(source)).let { decoded ->
            BackupCodec.encode(
                decoded.data, appVersion = "9.9.9", schemaVersion = 5,
                createdAt = 1_758_400_000_000L, backupSetId = "set-merge",
                formatVersion = BackupCodec.FORMAT_VERSION + 1,
            )
        }
        val target = Fakes()
        val before = target.everything()

        assertFailsWith<BackupNewerFormat> { runBlocking { target.merge.run(newer) } }

        assertEquals(before, target.everything())
    }
}
```

Three notes. `BackupCodec.encode`'s six-argument overload is `internal` (`BackupCodec.kt:72`) and exists so a test can seal a manifest claiming a format this codec does not write — `BackupUseCasesTest` uses it the same way. `InMemoryAttachmentStore.sha256Hex` is a public companion helper (`core/src/test/.../testing/InMemoryAttachmentStore.kt:47`–`51`), which is why the fixture's row can tell the truth about its bytes in one line rather than carrying a hard-coded digest. And `StoreState.NotConfigured` is named fully-qualified inline because it appears exactly once.

- [ ] **Step 7: Run it and watch it fail to compile.**

Run: `./gradlew :core:test --tests '*ImportBackupMergeTest' --console=plain`
Expected: **FAILURE** — `Unresolved reference: BuildBackupMergePlan`, `ApplyBackupMergePlan`, `ImportBackupMerge`, `MergeRefused`, `MergePlanStale`. That is the red.

- [ ] **Step 8: The three use cases and their two refusals.**

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/MergeErrors.kt`:

```kotlin
package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeReport

/**
 * The merge was refused because the plan holds conflicts. **Nothing was written** — the refusal is
 * raised before the transaction opens, or inside it, where it rolls back.
 *
 * [report] is the full report, conflicts and all, so the caller can say what overlapped without
 * asking again.
 */
class MergeRefused(val report: MergeReport) :
    IllegalStateException("merge refused: ${report.conflicts.size} conflict(s)")

/**
 * The destination changed between the plan being built and the plan being applied, in a way that
 * changes what the plan decided — and is **still** conflict-free, which is what makes this a
 * separate answer from [MergeRefused]. A change that introduced a conflict is reported as the
 * conflict it is.
 *
 * [report] is the *fresh* plan's report — what a merge would do now — so a caller can retry against
 * it directly. Over the loopback API the two calls are microseconds apart and this is nearly
 * unreachable; it is the guard for a future screen where the owner reviews a plan and applies it
 * minutes later (#44 slice E).
 */
class MergePlanStale(val report: MergeReport) :
    IllegalStateException("the destination changed since this plan was built")
```

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/BuildBackupMergePlan.kt`:

```kotlin
package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Decides what merging one archive into this install would do, and **writes nothing** (1.1.0, #46;
 * semantics from #44).
 *
 * Four steps and no more: decode — which refuses a corrupt or future-format file before anything
 * else happens — ask whether there is an attachment folder at all, hash and size whatever that
 * folder holds for the locators the archive names, and read the seven tables in one `uow.read` so
 * the planner sees a single consistent point in time rather than seven. The decision itself is
 * `mergePlanOf`, a pure function.
 *
 * The same nine collaborators, in the same order, as [ImportBackupReplace] — because the two are
 * the two halves of the same question, and a reader comparing them should have nothing to subtract.
 */
class BuildBackupMergePlan(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
) {
    suspend fun run(bytes: ByteArray): MergePlan {
        val backup = BackupCodec.decode(bytes)
        // Both store questions before the transaction: `store()` resolves a preference and a grant,
        // and `open` is a document-provider round trip. Neither belongs inside a Room transaction.
        val configured = storage.store() != null
        val stored = storedBytesOf(backup, storage)
        val snapshot = uow.read {
            mergeSnapshotOf(
                assets, tags, links, definitions, profiles, events, attachments, stored, configured,
            )
        }
        return mergePlanOf(backup, snapshot)
    }
}
```

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ApplyBackupMergePlan.kt`:

```kotlin
package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Applies an accepted, conflict-free plan — and refuses everything else (1.1.0, #46; #44's
 * "apply accepted plan in one Room transaction").
 *
 * **It does not execute the plan it is handed.** It re-reads the snapshot *inside its own write
 * transaction*, re-runs the pure planner over the archive the plan carries, and applies only that
 * fresh result. So there is no window between deciding and writing: a plan is never applied to a
 * destination it was not just checked against.
 *
 * **A conflict is named before staleness.** On the rebuilt plan, `applicable` is checked first and
 * the fingerprint second. That order is load-bearing: the fingerprint digests every decision, so
 * *any* destination change that introduces a conflict necessarily changes a verdict and therefore
 * the fingerprint — checking it first would make a conflict-on-rebuild unreachable and would answer
 * a real conflict with a stale-plan report that carries no conflict list. In this order
 * [MergeRefused] answers every conflicted rebuild, and [MergePlanStale] is reserved for its one
 * real case: a destination that changed, is still conflict-free, and no longer matches what was
 * accepted.
 *
 * The snapshot is re-read with the repositories directly and **not** through a nested `uow.read`:
 * [UnitOfWork.read]'s own contract says writing inside it is illegal, and a write transaction
 * already gives one consistent view. The two store questions are asked before the transaction
 * opens, for the same reason [BuildBackupMergePlan] asks them there — `open` is a provider round
 * trip. One consequence, stated rather than hidden: a file that vanishes between that read and the
 * write still yields an INSERT, because there is no transaction that spans Room and a document
 * provider. `docs/api/v1.md` records it.
 *
 * Every refusal happens before the first row is written, and any that did not would roll back with
 * the transaction. So a refused merge leaves this install byte for byte as it was, which is #44's
 * acceptance criteria 13 and 14.
 */
class ApplyBackupMergePlan(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
) {
    suspend fun run(plan: MergePlan): MergeReport {
        // The cheap refusal first, so a plan the caller already knows is conflicted never opens a
        // transaction at all.
        if (!plan.applicable) throw MergeRefused(plan.report())

        val configured = storage.store() != null
        val stored = storedBytesOf(plan.backup, storage)
        return uow.write {
            val fresh = mergePlanOf(
                plan.backup,
                mergeSnapshotOf(
                    assets, tags, links, definitions, profiles, events, attachments, stored, configured,
                ),
            )
            // Order matters — see the class KDoc.
            if (!fresh.applicable) throw MergeRefused(fresh.report())
            if (fresh.fingerprint != plan.fingerprint) throw MergePlanStale(fresh.report())

            // Field order is write order, and every list is ordered within itself.
            fresh.writes.assets.forEach { assets.upsert(it) }
            fresh.writes.definitions.forEach { definitions.upsert(it) }
            fresh.writes.profiles.forEach { profiles.upsert(it) }
            fresh.writes.links.forEach { links.upsert(it) }
            fresh.writes.tags.forEach { tags.upsert(it) }
            fresh.writes.events.forEach { events.upsert(it) }
            fresh.writes.attachments.forEach { attachments.upsert(it) }

            fresh.report()
        }
    }
}
```

Create `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ImportBackupMerge.kt`:

```kotlin
package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeReport

/**
 * The two-call merge, as one thing to wire (1.1.0, #46).
 *
 * [plan] decides and writes nothing; [run] decides and then applies, refusing with [MergeRefused]
 * if the plan holds a conflict and with [MergePlanStale] if the destination moved under it. The
 * semantics are the plan's — this is six lines of façade so that `AppGraph` and the loopback API
 * have one name to hold, exactly as #46 asked for.
 */
class ImportBackupMerge(
    private val build: BuildBackupMergePlan,
    private val apply: ApplyBackupMergePlan,
) {
    /** What a merge would do. Never mutates. */
    suspend fun plan(bytes: ByteArray): MergeReport = build.run(bytes).report()

    /**
     * Plan, then apply the fresh plan the apply itself rebuilds. The apply streams and hashes every
     * named attachment a second time on purpose: reusing this plan's map would reopen the window
     * the rebuild closes.
     */
    suspend fun run(bytes: ByteArray): MergeReport = apply.run(build.run(bytes))
}
```

- [ ] **Step 9: Run the end-to-end test and watch it pass.**

Run: `./gradlew :core:test --tests '*ImportBackupMergeTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, **9** cases green: `a disjoint archive merges into a non-empty install and leaves the local rows alone`, `importing the same archive twice is idempotent`, `a conflicting archive is refused and the destination is byte for byte unchanged`, `planning writes nothing, whether the plan is applicable or not`, `a destination that gained a conflicting row between plan and apply is refused as a conflict`, `a plan whose verdicts no longer hold is refused as stale and writes nothing`, `an attachment row is written only when the store holds the bytes the row claims`, `a corrupt archive is refused before a plan exists`, `an archive from a newer format is refused before a plan exists`.

- [ ] **Step 10: The gate.**

Run: `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. `core/build/reports/tests/test/` shows **33 classes / 380 tests** — the 31/338 the 1.0.0 evidence row records plus this task's two classes and their 42 cases (33 + 9). `:app`, `:nfc-core` and `:nfc-android` are unchanged at 37/258, 6/50 and 3/10.

- [ ] **Step 11: Stage the work, so the verification can see all of it.**

```bash
git add -A
git status --porcelain
```

`git diff` with no revision compares the work tree with the **index**, and `git grep` searches **tracked** files — so an untracked new file is invisible to both. All eight of this task's files are new, and left unstaged every grep in the next step would pass by not looking at them.

Expected: exactly **eight** entries, all `A `, and nothing with a `??`.

- [ ] **Step 12: Verify the blast radius, and the rules that are facts about the source.**

Every pattern here is deliberately narrowed to the **code shape** it means rather than the English word, because the plan's own KDoc discusses the same concepts and a grep that a correct implementation cannot pass is a grep that always looks red.

```bash
MERGE=core/src/main/kotlin/com/loosecannon/servicetag/core/merge
USE=core/src/main/kotlin/com/loosecannon/servicetag/core/usecase
git diff --cached --name-only
git diff --cached --stat -- libs app "$USE/ImportBackupReplace.kt" core/src/main/kotlin/com/loosecannon/servicetag/core/ports core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/test/kotlin/com/loosecannon/servicetag/core/testing app/schemas docs README.md
git grep --cached -nE '\.delete\(|deleteAll\(' -- "$MERGE" "$USE/ApplyBackupMergePlan.kt" "$USE/BuildBackupMergePlan.kt" "$USE/ImportBackupMerge.kt"
git grep --cached -nE '\.updatedAt|updatedAt' -- "$MERGE"
git grep --cached -nE '\.physicalUid|physicalUid' -- "$MERGE"
git grep --cached -cE '\.upsert\(' -- "$MERGE"
git grep --cached -cE '\.upsert\(' -- "$USE/ApplyBackupMergePlan.kt"
git grep --cached -c 'uow.write' -- "$USE/ApplyBackupMergePlan.kt"
git grep --cached -nE 'if \(!fresh\.applicable\)|if \(fresh\.fingerprint' -- "$USE/ApplyBackupMergePlan.kt"
git grep --cached -n 'UPDATE' -- "$MERGE/MergePlan.kt"
git grep --cached -n 'FORMAT_VERSION = ' -- core/src/main
git grep --cached -nE 'ImportBackupMerge\(' -- core/src/main app/src/main
git grep --cached -c 'unique = true' -- app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities | awk -F: '{s+=$2} END {print s}'
```

Expected, line by line:
- `--name-only` lists exactly the eight paths of this task and nothing else.
- **No output** from the `git diff --cached --stat` — the library, `:app`, `ImportBackupReplace.kt`, the ports, the backup package, the test doubles, the schemas, the docs and the README are all untouched.
- `\.delete\(|deleteAll\(` across the four merge sources: **no output at all**. The merge has no call that removes a row, and that is a fact about the files rather than a promise in a KDoc. (The KDocs are written to say "never removes one", not "never deletes one", precisely so that this grep stays a check on code.)
- `updatedAt` in `core/merge` — **no output**, for the bare word as well as the dotted read. Not one line of the planner names a timestamp: the whole-row `==` is the only thing that sees one, and the planner's own KDoc says "the last-modified stamp" rather than the field name so that this grep means what it says. This is the check that makes "never chooses by `updatedAt`" verifiable in five seconds.
- `physicalUid` in `core/merge` — **no output**, same shape. #44 acceptance 7 as a fact about the source.
- `\.upsert\(` in `core/merge` — **no output**: the planner writes nothing. In `ApplyBackupMergePlan.kt` — exactly **7 lines**, one per table, in `MergeWrites`' field order. And `uow.write` there is **1 file, 1 occurrence**: one transaction.
- The two guard lines in `ApplyBackupMergePlan.kt` come back **in this order**: `if (!fresh.applicable)` first, `if (fresh.fingerprint` second. Read the line numbers, not just the count — the order is the fix for the one bug a reviewer found in this class, and a future edit that swaps them makes `MergeRefused`-from-rebuild unreachable again.
- `UPDATE` in `MergePlan.kt`: exactly **1 line**, the `MergeVerdict` KDoc sentence saying there is deliberately no such verdict.
- `FORMAT_VERSION = 5` is still the one line in `:core`.
- `ImportBackupMerge(` is exactly **1 line** — its own class declaration; nothing in `:app` constructs it yet, which is Task 2's job.
- `unique = true` across the Room entities, summed over the three files that carry it, is **5** — the five unique indices decision 6 enumerates, and the number the planner is written against. If a future migration adds a sixth, this count is what says the planner has a gap.

- [ ] **Step 13: Commit.**

```bash
AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
git add -A
git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "plan the merge before writing it, and refuse anything ambiguous"
```

---

### Task 2: the loopback API — the wire, the router, the handlers

**Files:**
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/PairingCode.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/HttpWire.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiJson.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiDtos.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiHandlers.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiRouter.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/api/LoopbackApiServer.kt`
- Create test: `app/src/test/kotlin/com/loosecannon/servicetag/api/PairingCodeTest.kt`
- Create test: `app/src/test/kotlin/com/loosecannon/servicetag/api/HttpWireTest.kt`
- Create test: `app/src/test/kotlin/com/loosecannon/servicetag/api/ApiRouterTest.kt`
- Create test: `app/src/test/kotlin/com/loosecannon/servicetag/api/LoopbackApiServerTest.kt`
- Modify: `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` — **three** imports, **three** `val`s after line 158 (`buildBackupMergePlan`, `applyBackupMergePlan`, `importBackupMerge`), and `private companion object` → `internal companion object` at line 215, which is load-bearing: `ApiHandlers`' secondary constructor reads `AppGraph.SCHEMA_VERSION`, and that is inaccessible from `com.loosecannon.servicetag.api` while the companion is private
- Modify test: `app/src/test/kotlin/com/loosecannon/servicetag/testing/FakeGraph.kt` — the same three imports and the same three `val`s after line 170
- **Untouched, and checked at Step 17:** `app/src/main/AndroidManifest.xml` (Task 3's one line is Task 3's), `app/build.gradle.kts`, `gradle/libs.versions.toml`, `core/**`, `libs/**`, `app/schemas/**`, every existing file under `app/src/main/kotlin/com/loosecannon/servicetag/ui/`

**Interfaces:**
- Consumes (present at `0a582f8`, except where Task 1 produced it): `AppGraph.assets/tags/links/definitions/profiles/events/attachments` (`AppGraph.kt:91`–`98`); `AppGraph.createAsset/updateAsset/retireAsset/archiveAsset/saveDefinition/archiveDefinition/saveProfile/archiveProfile/logEvent/updateEvent/deleteEvent` (`AppGraph.kt:187`–`213`); **Task 1's** `ImportBackupMerge` (its `plan` and its `run`), `MergeReport`, `MergeTally`, `MergeDecision`, `DuplicateCandidate`, `MergeRefused`, `MergePlanStale`; `BuildConfig.VERSION_NAME`; the seven `…toDto()` mappers and their DTOs in `com.loosecannon.servicetag.core.backup` — `AssetDto`, `NfcTagDto`, `MeasurementDefinitionDto`, `EventProfileDto`, `AssetEventDto`, all `@Serializable` (`BackupFormat.kt:61`, `89`, `117`, `156`, `191`); `AssetCommand` (`AssetCommands.kt:20`), `DefinitionCommand` (`DefinitionCommands.kt:20`), `ProfileCommand`/`ProfileFieldInput`/`ProfileConsumableInput` (`ProfileCommands.kt:9`–`31`), `EventCommand`/`ConsumableInput` (`EventCommands.kt:24`, `:31`); the refusal types `AssetValidation`, `AssetCycle`, `AssetHasChildren`, `NoSuchAsset`, `EventValidation`, `EventOwnership`, `NoSuchEvent`, `DefinitionValidation`, `DefinitionInUse`, `DefinitionWouldBreakDerived`, `DefinitionWouldBreakProfiles`, `NoSuchDefinition`, `ProfileValidation`, `NoSuchProfile`, `UnknownTemplate` (`ApplyTemplate.kt:22`), `BackupCorrupt`/`BackupNewerFormat` (`backup/BackupErrors.kt`), `StoreIoException` (`ports/AttachmentStore.kt:12`); `AssetTree.children` (`model/AssetTree.kt:44`); `AttachmentRepository.count()`; `FakeGraph`'s same-named members (`FakeGraph.kt:86`–`170`).
- Produces (Tasks 3, 4, 5 and 6 rely on these names, all `internal` in `:app`, package `com.loosecannon.servicetag.api`):
  - `const val DEVELOPER_API_PORT: Int` = 17337; `const val API_VERSION: Int` = 1.
  - `const val PAIRING_ALPHABET: String`; `const val PAIRING_CODE_LENGTH: Int` = 8; `fun newPairingCode(random: SecureRandom = SecureRandom()): String`; `fun tokenMatches(expected: String, offered: String?): Boolean`.
  - `class ApiRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)`; `fun ApiRequest.bearerToken(): String?`.
  - `class ApiResponse(val status: Int, val reason: String, val body: ByteArray)` with `ApiResponse.json(status, reason, body: String)` and `ApiResponse.empty(status, reason)`.
  - `class MalformedRequest(val response: ApiResponse, val why: String) : Exception` (`why` is the exception message for a test or a debugger; the response body stays empty); `fun parseRequest(input: InputStream, bodyCapFor: (String) -> Int): ApiRequest`; `fun writeResponse(output: OutputStream, response: ApiResponse)`.
  - `class ApiFailure(status, reason, code, message, problems)`; `fun errorResponse(status, reason, code, message, problems = emptyList()): ApiResponse`; `fun mapDomainFailure(e: Exception): ApiResponse`; `val ApiJson: Json`.
  - `class ApiHandlers(assets, tags, links, definitions, profiles, events, attachments, createAsset, updateAsset, retireAsset, archiveAsset, saveDefinition, archiveDefinition, saveProfile, archiveProfile, logEvent, updateEvent, deleteEvent, importBackupMerge, appVersion, schemaVersion)` with a secondary `constructor(graph: AppGraph)`, and one `suspend fun` per endpoint, each returning `ApiResponse` — including `importMergePlan(request)` and `importMergeApply(request)`.
  - `const val MAX_BODY_BYTES: Int` = 65536; `const val MAX_IMPORT_BYTES: Int` = 4194304; `const val IMPORT_MERGE_PLAN_PATH: String` = "/v1/import-merge/plan"; `const val IMPORT_MERGE_APPLY_PATH: String` = "/v1/import-merge/apply".
  - The wire mirror of Task 1's report: `MergeTallyDto`, `MergeDecisionDto`, `DuplicateCandidateDto`, `MergeReportResponse`, and `fun MergeReport.toResponse(): MergeReportResponse`.
  - `class ApiRouter(handlers: ApiHandlers, token: String)` with `fun bodyCapFor(path: String): Int` and `fun handle(request: ApiRequest): ApiResponse`.
  - `class LoopbackApiServer(router: ApiRouter, port: Int = DEVELOPER_API_PORT)` with `fun start(): Boolean`, `fun stop()`, `val boundPort: Int`, `val requests: StateFlow<Int>`.
  - `AppGraph.importBackupMerge: ImportBackupMerge` and `FakeGraph.importBackupMerge: ImportBackupMerge`.

**The `/v1` surface, in full. Twenty-one method-and-path rows over eighteen path shapes, and nothing else matches.**

| method | path | use case | success |
|---|---|---|---|
| `GET` | `/v1/status` | reads only | 200 |
| `GET` | `/v1/assets` | `assets.all()` + `AssetTree.children` | 200 |
| `POST` | `/v1/assets` | `CreateAsset.run(cmd, templateKey)` | 201 |
| `GET` | `/v1/assets/{id}` | `assets.get` | 200 |
| `PATCH` | `/v1/assets/{id}` | `UpdateAsset.run(id, cmd)` | 200 |
| `POST` | `/v1/assets/{id}/components` | `CreateAsset.run(cmd.copy(parentAssetId = id), templateKey)` | 201 |
| `POST` | `/v1/assets/{id}/retire` | `RetireAsset.retire(id, on)` / `.unretire(id)` | 200 |
| `POST` | `/v1/assets/{id}/archive` | `ArchiveAsset.run(id)` / `.unarchive(id)` | 200 |
| `GET` | `/v1/assets/{id}/definitions` | `definitions.forAsset` | 200 |
| `GET` | `/v1/assets/{id}/profiles` | `profiles.forAsset` | 200 |
| `GET` | `/v1/assets/{id}/events` | `events.forAsset` | 200 |
| `POST` | `/v1/definitions` | `SaveDefinition.run(id, cmd)` — `id` in the body, null to create | 200 |
| `POST` | `/v1/definitions/{id}/archive` | `ArchiveDefinition.run(id, archived)` | 204 |
| `POST` | `/v1/profiles` | `SaveProfile.run(id, cmd)` — `id` in the body, null to create | 200 |
| `POST` | `/v1/profiles/{id}/archive` | `ArchiveProfile.run(id, archived)` | 204 |
| `POST` | `/v1/events` | `LogEvent.run(cmd)` | 201 |
| `PATCH` | `/v1/events/{id}` | `UpdateEvent.run(id, cmd)` | 200 |
| `DELETE` | `/v1/events/{id}` | `DeleteEvent.run(id)` | 204 |
| `GET` | `/v1/tags` | `tags.all()` — read only, no write route exists | 200 |
| `POST` | `/v1/import-merge/plan` | **Task 1's** `ImportBackupMerge.plan(bytes)`, `application/zip` — **never mutates** | 200 |
| `POST` | `/v1/import-merge/apply` | **Task 1's** `ImportBackupMerge.run(bytes)` — applies only a conflict-free plan | 200, or **409** with the same body and zero writes |

(Twenty-one rows, eighteen path shapes: **three** of them carry two methods — `/v1/assets` (GET, POST), `/v1/assets/{id}` (GET, PATCH) and `/v1/events/{id}` (PATCH, DELETE). That is the number `docs/api/v1.md`, the README, the MCP's tool list and the self-review all reconcile against.) **Never exposed, by having no route at all:** `ImportBackupReplace`, `ExportBackupSet`, `RestoreArtifacts`, `BindTag`, `ProvisionTag`, `DeleteAsset`, `DeleteDefinition`, `DeleteProfile`, `AddAttachment`, `UpdateAttachment`, `DeleteAttachment`, `AttachmentStore.open`, `AttachmentStore.put`, `LinkRepository` in any form, and the debug harness's wipe. A request for any of them falls through to 404.

**Decisions, and what lost:**

1. **The response rows are the backup format's own DTOs.** `GET /v1/assets/{id}` returns `AssetDto` — the same `@Serializable` class `data.json` carries, produced by the same `Asset.toDto()` (`BackupFormat.kt:251`). So a row read over the API and that row inside a backup archive are the *same JSON object*, field for field, and a workstation that can read one can read the other. A hand-written `ApiAssetDto` lost, twice over: it would be a second schema to keep in step with the model, and it would let the API and the backup format drift apart silently. The cost is that the API inherits the backup format's field names (`nfcTags`' `assetId`/`linkId` pair, the flat `attachments` owner columns); that is a cost worth paying for one schema instead of two, and `docs/api/v1.md` says where the shapes come from.
2. **Request bodies are `:app`'s own DTOs, not the domain commands.** `AssetCommand` holds `AssetId` value classes and `DefinitionCommand` holds `DerivedFormula`; making the domain commands `@Serializable` would put a wire concern into `:core`'s vocabulary and would tie the JSON to Kotlin's value-class encoding. So `ApiDtos.kt` declares the request shapes with plain `String`/`Int`/`Double?` fields, mirrors the DTO field names the responses use (`sourceAId`, not `sourceA`), and converts in one place per command.
3. **`ApiJson` refuses unknown keys.** `ignoreUnknownKeys = false` means a typo in an automation script is a 400 naming the field rather than a silently ignored intent — which for `PATCH /v1/assets/{id}` is the difference between "you misspelled `serialNumber`" and "your serial number was silently dropped". `encodeDefaults = true` matches `BackupCodec`'s own `Json` (`BackupCodec.kt:54`–`57`), so every field is present in a response and a client never has to distinguish absent from default.
4. **`ApiHandlers` takes twenty-one constructor parameters — nineteen collaborators, plus `appVersion` and `schemaVersion` — explicitly, and has a `constructor(graph: AppGraph)`.** That is the house pattern, stated in `AssetViewModels.kt:59`–`61`: *"Each takes the `AppGraph` members it actually uses — the secondary constructor is what the Compose entry calls, the primary one is what a test builds on a Room-backed fake graph."* It is why `ApiRouterTest` can build the whole API over `FakeGraph` with no Android and no emulator. An `AppGraph`-only constructor lost: `FakeGraph` is not an `AppGraph` and never will be, so the tests would all have had to be instrumented. A narrowing interface (`interface ApiDeps`) lost too: it would be a third name for the same twenty-one members, implemented twice.
5. **Handlers return `ApiResponse`, and encode their own bodies with an explicit serializer.** No `inline reified` helper, no reflective `serializer<T>()`: every encode names the class whose serializer it uses, so a wrong type is a compile error at the call site rather than a runtime `SerializationException` from inside a generic helper.
6. **One `runBlocking`, in the router, at the boundary.** `ApiRouter.handle` is called from the socket thread and must return bytes; everything below it is `suspend` because everything in `:core` is. `runBlocking(Dispatchers.IO)` is the one place the two meet, it is the same dispatcher `BackupViewModel` puts its zip-and-digest work on (`BackupViewModel.kt:134`, `:192`, `:206`, `:251`), and it is the only `runBlocking` in `app/src/main` — Task 6 Step 3 greps for exactly one. Making the handlers blocking instead lost: they would each need their own `runBlocking`, twenty of them, and `:core` would have to grow blocking twins of every use case.
7. **A single-connection accept loop, on one daemon thread.** Argued in the dependency decision above. The consequence worth naming here: no request can observe another's partial write, and `ApiHandlers` needs no synchronisation of its own — the transaction boundaries are the use cases' `uow.write`, exactly as they are for the UI.
8. **401 comes before routing, and before the body is parsed as JSON.** The token check is the first thing `handle` does; an unauthenticated caller cannot learn whether a path exists, whether its JSON was well formed, or how long the answer would have been. The body has already been read by then — the ceiling is what protects that, not the token — so the caps are enforced in `parseRequest`, below the router, where they apply to an unauthenticated caller too.
9. **`bodyCapFor` is asked by the parser, not by the router.** The parser has to know the ceiling *before* it reads, so the cap is a function of the path alone, and the one path with a different answer is named by a constant. That keeps 4 MiB from being reachable at any other path: `POST /v1/assets` with a 4 MiB body is 413.
10. **The request counter counts answers, including refusals.** `Requests this session` moves for a 401 and for a 400 as well as for a 200, because the number's job on that screen is to tell the owner whether *anything* is talking to their phone. It does not move for a connection that was accepted and then said nothing before the 5-second timeout.

- [ ] **Step 1: Write the two failing pure-wire tests first.**

Create `app/src/test/kotlin/com/loosecannon/servicetag/api/PairingCodeTest.kt`:

```kotlin
package com.loosecannon.servicetag.api

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the pairing code and the comparison that accepts it.
 *
 * Two of the release's security minimums live here: the code is fresh per screen visit and drawn
 * from an alphabet a person can read off a phone and type without ambiguity, and the comparison is
 * the JDK's own non-short-circuiting array compare rather than `==` on a String.
 */
class PairingCodeTest {

    @Test fun isEightCharactersOfTheUnambiguousAlphabet() {
        repeat(200) {
            val code = newPairingCode()
            assertEquals(PAIRING_CODE_LENGTH, code.length)
            assertTrue("$code is not all alphabet", code.all { it in PAIRING_ALPHABET })
        }
    }

    /**
     * The alphabet is exactly 32 characters, and none of the six a person confuses by eye. The
     * count matters: 32 is what makes an 8-character code 40 bits.
     */
    @Test fun theAlphabetHasNoLookalikes() {
        assertEquals(32, PAIRING_ALPHABET.length)
        assertEquals(32, PAIRING_ALPHABET.toSet().size)
        for (confusing in listOf('I', 'O', '0', '1', 'i', 'o')) {
            assertFalse("$confusing is in the alphabet", confusing in PAIRING_ALPHABET)
        }
    }

    /** Fresh every time the screen opens — the property that makes a code a session's and not the phone's. */
    @Test fun twoCodesAreNotTheSame() {
        val codes = List(200) { newPairingCode() }
        assertEquals(200, codes.toSet().size)
    }

    @Test fun onlyTheExactCodeMatches() {
        val code = newPairingCode(SecureRandom())
        assertTrue(tokenMatches(code, code))
        assertFalse(tokenMatches(code, null))
        assertFalse(tokenMatches(code, ""))
        assertFalse(tokenMatches(code, code.lowercase()))
        assertFalse(tokenMatches(code, code.dropLast(1)))
        assertFalse(tokenMatches(code, code + "A"))
    }
}
```

`code.lowercase()` is a real case, not a nit: the alphabet is upper-case, the MCP upper-cases what it is handed, and a token is compared as bytes — so the API is deliberately case-sensitive and that is pinned here.

Create `app/src/test/kotlin/com/loosecannon/servicetag/api/HttpWireTest.kt`:

```kotlin
package com.loosecannon.servicetag.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the framing, and every way a request can be refused before a route is looked up.
 *
 * This is the whole justification for a hand-rolled server rather than a library: the ceilings the
 * owner made the condition of one are asserted here, on the real parser, in the JVM gate CI already
 * runs. Nothing in this file needs a socket, an emulator or an Android class.
 */
class HttpWireTest {

    /** The production caps, as the router publishes them. */
    private val caps: (String) -> Int = { path ->
        if (path == IMPORT_MERGE_PLAN_PATH || path == IMPORT_MERGE_APPLY_PATH) {
            MAX_IMPORT_BYTES
        } else {
            MAX_BODY_BYTES
        }
    }

    private fun parse(raw: String) = parseRequest(ByteArrayInputStream(raw.toByteArray()), caps)

    private fun refusal(raw: String): ApiResponse =
        try {
            parse(raw)
            error("expected a refusal")
        } catch (e: MalformedRequest) {
            e.response
        }

    @Test fun aWellFormedGetParses() {
        val request = parse("GET /v1/status?verbose=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ABCD2345\r\n\r\n")
        assertEquals("GET", request.method)
        // The query string is dropped: no endpoint reads one, so nothing may depend on it.
        assertEquals("/v1/status", request.path)
        assertEquals("ABCD2345", request.bearerToken())
        assertEquals(0, request.body.size)
    }

    @Test fun aWellFormedPostReadsExactlyContentLengthBytes() {
        val request = parse("POST /v1/assets HTTP/1.1\r\nContent-Length: 16\r\n\r\n{\"name\":\"Hot tub\"}extra")
        assertEquals("POST", request.method)
        assertEquals("{\"name\":\"Hot tu", request.body.decodeToString().take(15))
        assertEquals(16, request.body.size)
    }

    @Test fun anUnknownMethodIs405() {
        assertEquals(405, refusal("PUT /v1/assets HTTP/1.1\r\n\r\n").status)
        assertEquals(405, refusal("OPTIONS /v1/assets HTTP/1.1\r\n\r\n").status)
        assertEquals(405, refusal("CONNECT /v1/assets HTTP/1.1\r\n\r\n").status)
    }

    @Test fun aChunkedBodyIs400() {
        val response = refusal("POST /v1/assets HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertEquals(400, response.status)
        assertEquals(0, response.body.size)
    }

    /**
     * Security minimum 6: a framing refusal is an answer to a peer that has not authenticated, so
     * the status is all it may say. In particular a 413 must not name the path's ceiling — that
     * would tell a caller which paths exist and how large each one's window is.
     */
    @Test fun everyFramingRefusalHasAnEmptyBody() {
        val shapes = listOf(
            "",
            "PUT /v1/assets HTTP/1.1\r\n\r\n",
            "POST /v1/assets HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "POST /v1/assets HTTP/1.1\r\n\r\n",
            "GET /v1/assets HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}",
            "POST /v1/assets HTTP/1.1\r\nContent-Length: ${MAX_BODY_BYTES + 1}\r\n\r\n",
            "POST $IMPORT_MERGE_PLAN_PATH HTTP/1.1\r\nContent-Length: ${MAX_IMPORT_BYTES + 1}\r\n\r\n",
            "GET /v1/status HTTP/1.1\r\nno-colon-here\r\n\r\n",
        )
        for (raw in shapes) {
            val response = refusal(raw)
            assertEquals("body for ${raw.take(24)}", 0, response.body.size)
            assertTrue("status for ${raw.take(24)}", response.status in setOf(400, 405, 413))
        }
        // And the cap number is nowhere in any of them.
        assertTrue(
            shapes.none { refusal(it).body.decodeToString().contains(MAX_IMPORT_BYTES.toString()) },
        )
    }

    /**
     * One canonical spelling, so the ceiling and the route cannot disagree. `ApiRouter.route` trims
     * slashes of its own, so without this a `POST /v1/import-merge/plan/` would reach the plan
     * handler with the 64 KiB cap instead of 4 MiB.
     */
    @Test fun aTrailingSlashIsDroppedSoTheCapComesFromTheCanonicalPath() {
        assertEquals("/v1/assets", parse("GET /v1/assets/ HTTP/1.1\r\n\r\n").path)
        assertEquals(
            IMPORT_MERGE_PLAN_PATH,
            parse("POST $IMPORT_MERGE_PLAN_PATH/ HTTP/1.1\r\nContent-Length: 0\r\n\r\n").path,
        )
        // A body that only the import ceiling allows, sent to the trailing-slash spelling: the
        // canonical path is what chose the cap, so it is accepted rather than refused.
        val big = MAX_BODY_BYTES + 1
        assertEquals(
            400,
            refusal("POST $IMPORT_MERGE_PLAN_PATH/ HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status,
        )
        // The root is left alone: it is one character and dropping it would make it empty.
        assertEquals("/", parse("GET / HTTP/1.1\r\n\r\n").path)
    }

    @Test fun aPostWithNoContentLengthIs400() {
        assertEquals(400, refusal("POST /v1/assets HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").status)
        assertEquals(400, refusal("PATCH /v1/assets/a1 HTTP/1.1\r\n\r\n").status)
    }

    @Test fun aGetOrDeleteWithABodyIs400() {
        assertEquals(400, refusal("GET /v1/assets HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").status)
        assertEquals(400, refusal("DELETE /v1/events/e1 HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").status)
    }

    @Test fun aBodyOverTheCapIs413() {
        val response = refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: ${MAX_BODY_BYTES + 1}\r\n\r\n")
        assertEquals(413, response.status)
    }

    /** The two paths with a bigger ceiling, and the proof that they are the only ones. */
    @Test fun theImportPathsHaveTheirOwnCap() {
        val big = MAX_BODY_BYTES + 1
        assertEquals(413, refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
        // Bare `/v1/import-merge` is not a route, and gets no favours from the parser either.
        assertEquals(413, refusal("POST /v1/import-merge HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
        for (path in listOf(IMPORT_MERGE_PLAN_PATH, IMPORT_MERGE_APPLY_PATH)) {
            // Same length, the import paths: accepted by the parser, which then waits for bytes
            // that never come — a short body is its own 400, which is what makes this assertion
            // about the cap and not about the body.
            assertEquals(400, refusal("POST $path HTTP/1.1\r\nContent-Length: $big\r\n\r\n").status)
            assertEquals(
                413,
                refusal("POST $path HTTP/1.1\r\nContent-Length: ${MAX_IMPORT_BYTES + 1}\r\n\r\n").status,
            )
        }
    }

    @Test fun anOversizedRequestLineOrHeaderBlockIs400() {
        assertEquals(400, refusal("GET /v1/" + "a".repeat(9_000) + " HTTP/1.1\r\n\r\n").status)
        val fatHeaders = (1..40).joinToString("") { "X-Pad-$it: ${"p".repeat(300)}\r\n" }
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\n$fatHeaders\r\n").status)
    }

    @Test fun tooManyHeadersIs400() {
        val many = (1..70).joinToString("") { "X-Pad-$it: p\r\n" }
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\n$many\r\n").status)
    }

    @Test fun garbageIsAlways400AndNeverACrash() {
        assertEquals(400, refusal("").status)
        assertEquals(400, refusal("\r\n\r\n").status)
        assertEquals(400, refusal("not a request line at all\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\nno-colon-here\r\n\r\n").status)
        assertEquals(400, refusal("GET /v1/status HTTP/1.1\r\nContent-Length: nine\r\n\r\n").status)
        assertEquals(400, refusal("POST /v1/assets HTTP/1.1\r\nContent-Length: 40\r\n\r\nshort").status)
    }

    @Test fun aResponseIsFramedWithItsLengthAndClosesTheConnection() {
        val out = ByteArrayOutputStream()
        writeResponse(out, ApiResponse.json(200, "OK", """{"ok":true}"""))
        val text = out.toByteArray().decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: application/json\r\n"))
        assertTrue(text.contains("Content-Length: 11\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"))
    }

    /** A 401 is a zero-byte body, and says nothing at all — not even what kind of thing it is. */
    @Test fun anEmptyResponseCarriesNoContentTypeAndNoBody() {
        val out = ByteArrayOutputStream()
        writeResponse(out, ApiResponse.empty(401, "Unauthorized"))
        val text = out.toByteArray().decodeToString()
        assertEquals("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", text)
    }
}
```

- [ ] **Step 2: Run them and watch them fail to compile.**

Run: `./gradlew :app:testDebugUnitTest --tests '*PairingCodeTest' --tests '*HttpWireTest' --console=plain`
Expected: **FAILURE** — `Unresolved reference: newPairingCode`, `PAIRING_ALPHABET`, `tokenMatches`, `parseRequest`, `MalformedRequest`, `ApiResponse`, `writeResponse`, `MAX_BODY_BYTES`, `MAX_IMPORT_BYTES`, `IMPORT_MERGE_PLAN_PATH`, `IMPORT_MERGE_APPLY_PATH`. That is the red.

- [ ] **Step 3: Write the pairing code, the JSON envelope and the wire.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/PairingCode.kt`:

```kotlin
package com.loosecannon.servicetag.api

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The 32 characters a pairing code is made of: the alphabet without `I` and `O`, and the digits
 * `2`–`9`. Exactly 32, so each character is five bits and an eight-character code is forty — and
 * none of the six shapes a person mistypes reading a phone (`I`/`1`, `O`/`0`, and either case of
 * the two letters).
 */
internal const val PAIRING_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Eight characters: short enough to read off a screen, forty bits against a local guesser. */
internal const val PAIRING_CODE_LENGTH: Int = 8

/**
 * A fresh code. [SecureRandom] and not `kotlin.random.Random`: this is the only thing standing
 * between a process on this phone and the owner's records, so it comes from the platform's CSPRNG.
 * The parameter exists so a test can pass its own instance; production never does.
 */
internal fun newPairingCode(random: SecureRandom = SecureRandom()): String =
    String(CharArray(PAIRING_CODE_LENGTH) { PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)] })

/**
 * Whether [offered] is [expected], compared without short-circuiting on the first differing byte.
 *
 * [MessageDigest.isEqual] is the JDK's own constant-time array comparison — the right tool, and not
 * a hand-rolled loop. It does return early when the two arrays differ in *length*, which is
 * deliberate and harmless here: every real code is exactly [PAIRING_CODE_LENGTH] characters, so
 * length leaks nothing an attacker does not already know from this file.
 */
internal fun tokenMatches(expected: String, offered: String?): Boolean {
    if (offered == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        offered.toByteArray(Charsets.UTF_8),
    )
}
```

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiJson.kt`:

```kotlin
package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.DefinitionInUse
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakDerived
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakProfiles
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchDefinition
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.NoSuchProfile
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.UnknownTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `/v1` contract's version, reported by `GET /v1/status`. Bumped only by a breaking change. */
internal const val API_VERSION: Int = 1

/**
 * Strict on the way in, complete on the way out.
 *
 * `ignoreUnknownKeys = false` so a misspelled field is a 400 naming it, rather than an intent
 * silently dropped — on a `PATCH` that is the difference between an error and a lost edit.
 * `encodeDefaults = true` matches `BackupCodec`'s own `Json` (`BackupCodec.kt:54`–`57`), so every
 * field is present in every response and a client never distinguishes absent from default.
 * `prettyPrint` is off: this goes over a socket, not into a file a person reads.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
internal data class ApiErrorBody(val error: ApiErrorDetail)

/** [code] is stable and machine-readable; [message] is for a person; [problems] names bad fields. */
@Serializable
internal data class ApiErrorDetail(
    val code: String,
    val message: String,
    val problems: List<String> = emptyList(),
)

/** A refusal a handler or the router raises deliberately, carrying the status it means. */
internal class ApiFailure(
    val status: Int,
    val reason: String,
    val code: String,
    message: String,
    val problems: List<String> = emptyList(),
) : Exception(message) {
    companion object {
        fun badRequest(message: String) = ApiFailure(400, "Bad Request", "bad_request", message)

        fun notFound(what: String) =
            ApiFailure(404, "Not Found", "not_found", "nothing here answers $what")

        fun methodNotAllowed(method: String, path: String) =
            ApiFailure(405, "Method Not Allowed", "method_not_allowed", "$method is not allowed on $path")

        fun unsupportedMediaType(expected: String, found: String?) = ApiFailure(
            415, "Unsupported Media Type", "unsupported_media_type",
            "this endpoint wants $expected, not ${found ?: "an unnamed type"}",
        )
    }
}

internal fun errorResponse(
    status: Int,
    reason: String,
    code: String,
    message: String,
    problems: List<String> = emptyList(),
): ApiResponse = ApiResponse.json(
    status, reason,
    ApiJson.encodeToString(
        ApiErrorBody.serializer(),
        ApiErrorBody(ApiErrorDetail(code, message, problems)),
    ),
)

/**
 * Every refusal `:core` can raise, given the status it means.
 *
 * 422 is a *validation* failure — the caller sent a bad field, and `problems` names each one, using
 * the sealed problem types' own `toString()` so the names in the JSON are the names in the code.
 * 409 is a refusal about *state*: the row is fine and the store will not have it (a cycle, a
 * definition that already has measurements, a derived reading that would break). 404 is a row that
 * is not there. 400 is a caller error that is not about a field. Anything left is a 500 carrying
 * the exception's class name and nothing else — no message, because an unanticipated message is the
 * one place a path or a value could leak into a response.
 */
internal fun mapDomainFailure(e: Exception): ApiResponse = when (e) {
    is AssetValidation -> errorResponse(
        422, "Unprocessable Content", "asset_validation", "the asset was refused",
        e.problems.map { it.toString() },
    )
    is EventValidation -> errorResponse(
        422, "Unprocessable Content", "event_validation", "the event was refused",
        e.problems.map { it.toString() },
    )
    is DefinitionValidation -> errorResponse(
        422, "Unprocessable Content", "definition_validation", "the reading was refused",
        e.problems.map { it.toString() },
    )
    is ProfileValidation -> errorResponse(
        422, "Unprocessable Content", "profile_validation", "the quick action was refused",
        e.problems.map { it.toString() },
    )
    is NoSuchAsset -> errorResponse(404, "Not Found", "no_such_asset", "no such asset")
    is NoSuchEvent -> errorResponse(404, "Not Found", "no_such_event", "no such event")
    is NoSuchDefinition -> errorResponse(404, "Not Found", "no_such_definition", "no such reading")
    is NoSuchProfile -> errorResponse(404, "Not Found", "no_such_profile", "no such quick action")
    is AssetCycle -> errorResponse(
        409, "Conflict", "asset_cycle", "that parent is already part of this asset",
    )
    is AssetHasChildren -> errorResponse(
        409, "Conflict", "asset_has_children", "this asset still has components",
    )
    is EventOwnership -> errorResponse(
        409, "Conflict", "ownership", "that row belongs to a different asset",
    )
    is DefinitionInUse -> errorResponse(
        409, "Conflict", "definition_in_use", "this reading already has measurements",
    )
    is DefinitionWouldBreakDerived -> errorResponse(
        409, "Conflict", "would_break_derived", "another derived reading reads this one",
    )
    is DefinitionWouldBreakProfiles -> errorResponse(
        409, "Conflict", "would_break_profiles", "a quick action offers this reading as a field",
    )
    is UnknownTemplate -> errorResponse(400, "Bad Request", "unknown_template", "no such template")
    is BackupNewerFormat -> errorResponse(
        409, "Conflict", "archive_newer_format", "that archive was written by a newer build",
    )
    is BackupCorrupt -> errorResponse(
        400, "Bad Request", "archive_corrupt", "that archive could not be read",
    )
    is StoreIoException -> errorResponse(
        409, "Conflict", "store_unavailable", "the attachment folder is not available",
    )
    // Defence in depth only: `ApiHandlers.importMergeApply` catches both of these itself, because
    // it can attach the report and its deterministic conflict list to the 409. If one ever reaches
    // here it still must not be a 500, and it still must not claim anything was written.
    is MergeRefused -> errorResponse(
        409, "Conflict", "merge_conflicts",
        "the merge was refused; nothing was written",
        e.report.conflicts.map { "${it.table.name}:${it.id}:${it.reason.name}" },
    )
    is MergePlanStale -> errorResponse(
        409, "Conflict", "merge_plan_stale",
        "this phone changed since the plan was built; nothing was written",
    )
    else -> errorResponse(
        500, "Internal Server Error", "internal", e.javaClass.simpleName,
    )
}
```

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/HttpWire.kt`:

```kotlin
package com.loosecannon.servicetag.api

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** The four methods this listener speaks. Anything else is refused before a path is looked at. */
private val METHODS = setOf("GET", "POST", "PATCH", "DELETE")

/** The request line's ceiling, in bytes. */
private const val MAX_REQUEST_LINE = 8 * 1024

/** The header block's two ceilings: total bytes, and how many lines. */
private const val MAX_HEADER_BYTES = 8 * 1024
private const val MAX_HEADERS = 64

private const val BEARER_PREFIX = "Bearer "

/**
 * One parsed request. [headers] keys are lower-cased, so a client's capitalisation cannot matter;
 * [body] is empty when there was none. Not a `data class` deliberately — a `ByteArray` member makes
 * the generated `equals` identity-based and misleading, and nothing here needs `copy`.
 */
internal class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** The bearer token this request offered, or null if it offered none in that shape. */
internal fun ApiRequest.bearerToken(): String? {
    val header = headers["authorization"] ?: return null
    if (!header.startsWith(BEARER_PREFIX)) return null
    return header.substring(BEARER_PREFIX.length)
}

/** What this endpoint said it was sending, lower-cased and stripped of any parameters. */
internal fun ApiRequest.mediaType(): String? =
    headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()

internal class ApiResponse(val status: Int, val reason: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, reason: String, body: String): ApiResponse =
            ApiResponse(status, reason, body.toByteArray(Charsets.UTF_8))

        fun empty(status: Int, reason: String): ApiResponse =
            ApiResponse(status, reason, ByteArray(0))
    }
}

/**
 * The request could not be framed. It carries the response to send, so the ceiling that caught it
 * is the thing that decides the status — and an `Exception` rather than an `IOException`, so the
 * server's "the client hung up" handler cannot swallow it.
 *
 * **Every response it carries has an empty body.** A framing refusal happens *before* the token is
 * checked, so it is an answer to an unauthenticated peer, and the status is all it may say: a 413
 * that named the path's ceiling would tell a caller which paths exist and how big each one's window
 * is. The status is enough to act on — 400 framing, 405 method, 413 too large — and every refusal
 * the *router* raises, which is after authentication, keeps its full JSON body.
 */
internal class MalformedRequest(val response: ApiResponse, val why: String) : Exception(why)

/**
 * Reads one HTTP/1.x request off [input], or throws [MalformedRequest] with the answer to send.
 *
 * Deliberately narrow, and the narrowness is the point (see the plan's dependency decision): one
 * request per connection, four methods, a `Content-Length` body or none, hard ceilings on the
 * request line, the header block and the header count. `Transfer-Encoding` in any form is refused
 * outright rather than implemented. A query string is dropped — no endpoint reads one, so nothing
 * may come to depend on one.
 *
 * [bodyCapFor] is consulted with the path *before* a single body byte is read, which is what makes
 * the 4 MiB import ceiling reachable at one path and nowhere else.
 */
internal fun parseRequest(input: InputStream, bodyCapFor: (String) -> Int): ApiRequest {
    val requestLine = readLine(input, MAX_REQUEST_LINE)
        ?: throw malformed(400, "Bad Request", "the connection said nothing")
    val parts = requestLine.split(' ')
    if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
        throw malformed(400, "Bad Request", "that is not an HTTP request line")
    }
    val method = parts[0]
    if (method !in METHODS) throw malformed(405, "Method Not Allowed")
    // The query string is dropped — no endpoint reads one — and trailing slashes are dropped
    // with it, so `bodyCapFor` below and `ApiRouter.route` (which trims slashes of its own) cannot
    // disagree about which path this is. Canonicalising in one place is what keeps a trailing-slash
    // spelling from reaching a handler with the wrong ceiling.
    val path = parts[1].substringBefore('?').let {
        it.trimEnd('/').ifEmpty { "/" }
    }
    val headers = readHeaders(input)

    if (headers.containsKey("transfer-encoding")) {
        throw malformed(400, "Bad Request", "send a body with a Content-Length, not a chunked one")
    }
    val declared = headers["content-length"]
    val length = when {
        declared == null -> 0
        else -> declared.toIntOrNull()
            ?: throw malformed(400, "Bad Request", "Content-Length is not a number")
    }
    if (length < 0) throw malformed(400, "Bad Request", "Content-Length is negative")
    if (length > 0 && (method == "GET" || method == "DELETE")) {
        throw malformed(400, "Bad Request", "a $method carries no body here")
    }
    if (declared == null && (method == "POST" || method == "PATCH")) {
        throw malformed(400, "Bad Request", "a $method needs a Content-Length")
    }
    // The cap is chosen from the canonical path, before a body byte is read; the refusal names
    // neither the path nor the number, because this is still a pre-authentication answer.
    if (length > bodyCapFor(path)) throw malformed(413, "Payload Too Large")
    return ApiRequest(method, path, headers, readExactly(input, length))
}

/** Writes [response] with its own length and closes: one request per connection, always. */
internal fun writeResponse(output: OutputStream, response: ApiResponse) {
    val head = buildString {
        append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        // A zero-byte body has no type, which is what a 401 here is: an answer that says nothing.
        if (response.body.isNotEmpty()) append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(response.body.size).append("\r\n")
        append("Cache-Control: no-store\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    output.write(head.toByteArray(Charsets.UTF_8))
    output.write(response.body)
}

/**
 * Every framing refusal, with an empty body. [why] is not sent anywhere: it exists so that the
 * throw site reads as an explanation rather than as a bare status, and so a future maintainer who
 * wants these diagnosable has one place to change.
 */
private fun malformed(status: Int, reason: String, why: String = reason) =
    MalformedRequest(ApiResponse.empty(status, reason), why)

/** One CRLF- or LF-terminated line, at most [max] bytes; null only at an immediate end of stream. */
private fun readLine(input: InputStream, max: Int): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) {
            return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
        }
        if (b == '\n'.code) {
            val bytes = out.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        out.write(b)
        if (out.size() > max) throw malformed(400, "Bad Request", "that line is too long")
        // `out.size()` is bytes, not characters, which is the ceiling this is meant to be.
    }
}

private fun readHeaders(input: InputStream): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    var bytes = 0
    while (true) {
        val line = readLine(input, MAX_HEADER_BYTES)
            ?: throw malformed(400, "Bad Request", "the header block ended early")
        if (line.isEmpty()) return headers
        // Bytes, not characters: a multibyte header value must not undercount against the ceiling.
        bytes += line.toByteArray(Charsets.UTF_8).size + 2
        if (bytes > MAX_HEADER_BYTES) throw malformed(400, "Bad Request", "the header block is too large")
        if (headers.size >= MAX_HEADERS) throw malformed(400, "Bad Request", "too many headers")
        val colon = line.indexOf(':')
        if (colon <= 0) throw malformed(400, "Bad Request", "a header line has no name")
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    if (length == 0) return ByteArray(0)
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) throw malformed(400, "Bad Request", "the body was shorter than Content-Length")
        read += n
    }
    return body
}
```

`ApiJson.kt` and `HttpWire.kt` reference each other (`errorResponse` needs `ApiResponse`; `parseRequest` needs `errorResponse`). That is one package compiled together, not a cycle at build time.

- [ ] **Step 4: Run the two wire tests and watch them pass.**

Run: `./gradlew :app:testDebugUnitTest --tests '*PairingCodeTest' --tests '*HttpWireTest' --console=plain`
Expected: `BUILD SUCCESSFUL`. `app/build/reports/tests/testDebugUnitTest/` shows `PairingCodeTest` 4/4 and `HttpWireTest` 15/15.

- [ ] **Step 5: Write the failing router test.**

Create `app/src/test/kotlin/com/loosecannon/servicetag/api/ApiRouterTest.kt`:

```kotlin
package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code this suite pairs with. Invented, and the only token these requests carry. */
private const val TOKEN = "ABCD2345"

/**
 * 1.1.0 (#46) — the whole API, over `FakeGraph`, on the JVM, with no socket and no emulator.
 *
 * **Why there is no `Dispatchers.setMain` and no shared `TestCoroutineScheduler` here**, against
 * the house convention quoted in the plan's Global Constraints: there is no `ViewModel` in this
 * layer and nothing to settle. `ApiRouter.handle` is a *blocking* call — that is its contract, since
 * its caller is a socket thread — so it does not return until Room has answered, and every
 * assertion below is already sequenced by that. Putting a virtual clock under it would deadlock a
 * `runBlocking` on a scheduler the test thread owns. `FakeGraph()` therefore takes its default
 * query context (`Dispatchers.Default`, `FakeGraph.kt:74`), which is exactly right for a fixture
 * whose calls are synchronous.
 *
 * The real `AppGraph` is never built here: `ApiHandlers`' primary constructor takes the members it
 * uses, the way every view model in this app does (`AssetViewModels.kt:59`–`61`), so the fake graph
 * with its in-memory Room database drives the production use cases, the production mappers and the
 * production serializers.
 */
class ApiRouterTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            appVersion = "1.1.0",
            schemaVersion = 5,
        ),
        TOKEN,
    )

    private fun call(
        method: String,
        path: String,
        body: String = "",
        token: String? = TOKEN,
        contentType: String = "application/json",
    ): ApiResponse {
        val headers = buildMap {
            put("host", "127.0.0.1")
            if (token != null) put("authorization", "Bearer $token")
            if (body.isNotEmpty()) put("content-type", contentType)
        }
        return router().handle(ApiRequest(method, path, headers, body.toByteArray()))
    }

    private fun ApiResponse.text(): String = body.decodeToString()

    private fun ApiResponse.code(): String =
        ApiJson.decodeFromString(ApiErrorBody.serializer(), text()).error.code

    private fun assetIn(response: ApiResponse) =
        ApiJson.decodeFromString(AssetResponse.serializer(), response.text()).asset

    /** One asset, created through the API, returned as its id. */
    private fun createHotTub(): String =
        assetIn(call("POST", "/v1/assets", """{"name":"Hot tub","category":"Water"}""")).id

    // --- the token ---------------------------------------------------------------------------

    @Test fun aRequestWithNoTokenIs401WithAnEmptyBody() {
        val response = call("GET", "/v1/status", token = null)
        assertEquals(401, response.status)
        assertEquals(0, response.body.size)
    }

    @Test fun aWrongTokenIs401WithAnEmptyBody() {
        for (wrong in listOf("", "abcd2345", "ABCD234", "ABCD23456", "ABCD2346")) {
            val response = call("GET", "/v1/status", token = wrong)
            assertEquals(401, response.status)
            assertEquals(0, response.body.size)
        }
    }

    /** An unauthenticated caller learns nothing about what exists: the 401 comes before routing. */
    @Test fun anUnauthenticatedCallerCannotProbeForRoutes() {
        assertEquals(401, call("GET", "/v1/assets", token = null).status)
        assertEquals(401, call("GET", "/v1/no-such-thing", token = null).status)
        assertEquals(401, call("POST", "/v1/assets", """{"nope":1}""", token = null).status)
    }

    // --- the shape of the surface ------------------------------------------------------------

    @Test fun anUnknownPathIs404() {
        assertEquals(404, call("GET", "/v1/no-such-thing").status)
        assertEquals(404, call("GET", "/v2/assets").status)
        assertEquals(404, call("GET", "/").status)
        assertEquals("not_found", call("GET", "/v1/no-such-thing").code())
    }

    @Test fun aKnownPathWithTheWrongMethodIs405() {
        assertEquals(405, call("DELETE", "/v1/status").status)
        assertEquals(405, call("PATCH", "/v1/assets").status)
        assertEquals(405, call("DELETE", "/v1/tags").status)
        assertEquals("method_not_allowed", call("DELETE", "/v1/status").code())
    }

    /**
     * The security minimum with the widest blast radius: the destructive and NFC-writing use cases
     * are not reachable, and they are not reachable because **nothing routes to them**. Every path
     * a plausible client would try falls through to 404.
     */
    @Test fun theDestructiveUseCasesHaveNoRoute() {
        val id = createHotTub()
        for ((method, path) in listOf(
            "POST" to "/v1/import-replace",
            "POST" to "/v1/import",
            "POST" to "/v1/wipe",
            "DELETE" to "/v1/assets",
            "GET" to "/v1/export",
            "POST" to "/v1/export",
            "POST" to "/v1/tags",
            "PATCH" to "/v1/tags",
            "DELETE" to "/v1/tags/t1",
            "POST" to "/v1/tags/t1/bind",
            "GET" to "/v1/attachments",
            "GET" to "/v1/attachments/att1/bytes",
            "GET" to "/v1/links",
            "DELETE" to "/v1/assets/$id",
            "DELETE" to "/v1/definitions/d1",
            "DELETE" to "/v1/profiles/p1",
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            assertTrue("$method $path answered ${response.status}", response.status == 404 || response.status == 405)
        }
    }

    // --- status ------------------------------------------------------------------------------

    @Test fun statusReportsTheVersionsAndTheCounts() {
        createHotTub()
        val status = ApiJson.decodeFromString(
            StatusResponse.serializer(), call("GET", "/v1/status").text(),
        )
        assertEquals("1.1.0", status.appVersion)
        assertEquals(API_VERSION, status.apiVersion)
        assertEquals(5, status.schemaVersion)
        assertEquals(BackupCodec.FORMAT_VERSION, status.backupFormatVersion)
        assertEquals(1, status.counts["assets"])
        assertEquals(0, status.counts["events"])
        assertEquals(0, status.counts["attachments"])
    }

    // --- assets ------------------------------------------------------------------------------

    @Test fun createThenGetAnAsset() {
        val created = call("POST", "/v1/assets", """{"name":"Hot tub","category":"Water"}""")
        assertEquals(201, created.status)
        val id = assetIn(created).id

        val fetched = call("GET", "/v1/assets/$id")
        assertEquals(200, fetched.status)
        assertEquals("Hot tub", assetIn(fetched).name)
        assertEquals("Water", assetIn(fetched).category)
        assertEquals(AssetStatus.ACTIVE.name, assetIn(fetched).status)
    }

    /** The list is the dashboard's shape: systems, and each system's components under its id. */
    @Test fun listReturnsTopLevelAssetsAndTheirComponents() {
        val tub = createHotTub()
        val pump = assetIn(call("POST", "/v1/assets/$tub/components", """{"name":"Pool pump"}""")).id
        assetIn(call("POST", "/v1/assets", """{"name":"Generator"}"""))

        val list = ApiJson.decodeFromString(
            AssetListResponse.serializer(), call("GET", "/v1/assets").text(),
        )
        assertEquals(listOf("Generator", "Hot tub"), list.topLevel.map { it.name })
        assertEquals(listOf(pump), list.components.getValue(tub).map { it.id })
        assertEquals(tub, list.components.getValue(tub).single().parentAssetId)
        // A component is listed under its parent and not again at the top.
        assertTrue(list.topLevel.none { it.id == pump })
    }

    @Test fun updateChangesFieldsAndKeepsIdentity() {
        val id = createHotTub()
        val updated = call("PATCH", "/v1/assets/$id", """{"name":"Hot tub","location":"Deck"}""")
        assertEquals(200, updated.status)
        assertEquals(id, assetIn(updated).id)
        assertEquals("Deck", assetIn(updated).location)
        // Not in the command, so it comes from the stored row, not from the request.
        assertEquals(AssetStatus.ACTIVE.name, assetIn(updated).status)
    }

    @Test fun aComponentIsParentedOnThePathIdWhateverTheBodySays() {
        val tub = createHotTub()
        val other = assetIn(call("POST", "/v1/assets", """{"name":"Generator"}""")).id
        val child = call(
            "POST", "/v1/assets/$tub/components",
            """{"name":"Pool pump","parentAssetId":"$other"}""",
        )
        assertEquals(201, child.status)
        assertEquals(tub, assetIn(child).parentAssetId)
    }

    @Test fun retireAndArchiveAreBothReversible() {
        val id = createHotTub()

        assertEquals("2026-04-01", assetIn(call("POST", "/v1/assets/$id/retire", """{"retiredOn":"2026-04-01"}""")).retiredOn)
        assertEquals(null, assetIn(call("POST", "/v1/assets/$id/retire", """{"retiredOn":null}""")).retiredOn)

        assertEquals(AssetStatus.ARCHIVED.name, assetIn(call("POST", "/v1/assets/$id/archive", """{"archived":true}""")).status)
        assertEquals(AssetStatus.ACTIVE.name, assetIn(call("POST", "/v1/assets/$id/archive", """{"archived":false}""")).status)
    }

    // --- definitions, profiles, events -------------------------------------------------------

    @Test fun saveListAndArchiveADefinition() {
        val id = createHotTub()
        val saved = call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","unit":"","decimals":1}""")
        assertEquals(200, saved.status)
        val definition = ApiJson.decodeFromString(DefinitionResponse.serializer(), saved.text()).definition
        assertEquals("ph", definition.key)
        assertEquals("ENTERED", definition.kind)

        val listed = ApiJson.decodeFromString(
            DefinitionListResponse.serializer(), call("GET", "/v1/assets/$id/definitions").text(),
        )
        assertEquals(listOf("pH"), listed.definitions.map { it.label })

        assertEquals(204, call("POST", "/v1/definitions/${definition.id}/archive", """{"archived":true}""").status)
        runBlocking {
            assertTrue(graph.definitions.get(DefinitionId(definition.id))!!.archivedAt != null)
        }

        // The same endpoint with an id in the body is the edit: `SaveDefinition.run(id, cmd)`.
        val edited = call(
            "POST", "/v1/definitions",
            """{"id":"${definition.id}","assetId":"$id","label":"pH (top)","decimals":2}""",
        )
        assertEquals(200, edited.status)
        assertEquals("pH (top)", ApiJson.decodeFromString(DefinitionResponse.serializer(), edited.text()).definition.label)
    }

    @Test fun saveListAndArchiveAProfile() {
        val id = createHotTub()
        val definition = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","decimals":1}""").text(),
        ).definition

        val saved = call(
            "POST", "/v1/profiles",
            """{"assetId":"$id","name":"Water test","eventKind":"MEASUREMENT",""" +
                """"fields":[{"definitionId":"${definition.id}","required":true}]}""",
        )
        assertEquals(200, saved.status)
        val profile = ApiJson.decodeFromString(ProfileResponse.serializer(), saved.text()).profile
        assertEquals("Water test", profile.name)
        assertEquals("Water test", profile.defaultTitle)
        assertEquals(listOf(definition.id), profile.fields.map { it.definitionId })

        val listed = ApiJson.decodeFromString(
            ProfileListResponse.serializer(), call("GET", "/v1/assets/$id/profiles").text(),
        )
        assertEquals(listOf("Water test"), listed.profiles.map { it.name })

        assertEquals(204, call("POST", "/v1/profiles/${profile.id}/archive", """{"archived":true}""").status)
    }

    @Test fun logListUpdateAndDeleteAnEvent() {
        val id = createHotTub()
        val definition = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","decimals":1}""").text(),
        ).definition

        val logged = call(
            "POST", "/v1/events",
            """{"assetId":"$id","kind":"MAINTENANCE","title":"Filter change",""" +
                """"occurredOn":"2026-09-21","tzId":"UTC","notes":"quarterly",""" +
                """"values":{"${definition.id}":"7.4"},""" +
                """"consumables":[{"name":"Cartridge","quantity":"1","unit":"ea"}]}""",
        )
        assertEquals(201, logged.status)
        val event = ApiJson.decodeFromString(EventResponse.serializer(), logged.text()).event
        assertEquals("Filter change", event.title)
        assertEquals(listOf(7.4), event.measurements.map { it.valueNum })
        assertEquals(listOf("Cartridge"), event.consumables.map { it.name })

        val listed = ApiJson.decodeFromString(
            EventListResponse.serializer(), call("GET", "/v1/assets/$id/events").text(),
        )
        assertEquals(listOf(event.id), listed.events.map { it.id })

        val edited = call(
            "PATCH", "/v1/events/${event.id}",
            """{"assetId":"$id","kind":"MAINTENANCE","title":"Filter change (redone)",""" +
                """"occurredOn":"2026-09-21","tzId":"UTC"}""",
        )
        assertEquals(200, edited.status)
        assertEquals(event.id, ApiJson.decodeFromString(EventResponse.serializer(), edited.text()).event.id)

        assertEquals(204, call("DELETE", "/v1/events/${event.id}").status)
        runBlocking { assertEquals(null, graph.events.get(EventId(event.id))) }
    }

    // --- tag bindings, read only -------------------------------------------------------------

    @Test fun tagBindingsAreReadableAndNotWritable() {
        val id = createHotTub()
        runBlocking {
            graph.provisionTag.begin(TagTarget.AssetTarget(AssetId(id)), label = "Lid")
        }
        val tags = ApiJson.decodeFromString(
            TagListResponse.serializer(), call("GET", "/v1/tags").text(),
        )
        assertEquals(1, tags.tags.size)
        assertEquals(id, tags.tags.single().assetId)
        assertEquals(PayloadFormat.V1.name, tags.tags.single().payloadFormat)
        // And there is no way in through this surface.
        assertEquals(405, call("POST", "/v1/tags", """{"assetId":"$id"}""").status)
        runBlocking { assertEquals(1, graph.tags.all().size) }
    }

    // --- refusals ----------------------------------------------------------------------------

    @Test fun aValidationFailureIs422AndNamesTheProblems() {
        val response = call("POST", "/v1/assets", """{"name":"  "}""")
        assertEquals(422, response.status)
        val error = ApiJson.decodeFromString(ApiErrorBody.serializer(), response.text()).error
        assertEquals("asset_validation", error.code)
        assertEquals(listOf("NameRequired"), error.problems)
    }

    @Test fun anAbsentRowIs404AndAnUnknownFieldIs400() {
        assertEquals(404, call("GET", "/v1/assets/nope").status)
        assertEquals("no_such_asset", call("PATCH", "/v1/assets/nope", """{"name":"Hot tub"}""").code())
        assertEquals(404, call("DELETE", "/v1/events/nope").status)

        val typo = call("POST", "/v1/assets", """{"name":"Hot tub","serialNo":"X1"}""")
        assertEquals(400, typo.status)
        assertEquals("bad_request", typo.code())
    }

    // --- the merge import over the wire ------------------------------------------------------

    /**
     * A donor install's real format-5 data archive, with ids that **cannot** collide with this
     * suite's. Do not simplify this to `donor.createAsset`.
     *
     * `FakeGraph`'s generator counts from zero per instance (`FakeGraph.kt:83`–`84`), so a second
     * `FakeGraph` mints `…8000-000000000001` for its first asset — and so does `graph` for
     * `createHotTub()`. Two unrelated rows would then share an id, every merge case below would
     * plan as `CONTENT_DIFFERS`, and two of them would fail for a reason that has nothing to do
     * with the API. So the donor gets its own generator in a disjoint range, wired into a
     * `CreateAsset` of its own; everything else about the export stays the production path.
     */
    private fun donorArchive(vararg names: String): ByteArray {
        val donor = FakeGraph()
        return try {
            var n = 0
            val disjoint = IdGenerator { "00000000-0000-4000-8000-9000%08d".format(++n) }
            val createAsset = CreateAsset(
                donor.assets, donor.uow, disjoint, donor.clock, donor.applyTemplate,
            )
            runBlocking {
                names.forEach { createAsset.run(it, "Power") }
                donor.exportBackupSet.run().data
            }
        } finally {
            donor.close()
        }
    }

    private fun postArchive(path: String, archive: ByteArray, type: String = "application/zip") =
        router().handle(
            ApiRequest(
                "POST", path,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to type),
                archive,
            ),
        )

    private fun reportIn(response: ApiResponse) =
        ApiJson.decodeFromString(MergeReportResponse.serializer(), response.text())

    /** The plan endpoint's whole promise, over the wire: it decides and it writes nothing. */
    @Test fun importMergePlanReportsWhatWouldHappenAndWritesNothing() {
        val archive = donorArchive("Generator")
        createHotTub()
        val before = runBlocking { graph.assets.all().map { it.id.value }.sorted() }

        val response = postArchive(IMPORT_MERGE_PLAN_PATH, archive)

        assertEquals(200, response.status)
        val report = reportIn(response)
        assertEquals(5, report.formatVersion)
        assertTrue(report.applicable)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.assets)
        assertEquals(emptyList(), report.conflicts)
        runBlocking { assertEquals(before, graph.assets.all().map { it.id.value }.sorted()) }
    }

    @Test fun importMergeApplyWritesTheUnionAndIsIdempotent() {
        val archive = donorArchive("Generator")
        createHotTub()

        val applied = postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        assertEquals(200, applied.status)
        assertTrue(reportIn(applied).applicable)
        assertEquals(MergeTallyDto(1, 0, 0, 0), reportIn(applied).assets)
        runBlocking {
            assertEquals(listOf("Generator", "Hot tub"), graph.assets.all().map { it.name }.sorted())
        }

        // #44 acceptance 4, at the endpoint: the second apply is all-IDENTICAL and changes nothing.
        val again = postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        assertEquals(200, again.status)
        assertEquals(MergeTallyDto(insert = 0, identical = 1, conflict = 0, skipped = 0), reportIn(again).assets)
        runBlocking { assertEquals(2, graph.assets.all().size) }
    }

    /**
     * The refusal contract: **409, the same body shape as a 200, the deterministic conflict list,
     * and zero writes.** The conflict is manufactured by importing an archive and then diverging
     * the very row it carries.
     */
    @Test fun importMergeApplyIs409WithTheConflictListAndNoWrites() {
        val archive = donorArchive("Generator")
        postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        val id = runBlocking { graph.assets.all().single { it.name == "Generator" }.id }
        runBlocking { graph.updateAsset.run(id, AssetCommand(name = "Diesel generator")) }
        val before = runBlocking { graph.assets.all().sortedBy { it.id.value } }

        val refused = postArchive(IMPORT_MERGE_APPLY_PATH, archive)

        assertEquals(409, refused.status)
        val report = reportIn(refused)
        assertFalse(report.applicable)
        assertEquals(1, report.conflicts.size)
        assertEquals("ASSETS", report.conflicts.single().table)
        assertEquals(id.value, report.conflicts.single().id)
        assertEquals("CONFLICT", report.conflicts.single().verdict)
        assertEquals("CONTENT_DIFFERS", report.conflicts.single().reason)
        assertEquals(MergeTallyDto(insert = 0, identical = 0, conflict = 1, skipped = 0), report.assets)
        runBlocking { assertEquals(before, graph.assets.all().sortedBy { it.id.value }) }
    }

    @Test fun bothImportPathsRefuseAnythingThatIsNotAZip() {
        for (path in listOf(IMPORT_MERGE_PLAN_PATH, IMPORT_MERGE_APPLY_PATH)) {
            val wrongType = postArchive(path, byteArrayOf(1, 2, 3), type = "application/json")
            assertEquals(415, wrongType.status)
            assertEquals("unsupported_media_type", wrongType.code())

            val notAnArchive = postArchive(path, byteArrayOf(1, 2, 3))
            assertEquals(400, notAnArchive.status)
            assertEquals("archive_corrupt", notAnArchive.code())
        }
        // And a bare `/v1/import-merge` is not a route at all.
        assertEquals(404, postArchive("/v1/import-merge", byteArrayOf(1, 2, 3)).status)
    }

    /** The one cap the router publishes, and exactly which paths get it. */
    @Test fun onlyTheTwoImportPathsHaveTheBiggerCap() {
        assertEquals(MAX_IMPORT_BYTES, router().bodyCapFor(IMPORT_MERGE_PLAN_PATH))
        assertEquals(MAX_IMPORT_BYTES, router().bodyCapFor(IMPORT_MERGE_APPLY_PATH))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/assets"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/status"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/import-merge"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/import-merge/plan/"))
    }
}
```

`graph.provisionTag.begin(TagTarget.AssetTarget(AssetId(id)), label = "Lid")` is the existing use case — note the member is `begin`, not `run` (`ProvisionTag.kt:25`; the class has `begin`/`complete`/`abandon` and no `run` at all) — wired at `AppGraph.kt:185` and `FakeGraph.kt:125`. It is the only way this suite gets a tag row, which is the point: the API has no way to make one, so the test cannot use the API to make one.

- [ ] **Step 6: Run it and watch it fail to compile.**

Run: `./gradlew :app:testDebugUnitTest --tests '*ApiRouterTest' --console=plain`
Expected: **FAILURE** — `Unresolved reference: ApiRouter`, `ApiHandlers`, `AssetResponse`, `AssetListResponse`, `StatusResponse`, `DefinitionResponse`, `DefinitionListResponse`, `ProfileResponse`, `ProfileListResponse`, `EventResponse`, `EventListResponse`, `TagListResponse`, `MergeReportResponse`, `MergeTallyDto`, `IMPORT_MERGE_PLAN_PATH`, `IMPORT_MERGE_APPLY_PATH`, and `Unresolved reference: importBackupMerge` / `buildBackupMergePlan` / `applyBackupMergePlan` on `FakeGraph`. That is the red.

- [ ] **Step 7: The wire shapes.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiDtos.kt`:

```kotlin
package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.AssetEventDto
import com.loosecannon.servicetag.core.backup.EventProfileDto
import com.loosecannon.servicetag.core.backup.MeasurementDefinitionDto
import com.loosecannon.servicetag.core.backup.NfcTagDto
import com.loosecannon.servicetag.core.merge.DuplicateCandidate
import com.loosecannon.servicetag.core.merge.MergeDecision
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import kotlinx.serialization.Serializable

/*
 * The shapes on the wire.
 *
 * **Responses reuse the backup format's own row DTOs** — `AssetDto`, `NfcTagDto`,
 * `MeasurementDefinitionDto`, `EventProfileDto`, `AssetEventDto`, all `@Serializable` in
 * `com.loosecannon.servicetag.core.backup` — so a row read here and the same row inside
 * `data.json` are the same JSON object, produced by the same `toDto()`. One schema, not two.
 *
 * **Requests are declared here and nowhere else**, with plain `String`/`Int`/`Double?` fields.
 * The domain commands hold value classes and enums; making them `@Serializable` would put a wire
 * concern into `:core`'s vocabulary and pin the JSON to Kotlin's value-class encoding. Each request
 * converts in exactly one place, below.
 */

// --- responses ----------------------------------------------------------------------------------

@Serializable
internal data class StatusResponse(
    val appVersion: String,
    val apiVersion: Int,
    val schemaVersion: Int,
    val backupFormatVersion: Int,
    /** One key per table: assets, tags, links, definitions, profiles, events, attachments. */
    val counts: Map<String, Int>,
)

/**
 * The dashboard's shape (spec §9): the systems, then each system's components under its own id. A
 * component appears under its parent and never at the top, and both lists are by name,
 * case-insensitively — the order `AssetTree.children` already defines.
 */
@Serializable
internal data class AssetListResponse(
    val topLevel: List<AssetDto>,
    val components: Map<String, List<AssetDto>>,
)

@Serializable
internal data class AssetResponse(val asset: AssetDto)

@Serializable
internal data class DefinitionListResponse(val definitions: List<MeasurementDefinitionDto>)

@Serializable
internal data class DefinitionResponse(val definition: MeasurementDefinitionDto)

@Serializable
internal data class ProfileListResponse(val profiles: List<EventProfileDto>)

@Serializable
internal data class ProfileResponse(val profile: EventProfileDto)

@Serializable
internal data class EventListResponse(val events: List<AssetEventDto>)

@Serializable
internal data class EventResponse(val event: AssetEventDto)

@Serializable
internal data class TagListResponse(val tags: List<NfcTagDto>)

/*
 * The merge report, on the wire. It is a 1:1 mirror of Task 1's `MergeReport` rather than that
 * class made `@Serializable`, for the same reason the request shapes are declared here: a plan is a
 * domain answer and its JSON is a wire concern, and `:core` should not gain an annotation because
 * `:app` grew a socket. The enums travel as their names, which are the stable codes a client
 * branches on.
 */

@Serializable
internal data class MergeTallyDto(
    val insert: Int,
    val identical: Int,
    val conflict: Int,
    val skipped: Int,
)

/** One row's outcome. `reason` is a `MergeReason` name; `detail` is an id or a key, never a label. */
@Serializable
internal data class MergeDecisionDto(
    val table: String,
    val id: String,
    val verdict: String,
    val reason: String,
    val detail: String,
)

/** A review hint. It never makes `applicable` false and never causes anything to happen. */
@Serializable
internal data class DuplicateCandidateDto(
    val incomingAssetId: String,
    val localAssetId: String,
    val hint: String,
)

/**
 * The body of `POST /v1/import-merge/plan` and of `POST /v1/import-merge/apply` — the same shape
 * on a 200 and on a 409, so a client parses one thing and reads [applicable] to know what happened.
 */
@Serializable
internal data class MergeReportResponse(
    val formatVersion: Int,
    val backupSetId: String,
    val applicable: Boolean,
    val assets: MergeTallyDto,
    val definitions: MergeTallyDto,
    val profiles: MergeTallyDto,
    val links: MergeTallyDto,
    val tags: MergeTallyDto,
    val events: MergeTallyDto,
    val attachments: MergeTallyDto,
    /** Deterministic: table order, then id. Empty when [applicable]. */
    val conflicts: List<MergeDecisionDto>,
    val duplicateCandidates: List<DuplicateCandidateDto>,
)

private fun MergeTally.dto() = MergeTallyDto(insert, identical, conflict, skipped)

private fun MergeDecision.dto() =
    MergeDecisionDto(table.name, id, verdict.name, reason.name, detail)

private fun DuplicateCandidate.dto() =
    DuplicateCandidateDto(incomingAssetId, localAssetId, hint.name)

internal fun MergeReport.toResponse() = MergeReportResponse(
    formatVersion = formatVersion,
    backupSetId = backupSetId,
    applicable = applicable,
    assets = assets.dto(),
    definitions = definitions.dto(),
    profiles = profiles.dto(),
    links = links.dto(),
    tags = tags.dto(),
    events = events.dto(),
    attachments = attachments.dto(),
    conflicts = conflicts.map { it.dto() },
    duplicateCandidates = duplicateCandidates.map { it.dto() },
)

// --- requests -----------------------------------------------------------------------------------

/**
 * Everything an asset form can say, as JSON. The field names are `AssetDto`'s, so a row read from
 * the API can be edited and sent back without renaming anything.
 *
 * [templateKey] is read on a create and **ignored on a `PATCH`**, which mirrors the domain exactly:
 * `AssetCommand` deliberately has no `templateKey`, because an edit must not re-seed an asset
 * (`AssetCommands.kt:12`–`19`).
 */
@Serializable
internal data class AssetCommandRequest(
    val name: String,
    val category: String = "",
    val description: String = "",
    val notes: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val purchaseOn: String? = null,
    val inServiceOn: String? = null,
    val purchasePriceMinor: Long? = null,
    val currency: String? = null,
    val vendor: String = "",
    val location: String = "",
    val warrantyExpiresOn: String? = null,
    val warrantyNotes: String = "",
    val parentAssetId: String? = null,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
    val templateKey: String? = null,
)

internal fun AssetCommandRequest.toCommand(parentOverride: String? = null) = AssetCommand(
    name = name,
    category = category,
    description = description,
    notes = notes,
    manufacturer = manufacturer,
    model = model,
    serialNumber = serialNumber,
    purchaseOn = purchaseOn,
    inServiceOn = inServiceOn,
    purchasePriceMinor = purchasePriceMinor,
    currency = currency,
    vendor = vendor,
    location = location,
    warrantyExpiresOn = warrantyExpiresOn,
    warrantyNotes = warrantyNotes,
    // The path wins on `POST /v1/assets/{id}/components`: the route says whose component this is.
    parentAssetId = (parentOverride ?: parentAssetId)?.let(::AssetId),
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
)

/** `null` un-retires; a date retires. The same shape either way, so one endpoint does both. */
@Serializable
internal data class RetireRequest(val retiredOn: String? = null)

/** `true` archives, `false` unarchives. Shared by assets, definitions and profiles. */
@Serializable
internal data class ArchiveRequest(val archived: Boolean)

/**
 * `SaveDefinition.run(id, cmd)` as one body: [id] null creates, [id] set edits. [key] blank means
 * "generate from the label" on a create and "leave it alone" on an edit — which is the domain's own
 * rule (`DefinitionCommands.kt:15`–`18`), not a new one.
 */
@Serializable
internal data class SaveDefinitionRequest(
    val id: String? = null,
    val assetId: String,
    val key: String = "",
    val label: String,
    val unit: String = "",
    val kind: String = "ENTERED",
    val valueType: String = "NUMBER",
    val decimals: Int = 0,
    val rangeLow: Double? = null,
    val rangeHigh: Double? = null,
    val isMeter: Boolean = false,
    val formula: String? = null,
    val sourceAId: String? = null,
    val sourceBId: String? = null,
)

internal fun SaveDefinitionRequest.toCommand() = DefinitionCommand(
    assetId = AssetId(assetId),
    key = key,
    label = label,
    unit = unit,
    kind = enumOr400<DefinitionKind>(kind, "kind"),
    valueType = enumOr400<ValueType>(valueType, "valueType"),
    decimals = decimals,
    rangeLow = rangeLow,
    rangeHigh = rangeHigh,
    isMeter = isMeter,
    formula = formula?.let { enumOr400<DerivedFormula>(it, "formula") },
    sourceAId?.let(::DefinitionId),
    sourceBId?.let(::DefinitionId),
)

@Serializable
internal data class ProfileFieldRequest(val definitionId: String, val required: Boolean = false)

@Serializable
internal data class ProfileConsumableRequest(
    val id: String? = null,
    val name: String,
    val defaultQuantity: Double? = null,
    val unit: String = "",
)

/** `SaveProfile.run(id, cmd)` as one body: [id] null creates, [id] set edits. */
@Serializable
internal data class SaveProfileRequest(
    val id: String? = null,
    val assetId: String,
    val name: String,
    val eventKind: String,
    val defaultTitle: String = "",
    val fields: List<ProfileFieldRequest> = emptyList(),
    val consumables: List<ProfileConsumableRequest> = emptyList(),
)

internal fun SaveProfileRequest.toCommand() = ProfileCommand(
    assetId = AssetId(assetId),
    name = name,
    eventKind = enumOr400<EventKind>(eventKind, "eventKind"),
    defaultTitle = defaultTitle,
    fields = fields.map { ProfileFieldInput(DefinitionId(it.definitionId), it.required) },
    consumables = consumables.map {
        ProfileConsumableInput(it.id, it.name, it.defaultQuantity, it.unit)
    },
)

/** A consumable line exactly as the entry form sends one: the quantity is text until validated. */
@Serializable
internal data class ConsumableRequest(
    val name: String,
    val quantity: String,
    val unit: String = "",
)

/**
 * One event, logged or edited. [values] is keyed by definition id, the text a person would type —
 * `EventCommand.values` is a `Map<DefinitionId, String>` for exactly that reason
 * (`EventCommands.kt:26`–`30`), and "absent" and "blank" both mean no value.
 */
@Serializable
internal data class EventRequest(
    val assetId: String,
    val profileId: String? = null,
    val kind: String,
    val title: String = "",
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val notes: String = "",
    val values: Map<String, String> = emptyMap(),
    val consumables: List<ConsumableRequest> = emptyList(),
)

internal fun EventRequest.toCommand() = EventCommand(
    assetId = AssetId(assetId),
    profileId = profileId?.let(::ProfileId),
    kind = enumOr400<EventKind>(kind, "kind"),
    title = title,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    notes = notes,
    values = values.mapKeys { (id, _) -> DefinitionId(id) },
    consumables = consumables.map { ConsumableInput(it.name, it.quantity, it.unit) },
)

/**
 * An enum by name, or a 400 naming the field and every name it would have accepted. `:core`'s own
 * reader does the same thing for a backup (`BackupFormat.kt:247`–`249`), with `BackupCorrupt` in
 * place of a status code.
 */
private inline fun <reified E : Enum<E>> enumOr400(name: String, field: String): E =
    enumValues<E>().firstOrNull { it.name == name }
        ?: throw ApiFailure.badRequest(
            "$field must be one of ${enumValues<E>().joinToString(", ") { it.name }}, not \"$name\"",
        )
```

`DefinitionCommand`'s last two parameters are positional in the conversion above because they are named `sourceA`/`sourceB` on the command and `sourceAId`/`sourceBId` on the wire (`DefinitionCommands.kt:32`–`33` against `BackupFormat.kt:135`–`136`); the wire keeps the DTO's names so a definition read from the API round-trips, and the two positional arguments are the one place the two spellings meet.

- [ ] **Step 8: The handlers.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiHandlers.kt`:

```kotlin
package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy

/**
 * One method per `/v1` endpoint, and every one of them goes through the use case the UI goes
 * through. Nothing here reads or writes a repository that a use case owns the rules for: the four
 * read-only list endpoints call `all()`/`forAsset()` exactly as the view models' flows do, and
 * every write is a `CreateAsset`, `UpdateAsset`, `RetireAsset`, `ArchiveAsset`, `SaveDefinition`,
 * `ArchiveDefinition`, `SaveProfile`, `ArchiveProfile`, `LogEvent`, `UpdateEvent`, `DeleteEvent` or
 * `ImportBackupMerge` call. There is deliberately no method for a wipe, a replace-import, an
 * export, an NFC write, an NFC bind or an attachment's bytes.
 *
 * **Nineteen collaborators plus two values, named one by one, with a `constructor(graph)` beside
 * them.** That is this app's pattern, stated at `AssetViewModels.kt:59`–`61`: *"Each takes the `AppGraph` members it
 * actually uses — the secondary constructor is what the Compose entry calls, the primary one is
 * what a test builds on a Room-backed fake graph."* It is the reason `ApiRouterTest` can drive the
 * entire API on the JVM over `FakeGraph`, with no Android class anywhere in this file.
 *
 * Every method is `suspend` and none of them catches anything: a domain refusal travels to
 * [ApiRouter], which is the one place a `Throwable` becomes a status code ([mapDomainFailure]).
 *
 * **Eleven handler methods share a name with the use-case property they call** — `createAsset`,
 * `updateAsset`, `retireAsset`, `archiveAsset`, `saveDefinition`, `archiveDefinition`,
 * `saveProfile`, `archiveProfile`, `logEvent`, `updateEvent`, `deleteEvent`. That is legal and
 * deliberate: Kotlin resolves a property and a function of the same name separately, so inside
 * `createAsset(request)` the expression `createAsset.run(…)` is the property, and at the router the
 * call reads `handlers.createAsset(request)`. The alternative — `handleCreateAsset` — would put a
 * word in eleven names to avoid a collision the compiler does not have.
 *
 * One honest qualification of the package's "no Android import" rule (Global Constraints): this
 * file imports `AppGraph`, whose own constructor takes a `Context`. The JVM suite is unaffected —
 * the secondary constructor is never invoked there, and the type resolves against the mockable
 * `android.jar` — but the `import android` grep in Task 6 Step 3 does not see that transitive
 * coupling, so it is written down here rather than credited to the grep.
 */
internal class ApiHandlers(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val createAsset: CreateAsset,
    private val updateAsset: UpdateAsset,
    private val retireAsset: RetireAsset,
    private val archiveAsset: ArchiveAsset,
    private val saveDefinition: SaveDefinition,
    private val archiveDefinition: ArchiveDefinition,
    private val saveProfile: SaveProfile,
    private val archiveProfile: ArchiveProfile,
    private val logEvent: LogEvent,
    private val updateEvent: UpdateEvent,
    private val deleteEvent: DeleteEvent,
    private val importBackupMerge: ImportBackupMerge,
    private val appVersion: String,
    private val schemaVersion: Int,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles, graph.events,
        graph.attachments,
        graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
        graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
        graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
        BuildConfig.VERSION_NAME, AppGraph.SCHEMA_VERSION,
    )

    // --- status ---------------------------------------------------------------------------

    suspend fun status(): ApiResponse = ok(
        StatusResponse.serializer(),
        StatusResponse(
            appVersion = appVersion,
            apiVersion = API_VERSION,
            schemaVersion = schemaVersion,
            backupFormatVersion = BackupCodec.FORMAT_VERSION,
            counts = mapOf(
                "assets" to assets.all().size,
                "tags" to tags.all().size,
                "links" to links.all().size,
                "definitions" to definitions.all().size,
                "profiles" to profiles.all().size,
                "events" to events.all().size,
                "attachments" to attachments.count(),
            ),
        ),
    )

    // --- assets ---------------------------------------------------------------------------

    suspend fun listAssets(): ApiResponse {
        val all = assets.all()
        val topLevel = all.filter { it.parentAssetId == null }.sortedBy { it.name.lowercase() }
        // `AssetTree.children` is the order the asset screen's COMPONENTS section already uses.
        val components = all
            .map { it.id }
            .associate { id -> id.value to AssetTree.children(all, id).map { it.toDto() } }
            .filterValues { it.isNotEmpty() }
        return ok(
            AssetListResponse.serializer(),
            AssetListResponse(topLevel.map { it.toDto() }, components),
        )
    }

    suspend fun getAsset(id: String): ApiResponse =
        ok(AssetResponse.serializer(), AssetResponse(asset(id).toDto()))

    suspend fun createAsset(request: ApiRequest): ApiResponse {
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = createAsset.run(body.toCommand(), body.templateKey)
        return createdResponse(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun createComponent(parentId: String, request: ApiRequest): ApiResponse {
        // The parent has to exist before the child is validated against it, so the 404 is ours and
        // not an `UnknownParent` validation problem about a path the caller can see is wrong.
        asset(parentId)
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = createAsset.run(body.toCommand(parentOverride = parentId), body.templateKey)
        return createdResponse(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun updateAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = updateAsset.run(AssetId(id), body.toCommand())
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun retireAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(RetireRequest.serializer())
        val saved = body.retiredOn
            ?.let { retireAsset.retire(AssetId(id), it) }
            ?: retireAsset.unretire(AssetId(id))
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun archiveAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = if (body.archived) archiveAsset.run(AssetId(id)) else archiveAsset.unarchive(AssetId(id))
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    // --- definitions ----------------------------------------------------------------------

    suspend fun listDefinitions(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            DefinitionListResponse.serializer(),
            DefinitionListResponse(definitions.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun saveDefinition(request: ApiRequest): ApiResponse {
        val body = request.decode(SaveDefinitionRequest.serializer())
        val saved = saveDefinition.run(body.id?.let(::DefinitionId), body.toCommand())
        return ok(DefinitionResponse.serializer(), DefinitionResponse(saved.toDto()))
    }

    suspend fun archiveDefinition(id: String, request: ApiRequest): ApiResponse {
        archiveDefinition.run(DefinitionId(id), request.decode(ArchiveRequest.serializer()).archived)
        return ApiResponse.empty(204, "No Content")
    }

    // --- profiles -------------------------------------------------------------------------

    suspend fun listProfiles(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            ProfileListResponse.serializer(),
            ProfileListResponse(profiles.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun saveProfile(request: ApiRequest): ApiResponse {
        val body = request.decode(SaveProfileRequest.serializer())
        val saved = saveProfile.run(body.id?.let(::ProfileId), body.toCommand())
        return ok(ProfileResponse.serializer(), ProfileResponse(saved.toDto()))
    }

    suspend fun archiveProfile(id: String, request: ApiRequest): ApiResponse {
        archiveProfile.run(ProfileId(id), request.decode(ArchiveRequest.serializer()).archived)
        return ApiResponse.empty(204, "No Content")
    }

    // --- events ---------------------------------------------------------------------------

    suspend fun listEvents(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            EventListResponse.serializer(),
            EventListResponse(events.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun logEvent(request: ApiRequest): ApiResponse {
        val logged = logEvent.run(request.decode(EventRequest.serializer()).toCommand())
        return createdResponse(EventResponse.serializer(), EventResponse(logged.toDto()))
    }

    suspend fun updateEvent(id: String, request: ApiRequest): ApiResponse {
        val saved = updateEvent.run(EventId(id), request.decode(EventRequest.serializer()).toCommand())
        return ok(EventResponse.serializer(), EventResponse(saved.toDto()))
    }

    suspend fun deleteEvent(id: String): ApiResponse {
        // `DeleteEvent` is silent about a row that is not there; the API is not, because a client
        // that asked to delete something needs to know whether it existed.
        events.get(EventId(id)) ?: throw ApiFailure.notFound("event $id")
        deleteEvent.run(EventId(id))
        return ApiResponse.empty(204, "No Content")
    }

    // --- tag bindings, read only ----------------------------------------------------------

    suspend fun listTagBindings(): ApiResponse =
        ok(TagListResponse.serializer(), TagListResponse(tags.all().map { it.toDto() }))

    // --- the additive import, planned before it writes --------------------------------------

    /** Decides and returns the plan. Writes nothing, ever, whatever the plan says. */
    suspend fun importMergePlan(request: ApiRequest): ApiResponse {
        requireZip(request)
        return ok(MergeReportResponse.serializer(), importBackupMerge.plan(request.body).toResponse())
    }

    /**
     * Applies, and only when the plan is conflict-free.
     *
     * A refusal is a **409 carrying the same body a 200 would** — the full report, with the
     * deterministic conflict list — because "what stopped you?" is the only question a client has
     * at that point, and making them call `plan` again to find out would be a second round trip for
     * an answer the refusal already holds. [MergePlanStale] answers with the *fresh* plan's report,
     * which is what a client would retry against.
     */
    suspend fun importMergeApply(request: ApiRequest): ApiResponse {
        requireZip(request)
        return try {
            ok(MergeReportResponse.serializer(), importBackupMerge.run(request.body).toResponse())
        } catch (e: MergeRefused) {
            conflict(MergeReportResponse.serializer(), e.report.toResponse())
        } catch (e: MergePlanStale) {
            conflict(MergeReportResponse.serializer(), e.report.toResponse())
        }
    }

    // --- plumbing -------------------------------------------------------------------------

    /** The asset, or `NoSuchAsset` — which [mapDomainFailure] turns into a 404. */
    private suspend fun asset(id: String) =
        assets.get(AssetId(id)) ?: throw NoSuchAsset(AssetId(id))

    private fun <T> ok(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(200, "OK", ApiJson.encodeToString(serializer, value))

    /** Named `createdResponse`, not `created`, so it cannot be misread as a local `val`. */
    private fun <T> createdResponse(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(201, "Created", ApiJson.encodeToString(serializer, value))

    private fun <T> conflict(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(409, "Conflict", ApiJson.encodeToString(serializer, value))

    private fun requireZip(request: ApiRequest) {
        val type = request.mediaType()
        if (type != ZIP_MEDIA_TYPE) throw ApiFailure.unsupportedMediaType(ZIP_MEDIA_TYPE, type)
    }

    /**
     * The body as JSON, or a 415 for the wrong type and a 400 for the wrong shape.
     *
     * The decoder's own message is passed through, because with `ignoreUnknownKeys = false` that
     * message is what names the misspelled field — the difference between a caller fixing a typo
     * and a caller guessing. What it echoes is the caller's own body, back to the caller, over this
     * phone's loopback address; nothing about the phone's data is in it.
     */
    private fun <T> ApiRequest.decode(serializer: DeserializationStrategy<T>): T {
        val type = mediaType()
        if (type != JSON_MEDIA_TYPE) throw ApiFailure.unsupportedMediaType(JSON_MEDIA_TYPE, type)
        return try {
            ApiJson.decodeFromString(serializer, body.decodeToString())
        } catch (e: SerializationException) {
            throw ApiFailure.badRequest(e.message ?: "that is not the JSON this endpoint wants")
        }
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val ZIP_MEDIA_TYPE = "application/zip"
    }
}
```

- [ ] **Step 9: The router.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiRouter.kt`:

```kotlin
package com.loosecannon.servicetag.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Every request body's ceiling but two: 64 KiB, which is a large asset form and a huge event. */
internal const val MAX_BODY_BYTES: Int = 64 * 1024

/** The two endpoints that take an archive instead of a form. */
internal const val IMPORT_MERGE_PLAN_PATH: String = "/v1/import-merge/plan"
internal const val IMPORT_MERGE_APPLY_PATH: String = "/v1/import-merge/apply"

/** Their ceiling: 4 MiB, which is a data archive of a very full phone and no attachment bytes. */
internal const val MAX_IMPORT_BYTES: Int = 4 * 1024 * 1024

/**
 * Authenticates, matches, and turns whatever comes back — an answer or a refusal — into bytes.
 *
 * **The token is checked first, before anything else at all.** A caller without it cannot learn
 * whether a path exists, whether its body parsed, or how long the answer would have been: the reply
 * is 401 with a zero-byte body, every time, for every path. The body has already been read off the
 * socket by then, which is why the ceilings live in [parseRequest] below this class and apply to an
 * unauthenticated caller too.
 *
 * **[handle] is blocking, and that is its contract.** Its caller is [LoopbackApiServer]'s one worker
 * thread, which has nothing else to do until there are bytes to write; everything below it is
 * `suspend`, because everything in `:core` is. `runBlocking(Dispatchers.IO)` is where the two meet —
 * the same dispatcher `BackupViewModel` puts its archive work on (`BackupViewModel.kt:134`) — and it
 * is the only `runBlocking` in `app/src/main`.
 */
internal class ApiRouter(
    private val handlers: ApiHandlers,
    private val token: String,
) {
    /** Asked by the parser with the path, before a body byte is read. */
    fun bodyCapFor(path: String): Int =
        if (path == IMPORT_MERGE_PLAN_PATH || path == IMPORT_MERGE_APPLY_PATH) {
            MAX_IMPORT_BYTES
        } else {
            MAX_BODY_BYTES
        }

    fun handle(request: ApiRequest): ApiResponse {
        if (!tokenMatches(token, request.bearerToken())) {
            return ApiResponse.empty(401, "Unauthorized")
        }
        return try {
            runBlocking(Dispatchers.IO) { route(request) }
        } catch (e: ApiFailure) {
            errorResponse(e.status, e.reason, e.code, e.message ?: e.code, e.problems)
        } catch (e: Exception) {
            mapDomainFailure(e)
        }
    }

    /**
     * The whole surface. Eighteen path shapes over twenty-one method-and-path rows; anything else is a 404, and a known shape with the
     * wrong verb is a 405. Written as an explicit `when` over the path's segments rather than a
     * table of regexes, so the set of things this listener answers can be read in one screen and
     * grepped in one line. Note that bare `/v1/import-merge` is **not** a route: the plan and the
     * apply are two different acts and neither is the default.
     */
    private suspend fun route(request: ApiRequest): ApiResponse {
        val segments = request.path.trim('/').split('/')
        if (segments.firstOrNull() != "v1") throw ApiFailure.notFound(request.path)
        val rest = segments.drop(1)
        val method = request.method

        return when {
            rest == listOf("status") ->
                if (method == "GET") handlers.status() else notAllowed(request)

            rest == listOf("assets") -> when (method) {
                "GET" -> handlers.listAssets()
                "POST" -> handlers.createAsset(request)
                else -> notAllowed(request)
            }

            rest.size == 2 && rest[0] == "assets" -> when (method) {
                "GET" -> handlers.getAsset(rest[1])
                "PATCH" -> handlers.updateAsset(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "assets" -> when (rest[2] to method) {
                "components" to "POST" -> handlers.createComponent(rest[1], request)
                "retire" to "POST" -> handlers.retireAsset(rest[1], request)
                "archive" to "POST" -> handlers.archiveAsset(rest[1], request)
                "definitions" to "GET" -> handlers.listDefinitions(rest[1])
                "profiles" to "GET" -> handlers.listProfiles(rest[1])
                "events" to "GET" -> handlers.listEvents(rest[1])
                else -> throw ApiFailure.notFound(request.path)
            }

            rest == listOf("definitions") ->
                if (method == "POST") handlers.saveDefinition(request) else notAllowed(request)

            rest.size == 3 && rest[0] == "definitions" && rest[2] == "archive" ->
                if (method == "POST") handlers.archiveDefinition(rest[1], request) else notAllowed(request)

            rest == listOf("profiles") ->
                if (method == "POST") handlers.saveProfile(request) else notAllowed(request)

            rest.size == 3 && rest[0] == "profiles" && rest[2] == "archive" ->
                if (method == "POST") handlers.archiveProfile(rest[1], request) else notAllowed(request)

            rest == listOf("events") ->
                if (method == "POST") handlers.logEvent(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "events" -> when (method) {
                "PATCH" -> handlers.updateEvent(rest[1], request)
                "DELETE" -> handlers.deleteEvent(rest[1])
                else -> notAllowed(request)
            }

            rest == listOf("tags") ->
                if (method == "GET") handlers.listTagBindings() else notAllowed(request)

            rest == listOf("import-merge", "plan") ->
                if (method == "POST") handlers.importMergePlan(request) else notAllowed(request)

            rest == listOf("import-merge", "apply") ->
                if (method == "POST") handlers.importMergeApply(request) else notAllowed(request)

            else -> throw ApiFailure.notFound(request.path)
        }
    }

    private fun notAllowed(request: ApiRequest): Nothing =
        throw ApiFailure.methodNotAllowed(request.method, request.path)
}
```

- [ ] **Step 10: Wire the two graphs.**

In `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt`, add three imports, each in its alphabetical position in the existing block:

```kotlin
import com.loosecannon.servicetag.core.usecase.ApplyBackupMergePlan   // after ApplyTemplate (:30), before ArchiveAsset (:31)
import com.loosecannon.servicetag.core.usecase.BuildBackupMergePlan   // after BindTag (:34), before CreateAsset (:35)
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge      // after ExportBackupSet (:41), before ImportBackupReplace (:42)
```

(Written on three lines with their positions as comments; the comments are not copied into the file — each import goes in on its own, where the comment says.)

and add the one construction site immediately after `importBackupReplace` (after line 158), inside the backup block:

```kotlin
    /**
     * 1.1.0 (#46), semantics from #44 — the additive merge, planned before it writes. `plan`
     * decides and writes nothing; `run` applies only a conflict-free plan, which the apply rebuilds
     * inside its own transaction. Reachable only from the Developer API screen's loopback listener;
     * there is no UI for it.
     */
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, tags, links, definitions, profiles, events, attachments, attachmentStorage, uow,
    )
    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, tags, links, definitions, profiles, events, attachments, attachmentStorage, uow,
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)
```

and make the companion readable, so `ApiHandlers`' `constructor(graph)` can report the schema version on `GET /v1/status`. Replace line 215:

```kotlin
    internal companion object {
```

`SCHEMA_VERSION` is already described there as *"Room's `@Database(version = ...)`; recorded in the manifest so an import can refuse"* (`AppGraph.kt:218`) — `/v1/status` reports the same number for the same reason, so a workstation can refuse too. `DB_NAME` becomes `internal` with it and gains no new reader; Step 17 greps to prove that.

In `app/src/test/kotlin/com/loosecannon/servicetag/testing/FakeGraph.kt`, add the same three imports in their alphabetical positions (`ApplyBackupMergePlan` after `ApplyTemplate` at line 19, `BuildBackupMergePlan` after `ArchiveProfile` at line 22, `ImportBackupMerge` after `ExportBackupSet` at line 29) and add the three members immediately after `importBackupReplace` (after line 170):

```kotlin
    val buildBackupMergePlan: BuildBackupMergePlan = BuildBackupMergePlan(
        assets, tags, links, definitions, profiles, events, attachments, attachmentStorage, uow,
    )
    val applyBackupMergePlan: ApplyBackupMergePlan = ApplyBackupMergePlan(
        assets, tags, links, definitions, profiles, events, attachments, attachmentStorage, uow,
    )
    val importBackupMerge: ImportBackupMerge =
        ImportBackupMerge(buildBackupMergePlan, applyBackupMergePlan)
```

The fake mirrors the real graph member for member, which is the whole reason `ApiRouterTest` exercises the production planner and the production apply rather than a stand-in.

- [ ] **Step 11: Run the router test and watch it pass.**

Run: `./gradlew :app:testDebugUnitTest --tests '*ApiRouterTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, **23** cases, all green: `aRequestWithNoTokenIs401WithAnEmptyBody`, `aWrongTokenIs401WithAnEmptyBody`, `anUnauthenticatedCallerCannotProbeForRoutes`, `anUnknownPathIs404`, `aKnownPathWithTheWrongMethodIs405`, `theDestructiveUseCasesHaveNoRoute`, `statusReportsTheVersionsAndTheCounts`, `createThenGetAnAsset`, `listReturnsTopLevelAssetsAndTheirComponents`, `updateChangesFieldsAndKeepsIdentity`, `aComponentIsParentedOnThePathIdWhateverTheBodySays`, `retireAndArchiveAreBothReversible`, `saveListAndArchiveADefinition`, `saveListAndArchiveAProfile`, `logListUpdateAndDeleteAnEvent`, `tagBindingsAreReadableAndNotWritable`, `aValidationFailureIs422AndNamesTheProblems`, `anAbsentRowIs404AndAnUnknownFieldIs400`, `importMergePlanReportsWhatWouldHappenAndWritesNothing`, `importMergeApplyWritesTheUnionAndIsIdempotent`, `importMergeApplyIs409WithTheConflictListAndNoWrites`, `bothImportPathsRefuseAnythingThatIsNotAZip`, `onlyTheTwoImportPathsHaveTheBiggerCap`.

- [ ] **Step 12: Write the failing socket test.**

Create `app/src/test/kotlin/com/loosecannon/servicetag/api/LoopbackApiServerTest.kt`:

```kotlin
package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val TOKEN = "ABCD2345"

/**
 * 1.1.0 (#46) — the listener itself, over a real loopback socket, on the JVM.
 *
 * **Port 0, not 17337.** The production port is fixed by the design and by what the workstation
 * forwards to; a test that bound it would fight the emulator, another test run and an actual app on
 * the same machine. `LoopbackApiServer` therefore takes its port, defaulted to
 * [DEVELOPER_API_PORT], and these cases ask the kernel for a free one and read [boundPort] back —
 * which is also what proves `boundPort` reports the truth.
 *
 * No Android and no Robolectric: `java.net` on the JVM is the same `java.net` on the device, and
 * what is genuinely Android about this feature — the permission, the lifecycle, the screen — is
 * `DeveloperApiListenerTest`'s on `emulator-5554`.
 */
class LoopbackApiServerTest {

    private val graph = FakeGraph()
    private lateinit var server: LoopbackApiServer

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile,
            graph.archiveProfile, graph.logEvent, graph.updateEvent, graph.deleteEvent,
            graph.importBackupMerge,
            appVersion = "1.1.0",
            schemaVersion = 5,
        ),
        TOKEN,
    )

    @Before fun startOnAFreePort() {
        server = LoopbackApiServer(router(), port = 0)
        assertTrue("the listener did not bind", server.start())
    }

    @After fun stopAndClose() {
        server.stop()
        graph.close()
    }

    /** Sends [raw] verbatim and reads the whole answer back, as a workstation's client would. */
    private fun speak(raw: String): String = Socket("127.0.0.1", server.boundPort).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
        socket.getInputStream().readBytes().decodeToString()
    }

    @Test fun itAnswersStatusOverARealSocket() {
        val answer = speak("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("Content-Type: application/json\r\n"))
        assertTrue(answer, answer.contains("\"apiVersion\":$API_VERSION"))
        assertTrue(answer, answer.contains("\"assets\":0"))
    }

    /** The first security minimum: the socket is on this phone's own address, and nowhere else. */
    @Test fun itBindsTheLoopbackAddressAndNothingElse() {
        assertEquals("127.0.0.1", server.boundAddress)
        assertNotEquals(0, server.boundPort)
        assertEquals(17337, DEVELOPER_API_PORT)
    }

    @Test fun anUnauthorisedRequestGetsAnEmptyBodyOverTheWire() {
        val noToken = speak("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals(
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            noToken,
        )
        val wrongToken = speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer NOPENOPE\r\n\r\n")
        assertEquals(noToken, wrongToken)
    }

    /** Leaving the screen is what this models: after `stop`, there is nothing on the port. */
    @Test fun stopRefusesFurtherConnections() {
        val port = server.boundPort
        assertTrue(speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n").isNotEmpty())

        server.stop()
        assertEquals(0, server.boundPort)
        try {
            Socket("127.0.0.1", port).use { it.getInputStream().read() }
            fail("the port is still answering after stop()")
        } catch (e: IOException) {
            // Refused, which is the whole point of the lifecycle binding.
        }
    }

    /** What the screen's third line counts: answers given, refusals included. */
    @Test fun theRequestCountMovesForEveryAnswerIncludingRefusals() {
        assertEquals(0, server.requests.value)
        speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n")
        speak("GET /v1/status HTTP/1.1\r\n\r\n")
        speak("PUT /v1/status HTTP/1.1\r\n\r\n")
        assertEquals(3, server.requests.value)
    }

    /**
     * Security minimum 1's second half. It is a predicate and not a socket case on purpose: a
     * listener bound to `127.0.0.1` cannot be *reached* by a non-loopback peer, which is exactly why
     * the check inside `answer` is defence in depth rather than the boundary. Asserting the bind
     * (above) and asserting the predicate (here) together are what the minimum claims.
     */
    @Test fun onlyALoopbackPeerIsAnswered() {
        assertTrue(isAcceptablePeer(InetAddress.getByName("127.0.0.1")))
        assertTrue(isAcceptablePeer(InetAddress.getByName("127.0.0.53")))
        assertTrue(isAcceptablePeer(InetAddress.getByName("::1")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("192.168.1.10")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("10.0.2.2")))
        assertFalse(isAcceptablePeer(InetAddress.getByName("8.8.8.8")))
    }

    /**
     * The dependency decision claims a half-open socket cannot hold the only worker. This is that
     * claim: a client that connects, sends a fragment and stops is dropped after the read timeout,
     * and the **next** connection is answered normally. Built with a 200 ms timeout so the case
     * costs 200 ms rather than five seconds; the production default is [SOCKET_TIMEOUT_MILLIS].
     */
    @Test fun aHalfOpenClientDoesNotHoldTheWorker() {
        val impatient = LoopbackApiServer(router(), port = 0, readTimeoutMillis = 200)
        assertTrue(impatient.start())
        try {
            // A fragment with no terminator: the parser blocks, then the socket times out.
            val stalled = Socket("127.0.0.1", impatient.boundPort)
            stalled.getOutputStream().apply {
                write("GET /v1/sta".toByteArray())
                flush()
            }

            val answer = Socket("127.0.0.1", impatient.boundPort).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer $TOKEN\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().readBytes().decodeToString()
            }

            assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
            // The stalled connection was dropped without ever being answered, so it counts as one
            // refusal at most and never blocked the one that mattered.
            assertEquals("requests", 1, impatient.requests.value)
            stalled.close()
        } finally {
            impatient.stop()
        }
    }
}
```

- [ ] **Step 13: Run it and watch it fail to compile.**

Run: `./gradlew :app:testDebugUnitTest --tests '*LoopbackApiServerTest' --console=plain`
Expected: **FAILURE** — `Unresolved reference: LoopbackApiServer`, `DEVELOPER_API_PORT`, `isAcceptablePeer`, `SOCKET_TIMEOUT_MILLIS`. That is the red.

- [ ] **Step 14: The listener.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/api/LoopbackApiServer.kt`:

```kotlin
package com.loosecannon.servicetag.api

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The only address this listener will ever bind. A literal, not [InetAddress.getLoopbackAddress],
 * because that is free to answer `::1` and because a literal is a thing a reviewer and a grep can
 * both see.
 */
private const val LOOPBACK_ADDRESS = "127.0.0.1"

/** The port the app listens on and the workstation forwards to. Fixed, so nothing has to discover it. */
internal const val DEVELOPER_API_PORT: Int = 17337

/** How long a connection may go quiet before it is dropped, so it cannot hold the one worker. */
internal const val SOCKET_TIMEOUT_MILLIS = 5_000

/**
 * Whether a peer that reached this listener may be answered: loopback only.
 *
 * Extracted so it can be tested, because it cannot be *driven* through a socket bound to
 * `127.0.0.1` — which is the point of it being defence in depth rather than the boundary. The bind
 * is the boundary; this is the second check on it.
 */
internal fun isAcceptablePeer(address: InetAddress): Boolean = address.isLoopbackAddress

/** How many connections may queue while one is being answered. */
private const val ACCEPT_BACKLOG = 4

/**
 * A loopback HTTP/1.1 listener, one connection at a time, on one daemon thread.
 *
 * **Deliberately narrow, deliberately hand-rolled** — the plan's dependency decision argues it in
 * full. What matters here: there is no chunked decoding, no multipart, no body spooled to disk, no
 * session, no thread pool and no keep-alive. One client (the workstation's MCP server) makes one
 * call at a time, so serialising connections means no shared mutable state between requests and no
 * concurrency to reason about; a second caller waits in the backlog or is refused.
 *
 * **Two independent checks on who is talking.** The socket is bound to [LOOPBACK_ADDRESS], so the
 * kernel refuses anything from off this phone; and every accepted connection's peer is checked
 * again with [Socket.getInetAddress] before a byte of it is read, so a misconfiguration cannot
 * quietly widen the first check.
 *
 * **[start] returns whether it bound**, rather than throwing: the caller is a Compose lifecycle
 * effect, a `SocketException` there would be a crash on a screen opening, and a port already in use
 * is a thing that happens. The one log line this feature produces is the caller's, on a `false`.
 */
internal class LoopbackApiServer(
    private val router: ApiRouter,
    private val port: Int = DEVELOPER_API_PORT,
    /** Parameterised only so a test can prove the drop without waiting five seconds for it. */
    private val readTimeoutMillis: Int = SOCKET_TIMEOUT_MILLIS,
) {
    private val _requests = MutableStateFlow(0)

    /** Answers given **this session**, refusals included — never reset by a restart within it. */
    val requests: StateFlow<Int> = _requests.asStateFlow()

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    /** The connection currently being answered, so [stop] can close it rather than wait for it. */
    @Volatile private var inFlight: Socket? = null

    /** The port actually bound, or 0 while stopped. */
    val boundPort: Int get() = socket?.localPort ?: 0

    /** The address actually bound, or null while stopped. Always [LOOPBACK_ADDRESS] when running. */
    val boundAddress: String? get() = socket?.inetAddress?.hostAddress

    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        val bound = try {
            ServerSocket(port, ACCEPT_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        } catch (e: IOException) {
            return false
        }
        socket = bound
        worker = Thread({ acceptLoop(bound) }, "servicetag-developer-api").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Stops accepting, and closes the connection currently being answered.
     *
     * What this guarantees: after it returns, nothing new is accepted, and no response is delivered
     * to a connection this listener had recorded as in flight — closing its socket makes the
     * in-flight `writeResponse` throw, which the per-connection handler swallows. A connection
     * accepted in the instant before `stop()` ran, and not yet recorded, may still be answered in
     * full; its peer connected while the screen was resumed and still needs the token. What it deliberately does **not** do is
     * join the worker: this is called from a Compose lifecycle effect on the main thread, and a
     * bounded join there is an ANR waiting for a slow query. So a domain call already inside
     * `uow.write` runs to completion — as **one transaction**, which is the right outcome, because
     * abandoning a half-applied merge would be strictly worse than finishing it. The write lands or
     * rolls back atomically; the answer is simply never sent.
     */
    @Synchronized
    fun stop() {
        val bound = socket ?: return
        socket = null
        // Closing the server socket is what unblocks `accept()`; the loop then sees `isClosed`.
        closeQuietly(bound)
        inFlight?.let { closeQuietly(it) }
        inFlight = null
        worker = null
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (!bound.isClosed) {
            val client = try {
                bound.accept()
            } catch (e: IOException) {
                return // `stop()` closed it, or the OS did. Either way this listener is finished.
            }
            inFlight = client
            try {
                client.use { answer(it) }
            } catch (t: Throwable) {
                // `Throwable`, not `IOException`, and per connection: a client that hung up, a
                // `RuntimeException` from a handler, an `OutOfMemoryError` from a 4 MiB body that
                // never arrives — none of them may take the listener down, because a dead worker
                // leaves the screen still showing a port that answers nothing.
            } finally {
                inFlight = null
            }
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Already closed, or the OS refused; either way there is nothing left to close.
        }
    }

    private fun answer(client: Socket) {
        // The second check. The bind already refuses anything off this phone; this refuses anything
        // that reached us some other way, before a byte of it is parsed.
        if (!isAcceptablePeer(client.inetAddress)) return
        client.soTimeout = readTimeoutMillis
        val response = try {
            val request = parseRequest(client.getInputStream(), router::bodyCapFor)
            _requests.update { it + 1 }
            router.handle(request)
        } catch (e: MalformedRequest) {
            _requests.update { it + 1 }
            e.response
        }
        val output = client.getOutputStream()
        writeResponse(output, response)
        output.flush()
    }
}
```

- [ ] **Step 15: Run the socket test, then the whole gate.**

```bash
./gradlew :app:testDebugUnitTest --tests '*LoopbackApiServerTest' --console=plain
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` both times. `LoopbackApiServerTest` 7/7. The full gate shows `:app` at **41 classes / 307 tests** — the 37/258 the 1.0.0 evidence row records plus this task's four classes and their 49 cases (4 + 15 + 23 + 7). `:core` stays at Task 1's 33/380; `:nfc-core` 6/50 and `:nfc-android` 3/10 are untouched. `:app:assembleDebug` must be **clean of warnings about the new package** — in particular no "unused parameter" or "never used" on anything in `api/`.

- [ ] **Step 16: Stage the work, so the verification can see all of it.**

```bash
git add -A
git status --porcelain
```

Expected: exactly **13** entries — 11 `A ` (seven under `api/`, four under `app/src/test/.../api/`) and 2 `M ` (`AppGraph.kt`, `FakeGraph.kt`) — and nothing with a `??`. **Eleven** of the thirteen are new files; left unstaged, every grep in the next step would pass by not looking at them.

- [ ] **Step 17: Verify the blast radius, and the security minimums that are facts about the source.**

```bash
API=app/src/main/kotlin/com/loosecannon/servicetag/api
git diff --cached --name-only
git diff --cached --stat -- libs core app/schemas app/build.gradle.kts gradle app/src/main/AndroidManifest.xml docs README.md
git diff --cached --stat -- app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/nfc app/src/main/kotlin/com/loosecannon/servicetag/MainActivity.kt
git grep --cached -cE '^import android' -- "$API"
git grep --cached -n 'ServerSocket(' -- app/src/main
git grep --cached -n '"127\.0\.0\.1"' -- app/src/main
git grep --cached -nE '0\.0\.0\.0' -- app core
git grep --cached -cE '^\s+runBlocking\(' -- app/src/main
git grep --cached -nE 'ImportBackupReplace|ExportBackupSet|RestoreArtifacts|BindTag|ProvisionTag|DeleteAsset|DeleteDefinition|DeleteProfile|AddAttachment|UpdateAttachment|DeleteAttachment' -- "$API"
git grep --cached -nE 'links\.\w+\(' -- "$API"
git grep --cached -n 'DB_NAME' -- app/src
git grep --cached -c 'SCHEMA_VERSION' -- app/src/main
git grep --cached -nE '"/v1/import-merge' -- app/src/main
```

Every pattern is narrowed to the **code shape** it means rather than the English word, because this package's own KDoc discusses `runBlocking`, `127.0.0.1` and `import-merge` by name and a grep that a correct implementation cannot pass is a grep that always looks red.

Expected, line by line:
- `--name-only` lists exactly thirteen paths and no others: the seven `api/` sources, the four `api/` tests, `di/AppGraph.kt`, `testing/FakeGraph.kt`.
- **No output** from either `git diff --cached --stat` — the library, `:core`, the schemas, the build files, the version catalog, the **manifest**, the docs, the README, every existing screen, the NFC trampoline and `MainActivity` are all untouched by this task. (The manifest's one line is Task 3's, because it is the listener's *lifecycle* that needs it and the lifecycle is Task 3's.)
- `^import android` (anchored to the line start, so `ApiHandlers`' KDoc, which names this grep, cannot match) in `api/`: **no output at all**. That is the claim that makes `testDebugUnitTest` able to run this layer, so it is checked rather than asserted. It does **not** catch a transitive dependency: `ApiHandlers.kt` imports `AppGraph`, whose constructor takes a `Context` — see that class's KDoc, which says so rather than letting the grep take the credit.
- `ServerSocket(` → exactly **1 line**, in `LoopbackApiServer.kt`. One construction site.
- `"127.0.0.1"` — **with the quotes**, because `LoopbackApiServer`'s KDoc and Task 3's manifest comment both discuss the address in prose — → exactly **1 line** in `app/src/main`, the `LOOPBACK_ADDRESS` constant. (It was **0** at `0a582f8`, quoted or not.)
- `0.0.0.0` → **no output**, in `:app` or `:core`. It was absent before and stays absent.
- `^\s+runBlocking\(` — **anchored to the call's indentation**, so the import and the two KDoc mentions (one of which quotes the call) do not count — → **1 file, 1 occurrence**, `ApiRouter.kt`. The bare word was **absent entirely** from `app/src/main` at `0a582f8`, which is why "the only one" is checkable at all.
- The eleven never-exposed use cases → **no output**. `links\.\w+\(` → exactly **1 line**, `links.all().size` in `status()`. `LinkRepository` itself *is* imported by `ApiHandlers.kt`, because `links` is one of its collaborators and `GET /v1/status` counts the tombstone rows; what Global Constraints forbids is a link **endpoint**, and this narrowed grep is the claim actually worth proving.
- `DB_NAME` → exactly **1 line**, its declaration in `AppGraph.kt`. Making the companion `internal` gave it no new reader.
- `SCHEMA_VERSION` → **2 files** in `app/src/main`: `AppGraph.kt` (the declaration and its use in `exportBackupSet`) and `ApiHandlers.kt` (the one new reader).
- `"/v1/import-merge` → exactly **2 lines**, both in `ApiRouter.kt`: the two path constants. The bare word `import-merge` appears in four more places (an `ApiDtos.kt` KDoc, an `ApiRouter.kt` KDoc and the two route branches), which is why the pattern is anchored on the opening quote. Bare `/v1/import-merge` is not a route and exists nowhere as a string literal.

- [ ] **Step 18: Commit.**

```bash
AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
git add -A
git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "a json api on this phone's own loopback address"
```

---

### Task 3: the Developer API screen, and the listener's lifetime

**Files:**
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiViewModel.kt`
- Create: `app/src/main/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiScreen.kt`
- Modify: `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` (after line 48)
- Modify: `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt` (the import block; the `Route.Settings` entry at lines 262–269; one new entry)
- Modify: `app/src/main/kotlin/com/loosecannon/servicetag/ui/settings/SettingsScreen.kt` (the KDoc at lines 76–78; the signature at lines 82–87; the Utilities block at lines 232–242)
- Modify: `app/src/main/AndroidManifest.xml` (one `<uses-permission>` line after line 3)
- Modify test: `app/src/test/kotlin/com/loosecannon/servicetag/ui/nav/RouteTest.kt` (one assertion in `onlyTheTagScreensHoldReaderMode`)
- Create test: `app/src/test/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiViewModelTest.kt`
- Create test: `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiListenerTest.kt`
- Modify test: `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/settings/SettingsBackupEntryTest.kt` (the KDoc; the existing call gains `onDeveloperApi = {}`; one new case)
- **Untouched, and checked at Step 9:** every file under `app/src/main/kotlin/com/loosecannon/servicetag/api/` (Task 2's, and finished), `core/**`, `libs/**`, `app/schemas/**`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `MainActivity.kt`, `nfc/NfcDispatchActivity.kt`, `ui/scan/**`, `ui/backup/**`

**Interfaces:**
- Consumes: **Task 2's** `ApiHandlers(graph: AppGraph)`, `ApiRouter(handlers, token)`, `LoopbackApiServer(router, port)` with `start(): Boolean`/`stop()`/`requests`/`boundPort`, `newPairingCode()`, `DEVELOPER_API_PORT`. Present at `0a582f8`: `LifecycleResumeEffect` (`ServiceTagRoot.kt:11`, `:75`), `viewModel(key = …) { … }` (`BackupScreen.kt:78`), `collectAsStateWithLifecycle` (`BackupScreen.kt:79`), `LabelValue(label, value, mono, valueStyle, modifier)` (`components/LabelValue.kt:18`), `QuietLine(text, modifier)` (`components/QuietLine.kt:13`), `MeasurementHeroText` (`theme/Type.kt:26`), `ServiceTagIcons.Speed` (`components/ServiceTagIcons.kt:27`), `UtilityRow(icon, label, onClick)` (private in `SettingsScreen.kt:306`), `Route.readsTags()` (`Route.kt:83`), `clearInstall()`/`app` (`ui/AppSmokeTest.kt:42`, `:56`).
- Produces (Tasks 5 and 6 rely on these): `Route.DeveloperApi` (a `@Serializable data object`); `internal class DeveloperApiViewModel(handlers: ApiHandlers, port: Int = DEVELOPER_API_PORT)` with `constructor(graph: AppGraph)`, `val pairingCode: String`, `val requests: StateFlow<Int>`, `val failedToStart: StateFlow<Boolean>`, `val boundPort: Int`, `fun listen()`, `fun stopListening()`; `internal fun DeveloperApiScreen(graph: AppGraph, onBack: () -> Unit)`; `SettingsScreen(graph, onBack, onReadTag, onBackup, onDeveloperApi)` — one parameter added, **last**, undefaulted, so every call site is forced to say what it does.

**Decisions, and what lost:**

1. **`LifecycleResumeEffect`, keyed on the view model.** It is the primitive `ServiceTagRoot.kt:75`–`78` already uses to hold reader mode while a tag screen is on top, and it gives exactly the two edges the design asks for: resumed → listen, paused or disposed → stop. "Paused" covers the screen going off, the phone locking and an app switch; "disposed" covers leaving the screen, because `NavDisplay` composes the top entry and pops this one on back. A `DisposableEffect` lost: it would keep the socket open while the phone sat locked on this screen.
2. **A `ViewModel`, scoped to the nav entry, for the code.** `ServiceTagRoot` decorates every entry with `rememberViewModelStoreNavEntryDecorator()` precisely so a model is per visit and is cleared on pop (`ServiceTagRoot.kt:88`–`95`). That is the definition of "a fresh code every time the screen opens", and it survives recomposition, which a bare `remember` would not survive a theme change. A `rememberSaveable` code lost outright: it would persist the token into saved state, which is the one place a secret must not be.
   **What a rotation does, since a reader will ask.** Nothing: `MainActivity` declares `android:configChanges="orientation|screenSize|keyboardHidden"` (`AndroidManifest.xml:25`), so a rotation is not an activity recreation — the composable is never disposed, `LifecycleResumeEffect(model)` does not re-fire, and the listener keeps running on the same code. That is the best of the three possible answers, and it is a fact about the tree rather than a design choice here. If that attribute were ever removed the behaviour would still be correct: the old activity is destroyed before the new one is created, so the effect's dispose stops the listener before its resume starts it again — and the *code* would survive, because a nav entry's `ViewModelStore` outlives a configuration change.
3. **`onCleared` does not stop the server, and that is deliberate.** `LifecycleResumeEffect`'s `onPauseOrDispose` has already stopped it before the store is cleared; adding a second stop in `onCleared` would be a second owner of the same lifetime, and a stop is not idempotent-looking enough to have two callers. The listener cannot outlive the effect, because the effect's dispose runs when the composable leaves — and the composable leaves before its entry's store is cleared.
4. **The screen shows the constant, not the bound port.** `Port` is `DEVELOPER_API_PORT`, a compile-time 17337, because that is what the label means and what the workstation forwards to. Showing `boundPort` would mean showing `0` in the frame between composition and the effect running, and would raise the question of what to show on a bind failure — which is a string the owner has not ratified.
5. **A bind failure is said out loud, in the ratified words of S6.** If 17337 is already taken the listener does not start, and the screen says `The Developer API could not start. Leave this screen and open it again.` — which is true advice, because leaving and returning is a fresh model, a fresh code and a fresh `start()`. The view model exposes it as a `StateFlow<Boolean>` rather than a thrown exception, because the caller is a Compose lifecycle effect where an escaping `SocketException` would be a crash on a screen opening. `android.util.Log.w` records the same fact for a logcat, with no token, no path and no body. The line is drawn in `MaterialTheme.colorScheme.error` and the three `LabelValue` rows stay where they are: the port is still a fact, the code is still this session's, and `Requests this session` staying at 0 is itself the corroboration. An earlier draft of this plan left the failure silent to avoid a sixth string; the owner ratified the string instead, which is the better answer.
6. **`ServiceTagIcons.Speed` for the Utilities row**, because it already exists (`ServiceTagIcons.kt:27`) and 1.1.0 adds no drawable. The row is **third**, after `Backup and restore` and `Read / inspect tag`, so the two doors the owner already knows keep their positions.
7. **`onDeveloperApi` is the last parameter and has no default.** A defaulted callback would let a future caller of `SettingsScreen` silently draw a row that does nothing. `SettingsBackupEntryTest` is the only other caller, and **Step 8** updates it (Step 7 is the manifest).
8. **No deep link, and the manifest gains no component.** `MainActivity`'s filter answers `servicetag://asset` and `servicetag://tag` only (`AndroidManifest.xml:36`–`37`); adding `servicetag://developer-api` would make an exported, browsable way to open the screen that starts the listener, which is precisely the thing the lifecycle binding exists to prevent. Task 6's end-to-end proof therefore drives the real UI with `uiautomator dump` and `input tap`, which is what the design asked for when no deep link is added.

- [ ] **Step 1: Write the failing connected test first.**

Create `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiListenerTest.kt`:

```kotlin
package com.loosecannon.servicetag.ui.api

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for the lifecycle effect to bind or release the port. */
private const val SETTLE_MILLIS = 5_000L

/**
 * 1.1.0 (#46) — the half of this feature that only a device can prove: that the listener really
 * comes up on 17337 when the screen is shown, that the code on the screen is the code it accepts,
 * and that it is **gone** when the screen goes.
 *
 * **What is proved elsewhere, and is not repeated here.** The parser, the ceilings, the router, the
 * handlers and the 401 are `:app`'s JVM suite (`HttpWireTest`, `ApiRouterTest`,
 * `LoopbackApiServerTest`), on a real socket, with no emulator. What that suite cannot reach is the
 * `INTERNET` permission actually being granted, `LifecycleResumeEffect` actually running, and a
 * `ViewModel` scoped to a nav entry actually minting one code per visit. That is this file.
 *
 * **How the code gets from the screen to the assertion.** The model is built here and seeded into
 * the `ViewModelStore` this test provides, under the key `DeveloperApiScreen` resolves with —
 * `viewModel(key = "developer-api")` returns the stored instance rather than calling its
 * initializer. So `model.pairingCode` is the screen's own code, and the test both types it at the
 * socket and asserts it is on screen. No seam is added to production code. (Task 6's end-to-end
 * proof does it the other way round, off a `uiautomator` dump, which is the workstation's path.)
 *
 * The production port is used deliberately: it is what the workstation forwards to, and an
 * emulator running one instrumented suite has nothing else on it.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class DeveloperApiListenerTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun freshInstall() = clearInstall()

    /** Sends [raw] verbatim to the listener and reads the whole answer back. */
    private fun speak(raw: String): String = Socket("127.0.0.1", DEVELOPER_API_PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
        socket.getInputStream().readBytes().decodeToString()
    }

    private fun seededModel(): Pair<ViewModelStoreOwner, DeveloperApiViewModel> {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val model = ViewModelProvider.create(
            owner,
            viewModelFactory { initializer { DeveloperApiViewModel(app.graph) } },
        )["developer-api", DeveloperApiViewModel::class.java]
        return owner to model
    }

    @Test fun theScreenSaysWhatItIsDoingAndTheListenerAnswersWhileItIsShown() {
        val (owner, model) = seededModel()
        var shown by mutableStateOf(true)

        rule.setContent {
            ServiceTagTheme {
                if (shown) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }
        }

        // The five ratified strings, exactly as ratified. `LabelValue` uppercases a label
        // (`LabelValue.kt:27`), so the three labels read back in capitals; the title and the
        // sentence read back as written.
        rule.awaitText("Developer API")
        rule.onNodeWithText("Developer API").assertIsDisplayed()
        rule.onNodeWithText(
            "While this screen is open, ServiceTag accepts commands on this phone's " +
                "loopback address only. Pair with the code below; leave the screen to stop.",
        ).assertIsDisplayed()
        rule.onNodeWithText("PORT").assertIsDisplayed()
        rule.onNodeWithText("PAIRING CODE").assertIsDisplayed()
        rule.onNodeWithText("REQUESTS THIS SESSION").assertIsDisplayed()
        rule.onNodeWithText(DEVELOPER_API_PORT.toString()).assertIsDisplayed()
        rule.onNodeWithText(model.pairingCode).assertIsDisplayed()
        // S6 is the failure line, so on a working listener it must not be anywhere.
        rule.onAllNodesWithText(
            "The Developer API could not start. Leave this screen and open it again.",
        ).assertCountEquals(0)

        // The effect has bound the port.
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == DEVELOPER_API_PORT }
        assertFalse(model.failedToStart.value)

        val answer = speak(
            "GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Authorization: Bearer ${model.pairingCode}\r\n\r\n",
        )
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("\"apiVersion\":1"))
        assertTrue(answer, answer.contains("\"assets\":0"))

        // Leaving the screen is what stops it — and after that there is nothing on the port.
        rule.runOnIdle { shown = false }
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == 0 }
        try {
            Socket("127.0.0.1", DEVELOPER_API_PORT).use { it.getInputStream().read() }
            fail("the port is still answering after the screen went away")
        } catch (e: IOException) {
            // Refused. That is the lifecycle binding working.
        }
    }

    @Test fun onlyTheCodeOnTheScreenIsAcceptedAndEveryTryIsCounted() {
        val (owner, model) = seededModel()

        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    DeveloperApiScreen(graph = app.graph, onBack = {})
                }
            }
        }
        rule.awaitText("Developer API")
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == DEVELOPER_API_PORT }

        val refused = speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer NOPENOPE\r\n\r\n")
        assertEquals(
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            refused,
        )
        speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer ${model.pairingCode}\r\n\r\n")

        // The screen's third line is the count of answers given, refusals included.
        rule.waitUntil(SETTLE_MILLIS) { model.requests.value == 2 }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    /**
     * **S6, byte-exactly, on a device.** `DeveloperApiViewModelTest` proves the flag flips;
     * `theScreenSaysWhatItIsDoingAndTheListenerAnswersWhileItIsShown` proves the sentence is
     * *absent* on the happy path — which a typo'd screen also satisfies. This is the only case that
     * reads the ratified words off a rendered screen, so a wrong word in the one string the owner
     * added *because* an earlier draft left the failure silent cannot ship green.
     *
     * The port is squatted by the test itself, and the model is handed that port, so the case is
     * hermetic: it cannot collide with 17337, with a real listener or with another test. It also
     * asserts what decision 5 claims and nothing else checked — that the three `LabelValue` rows
     * stay where they are when the listener does not come up.
     */
    @Test fun theScreenSaysSoWhenTheListenerCannotStart() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val owner = object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
            val model = ViewModelProvider.create(
                owner,
                viewModelFactory {
                    initializer { DeveloperApiViewModel(app.graph, port = squatter.localPort) }
                },
            )["developer-api", DeveloperApiViewModel::class.java]

            rule.setContent {
                ServiceTagTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }

            rule.awaitText("The Developer API could not start. Leave this screen and open it again.")
            rule.onNodeWithText(
                "The Developer API could not start. Leave this screen and open it again.",
            ).assertIsDisplayed()
            assertTrue(model.failedToStart.value)
            assertEquals(0, model.boundPort)

            // Decision 5: the three rows stay, because the port is still a fact and the count
            // staying at 0 is itself the corroboration.
            rule.onNodeWithText("PORT").assertIsDisplayed()
            rule.onNodeWithText("PAIRING CODE").assertIsDisplayed()
            rule.onNodeWithText("REQUESTS THIS SESSION").assertIsDisplayed()
            rule.onNodeWithText("0").assertIsDisplayed()
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile.**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.api.DeveloperApiListenerTest \
  --console=plain
```

Expected: **FAILURE** at compilation — `Unresolved reference: DeveloperApiScreen`, `Unresolved reference: DeveloperApiViewModel`. That is the red. (One class per invocation: a comma-separated list silently runs only the first.)

- [ ] **Step 3: The view model and the screen.**

Create `app/src/main/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiViewModel.kt`:

```kotlin
package com.loosecannon.servicetag.ui.api

import android.util.Log
import androidx.lifecycle.ViewModel
import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ApiRouter
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.api.LoopbackApiServer
import com.loosecannon.servicetag.api.newPairingCode
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ServiceTagApi"

/**
 * Owns one pairing code and one listener, for one visit to the Developer API screen.
 *
 * **The code is this model's, which is why it is fresh every time the screen opens.**
 * `ServiceTagRoot` decorates every nav entry with `rememberViewModelStoreNavEntryDecorator()` so
 * that a model lives for the visit and is cleared on the pop (`ServiceTagRoot.kt:88`–`95`). A new
 * visit is therefore a new model and a new code, and there is nowhere for the old one to be
 * remembered: it is deliberately not saved state, not a preference and not a `companion object`.
 *
 * **Starting and stopping is the screen's**, through `LifecycleResumeEffect` — the same primitive
 * the nav shell uses for reader mode (`ServiceTagRoot.kt:75`–`78`). This class exposes the two
 * calls and no lifecycle opinion of its own, and deliberately does **not** also stop in
 * `onCleared`: the effect's dispose has already run by the time the entry's store is cleared, and
 * two owners of one lifetime is how a socket ends up outliving a screen.
 */
internal class DeveloperApiViewModel(
    handlers: ApiHandlers,
    port: Int = DEVELOPER_API_PORT,
) : ViewModel() {

    constructor(graph: AppGraph) : this(ApiHandlers(graph))

    /** Shown on the screen, and the only token the listener accepts. Never logged, never stored. */
    val pairingCode: String = newPairingCode()

    private val server = LoopbackApiServer(ApiRouter(handlers, pairingCode), port)

    /** Answers given this session, refusals included — the screen's third line. */
    val requests: StateFlow<Int> = server.requests

    private val _failedToStart = MutableStateFlow(false)

    /**
     * True when [listen] could not bind the port — the screen then shows **S6**.
     *
     * A flow and not a thrown exception: the caller is a Compose lifecycle effect, where an
     * escaping `SocketException` would be a crash on a screen *opening*, and a port already in use
     * is an ordinary thing that happens.
     */
    val failedToStart: StateFlow<Boolean> = _failedToStart.asStateFlow()

    /** The port actually bound, or 0 while stopped. Read by the proofs, not by the screen. */
    val boundPort: Int get() = server.boundPort

    fun listen() {
        val bound = server.start()
        _failedToStart.value = !bound
        if (!bound) {
            // The only line this feature logs, and all it says: no token, no path, no body.
            Log.w(TAG, "the developer API could not bind its port")
        }
    }

    fun stopListening() {
        server.stop()
        // The message belongs to a visit. Coming back is a fresh model, a fresh code and a fresh
        // attempt, so it must not arrive already complaining about the last one.
        _failedToStart.value = false
    }
}
```

Create `app/src/main/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiScreen.kt`:

```kotlin
package com.loosecannon.servicetag.ui.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.theme.MeasurementHeroText

/**
 * The one screen the automation API has, and the whole of its lifetime (1.1.0, issue #46).
 *
 * While this screen is on top and the activity is resumed, the app listens on this phone's own
 * loopback address, port [DEVELOPER_API_PORT], and answers `/v1` requests that carry the code shown
 * here. Pausing — the screen going off, the phone locking, an app switch — stops it; leaving the
 * screen disposes the composable and stops it; the next visit is a different code. There is no
 * service, no background work and no way to start the listener without this screen in front of the
 * owner, which is the point: the honest answer to "when is my phone accepting commands?" is "while
 * you are looking at the screen that says so".
 *
 * Nothing here decides anything about the API. The code, the router and the socket are
 * [DeveloperApiViewModel]'s; this draws four lines and binds two lifecycle edges.
 *
 * The port is drawn from the constant rather than from `boundPort`, because the constant is what
 * the label means and what a workstation forwards to; a bind failure therefore shows as a request
 * count that never moves, and is logged once (see [DeveloperApiViewModel.listen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeveloperApiScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    val model: DeveloperApiViewModel =
        viewModel(key = "developer-api") { DeveloperApiViewModel(graph) }
    val requests by model.requests.collectAsStateWithLifecycle()
    val failed by model.failedToStart.collectAsStateWithLifecycle()

    LifecycleResumeEffect(model) {
        model.listen()
        onPauseOrDispose { model.stopListening() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer API") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuietLine(
                "While this screen is open, ServiceTag accepts commands on this phone's " +
                    "loopback address only. Pair with the code below; leave the screen to stop.",
            )
            if (failed) {
                // S6. Drawn in the error colour rather than as a QuietLine, because the one thing
                // this screen exists to do is not happening.
                Text(
                    text = "The Developer API could not start. Leave this screen and open it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LabelValue(label = "Port", value = DEVELOPER_API_PORT.toString(), mono = true)
            LabelValue(
                label = "Pairing code",
                value = model.pairingCode,
                valueStyle = MeasurementHeroText,
            )
            LabelValue(label = "Requests this session", value = requests.toString(), mono = true)
        }
    }
}
```

`MeasurementHeroText` is the 30sp medium monospace with tabular numerals (`theme/Type.kt:26`) the journal uses for a hero reading — which is exactly "shown large" for eight characters a person is about to type on another machine.

- [ ] **Step 4: The route, the nav entry, and the Settings row.**

In `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt`, add one member after `Settings` (after line 48), inside the `sealed interface`:

```kotlin

    /**
     * The loopback automation API's one screen (1.1.0, #46). A pushed destination reached from
     * Settings, exactly like [Backup] and [Scan]: the listener is alive only while this entry is on
     * top, so there is deliberately no deep link and no way to reach it but through Settings.
     */
    @Serializable data object DeveloperApi : Route
```

It reads no tags, so `Route.readsTags()`'s `else -> false` already answers for it (`Route.kt:83`–`87`) and that function is **not edited**. Step 5 adds the assertion that says so.

In `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt`, add one import in alphabetical position — after line 18 (`import com.loosecannon.servicetag.di.AppGraph`) and before line 19 (`import com.loosecannon.servicetag.ui.asset.AssetDetailScreen`):

```kotlin
import com.loosecannon.servicetag.ui.api.DeveloperApiScreen
```

Replace the `Route.Settings` entry (lines 262–269) with the same entry plus the new callback, and add the new entry immediately after it — so the two Settings destinations sit together and the block ends where the old one did:

```kotlin
                entry<Route.Settings> {
                    SettingsScreen(
                        graph = graph,
                        onBack = { backStack.removeLastOrNull() },
                        onReadTag = { backStack.add(Route.Scan) },
                        onBackup = { backStack.add(Route.Backup) },
                        onDeveloperApi = { backStack.add(Route.DeveloperApi) },
                    )
                }
                entry<Route.DeveloperApi> {
                    DeveloperApiScreen(
                        graph = graph,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
```

In `app/src/main/kotlin/com/loosecannon/servicetag/ui/settings/SettingsScreen.kt`, **no import changes** — `ServiceTagIcons` is already imported (`:57`) and `UtilityRow` is in this file. Three edits.

First, the KDoc. Replace lines 76–78 (the `Read / inspect tag` paragraph) with that paragraph unchanged plus one more:

```kotlin
 * Read / inspect tag is the scan screen kept as a utility (D12 §16 correction, spec §9): normal
 * tag reading is ambient dispatch, so the only reason to open it deliberately is to identify a
 * tag with nothing else prompting the read.
 *
 * Developer API is the third utility (1.1.0, #46) and the only one whose *screen* is the feature:
 * while it is open the app answers commands on this phone's own loopback address, and closing it
 * is what stops that. The row is last, so the two doors the owner already knows keep their places.
```

Second, the signature. Replace lines 82–87 with:

```kotlin
fun SettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onReadTag: () -> Unit,
    onBackup: () -> Unit,
    onDeveloperApi: () -> Unit,
) {
```

`onDeveloperApi` is last and **undefaulted**, so no caller can draw a row that does nothing by forgetting it.

Third, the Utilities block. Replace lines 232–242 with:

```kotlin
            SectionHeader(title = "Utilities")
            UtilityRow(
                icon = ServiceTagIcons.Backup,
                label = "Backup and restore",
                onClick = onBackup,
            )
            UtilityRow(
                icon = ServiceTagIcons.Contactless,
                label = "Read / inspect tag",
                onClick = onReadTag,
            )
            UtilityRow(
                icon = ServiceTagIcons.Speed,
                label = "Developer API",
                onClick = onDeveloperApi,
            )
```

- [ ] **Step 5: The view model's JVM tests, including S6's failure state.**

The failure *is* JVM-testable, because `DeveloperApiViewModel` takes its port: a test occupies one and passes it. So there is no need to fight for it on the emulator — the emulator's job is the success path, which is what `DeveloperApiListenerTest` does.

`androidx.lifecycle.ViewModel` constructs fine in `testDebugUnitTest` (`BackupViewModelTest` does it twenty-two times), and the one Android call in the class, `android.util.Log.w`, returns a default rather than throwing because of `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:90`) — which that setting's own comment says is exactly what it is there for.

Create `app/src/test/kotlin/com/loosecannon/servicetag/ui/api/DeveloperApiViewModelTest.kt`:

```kotlin
package com.loosecannon.servicetag.ui.api

import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.PAIRING_ALPHABET
import com.loosecannon.servicetag.api.PAIRING_CODE_LENGTH
import com.loosecannon.servicetag.testing.FakeGraph
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the per-visit pairing code, and **S6**: what the screen is told when the listener
 * cannot bind.
 *
 * No `Dispatchers.setMain` and no shared scheduler: this view model has no `viewModelScope` work,
 * no Room flow and nothing to settle — `listen()` binds a socket synchronously and returns.
 */
class DeveloperApiViewModelTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private fun handlers() = ApiHandlers(
        graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles, graph.events,
        graph.attachments,
        graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
        graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
        graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
        appVersion = "1.1.0",
        schemaVersion = 5,
    )

    /** A fresh code per model is what "a fresh code every time the screen opens" reduces to. */
    @Test fun eachModelMintsItsOwnCode() {
        val codes = List(50) { DeveloperApiViewModel(handlers(), port = 0).pairingCode }
        assertEquals(50, codes.toSet().size)
        codes.forEach {
            assertEquals(PAIRING_CODE_LENGTH, it.length)
            assertTrue(it.all { c -> c in PAIRING_ALPHABET })
        }
        assertNotEquals(codes[0], codes[1])
    }

    @Test fun listeningOnAFreePortReportsNoFailure() {
        val model = DeveloperApiViewModel(handlers(), port = 0)
        try {
            model.listen()
            assertFalse(model.failedToStart.value)
            assertNotEquals(0, model.boundPort)
        } finally {
            model.stopListening()
        }
        assertEquals(0, model.boundPort)
    }

    /** S6's trigger: something else already has the port. */
    @Test fun listeningOnAnOccupiedPortReportsTheFailure() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val model = DeveloperApiViewModel(handlers(), port = squatter.localPort)
            model.listen()

            assertTrue(model.failedToStart.value)
            assertEquals(0, model.boundPort)
            assertEquals(0, model.requests.value)

            // Leaving the screen clears the message: the next visit decides for itself.
            model.stopListening()
            assertFalse(model.failedToStart.value)
        }
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests '*DeveloperApiViewModelTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 cases.

- [ ] **Step 6: The route's JVM assertion.**

In `app/src/test/kotlin/com/loosecannon/servicetag/ui/nav/RouteTest.kt`, add one line to `onlyTheTagScreensHoldReaderMode`, immediately after `assertFalse(Route.Settings.readsTags())` (line 51):

```kotlin
        assertFalse(Route.DeveloperApi.readsTags())
```

and add one sentence to that test's KDoc, after the "link" write-route paragraph:

```
     * Neither is the Developer API screen (1.1.0, #46): it holds a socket, not a tag, and adding it
     * here would turn reader mode on over a screen with no sink.
```

Run: `./gradlew :app:testDebugUnitTest --tests '*RouteTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 3 cases — the same three, one of them with one more assertion.

- [ ] **Step 7: The manifest, and exactly what changes in it.**

In `app/src/main/AndroidManifest.xml`, add one line after line 3, with the owner's ratified sentence as its comment. The file's first thirteen lines become:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.NFC" />
    <!-- A TCP socket needs this on Android even when it is bound to the loopback address: the
         platform gates AF_INET socket creation on the `inet` group this permission grants, not on
         the address the socket ends up bound to. It is a normal, install-time permission with no
         runtime prompt. No component is added for it — a socket is not a component, so there is
         nothing here to export.

         INTERNET gives the process network capability, but ServiceTag 1.1.0 introduces no outbound networking and the Developer API listens only on localhost; future cloud features may make intentional outbound use of the permission under their own designs. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.hardware.nfc" android:required="true" />
```

The last paragraph of that comment is the owner's sentence, **verbatim and on one physical line**, and it appears in two more places, also unbroken: `docs/api/v1.md` and the README subsection (Task 5 Steps 3 and 4). Three proofs grep for the substring `no outbound networking and the Developer API listens only on localhost`, so the line must not be re-wrapped — which is also why the paragraph above it says "the loopback address" instead of repeating the literal, so that the `"127.0.0.1"` grep stays a check on code.

**Nothing else in the file changes.** No `<service>`, no `<receiver>`, no new `<provider>`, no new `<activity>`, no new `<intent-filter>`, no new `<data>` scheme, and no `android:exported` anywhere: the three exported components stay exactly the three that are there — `MainActivity`, `NfcDispatchActivity` and the `FileProvider` (which is `android:exported="false"`). `app/src/debug/AndroidManifest.xml` is untouched; a permission declared in the main manifest merges into every variant, so the debug build needs nothing of its own.

The one alternative that would avoid this line entirely — an abstract `AF_UNIX` socket, which needs no permission and which `adb forward tcp:17337 localabstract:servicetag` speaks natively — is written up in **Deviations note D1**, because it contradicts the approved design's "127.0.0.1, fixed port 17337" and is the owner's call, not this plan's.

- [ ] **Step 8: The Settings entry's connected case.**

In `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/settings/SettingsBackupEntryTest.kt`: the existing call at lines 34–39 gains the new lambda, the KDoc gains a sentence, and one case is added. The class keeps its name — it is the file that pins Settings' utility rows as doors that never close, and 1.1.0 adds a third door.

Add to the KDoc, after its existing paragraph:

```
 * 1.1.0 (#46) adds a third row, Developer API, and the same reasoning applies twice over: that
 * screen *is* the automation API's lifetime, so a Settings edit that dropped the row would remove
 * the only way to start the listener at all — there is deliberately no deep link to it.
```

Replace the existing `SettingsScreen(` call with:

```kotlin
                SettingsScreen(
                    graph = graph,
                    onBack = {},
                    onReadTag = {},
                    onBackup = { backupTapped++ },
                    onDeveloperApi = {},
                )
```

and add one case after `backupRowIsPresentAndInvokesOnBackup`:

```kotlin
    @Test fun developerApiRowIsPresentAndInvokesOnDeveloperApi() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        var apiTapped = 0

        rule.setContent {
            ServiceTagTheme {
                SettingsScreen(
                    graph = graph,
                    onBack = {},
                    onReadTag = {},
                    onBackup = {},
                    onDeveloperApi = { apiTapped++ },
                )
            }
        }
        rule.waitForIdle()

        // `SettingsScreen` is a `verticalScroll` column (`SettingsScreen.kt:164`, `:167`) and this
        // is the third Utilities row, where the About header used to sit — below the fold on a
        // phone. `performScrollTo()` is the house pattern for exactly this (`AppSmokeTest.kt:219`).
        rule.onNodeWithText("Developer API").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Developer API").performScrollTo().performClick()

        assertEquals(1, apiTapped)
    }
```

- [ ] **Step 9: Run the connected classes, one invocation each, and watch them pass.**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.api.DeveloperApiListenerTest \
  --console=plain
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.settings.SettingsBackupEntryTest \
  --console=plain
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.nav.NavigationSmokeTest \
  --console=plain
```

Expected: `BUILD SUCCESSFUL` three times. `app/build/reports/androidTests/connected/` shows `DeveloperApiListenerTest` **3/3**, `SettingsBackupEntryTest` **2/2** (was 1/1), `NavigationSmokeTest` at its previous count — it reaches Settings by content description and does not enumerate the rows, so the third row cannot disturb it. The existing `backupRowIsPresentAndInvokesOnBackup` case needs no scroll and gets none: `Backup and restore` is the *first* Utilities row and the new row is added below it, so nothing that was above the fold has moved.

If `DeveloperApiListenerTest` fails at `model.boundPort == DEVELOPER_API_PORT` with the logcat line `the developer API could not bind its port`, the permission is the thing to check first: `adb -s emulator-5554 shell dumpsys package com.loosecannon.servicetag | grep -i internet` must show `android.permission.INTERNET: granted=true`. That is Step 6's line doing its job, or not being there.

- [ ] **Step 10: The gate, then stage and verify.**

```bash
MAN=app/src/main/AndroidManifest.xml
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain
git add -A
git status --porcelain
git diff --cached --name-only
git diff --cached --stat -- libs core app/schemas app/build.gradle.kts gradle docs README.md app/src/main/kotlin/com/loosecannon/servicetag/api
git diff --cached --stat -- app/src/main/kotlin/com/loosecannon/servicetag/MainActivity.kt app/src/main/kotlin/com/loosecannon/servicetag/nfc app/src/main/kotlin/com/loosecannon/servicetag/ui/scan app/src/main/kotlin/com/loosecannon/servicetag/ui/backup
git grep --cached -c 'android:exported' -- "$MAN"
git grep --cached -c 'uses-permission' -- "$MAN"
git grep --cached -n 'android.permission' -- "$MAN"
git grep --cached -c '<service\|<receiver\|<provider' -- "$MAN"
git grep --cached -n 'uses-permission' -- app/src/debug/AndroidManifest.xml
git grep --cached -c 'android:scheme' -- "$MAN"
git grep --cached -nE 'DeveloperApiScreen\(' -- app/src/main
git grep --cached -nE 'LifecycleResumeEffect\(' -- app/src/main
git grep --cached -nE 'label = "Developer API"|Text\("Developer API"\)' -- app/src/main
git grep --cached -n 'readsTags' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt
git grep --cached -nE '"The Developer API could not start' -- app/src/main
git grep --cached -c 'no outbound networking and the Developer API listens only on localhost' -- "$MAN"
git grep --cached -nE '"127\.0\.0\.1"' -- app/src/main
```

Expected: `BUILD SUCCESSFUL`, `:app` at **42 classes / 310 tests** — Task 2's 41/307 plus `DeveloperApiViewModelTest`'s three cases. The `RouteTest` assertion adds no case, so that class stays at 3. `:core` 33/380, `:nfc-core` 6/50, `:nfc-android` 3/10.

`git status --porcelain` lists exactly **ten** entries: four `A ` (`ui/api/DeveloperApiViewModel.kt`, `ui/api/DeveloperApiScreen.kt`, `test/.../ui/api/DeveloperApiViewModelTest.kt`, `androidTest/.../ui/api/DeveloperApiListenerTest.kt`) and six `M ` (`Route.kt`, `ServiceTagRoot.kt`, `SettingsScreen.kt`, `AndroidManifest.xml`, `RouteTest.kt`, `SettingsBackupEntryTest.kt`), and nothing with a `??`. **No output** from either `git diff --cached --stat`: `:core`, the library, the schemas, the build files, the docs, the README, **Task 2's whole `api/` package**, `MainActivity`, the NFC trampoline, the scan screens and the backup screen are all untouched.

Then the manifest facts, each pattern narrowed to what it means:
- `android:exported` is still **3**, and `<service|<receiver|<provider` still **1** (the existing `FileProvider`): no component was added.
- `uses-permission` is **2** (was 1), and the `android.permission` read-out shows exactly which two — `NFC` and `INTERNET`, nothing else.
- The debug manifest yields **no** `uses-permission` line.
- `android:scheme` is **4**, unchanged — `content` in the `<queries>` block (`AndroidManifest.xml:10`), `servicetag` twice and `vnd.android.nfc` once. It was already 4 at `0a582f8`; 1.1.0 adds no scheme, which is the claim.
- `no outbound networking and the Developer API listens only on localhost` is **1** in the manifest: the owner's sentence, unbroken, in the first of its three places. If the comment is ever re-wrapped this drops to 0, which is the point of grepping the long form.
- `"127.0.0.1"` **with the quotes** is **1 line** — the `LOOPBACK_ADDRESS` constant — and not the manifest comment, which says "the loopback address" in prose for exactly this reason.

Then the code facts: `DeveloperApiScreen(` is **2 lines**, its declaration and `ServiceTagRoot`'s one entry. `LifecycleResumeEffect(` — **with the parenthesis**, because five bare-word matches already exist in `ServiceTagRoot.kt` and `ReaderMode.kt` — is **3 lines**: `ServiceTagRoot.kt:75`, `ReaderMode.kt:158` and the listener's in `DeveloperApiScreen.kt`. The two `Developer API` patterns are grepped **separately**, `label = "Developer API"` → 1 and `Text("Developer API")` → 1, rather than as one count of the bare words, which would be 6 lines over 4 files once the two KDoc paragraphs and the manifest comment are included. `readsTags` in `Route.kt` is unchanged at its 1 line (the function), because the new route needs no case. `"The Developer API could not start` is **1 line, 1 file** — S6, in `DeveloperApiScreen.kt` and nowhere else, anchored on the opening quote so a KDoc that discusses it cannot pad the count.

- [ ] **Step 11: Commit.**

```bash
AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
git add -A
git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "the api lives as long as the screen that says so"
```

---

### Task 4: the workstation MCP server, its tests, its CI job and its README

**Files:**
- Create: `tools/servicetag-mcp/pyproject.toml`
- Create: `tools/servicetag-mcp/.python-version`
- Create: `tools/servicetag-mcp/uv.lock` (generated by `uv lock` at Step 8 and committed)
- Create: `tools/servicetag-mcp/README.md`
- Create: `tools/servicetag-mcp/src/servicetag_mcp/__init__.py`
- Create: `tools/servicetag-mcp/src/servicetag_mcp/client.py`
- Create: `tools/servicetag-mcp/src/servicetag_mcp/server.py`
- Create: `tools/servicetag-mcp/tests/conftest.py`
- Create: `tools/servicetag-mcp/tests/test_client.py`
- Create: `tools/servicetag-mcp/tests/test_tools.py`
- Modify: `.github/workflows/ci.yml` (one new job, after the `build` job)
- **Untouched, and checked at Step 12:** `.github/workflows/release.yml`, every existing step of `ci.yml`'s `build` job, `app/**`, `core/**`, `libs/**`, `gradle/**`, `docs/**`, `README.md`

**Interfaces:**
- Consumes: **Task 2's** `/v1` contract — every path, method, status and body shape in Task 2's route table, and the `Authorization: Bearer <code>` rule; **Task 3's** screen, which is where the code comes from; `DEVELOPER_API_PORT` = 17337 as a number this side has to agree on.
- Produces (Task 5's README and `docs/api/v1.md` reference these; Task 6 runs them):
  - `servicetag_mcp.client.Device` with `base_url`, `serial`, `adb`, `token`, `forwarded`; `Device.from_env()`; `Device.ensure_forward()`; `Device.request(method, path, *, json_body=None, content=None, content_type=None, report_statuses=())`.
  - `servicetag_mcp.client.ApiError(status, code, message, problems)` and `servicetag_mcp.client.NotPaired`.
  - `servicetag_mcp.client.DEFAULT_PORT` = 17337; `BASE_URL_ENV` = `"SERVICETAG_API_BASE_URL"`; `SERIAL_ENV` = `"SERVICETAG_ADB_SERIAL"`; `ADB_ENV` = `"SERVICETAG_ADB"`.
  - `servicetag_mcp.server` with `mcp`, `device`, `TOOL_NAMES`, `main()`, and the **twenty-one** tool functions: `pair`, `status`, `list_assets`, `get_asset`, `create_asset`, `update_asset`, `create_component`, `retire_asset`, `archive_asset`, `list_definitions`, `save_definition`, `archive_definition`, `list_profiles`, `save_profile`, `archive_profile`, `list_events`, `log_event`, `update_event`, `delete_event`, `list_tag_bindings`, `import_merge`. That is `pair` plus one tool per API operation — **R2**, approved: the MCP mirrors the whole surface, so `archive_definition` and `archive_profile` are tools and not curl-only endpoints.
  - The console script `servicetag-mcp`.

**Decisions, and what lost:**

1. **`MCPServer`, not `FastMCP`.** The approved design says "the `mcp` package's FastMCP". In the SDK's 2.x line that class is called `MCPServer` and lives at `mcp.server` — `from mcp.server import MCPServer`, with the SDK's own migration note reading *"Use MCPServer instead of FastMCP for the high-level server class in v2. The previous import path under fastmcp has been replaced with mcpserver."* It is the same high-level server the design means. Pinning `mcp>=1.2,<2` to keep the old name lost: it would pin a superseded major to honour a word. See **Deviations note D5**.
2. **`@mcp.tool()` with parentheses, everywhere.** The SDK raises `TypeError: The @tool decorator was used incorrectly. Did you forget to call it? Use @tool() instead of @tool` at *import* time for a bare `@mcp.tool` — before any client connects, so it is a server that will not start. Named here because it is the one mistake that turns twenty-one working tools into none.
3. **`adb forward` is the client's own business, and it is skipped whenever someone else owns the transport.** `Device.from_env()` reads `SERVICETAG_API_BASE_URL` first; if it is set, the device is marked already-forwarded and **`adb` is never invoked**. That single rule is what lets the whole pytest suite run against a stdlib HTTP server on a free port with no device attached, and it is also the escape hatch for an operator who set up their own forward. The serial comes from `SERVICETAG_ADB_SERIAL` or the MCP config's `env` block and from nowhere else — **no file in this repository names a device**.
4. **The forward is lazy and once.** `ensure_forward()` runs on the first real call, not at import: an MCP server is started by the editor whether or not a phone is plugged in, and a module that shells out to `adb` at import time is a server that fails to load on a laptop with no device. `adb forward` is idempotent, but the flag means it is one subprocess per session rather than one per tool call.
5. **`pair` is a tool, not an environment variable.** The code changes every time the screen opens, so it cannot live in a config file; and putting it in the environment would mean restarting the MCP server to re-pair. A tool call is the only shape that matches a per-session secret. It is stored in memory on the `Device` and written nowhere.
6. **A 401 is reported as `NotPaired`, not as an `ApiError`.** It has an empty body by design, so there is nothing to report but the one thing it means: the code is wrong or the screen has been closed and reopened. The message says so, which is what turns a stale pairing from a mystery into one more `pair` call.
7. **`httpx`, synchronously.** The design names it, and `httpx.request` against loopback with a 30-second timeout is the whole need: there is one client, one call at a time, and the app's listener answers one connection at a time anyway, so an async client would buy concurrency the server side refuses. (The SDK brings its own HTTP stack for its own transport; this depends on `httpx` directly rather than reaching into the SDK's.)
8. **pytest drives the tool functions directly, and a stdlib `ThreadingHTTPServer` answers them.** `http.server` is in the standard library, so the fake API is not a dependency; and calling `server.list_assets()` rather than going through an MCP session tests the thing that can actually be wrong — the path, the method, the body and the error mapping — without asserting anything about the SDK's own transport, which is the SDK's to test. A recorded-request list is what each case asserts against.
9. **`TOOL_NAMES` is a literal in the module and a literal in the test.** The one thing a fresh reader cannot check by reading is whether a tool was quietly dropped, so the twenty-one names exist twice, on purpose, and `test_every_tool_the_design_names_is_registered` fails if either list moves. The tuple is not built by introspecting the SDK: a registry's private shape is not a contract.
10. **`import_merge` plans first, always, and applies only a conflict-free plan.** One tool, not two, because "merge this archive" is one intention and a client that had to orchestrate plan-then-apply would be a client that could get it wrong. `plan_only=True` stops after the plan; a plan with conflicts also stops after the plan and is returned as-is, so the caller gets the deterministic conflict list from the same call. The two API endpoints stay separate because a future UI needs them separate (#44 slice E), and this tool is the automation shape on top of them.

- [ ] **Step 1: Scaffold the project.**

Create `tools/servicetag-mcp/pyproject.toml`:

```toml
[project]
name = "servicetag-mcp"
version = "1.1.0"
description = "A workstation MCP server for ServiceTag's loopback automation API"
readme = "README.md"
requires-python = ">=3.12"
dependencies = [
    "mcp>=2.2,<3",
    "httpx>=0.28,<1",
]

[project.scripts]
servicetag-mcp = "servicetag_mcp.server:main"

[dependency-groups]
dev = ["pytest>=8.3,<9"]

[build-system]
requires = ["hatchling>=1.25"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/servicetag_mcp"]

[tool.pytest.ini_options]
testpaths = ["tests"]
addopts = "-q"
```

Create `tools/servicetag-mcp/.python-version`:

```
3.12
```

Create `tools/servicetag-mcp/src/servicetag_mcp/__init__.py`:

```python
"""A workstation MCP server for ServiceTag's loopback automation API (see docs/api/v1.md)."""
```

- [ ] **Step 2: Write the failing client tests first.**

Create `tools/servicetag-mcp/tests/conftest.py`:

```python
"""A stand-in for the phone: a stdlib HTTP server on 127.0.0.1, and no device anywhere.

`http.server` is in the standard library, so the fake API costs no dependency, and it speaks the
same HTTP/1.1 the app's own hand-rolled listener speaks. Every request it receives is recorded, so
a test asserts on the method, the path, the headers and the body the tool actually sent.
"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from servicetag_mcp import client as client_module
from servicetag_mcp import server as server_module


@dataclass
class Recorded:
    method: str
    path: str
    headers: dict[str, str]
    body: bytes


@dataclass
class FakeApi:
    url: str
    requests: list[Recorded] = field(default_factory=list)
    replies: dict[tuple[str, str], tuple[int, bytes]] = field(default_factory=dict)

    def reply(self, method: str, path: str, status: int, body: object) -> None:
        """What to answer for one (method, path). Anything unset answers 200 `{}`."""
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.replies[(method, path)] = (status, raw)

    def last(self) -> Recorded:
        assert self.requests, "the tool sent no request at all"
        return self.requests[-1]


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch):
    state: dict[str, FakeApi] = {}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args: object) -> None:  # keep pytest output clean
            return

        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            fake = state["api"]
            fake.requests.append(
                Recorded(self.command, self.path, dict(self.headers.items()), body)
            )
            status, payload = fake.replies.get((self.command, self.path), (200, b"{}"))
            self.send_response(status)
            if payload:
                self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if payload:
                self.wfile.write(payload)

        do_GET = _handle
        do_POST = _handle
        do_PATCH = _handle
        do_DELETE = _handle

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    fake = FakeApi(url=f"http://127.0.0.1:{httpd.server_address[1]}")
    state["api"] = fake
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()

    # An explicit base URL is the documented signal that someone else owns the transport, so the
    # client never shells out to adb. `monkeypatch` also guarantees no serial leaks in from the
    # developer's own environment.
    monkeypatch.setenv(client_module.BASE_URL_ENV, fake.url)
    monkeypatch.delenv(client_module.SERIAL_ENV, raising=False)
    server_module.device = client_module.Device.from_env()

    try:
        yield fake
    finally:
        httpd.shutdown()
        httpd.server_close()


@pytest.fixture
def paired(api: FakeApi):
    """The fake API, with the tools already holding a pairing code."""
    server_module.pair("ABCD2345")
    return api
```

Create `tools/servicetag-mcp/tests/test_client.py`:

```python
"""The HTTP side: the token, the error mapping, and the one rule that keeps adb out of the tests."""

from __future__ import annotations

import pytest

from servicetag_mcp import server as server_module
from servicetag_mcp.client import ApiError, Device, NotPaired


def test_a_request_carries_the_bearer_token(paired) -> None:
    server_module.status()
    sent = paired.last()
    assert sent.method == "GET"
    assert sent.path == "/v1/status"
    assert sent.headers["Authorization"] == "Bearer ABCD2345"


def test_an_unpaired_client_refuses_to_call_and_says_where_the_code_is(api) -> None:
    with pytest.raises(NotPaired) as raised:
        server_module.status()
    assert "Developer API" in str(raised.value)
    assert api.requests == [], "an unpaired client must not reach the phone at all"


def test_a_401_is_reported_as_a_stale_pairing(paired) -> None:
    paired.reply("GET", "/v1/status", 401, b"")
    with pytest.raises(NotPaired) as raised:
        server_module.status()
    assert "every time the screen opens" in str(raised.value)


def test_an_error_body_becomes_an_ApiError_with_its_problems(paired) -> None:
    paired.reply(
        "POST",
        "/v1/assets",
        422,
        {"error": {"code": "asset_validation", "message": "the asset was refused",
                   "problems": ["NameRequired"]}},
    )
    with pytest.raises(ApiError) as raised:
        server_module.create_asset(name="  ")
    assert raised.value.status == 422
    assert raised.value.code == "asset_validation"
    assert raised.value.problems == ["NameRequired"]


def test_an_error_with_no_json_body_still_raises_something_readable(paired) -> None:
    paired.reply("GET", "/v1/status", 500, b"not json")
    with pytest.raises(ApiError) as raised:
        server_module.status()
    assert raised.value.status == 500


def test_a_204_is_an_empty_result(paired) -> None:
    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    assert server_module.delete_event(event_id="e1") == {}


def test_an_explicit_base_url_means_no_adb_forward(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("SERVICETAG_API_BASE_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("SERVICETAG_ADB_SERIAL", "whatever")
    device = Device.from_env()
    assert device.forwarded is True
    # Would raise if it tried to run anything: there is no adb on this path.
    monkeypatch.setattr(
        "subprocess.run", lambda *a, **k: pytest.fail("adb must not run with an explicit base URL")
    )
    device.ensure_forward()


def test_no_serial_and_no_base_url_is_a_clear_refusal(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("SERVICETAG_API_BASE_URL", raising=False)
    monkeypatch.delenv("SERVICETAG_ADB_SERIAL", raising=False)
    device = Device.from_env()
    assert device.base_url == "http://127.0.0.1:17337"
    with pytest.raises(RuntimeError) as raised:
        device.ensure_forward()
    assert "SERVICETAG_ADB_SERIAL" in str(raised.value)
```

- [ ] **Step 3: Run them and watch them fail.**

```bash
cd tools/servicetag-mcp && uv run pytest
```

Expected: **collection error** — `ModuleNotFoundError: No module named 'servicetag_mcp.client'` (and `…server`). That is the red.

- [ ] **Step 4: The client.**

Create `tools/servicetag-mcp/src/servicetag_mcp/client.py`:

```python
"""One forward, one bearer token, one HTTP client against this phone's own loopback address.

Nothing here knows what a tool is: `server.py` owns the MCP surface and this owns the transport,
which is what lets the whole test suite point at a stdlib HTTP server and never touch a device.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from typing import Any

import httpx

DEFAULT_PORT = 17337
"""The port the app listens on, and the port this forwards to. Fixed by the app, not negotiated."""

BASE_URL_ENV = "SERVICETAG_API_BASE_URL"
"""Set this and the client talks to it directly and **never runs adb** — tests, or your own forward."""

SERIAL_ENV = "SERVICETAG_ADB_SERIAL"
"""The device serial `adb forward` is aimed at. From the environment or the MCP config, never a file."""

ADB_ENV = "SERVICETAG_ADB"
"""Where `adb` is, if it is not on PATH."""

_TIMEOUT = 30.0


class ApiError(RuntimeError):
    """The phone answered, and the answer was a refusal."""

    def __init__(self, status: int, code: str, message: str, problems: list[str]) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message
        self.problems = problems


class NotPaired(RuntimeError):
    """No pairing code, or one the phone no longer accepts."""


@dataclass
class Device:
    base_url: str
    serial: str | None
    adb: str
    token: str | None = None
    forwarded: bool = False

    @classmethod
    def from_env(cls) -> Device:
        import os

        override = os.environ.get(BASE_URL_ENV)
        return cls(
            base_url=override or f"http://127.0.0.1:{DEFAULT_PORT}",
            serial=os.environ.get(SERIAL_ENV),
            adb=os.environ.get(ADB_ENV, "adb"),
            # An explicit base URL means someone else owns the transport. Marking it forwarded is
            # what keeps `adb` out of the test suite entirely.
            forwarded=bool(override),
        )

    def ensure_forward(self) -> None:
        """`adb forward tcp:17337 tcp:17337`, once per session, lazily.

        Lazily because an editor starts this server whether or not a phone is plugged in, and a
        module that shells out at import time is a server that fails to load on a laptop.
        """
        if self.forwarded:
            return
        if not self.serial:
            raise RuntimeError(
                f"set {SERIAL_ENV} to the device serial adb should forward to "
                f"(or {BASE_URL_ENV} if you set up the forward yourself)"
            )
        try:
            subprocess.run(
                [
                    self.adb,
                    "-s",
                    self.serial,
                    "forward",
                    f"tcp:{DEFAULT_PORT}",
                    f"tcp:{DEFAULT_PORT}",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
        except subprocess.CalledProcessError as failure:
            # `CalledProcessError.__str__` embeds the whole argv, including `-s <serial>`, so
            # letting it escape would print the device serial in the MCP's error text. Nothing
            # about the device belongs in an error a model reads back.
            raise RuntimeError(
                "adb forward failed: check the device is connected and authorised"
            ) from None
        except FileNotFoundError:
            raise RuntimeError(
                f"adb was not found; put it on PATH or set {ADB_ENV}"
            ) from None
        self.forwarded = True

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body: Any | None = None,
        content: bytes | None = None,
        content_type: str | None = None,
        report_statuses: tuple[int, ...] = (),
    ) -> Any:
        """One call. [report_statuses] names the statuses whose body is **data, not an error**.

        The API deliberately answers `POST /v1/import-merge/apply` with a **409 carrying the merge
        report** — the same shape a 200 carries — because "what stopped you?" is the only question a
        caller has at that point and the refusal already holds the deterministic conflict list. So
        `import_merge` passes `report_statuses=(409,)` and gets the report; every other call leaves
        it empty and a 409 is an `ApiError` as usual.
        """
        if self.token is None:
            raise NotPaired(
                "call the pair tool with the code on the phone's Developer API screen first"
            )
        self.ensure_forward()
        headers = {"Authorization": f"Bearer {self.token}"}
        if content_type:
            headers["Content-Type"] = content_type
        response = httpx.request(
            method,
            f"{self.base_url}{path}",
            headers=headers,
            json=json_body,
            content=content,
            timeout=_TIMEOUT,
        )
        if response.status_code == 401:
            # The app answers 401 with an empty body on purpose, so this is all it can mean.
            raise NotPaired(
                "the phone refused that pairing code — it is new every time the screen opens, "
                "so read it again and call pair"
            )
        if response.status_code in report_statuses:
            return response.json()
        if response.status_code >= 400:
            code, message, problems = _detail(response)
            raise ApiError(response.status_code, code, message, problems)
        if response.status_code == 204 or not response.content:
            return {}
        return response.json()


def _detail(response: httpx.Response) -> tuple[str, str, list[str]]:
    """The error envelope, or something honest when the body is not one."""
    try:
        error = response.json()["error"]
        return (
            str(error.get("code", "unknown")),
            str(error.get("message", "")),
            [str(p) for p in error.get("problems", [])],
        )
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return ("unknown", response.text[:200], [])
```

- [ ] **Step 5: Write the failing tool tests.**

Create `tools/servicetag-mcp/tests/test_tools.py`:

```python
"""One case per tool: the method, the path and the body it sends. No device, no MCP session.

Calling the tool functions directly is deliberate: what can be wrong here is a path, a verb, a
field name or an error mapping. Whether the SDK can serve `tools/list` is the SDK's to test.
"""

from __future__ import annotations

import json
import zipfile

import pytest

from servicetag_mcp import server as server_module

EXPECTED_TOOLS = (
    "pair",
    "status",
    "list_assets",
    "get_asset",
    "create_asset",
    "update_asset",
    "create_component",
    "retire_asset",
    "archive_asset",
    "list_definitions",
    "save_definition",
    "archive_definition",
    "list_profiles",
    "save_profile",
    "archive_profile",
    "list_events",
    "log_event",
    "update_event",
    "delete_event",
    "list_tag_bindings",
    "import_merge",
)


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def test_every_tool_the_design_names_is_registered() -> None:
    assert server_module.TOOL_NAMES == EXPECTED_TOOLS
    assert len(EXPECTED_TOOLS) == 21
    for name in EXPECTED_TOOLS:
        assert callable(getattr(server_module, name)), f"{name} is missing"


def test_pair_stores_the_code_upper_cased(api) -> None:
    server_module.pair(" abcd2345 ")
    assert server_module.device.token == "ABCD2345"


def test_the_read_tools_get_their_paths(paired) -> None:
    for call, path in (
        (lambda: server_module.status(), "/v1/status"),
        (lambda: server_module.list_assets(), "/v1/assets"),
        (lambda: server_module.get_asset(asset_id="a1"), "/v1/assets/a1"),
        (lambda: server_module.list_definitions(asset_id="a1"), "/v1/assets/a1/definitions"),
        (lambda: server_module.list_profiles(asset_id="a1"), "/v1/assets/a1/profiles"),
        (lambda: server_module.list_events(asset_id="a1"), "/v1/assets/a1/events"),
        (lambda: server_module.list_tag_bindings(), "/v1/tags"),
    ):
        call()
        assert paired.last().method == "GET"
        assert paired.last().path == path


def test_create_asset_posts_only_what_was_given(paired) -> None:
    server_module.create_asset(name="Hot tub", category="Water", template_key="hot_tub")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets"
    assert body_of(sent) == {"name": "Hot tub", "category": "Water", "templateKey": "hot_tub"}


def test_update_asset_patches_the_body(paired) -> None:
    server_module.update_asset(asset_id="a1", name="Hot tub", location="Deck")
    sent = paired.last()
    assert sent.method == "PATCH"
    assert sent.path == "/v1/assets/a1"
    assert body_of(sent) == {"name": "Hot tub", "location": "Deck"}


def test_create_component_posts_to_the_component_path(paired) -> None:
    server_module.create_component(parent_asset_id="a1", name="Pool pump")
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/assets/a1/components"
    assert body_of(sent) == {"name": "Pool pump"}


def test_retire_and_archive_post_their_flags(paired) -> None:
    server_module.retire_asset(asset_id="a1", retired_on="2026-04-01")
    assert paired.last().path == "/v1/assets/a1/retire"
    assert body_of(paired.last()) == {"retiredOn": "2026-04-01"}

    server_module.retire_asset(asset_id="a1", retired_on=None)
    assert body_of(paired.last()) == {"retiredOn": None}

    server_module.archive_asset(asset_id="a1", archived=True)
    assert paired.last().path == "/v1/assets/a1/archive"
    assert body_of(paired.last()) == {"archived": True}


def test_save_definition_creates_and_edits(paired) -> None:
    server_module.save_definition(asset_id="a1", label="pH", decimals=1)
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/definitions"
    assert body_of(sent) == {"assetId": "a1", "label": "pH", "decimals": 1}

    server_module.save_definition(asset_id="a1", label="pH", definition_id="d1")
    assert body_of(paired.last()) == {"assetId": "a1", "label": "pH", "id": "d1"}


def test_the_archive_tools_post_their_flags(paired) -> None:
    """R2: the MCP mirrors every API operation, archiving included."""
    server_module.archive_definition(definition_id="d1")
    assert paired.last().method == "POST"
    assert paired.last().path == "/v1/definitions/d1/archive"
    assert body_of(paired.last()) == {"archived": True}

    server_module.archive_definition(definition_id="d1", archived=False)
    assert body_of(paired.last()) == {"archived": False}

    server_module.archive_profile(profile_id="p1")
    assert paired.last().path == "/v1/profiles/p1/archive"
    assert body_of(paired.last()) == {"archived": True}

    server_module.archive_profile(profile_id="p1", archived=False)
    assert body_of(paired.last()) == {"archived": False}


def test_save_profile_sends_its_fields(paired) -> None:
    server_module.save_profile(
        asset_id="a1",
        name="Water test",
        event_kind="MEASUREMENT",
        fields=[{"definitionId": "d1", "required": True}],
    )
    sent = paired.last()
    assert sent.path == "/v1/profiles"
    assert body_of(sent) == {
        "assetId": "a1",
        "name": "Water test",
        "eventKind": "MEASUREMENT",
        "fields": [{"definitionId": "d1", "required": True}],
    }


def test_the_event_tools_log_update_and_delete(paired) -> None:
    server_module.log_event(
        asset_id="a1",
        kind="MAINTENANCE",
        occurred_on="2026-09-21",
        tz_id="UTC",
        title="Filter change",
        values={"d1": "7.4"},
        consumables=[{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    )
    sent = paired.last()
    assert sent.method == "POST"
    assert sent.path == "/v1/events"
    assert body_of(sent) == {
        "assetId": "a1",
        "kind": "MAINTENANCE",
        "occurredOn": "2026-09-21",
        "tzId": "UTC",
        "title": "Filter change",
        "values": {"d1": "7.4"},
        "consumables": [{"name": "Cartridge", "quantity": "1", "unit": "ea"}],
    }

    server_module.update_event(
        event_id="e1", asset_id="a1", kind="MAINTENANCE", occurred_on="2026-09-21", tz_id="UTC"
    )
    assert paired.last().method == "PATCH"
    assert paired.last().path == "/v1/events/e1"

    paired.reply("DELETE", "/v1/events/e1", 204, b"")
    server_module.delete_event(event_id="e1")
    assert paired.last().method == "DELETE"
    assert paired.last().path == "/v1/events/e1"
    assert paired.last().body == b""


def an_archive(tmp_path):
    archive = tmp_path / "ServiceTag-data.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("manifest.json", "{}")
    return archive


def test_import_merge_plans_first_and_applies_when_the_plan_is_clean(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    paired.reply("POST", "/v1/import-merge/apply", 200, {"applicable": True, "conflicts": []})

    result = server_module.import_merge(archive_path=str(archive))

    assert [r.path for r in paired.requests] == [
        "/v1/import-merge/plan",
        "/v1/import-merge/apply",
    ]
    for sent in paired.requests:
        assert sent.method == "POST"
        assert sent.headers["Content-Type"] == "application/zip"
        assert sent.body == archive.read_bytes()
    assert result["applicable"] is True


def test_import_merge_plan_only_stops_after_the_plan(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})

    result = server_module.import_merge(archive_path=str(archive), plan_only=True)

    assert [r.path for r in paired.requests] == ["/v1/import-merge/plan"]
    assert result["applicable"] is True


def test_import_merge_never_applies_a_plan_with_conflicts(paired, tmp_path) -> None:
    archive = an_archive(tmp_path)
    conflicts = [
        {"table": "TAGS", "id": "t1", "verdict": "CONFLICT",
         "reason": "PAYLOAD_BOUND_TO_ANOTHER_ASSET", "detail": "t-local"},
    ]
    paired.reply(
        "POST", "/v1/import-merge/plan", 200, {"applicable": False, "conflicts": conflicts}
    )

    result = server_module.import_merge(archive_path=str(archive))

    # The apply is never even attempted, so the phone is never asked to refuse.
    assert [r.path for r in paired.requests] == ["/v1/import-merge/plan"]
    assert result["applicable"] is False
    assert result["conflicts"] == conflicts


def test_import_merge_returns_the_report_when_the_apply_answers_409(paired, tmp_path) -> None:
    """The one 4xx whose body is data. Without `report_statuses=(409,)` this raises instead."""
    archive = an_archive(tmp_path)
    conflicts = [
        {"table": "ASSETS", "id": "a1", "verdict": "CONFLICT",
         "reason": "CONTENT_DIFFERS", "detail": "a1"},
    ]
    paired.reply("POST", "/v1/import-merge/plan", 200, {"applicable": True, "conflicts": []})
    paired.reply(
        "POST", "/v1/import-merge/apply", 409,
        {"applicable": False, "conflicts": conflicts, "assets": {"insert": 0, "identical": 0, "conflict": 1, "skipped": 0}},
    )

    result = server_module.import_merge(archive_path=str(archive))

    assert [r.path for r in paired.requests] == [
        "/v1/import-merge/plan",
        "/v1/import-merge/apply",
    ]
    assert result["applicable"] is False
    assert result["conflicts"] == conflicts


def test_import_merge_says_so_when_the_file_is_not_there(paired, tmp_path) -> None:
    with pytest.raises(FileNotFoundError):
        server_module.import_merge(archive_path=str(tmp_path / "nope.zip"))
    assert paired.requests == []
```

- [ ] **Step 6: Run them and watch them fail.**

```bash
cd tools/servicetag-mcp && uv run pytest
```

Expected: `ModuleNotFoundError: No module named 'servicetag_mcp.server'`. That is the red.

- [ ] **Step 7: The tools.**

Create `tools/servicetag-mcp/src/servicetag_mcp/server.py`:

```python
"""The MCP surface: one tool per `/v1` endpoint, and `pair` for the code on the phone's screen.

Nothing here reaches past the API. Every tool is a method, a path and a body — the contract is
`docs/api/v1.md`, and the app's own router is what enforces it.

`@mcp.tool()` is called with parentheses on purpose: the SDK raises a `TypeError` at *import* time
for a bare `@mcp.tool`, which is a server that never starts.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from mcp.server import MCPServer

from .client import Device

mcp = MCPServer("servicetag")

device = Device.from_env()

TOOL_NAMES: tuple[str, ...] = (
    "pair",
    "status",
    "list_assets",
    "get_asset",
    "create_asset",
    "update_asset",
    "create_component",
    "retire_asset",
    "archive_asset",
    "list_definitions",
    "save_definition",
    "archive_definition",
    "list_profiles",
    "save_profile",
    "archive_profile",
    "list_events",
    "log_event",
    "update_event",
    "delete_event",
    "list_tag_bindings",
    "import_merge",
)
"""Every tool this server offers — `pair` plus one per API operation — written out so a dropped one
is a test failure and not a surprise."""


def _body(**fields: Any) -> dict[str, Any]:
    """Only what the caller actually gave. The app's decoder is strict, and its defaults are right."""
    return {k: v for k, v in fields.items() if v is not None}


@mcp.tool()
def pair(code: str) -> str:
    """Hand over the pairing code shown on the phone's Settings > Utilities > Developer API screen.

    The code is new every time that screen opens, so pair again after closing and reopening it.
    """
    device.token = code.strip().upper()
    return "paired"


@mcp.tool()
def status() -> dict[str, Any]:
    """The app's version, the contract version, and a row count per table."""
    return device.request("GET", "/v1/status")


@mcp.tool()
def list_assets() -> dict[str, Any]:
    """Every asset: the top-level systems, and each system's components under its own id."""
    return device.request("GET", "/v1/assets")


@mcp.tool()
def get_asset(asset_id: str) -> dict[str, Any]:
    """One asset, in the same shape a backup archive carries it."""
    return device.request("GET", f"/v1/assets/{asset_id}")


@mcp.tool()
def create_asset(
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    location: str | None = None,
    parent_asset_id: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset. `template_key` seeds its readings and quick actions, in the same write."""
    return device.request(
        "POST",
        "/v1/assets",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            manufacturer=manufacturer,
            model=model,
            serialNumber=serial_number,
            location=location,
            parentAssetId=parent_asset_id,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


@mcp.tool()
def update_asset(
    asset_id: str,
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    location: str | None = None,
    parent_asset_id: str | None = None,
) -> dict[str, Any]:
    """Edit an asset's fields. Identity, status and retirement are not editable here, by design."""
    return device.request(
        "PATCH",
        f"/v1/assets/{asset_id}",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            manufacturer=manufacturer,
            model=model,
            serialNumber=serial_number,
            location=location,
            parentAssetId=parent_asset_id,
        ),
        content_type="application/json",
    )


@mcp.tool()
def create_component(
    parent_asset_id: str,
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset as a component of another. The path decides the parent, not the body."""
    return device.request(
        "POST",
        f"/v1/assets/{parent_asset_id}/components",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


@mcp.tool()
def retire_asset(asset_id: str, retired_on: str | None = None) -> dict[str, Any]:
    """Retire an asset on a date (`YYYY-MM-DD`), or un-retire it with `retired_on=None`."""
    return device.request(
        "POST",
        f"/v1/assets/{asset_id}/retire",
        json_body={"retiredOn": retired_on},
        content_type="application/json",
    )


@mcp.tool()
def archive_asset(asset_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive an asset. Archive is not delete: tags bound to it still resolve."""
    return device.request(
        "POST",
        f"/v1/assets/{asset_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_definitions(asset_id: str) -> dict[str, Any]:
    """The readings defined on one asset, archived ones included."""
    return device.request("GET", f"/v1/assets/{asset_id}/definitions")


@mcp.tool()
def save_definition(
    asset_id: str,
    label: str,
    definition_id: str | None = None,
    key: str | None = None,
    unit: str | None = None,
    kind: str | None = None,
    value_type: str | None = None,
    decimals: int | None = None,
    range_low: float | None = None,
    range_high: float | None = None,
    is_meter: bool | None = None,
    formula: str | None = None,
    source_a_id: str | None = None,
    source_b_id: str | None = None,
) -> dict[str, Any]:
    """Create a reading, or edit one by passing `definition_id`.

    `kind` is `ENTERED` or `DERIVED`; a derived reading needs `formula` (`PERCENT_DROP`) and both
    sources. `value_type` is `NUMBER`, `TEXT` or `BOOLEAN`. Once measurements exist, the app
    refuses a change to `value_type`, `kind` or `key`.
    """
    return device.request(
        "POST",
        "/v1/definitions",
        json_body=_body(
            id=definition_id,
            assetId=asset_id,
            key=key,
            label=label,
            unit=unit,
            kind=kind,
            valueType=value_type,
            decimals=decimals,
            rangeLow=range_low,
            rangeHigh=range_high,
            isMeter=is_meter,
            formula=formula,
            sourceAId=source_a_id,
            sourceBId=source_b_id,
        ),
        content_type="application/json",
    )


@mcp.tool()
def archive_definition(definition_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a reading. Archived readings leave the forms and keep their history."""
    return device.request(
        "POST",
        f"/v1/definitions/{definition_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_profiles(asset_id: str) -> dict[str, Any]:
    """The quick actions defined on one asset, archived ones included."""
    return device.request("GET", f"/v1/assets/{asset_id}/profiles")


@mcp.tool()
def save_profile(
    asset_id: str,
    name: str,
    event_kind: str,
    profile_id: str | None = None,
    default_title: str | None = None,
    fields: list[dict[str, Any]] | None = None,
    consumables: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Create a quick action, or edit one by passing `profile_id`.

    `fields` is a list of `{"definitionId": ..., "required": bool}`; each must name an ENTERED
    reading of the same asset. `consumables` is `{"name", "defaultQuantity", "unit"}`.
    """
    return device.request(
        "POST",
        "/v1/profiles",
        json_body=_body(
            id=profile_id,
            assetId=asset_id,
            name=name,
            eventKind=event_kind,
            defaultTitle=default_title,
            fields=fields,
            consumables=consumables,
        ),
        content_type="application/json",
    )


@mcp.tool()
def archive_profile(profile_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a quick action. Events logged through it keep pointing at it."""
    return device.request(
        "POST",
        f"/v1/profiles/{profile_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_events(asset_id: str) -> dict[str, Any]:
    """One asset's service record, newest first."""
    return device.request("GET", f"/v1/assets/{asset_id}/events")


@mcp.tool()
def log_event(
    asset_id: str,
    kind: str,
    occurred_on: str,
    tz_id: str,
    title: str | None = None,
    profile_id: str | None = None,
    occurred_time: str | None = None,
    notes: str | None = None,
    values: dict[str, str] | None = None,
    consumables: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Log a maintenance event with its readings and the materials that went in.

    `values` is keyed by definition id, the text a person would type. `consumables` is
    `{"name", "quantity", "unit"}`. `kind` is one of MAINTENANCE, INSPECTION, MEASUREMENT,
    TREATMENT, INCIDENT, REPLACEMENT, SEASON_START, SEASON_END, NOTE, CUSTOM.
    """
    return device.request(
        "POST",
        "/v1/events",
        json_body=_body(
            assetId=asset_id,
            profileId=profile_id,
            kind=kind,
            title=title,
            occurredOn=occurred_on,
            occurredTime=occurred_time,
            tzId=tz_id,
            notes=notes,
            values=values,
            consumables=consumables,
        ),
        content_type="application/json",
    )


@mcp.tool()
def update_event(
    event_id: str,
    asset_id: str,
    kind: str,
    occurred_on: str,
    tz_id: str,
    title: str | None = None,
    profile_id: str | None = None,
    occurred_time: str | None = None,
    notes: str | None = None,
    values: dict[str, str] | None = None,
    consumables: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Edit a logged event. `asset_id` must be the asset it already belongs to — an edit never re-parents."""
    return device.request(
        "PATCH",
        f"/v1/events/{event_id}",
        json_body=_body(
            assetId=asset_id,
            profileId=profile_id,
            kind=kind,
            title=title,
            occurredOn=occurred_on,
            occurredTime=occurred_time,
            tzId=tz_id,
            notes=notes,
            values=values,
            consumables=consumables,
        ),
        content_type="application/json",
    )


@mcp.tool()
def delete_event(event_id: str) -> dict[str, Any]:
    """Delete a logged event and its readings. This one is destructive and has no undo."""
    return device.request("DELETE", f"/v1/events/{event_id}")


@mcp.tool()
def list_tag_bindings() -> dict[str, Any]:
    """Every NFC tag binding this phone holds. Read-only: the API cannot write or bind a tag."""
    return device.request("GET", "/v1/tags")


@mcp.tool()
def import_merge(archive_path: str, plan_only: bool = False) -> dict[str, Any]:
    """Merge a ServiceTag **data** archive into the phone. It plans first, always.

    Takes the local path to a format-5 `ServiceTag-data-*.zip`. The phone decides, per row, whether
    it is new (INSERT), already here and identical (IDENTICAL, a no-op), declined (SKIPPED) or
    contested (CONFLICT) — and **one conflict anywhere means nothing is written at all**. Rows are
    only ever inserted: an id already on the phone is never overwritten and nothing is ever deleted.

    This tool asks for the plan and then applies it **only when the plan has no conflicts**. With
    `plan_only=True`, or when the plan does have conflicts, it stops and returns the plan — whose
    `conflicts` list names each one by table, id and a stable reason code, in a deterministic order.
    Read `applicable` to know which happened.

    Attachment rows are written only when their bytes are already in the phone's attachment folder,
    and skipped otherwise, so a merge can never leave a row pointing at a file that is not there.
    """
    body = Path(archive_path).read_bytes()
    plan = device.request(
        "POST", "/v1/import-merge/plan", content=body, content_type="application/zip"
    )
    if plan_only or not plan.get("applicable", False):
        return plan
    # A 409 from the apply is the report, not an error: the phone re-planned inside its own
    # transaction, found a conflict (or found the destination had moved), and wrote nothing. Read
    # `applicable` — it is false — and `conflicts`.
    return device.request(
        "POST",
        "/v1/import-merge/apply",
        content=body,
        content_type="application/zip",
        report_statuses=(409,),
    )


def main() -> None:
    """The console script: serve over stdio, which is what an editor connects to."""
    mcp.run()
```

- [ ] **Step 8: Run the suite, lock, and run it frozen.**

```bash
cd tools/servicetag-mcp && uv run pytest
cd tools/servicetag-mcp && uv lock
cd tools/servicetag-mcp && uv run --frozen pytest
```

Expected: `BUILD`-free green output — **24 passed** on the first run; `uv lock` writes `tools/servicetag-mcp/uv.lock`; the frozen run passes identically, which is the run CI will make. The lock file is committed: `uv.lock` is how CI gets the same `mcp`, `httpx` and `pytest` this task was written against, and `.gitignore` at `0a582f8` excludes nothing under `tools/`.

- [ ] **Step 9: The CI job.**

In `.github/workflows/ci.yml`, add one job after the existing `build` job — that is, after line 34, at the same indentation as `build:` (two spaces). **Nothing in the `build` job changes.**

```yaml
  mcp:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5.1.0
        with:
          persist-credentials: false            # this job needs no git credentials after the checkout
      - uses: astral-sh/setup-uv@c18668ad3cf93ea998bef934396af7bb5c839dc7 # v10.2.0
        with:
          python-version: '3.12'
          enable-cache: true
          cache-dependency-glob: "tools/servicetag-mcp/uv.lock"
      - name: the workstation MCP server's tests
        run: uv run --frozen pytest
        working-directory: tools/servicetag-mcp
```

Three things about that job, stated so nobody has to guess later:

- **No `submodules: recursive` and no `fetch-depth: 0`.** The `build` job needs both — it runs `tools/check-submodule-pin.sh` and builds `:nfc-core` from the submodule. This job runs Python in one directory and needs neither, so it takes the defaults and is the faster of the two.
- **Both of this job's actions are pinned to a commit SHA with the tag in a trailing comment**, which is `release.yml`'s house form (`release.yml:16`, `:27`, `:31`, `:35`). `actions/checkout` is **v5.1.0** = `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` — the same SHA `release.yml:16` already pins, so no new external fact is introduced. `astral-sh/setup-uv` is **v10.2.0** = `c18668ad3cf93ea998bef934396af7bb5c839dc7`, read from the action's own repository; if that release turns out to be too fresh, **v10.1.0** = `bec219d24cd3e171d82865faccec33120bb574f4` is a drop-in substitution. `cache-dependency-glob` is pinned rather than left to setup-uv's default `**/uv.lock`, so a second lockfile added later cannot silently share this job's cache key. Note that `ci.yml`'s existing five steps use floating major tags (`@v5`, `@v5`, `@v4`, `@v6`, `@v7`) and are **deliberately left alone** — see **Deviations note D3**.
- **`uv run --frozen`** means CI resolves nothing: it installs exactly `uv.lock`, so a new `mcp` release cannot turn a green tree red overnight, and a dependency bump is a commit.

- [ ] **Step 10: The README.**

Create `tools/servicetag-mcp/README.md`:

````markdown
# servicetag-mcp

A workstation MCP server for ServiceTag's local automation API. It forwards a port to the phone,
takes the pairing code the phone shows, and exposes one tool per `/v1` endpoint.

The contract it speaks is `docs/api/v1.md` in this repository. Read that for the shapes, the status
codes and the limits; this file is about running the thing.

## What it needs

- Python 3.12 and [uv](https://docs.astral.sh/uv/).
- `adb` on `PATH` (or `SERVICETAG_ADB` pointing at it), and the phone connected with USB debugging
  on. The serial comes from `SERVICETAG_ADB_SERIAL`; nothing in this repository names a device.
- ServiceTag 1.1.0 or later on the phone, with **Settings > Utilities > Developer API** open. The
  listener exists only while that screen is open, and the pairing code is new every time it opens.

## Using it

1. Open **Settings > Utilities > Developer API** on the phone and leave it open.
2. Call the `pair` tool with the eight-character code the screen shows.
3. Call anything else. The first call runs `adb forward tcp:17337 tcp:17337` itself.

Leaving the screen, locking the phone or switching apps stops the listener; every call after that
fails until the screen is open again and `pair` has been called with the new code.

## Claude Code MCP configuration

Put this in your MCP configuration and replace `<serial>` with the serial `adb devices` prints for
your phone:

```json
{
  "mcpServers": {
    "servicetag": {
      "command": "uv",
      "args": ["--directory", "tools/servicetag-mcp", "run", "servicetag-mcp"],
      "env": {
        "SERVICETAG_ADB_SERIAL": "<serial>"
      }
    }
  }
}
```

`--directory` is relative to wherever the MCP client is started; use an absolute path to this
directory if that is not the repository root.

## Environment

| variable | what it does |
|---|---|
| `SERVICETAG_ADB_SERIAL` | the device serial `adb forward` is aimed at. Required unless `SERVICETAG_API_BASE_URL` is set. |
| `SERVICETAG_API_BASE_URL` | talk to this URL directly and **never run `adb`**. For a forward you set up yourself, and what the test suite uses. |
| `SERVICETAG_ADB` | where `adb` is, if it is not on `PATH`. |

## The tools

Twenty-one: `pair` plus one per API operation.

`pair`, `status`, `list_assets`, `get_asset`, `create_asset`, `update_asset`, `create_component`,
`retire_asset`, `archive_asset`, `list_definitions`, `save_definition`, `archive_definition`,
`list_profiles`, `save_profile`, `archive_profile`, `list_events`, `log_event`, `update_event`,
`delete_event`, `list_tag_bindings`, `import_merge`.

### `import_merge`

Takes a local path to a format-5 `ServiceTag-data-*.zip` and merges it into the phone. **It plans
before it writes**, and it never overwrites or deletes anything:

- a row whose id is not on the phone is **inserted**, with its UUID preserved exactly;
- a row whose id is there and whose content is identical is a **no-op**;
- a row whose id is there and whose content differs is a **conflict** — and **one conflict anywhere
  means nothing at all is written**;
- an NFC tag is matched on `(payloadFormat, payloadKey)` as well as on its row id, so the same
  physical tag cannot end up bound to two different assets;
- an attachment row is written only when its bytes are already in the phone's attachment folder.

The tool asks for the plan and applies it only when the plan has no conflicts. Pass
`plan_only=True` to stop after the plan. Either way the result has `applicable` and, when it is
false, a `conflicts` list naming each one by table, id and a stable reason code, in a deterministic
order. Resolving conflicts is a later release (issue #44's interactive slice); 1.1.0 merges what is
unambiguous and refuses the rest safely.

There is deliberately **no** tool for a wipe, a replace-import, an export, an NFC write, an NFC
bind or an attachment's bytes: the API has no route for any of them.

## Tests

```bash
cd tools/servicetag-mcp && uv run pytest
```

They run against a stdlib HTTP server on `127.0.0.1` and never touch a device or run `adb`. CI runs
`uv run --frozen pytest` in the `mcp` job of `.github/workflows/ci.yml`.
````

- [ ] **Step 11: The gate, both halves.**

```bash
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug --console=plain
cd tools/servicetag-mcp && uv run --frozen pytest
```

Expected: `BUILD SUCCESSFUL` with every count unchanged from Task 3 (`:app` 42/310, `:core` 33/380, `:nfc-core` 6/50, `:nfc-android` 3/10) — this task touches no Kotlin at all — and **24 passed** from pytest.

- [ ] **Step 12: Stage and verify.**

```bash
git add -A
git status --porcelain
git diff --cached --name-only
git diff --cached --stat -- app core libs gradle docs README.md .github/workflows/release.yml
git diff --cached -- .github/workflows/ci.yml
grep -c 'servicetag-mcp' .github/workflows/ci.yml
grep -rn 'emulator-\|/home/\|/Users/' tools/servicetag-mcp || echo 'no device id and no absolute home path'
grep -rnE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' tools/servicetag-mcp || echo 'no e-mail address'
grep -c 'FastMCP' tools/servicetag-mcp/src/servicetag_mcp/server.py || echo 'MCPServer, not FastMCP'
```

Expected: **eleven** staged entries — ten `A ` (`pyproject.toml`, `.python-version`, `uv.lock`, `README.md`, `src/servicetag_mcp/__init__.py`, `client.py`, `server.py`, `tests/conftest.py`, `tests/test_client.py`, `tests/test_tools.py`) and one `M ` (`ci.yml`). **No output** from `git diff --cached --stat`: no Kotlin, no library, no Gradle file, no doc, no README at the repository root and **not `release.yml`**. The `ci.yml` diff is **additions only** — **14** added lines, 0 removed — and `grep -c 'servicetag-mcp'` returns **2** (the `cache-dependency-glob` and the `working-directory`). The three hygiene greps print their `no …` lines. `grep -c 'FastMCP' …` prints **`0`** and exits 1, so the `|| echo` fires: `FastMCP` appears nowhere.

- [ ] **Step 13: Commit.**

```bash
AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
git add -A
git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "an mcp server so the workstation can drive the phone"
```

---

### Task 5: version 1.1.0, the README, the contract document, and the versioning history

**Files:**
- Modify: `app/build.gradle.kts` (lines 43–44)
- Modify: `README.md` (one new bullet after line 77; the Settings bullet at lines 78–79; one new subsection after line 90)
- Create: `docs/api/v1.md`
- Modify: `docs/versioning.md` (the supported-history table's **rows**, lines 31–34; the header and separator at 29–30 are untouched)
- **Not modified:** `docs/architecture/product-split-evidence.md` (the controller writes 1.1.0's evidence after Task 6; the 1.0.0 entry is history and is not rewritten), `docs/architecture/product-split-migration.md` (1.1.0 adds no physical runbook row — a loopback socket is not an NFC behaviour, and R1/R2 are unchanged), `docs/architecture/product-split-target.md` and `product-split-archaeology.md` (no identity, filter or division of labour changes), `docs/design/**` (history — never rewritten), `docs/superpowers/**` (this plan is the only new document there, and the controller commits it), `.github/workflows/release.yml`, `tools/release-dry-run.sh`

**Interfaces:**
- Consumes: everything Tasks 1–4 produced. In particular the exact `/v1` route table, status codes and body shapes from Task 2, whose route table this document is the public form of; the six ratified strings from Task 3; the twenty-one tool names from Task 4.
- Produces: `BuildConfig.VERSION_NAME` becomes `"1.1.0"`, which `SettingsScreen` shows (`SettingsScreen.kt:247`), `ExportBackupSet` writes into `BackupManifest.appVersion` (`AppGraph.kt:152`), and `ApiHandlers` reports on `GET /v1/status`. `tools/release-dry-run.sh` reads the same `versionName` out of `app/build.gradle.kts` and compares it with the built artifact's. `docs/api/v1.md` is the path the README and `tools/servicetag-mcp/README.md` both point at.

- [ ] **Step 1: The version bump.**

In `app/build.gradle.kts`, inside `defaultConfig` (lines 43–44):

```kotlin
        versionCode = 12
        versionName = "1.1.0"
```

Nothing else in that file changes — no dependency (there is none to add), no `compileSdk`, no `targetSdk`, no signing block, no `testOptions`. `AppGraph.SCHEMA_VERSION` stays 5 and `BackupCodec.FORMAT_VERSION` stays 5.

The classification, run before the number was chosen, as `docs/versioning.md` requires: a loopback JSON API, an MCP server and an additive import are **a new user-facing capability**, which the rubric's fourth row makes a **MINOR**. Nothing older stops working: the backup format is untouched at 5, the schema is untouched at 5, the NFC record is untouched, and every existing screen behaves identically. So `1.0.0` → `1.1.0`, PATCH reset to 0 by the same document's first rule, and `versionCode` 11 → 12 by its second.

- [ ] **Step 2: The README's new bullet, and the Settings bullet.**

In `README.md`, inside `## What it does today`, insert one bullet **after** the "Back up and restore" bullet (after line 77) and **before** the Settings bullet:

```markdown
- **A local automation API** — a workstation can drive this phone's records without a screen.
  Settings > Utilities > Developer API shows a port and an eight-character pairing code; while that
  screen is open, and only while it is open, the app answers JSON requests on the phone's own
  loopback address that carry the code. There is one endpoint per thing the app can already do —
  read and write assets, components, readings, quick actions and journal entries, read tag bindings
  — and nothing that bypasses one. A wipe, a replace-restore, an export, an NFC write and an
  attachment's bytes have no endpoint at all. Two more endpoints merge a backup's data archive into
  this phone *additively*: one returns a **plan** and writes nothing, the other applies it — and
  only when the plan has no conflicts, because a row that is already here is never overwritten and
  nothing is ever deleted. `tools/servicetag-mcp/` is the workstation side, an MCP server with one
  tool per operation, and `docs/api/v1.md` is the contract.
```

Then replace the Settings bullet (lines 78–79) with:

```markdown
- **Settings** — appearance (system / light / dark), the palette's name, the attachment folder and
  the provider behind it, Read / inspect tag, Developer API, the build's version and a link to the
  project.
```

- [ ] **Step 3: The README's own subsection.**

Insert a new subsection immediately after the `### Note links are NoteTag's` section (after line 90) and before `## Building`:

```markdown
### The automation API is loopback-only and lives as long as its screen

The API exists so an agent or a script on a workstation can do in one call what would otherwise be
an afternoon of tapping: load a season's worth of readings, rename every component of a system,
check what the phone actually holds. It is deliberately the narrowest thing that can do that.

- **It answers on `127.0.0.1` and nowhere else**, on port 17337, and it checks the peer's address a
  second time on every accepted connection. There is no server, no service and no background work:
  the listener is started by the Developer API screen and stopped when that screen pauses or goes
  away, so the honest answer to "when is my phone accepting commands?" is "while you are looking at
  the screen that says so".
- **Every request must carry `Authorization: Bearer <code>`** with the code on that screen. The code
  is eight characters from a 32-character unambiguous alphabet, drawn from the platform's CSPRNG, and
  **new every time the screen opens** — it is never saved, never persisted and never logged. Anything
  without it gets a 401 with an empty body: no code, no message, no hint about what exists.
- **The workstation reaches it with `adb forward tcp:17337 tcp:17337`**, which the MCP server runs
  itself. The app declares `android.permission.INTERNET` because Android gates TCP socket *creation*
  on it even for a loopback address. `INTERNET` gives the process network capability, but ServiceTag
  1.1.0 introduces no outbound networking and the Developer API listens only on localhost; future
  cloud features may make intentional outbound use of the permission under their own designs.
- **Every endpoint is one of the app's own use cases.** None of them reaches past one, and the
  destructive ones have no endpoint: no wipe, no replace-restore, no export, no NFC write, no NFC
  bind, no attachment bytes, and nothing at all for the pre-split link tombstones.
- **The additive merge import is planned before it writes.** `POST /v1/import-merge/plan` reads an
  ordinary format-5 data archive, compares it with what this phone holds, and answers with a plan —
  writing nothing. Per row: not here → **insert**, with the UUID preserved; here and identical →
  **no-op**; here and different → **conflict**. NFC tags are matched on their payload identity as
  well as their row id, so the same physical tag cannot end up bound to two assets. `POST
  /v1/import-merge/apply` writes the plan, and **only when it has no conflicts** — one conflict
  anywhere and nothing at all is written, with the conflict list returned instead. So a merge can
  never overwrite or delete a row this phone already had. An attachment row is written only when its
  bytes are already in the attachment folder. Resolving conflicts, and mapping an imported asset
  onto a local one, are a later release; there is no screen for any of it in 1.1.0.

The full contract — endpoints, request and response shapes, status codes and limits — is
[`docs/api/v1.md`](docs/api/v1.md). The workstation side is
[`tools/servicetag-mcp/`](tools/servicetag-mcp/README.md).
```

- [ ] **Step 4: The contract document.**

Create `docs/api/v1.md`:

````markdown
# ServiceTag local automation API, version 1

Introduced in ServiceTag **1.1.0** (issue #46). This document is the contract; the enforcement is
`app/src/main/kotlin/com/loosecannon/servicetag/api/`, and every claim below has a test named beside
it in `app/src/test/kotlin/com/loosecannon/servicetag/api/`.

## Where it is, and when

- **Address:** `127.0.0.1`, port **17337**. Nothing else is ever bound, and an accepted connection
  whose peer is not a loopback address is closed before a byte of it is read.
- **Lifetime:** the listener runs while **Settings > Utilities > Developer API** is the screen on top
  and the activity is resumed. Pausing (screen off, lock, app switch) stops it; leaving the screen
  stops it. There is no service, no background work, no boot hook and no way to start it without
  that screen in front of the owner.
- **Reaching it from a workstation:** `adb forward tcp:17337 tcp:17337`. The MCP server in
  `tools/servicetag-mcp/` does this itself.
- **The permission, and what it does not mean.** The app declares
  `android.permission.INTERNET`, because Android gates the *creation* of a TCP socket on the group
  that permission grants, whatever address the socket is bound to.
  `INTERNET` gives the process network capability, but ServiceTag 1.1.0 introduces no outbound networking and the Developer API listens only on localhost; future cloud features may make intentional outbound use of the permission under their own designs.
  There is no `HttpURLConnection`, no `OkHttp`, no `URL`, no
  `openConnection`, no client socket and no `WebView` anywhere in the app, and the release proof
  greps for each of them.
- **If it cannot start:** when port 17337 is already taken the listener does not come up, and the
  screen says so — `The Developer API could not start. Leave this screen and open it again.` —
  because leaving and returning is a fresh session, a fresh code and a fresh attempt.
  `Requests this session` staying at 0 corroborates it, and a logcat carries one `Log.w` line with
  no token, no path and no body.

## Authentication

Every request must carry:

```
Authorization: Bearer <pairing code>
```

The pairing code is the eight characters shown on the Developer API screen. It is drawn from
`SecureRandom` over the 32-character alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `I`, `O`, `0` or
`1`), is **case-sensitive**, and is **new every time the screen opens**. It is held in memory for
that visit only: never saved state, never a preference, never a log line.

Anything without a valid code — no header, the wrong prefix, the wrong code, the right code in the
wrong case — gets:

```
HTTP/1.1 401 Unauthorized
Content-Length: 0
```

and **nothing else**. The check happens before routing, so an unauthenticated caller cannot learn
which paths exist. The comparison is `java.security.MessageDigest.isEqual`.

## Framing and limits

One request per connection; the answer always carries `Connection: close`.

Every refusal in this table answers with an **empty body**: a framing refusal happens before the
pairing code is checked, so the status is all it may say — in particular a 413 does not name the
path or its ceiling. Every refusal *past* authentication (a bad field, an unknown route, a domain
conflict) carries the full JSON error body described under **Errors**.

A trailing slash is dropped before anything else: `/v1/assets/` is `/v1/assets`, and
`/v1/import-merge/plan/` gets the import ceiling, not the small one.

| limit | value | over it |
|---|---|---|
| methods | `GET`, `POST`, `PATCH`, `DELETE` | 405 |
| request line | 8 KiB | 400 |
| header block | 8 KiB **of bytes**, 64 headers | 400 |
| body | **64 KiB** | 413 |
| body, the two `POST /v1/import-merge/*` paths only | **4 MiB** | 413 |
| idle connection | 5 s | dropped |
| `Transfer-Encoding` | refused in any form | 400 |
| a body with no `Content-Length` | refused | 400 |
| a body on `GET` or `DELETE` | refused | 400 |

Only one connection is answered at a time: there is one client and one call at a time, so nothing
interleaves. A second caller waits in a backlog of 4 or is refused.

A query string is ignored — no endpoint reads one, so nothing may come to depend on one.

## Bodies

Requests are `Content-Type: application/json`, UTF-8, except `POST /v1/import-merge/plan` and
`POST /v1/import-merge/apply`, which are `Content-Type: application/zip`. The wrong type is a
**415**. Note that `/v1/import-merge` with nothing after it is **not a route**: it is a 404, and it
gets the ordinary 64 KiB ceiling, not the bigger one.

**Unknown fields are rejected.** A misspelled field is a 400 naming it, rather than an edit silently
dropped.

**Response rows are the backup format's own shapes.** `AssetDto`, `NfcTagDto`,
`MeasurementDefinitionDto`, `EventProfileDto` and `AssetEventDto` are the `@Serializable` classes
`data.json` inside a backup archive carries, produced by the same mappers
(`core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupFormat.kt`). A row read here and
that row in an archive are the same JSON object, field for field. That is one schema on purpose:
there is nowhere for the API and the backup format to drift apart.

Every field is present in every response (defaults are encoded), so a client never has to
distinguish absent from default.

## Endpoints

| method | path | body | success | notes |
|---|---|---|---|---|
| `GET` | `/v1/status` | — | 200 | `{appVersion, apiVersion, schemaVersion, backupFormatVersion, counts}` |
| `GET` | `/v1/assets` | — | 200 | `{topLevel: [AssetDto], components: {parentId: [AssetDto]}}` — a component appears under its parent, never at the top; both by name |
| `POST` | `/v1/assets` | asset command, plus optional `templateKey` | 201 | `{asset}` |
| `GET` | `/v1/assets/{id}` | — | 200 | `{asset}` |
| `PATCH` | `/v1/assets/{id}` | asset command | 200 | `{asset}`; `templateKey` is ignored — an edit never re-seeds |
| `POST` | `/v1/assets/{id}/components` | asset command | 201 | `{asset}`; the **path** decides the parent, whatever the body says |
| `POST` | `/v1/assets/{id}/retire` | `{"retiredOn": "YYYY-MM-DD"}` or `{"retiredOn": null}` | 200 | `{asset}`; `null` un-retires |
| `POST` | `/v1/assets/{id}/archive` | `{"archived": true｜false}` | 200 | `{asset}`; archive is not delete — bound tags still resolve |
| `GET` | `/v1/assets/{id}/definitions` | — | 200 | `{definitions: [...]}`, archived included |
| `POST` | `/v1/definitions` | save-definition | 200 | `{definition}`; `id` absent creates, `id` present edits |
| `POST` | `/v1/definitions/{id}/archive` | `{"archived": true｜false}` | 204 | — |
| `GET` | `/v1/assets/{id}/profiles` | — | 200 | `{profiles: [...]}`, archived included |
| `POST` | `/v1/profiles` | save-profile | 200 | `{profile}`; `id` absent creates, `id` present edits |
| `POST` | `/v1/profiles/{id}/archive` | `{"archived": true｜false}` | 204 | — |
| `GET` | `/v1/assets/{id}/events` | — | 200 | `{events: [...]}`, newest first |
| `POST` | `/v1/events` | event | 201 | `{event}` |
| `PATCH` | `/v1/events/{id}` | event | 200 | `{event}`; `assetId` must be the event's own — an edit never re-parents |
| `DELETE` | `/v1/events/{id}` | — | 204 | destructive, no undo |
| `GET` | `/v1/tags` | — | 200 | `{tags: [NfcTagDto]}` — **read only** |
| `POST` | `/v1/import-merge/plan` | a format-5 data archive, `application/zip` | 200 | the merge report. **Writes nothing, ever.** |
| `POST` | `/v1/import-merge/apply` | the same archive | 200, or **409** | the same report either way. 409 means the plan had conflicts (or the phone changed under it) and **nothing was written**. |

**The asset command** takes `name` (required) and, all optional: `category`, `description`, `notes`,
`manufacturer`, `model`, `serialNumber`, `purchaseOn`, `inServiceOn`, `purchasePriceMinor`,
`currency`, `vendor`, `location`, `warrantyExpiresOn`, `warrantyNotes`, `parentAssetId`,
`seasonStartMmdd`, `seasonEndMmdd`, `templateKey`. Dates are ISO `YYYY-MM-DD`; `purchasePriceMinor`
needs a `currency`.

**Save-definition** takes `assetId` and `label`, plus optional `id`, `key` (blank generates on a
create and keeps on an edit), `unit`, `kind` (`ENTERED`｜`DERIVED`), `valueType`
(`NUMBER`｜`TEXT`｜`BOOLEAN`), `decimals` (0–4), `rangeLow`, `rangeHigh`, `isMeter`, `formula`
(`PERCENT_DROP`), `sourceAId`, `sourceBId`. Once measurements exist, `valueType`, `kind` and `key`
are frozen and a change to any of them is a 409.

**Save-profile** takes `assetId`, `name`, `eventKind`, plus optional `id`, `defaultTitle`, `fields`
(`[{definitionId, required}]`, each an ENTERED reading of the same asset) and `consumables`
(`[{id, name, defaultQuantity, unit}]`).

**Event** takes `assetId`, `kind`, `occurredOn`, `tzId`, plus optional `profileId`, `title`,
`occurredTime` (`HH:MM`), `notes`, `values` (`{definitionId: "as typed"}`) and `consumables`
(`[{name, quantity, unit}]`). `kind` is one of `MAINTENANCE`, `INSPECTION`, `MEASUREMENT`,
`TREATMENT`, `INCIDENT`, `REPLACEMENT`, `SEASON_START`, `SEASON_END`, `NOTE`, `CUSTOM`.

**The merge report** is
`{formatVersion, backupSetId, applicable, assets, definitions, profiles, links, tags, events,
attachments, conflicts, duplicateCandidates}`. Each of the seven tables is
`{insert, identical, conflict, skipped}`. `applicable` is true exactly when `conflicts` is empty —
it is the only field a client has to read to know whether an apply will do anything. Each conflict
is `{table, id, verdict, reason, detail}`, sorted by table order then id, with `reason` one of the
stable codes below. Each duplicate candidate is `{incomingAssetId, localAssetId, hint}` and never
makes `applicable` false.

## What has no endpoint, deliberately

A wipe. A replace-restore. An export. Writing an NFC tag. Binding an NFC tag. Provisioning a tag.
Deleting an asset, a reading or a quick action. Adding, editing or deleting an attachment, and
reading or writing an attachment's bytes. Anything at all for the pre-split `externalLinks`
tombstones. Each of these is absent because **nothing routes to it** — a request for one is a 404 —
and `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` asserts that against the paths a client would
plausibly try.

## Errors

Every failure but a 401 answers with

```json
{"error": {"code": "…", "message": "…", "problems": ["…"]}}
```

| status | when |
|---|---|
| 400 | malformed HTTP, malformed JSON, an unknown field, an unknown enum name, an unreadable archive |
| 401 | no or wrong pairing code. **Empty body.** |
| 404 | no such route, or a named asset, event, reading or quick action that is not there |
| 405 | a known path with a method it does not take |
| 413 | a body over the path's limit |
| 415 | the wrong `Content-Type` |
| 422 | a validation failure. `problems` names each bad field, using the domain's own problem names (`NameRequired`, `BadDate(field=purchaseOn)`, …) |
| 409 | a refusal about state: an asset cycle, an asset that still has components, a row that belongs to another asset, a reading that already has measurements, a change that would break a derived reading or a quick action, an archive from a newer build, an attachment folder that is not available |
| 409 | **`/v1/import-merge/apply` only** — the plan had conflicts, or this phone changed since the plan was built. The body is the **merge report**, not an `error` envelope, and nothing was written. |
| 500 | anything unanticipated. The body carries the exception's class name and nothing else. |

## The additive merge import

Semantics from issue #44. The endpoints read an ordinary ServiceTag **data** archive — the
`ServiceTag-data-*.zip` half of a backup set, format 5 — through the same `BackupCodec` a restore
uses. `plan` decides; `apply` decides again and writes.

**It is planned before it writes, and "incoming wins" is not a thing it can do.** Per row:

| verdict | when |
|---|---|
| `INSERT` | the id is not on this phone, every reference it makes resolves, and no unique index objects |
| `IDENTICAL` | the id is here and **every** backup-format field matches, `createdAt` and `updatedAt` included — or a local NFC tag under a different row id already is this tag, field for field |
| `SKIPPED` | the plan declined it. In 1.1.0 the only reason is `ATTACHMENT_BYTES_ABSENT` |
| `CONFLICT` | anything else. See the codes below |

**A merge only ever inserts.** There is no update verdict: an id that is already here either matches
or conflicts, so **no row this phone already had can be modified or deleted by a merge**. And
**there is no partial merge**: one conflict anywhere means `apply` writes nothing at all and answers
409 with the conflict list.

**Identity is a UUID, or an NFC payload identity — never a name.** A tag is matched on
`(payloadFormat, payloadKey)` as well as on its row id, so the same physical tag cannot end up bound
to two assets. The NFC hardware UID is informational and is never identity. Matching
manufacturer + model + serial produces a `duplicateCandidates` entry — a **review hint**, never an
action. Matching names do not even hint.

**Timestamps never pick a winner.** `updatedAt` is compared like any other field and read for
nothing else.

**Write order is dependency-safe**: assets in topological order (parents first), then ENTERED
readings, then DERIVED ones, then quick actions, tombstone links, tag bindings, events, and
attachment rows last. A row whose parent or owner is neither on this phone nor being inserted by the
same plan is a conflict (`OWNER_NOT_AVAILABLE`), not an orphan.

**An attachment row is written only when the folder holds the bytes the row claims.** Four answers,
in this order: another local row already claims the locator → `ATTACHMENT_LOCATOR_TAKEN`, a
conflict; there is no attachment folder at all → **skipped**, `ATTACHMENT_STORE_NOT_CONFIGURED`
(and `applicable` stays true — the other six tables still merge); nothing is at the locator →
**skipped**, `ATTACHMENT_BYTES_ABSENT`; bytes are there but their size or sha256 is not the row's →
`ATTACHMENT_BYTES_DIFFER`, a conflict, because a diverged file is a diverged overlap and not a
missing one. So a merge can never leave a row pointing at a file that is not there, and never leave
one whose recorded hash does not describe what is stored. No byte is ever written by a merge;
restoring bytes is still the Backup screen's job.

One honest gap: the size-and-hash check runs just before the transaction, not inside it, because
there is no transaction that spans the database and a document provider. A file deleted or replaced
in that window still yields an inserted row whose hash no longer describes the bytes on disk. The
next `RestoreArtifacts` reports it.

**Conflict reason codes**, stable and machine-readable:

| code | meaning |
|---|---|
| `CONTENT_DIFFERS` | the id is here and some field differs |
| `PAYLOAD_BOUND_TO_ANOTHER_ASSET` | a local tag holds this payload identity and binds a different asset |
| `PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG` | same payload identity, same asset, some other field differs |
| `PAYLOAD_DUPLICATED_IN_ARCHIVE` | two rows of the archive claim one payload identity |
| `DEFINITION_KEY_TAKEN` | `measurement_definition(asset_id, key)` is unique and something else holds it |
| `EVENT_SOURCE_REF_TAKEN` | `asset_event(source, source_ref)` is unique and something else holds it |
| `ATTACHMENT_LOCATOR_TAKEN` | `attachment(storage_provider, storage_locator)` is unique and another row claims it |
| `PROFILE_FIELD_DEFINITION_TAKEN` | `profile_field(profile_id, definition_id)` is unique and this quick action offers one reading twice |
| `CHILD_ROW_ID_TAKEN` | an aggregate child row's own primary key is already held here — a field, a profile consumable, a measurement or a consumable usage. Those ids are durable identity and survive a backup verbatim |
| `ATTACHMENT_BYTES_DIFFER` | bytes *are* at this locator and they are not the row's: the size or the sha256 differs |
| `OWNER_NOT_AVAILABLE` | a reference this row makes resolves to nothing here and to nothing being inserted |
| `PARENT_CYCLE` | the parent it names sits under it once local and incoming rows are put together |

Three more codes exist and ride on a verdict that is **not** a conflict:
`PAYLOAD_HELD_BY_AN_EQUIVALENT_LOCAL_TAG` on an `IDENTICAL`, and `ATTACHMENT_BYTES_ABSENT` and
`ATTACHMENT_STORE_NOT_CONFIGURED` on a `SKIPPED`.

**Between plan and apply.** `apply` does not execute a plan it was handed: it re-reads this phone
inside its own transaction and re-decides, so a plan is never applied to a state it was not just
checked against. If the answer has changed, it refuses with 409 rather than writing a different
thing than the plan said.

A corrupt archive, or one from a newer build, is refused **before** any plan exists: the phone is
byte-identical afterwards.

**What the plan checks, exactly.** Every uniqueness constraint the database actually declares: the
five unique indices — on a tag's payload identity, a reading's key per asset, a quick action's
`(action, reading)` pair, an event's `(source, sourceRef)`, and an attachment's storage locator —
and the four aggregate child-row primary keys. Plus every foreign key, and the asset tree's
no-cycles rule.

**Known limits in 1.1.0.** Resolving a conflict, and mapping an imported asset onto a local one
(#44's interactive slice), are a later release — 1.1.0 merges what is unambiguous and refuses the
rest. The bytes half of a backup set (`ServiceTag-artifacts-*.zip`) has no endpoint. A merged quick
action may end up sharing its name with a local one on the same asset: the app keeps those names
unique when *you* create one, but the schema has no unique index on them, so the merge does not
invent a rule the database does not have. And a `(source, sourceRef)` collision is always a
conflict, never a coalesce candidate — #44 allows the softer reading, and it needs the interactive
slice to be useful.
````

- [ ] **Step 5: The versioning history.**

In `docs/versioning.md`, replace the supported-history table's four rows (lines 31–34) with five. The 1.0.0 row is **unchanged, word for word**; the three forward-looking rows are re-dated, because they reserved 1.1.0 for schedules and 1.1.0 is this.

```markdown
| 1.0.0 | 11 | first supported baseline: NFC asset identity, asset hierarchy, maintenance journal, typed measurements, event profiles, attachments, backup and restore, inspect and write NFC workflows, local-first persistence, a tested upgrade and signing path |
| 1.1.0 | 12 | local automation API: a loopback JSON API behind a per-session pairing code, alive only while the Developer API screen is open; a workstation MCP server at `tools/servicetag-mcp/`; an additive backup import that upserts and never deletes. No schema and no backup-format change. Contract: `docs/api/v1.md` |
| next compatible fix | 13 | 1.1.1 |
| schedules and reminders | after that | 1.2.0 |
| an incompatible backup or protocol change | after that | 2.0.0 |
```

Everything above that table is untouched: the rubric, the five rules and the historical note about the retired 2.x development line all stand exactly as they are. (The note matters here: it says the retired builds used `versionCode` 7 to 10, *"which is why 1.0.0 carries code 11"* — so 12 is the next, and the monotonic rule is satisfied.)

- [ ] **Step 6: Verify.**

```bash
git diff --stat -- docs/design docs/architecture .github tools/servicetag-mcp app/src core/src libs
grep -n 'versionCode\|versionName' app/build.gradle.kts
git diff --stat -- app/build.gradle.kts
grep -c 'A local automation API' README.md
grep -c 'Read / inspect tag, Developer API' README.md
grep -c 'The automation API is loopback-only' README.md
grep -c 'docs/api/v1.md' README.md
grep -c 'no outbound networking and the Developer API listens only on localhost' README.md docs/api/v1.md app/src/main/AndroidManifest.xml
grep -c 'import-merge/plan' docs/api/v1.md
grep -c 'import-merge/apply' docs/api/v1.md
grep -c 'PAYLOAD_BOUND_TO_ANOTHER_ASSET' docs/api/v1.md
grep -c '| 1.1.0 | 12 |' docs/versioning.md
grep -c '| schedules and reminders | after that | 1.2.0 |' docs/versioning.md
grep -c 'supported release history begins at 1.0.0' docs/versioning.md
./gradlew :app:assembleDebug --console=plain
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -oE "versionCode='[^']*'|versionName='[^']*'"
```

Expected: **no output** from the `git diff --stat` — no design document, no architecture document, no workflow, no Python, no Kotlin and no library changed in this task; it is four files, all of them documentation or the version. `versionCode = 12` and `versionName = "1.1.0"`, with `app/build.gradle.kts` showing `1 file changed, 2 insertions(+), 2 deletions(-)`. Each README `grep -c` returns **1**, except `docs/api/v1.md` which returns **2** (the bullet and the subsection's closing link). The owner's sentence returns **1 in each of the three files**, which is the check that it is in all three and duplicated in none. `import-merge/plan` and `import-merge/apply` each return **2** in the contract (the endpoint table and the merge section), and `PAYLOAD_BOUND_TO_ANOTHER_ASSET` returns **1** (the reason-code table). The 1.1.0 history row returns **1**, the re-dated schedules row returns **1**, and the historical note about the 2.x line is still there. Then `BUILD SUCCESSFUL`, `versionCode='12'` and `versionName='1.1.0'`. (Per the standing note, `aapt2` is at build-tools **36.0.0**.)

- [ ] **Step 7: Commit.**

```bash
AUTHOR_EMAIL=$(git log -1 --format='%ae' master)
git add -A
git -c user.name=GonzRon -c user.email="$AUTHOR_EMAIL" commit -m "1.1.0: write the api contract down and bump the version"
```

---

### Task 6 (controller-run): the proofs

**Files:** none. This task runs and records; the evidence file is the controller's to write afterwards.

**Interfaces:**
- Consumes: everything Tasks 1–5 produced — the `core/merge` vocabulary, `BuildBackupMergePlan`/`ApplyBackupMergePlan`/`ImportBackupMerge`, the seven `api/` files, `AppGraph.importBackupMerge`, `FakeGraph.importBackupMerge`, `DeveloperApiViewModel` (with `failedToStart`), `DeveloperApiScreen`, `Route.DeveloperApi`, `SettingsScreen(… onDeveloperApi)`, the manifest's `INTERNET` line, `tools/servicetag-mcp/`, the `mcp` CI job, `versionCode` 12 / `versionName` 1.1.0, `docs/api/v1.md` and `docs/versioning.md`'s new row.
- Produces: the recorded verdicts for proofs (1)–(7), each with the command that produced it, and the phase-end sentence in Step 8.

- [ ] **Step 1: Proof (1) — both unit gates, from scratch.**

```bash
./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest :app:assembleDebug \
  --rerun-tasks --console=plain
cd tools/servicetag-mcp && uv run --frozen pytest
```

Expected: `BUILD SUCCESSFUL`, 0 failures, then **24 passed**. The Kotlin counts, derived from the 1.0.0 row recorded in `docs/architecture/product-split-evidence.md` (proof 1: 656 tests — app 37 classes/258, core 31/338, nfc-core 6/50, nfc-android 3/10) plus what 1.1.0 adds — **42** cases in two new `:core` classes and **52** cases in five new `:app` classes — are **750 tests: app 42/310, core 33/380, nfc-core 6/50, nfc-android 3/10**, which is **84 classes** in all. Read them off the reports rather than assuming them, and in particular read these class rows: `MergePlannerTest` 33/33, `ImportBackupMergeTest` 9/9, `PairingCodeTest` 4/4, `HttpWireTest` 15/15, `ApiRouterTest` 23/23, `LoopbackApiServerTest` 7/7, `DeveloperApiViewModelTest` 3/3, `RouteTest` 3/3 (same three cases, one with a new assertion). A pre-existing class at a different count than the 1.0.0 row recorded is a regression to investigate, not a number to write down. Record the class list and both counts.

- [ ] **Step 2: Proof (2) — the whole connected suite on the emulator, with the preserved set staged.**

`PreservedSetRestoreTest` reads a real pre-split data archive from `/data/local/tmp`; without it staged that class skips its assertion and the format-5 restore is not actually proved. Push it first, run the suite, remove it after — nothing is written to the phone and nothing personal is printed. `0771` on `/data/local/tmp` gives others traverse, so `chmod 644` is what lets an app-uid test open it:

```bash
SET=~/Documents/Projects/AndroidStudioProjects/noteNFC-backups/transition-20260918-075440
adb -s emulator-5554 push "$SET/noteNFC-data-20260918-075301.zip" /data/local/tmp/servicetag-proof-data.zip
adb -s emulator-5554 shell chmod 644 /data/local/tmp/servicetag-proof-data.zip
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --console=plain
adb -s emulator-5554 shell rm -f /data/local/tmp/servicetag-proof-data.zip
```

`~` is what the committed plan says, and it is right for the owner's own shell. If the shell running this is not the owner's — an agent shell's `$HOME` is not theirs — expand `~` to the owner's home *in the shell only*, e.g. `SET=$(getent passwd "$(stat -c %U .)" | cut -d: -f6)/Documents/Projects/AndroidStudioProjects/noteNFC-backups/transition-20260918-075440`, and **never write the expanded path into a committed file or a report**. If the `adb push` fails, stop: without the archive `PreservedSetRestoreTest`'s assertion does not run, so a green suite would not mean the format-5 restore was proved.

Expected: `BUILD SUCCESSFUL`. In `app/build/reports/androidTests/connected/`: `DeveloperApiListenerTest` **3/3**, `SettingsBackupEntryTest` **2/2**, `PreservedSetRestoreTest` 1/1, and every other pre-existing class at its previous count — in particular `InspectNamesABoundTagTest` 2/2, `EmptyStoreRestorePromptTest` 1/1, `InspectBackDismissesTheAnswerTest` 1/1, `ReadScopedSheetOwnerTest` 1/1, `ReaderModeHoldTest` 3/3, `DashboardSearchTest` 2/2, `PreSplitLinkTagSheetTest` 1/1, `NavigationSmokeTest`, `WriteTagScreenConsentWordingTest`, `AssetModelDeviceProofTest` and `RemovedSurfacesTest` unchanged. Against the 83 tests / 23 classes the 1.0.0 row records, 1.1.0 adds one class and four cases: **87 tests, 24 classes** (`DeveloperApiListenerTest` 3/3 is the new class; `SettingsBackupEntryTest` goes 1/1 to 2/2). There is no expected deletion — 1.1.0 removes no test. Record the class list, the counts and the run's timestamp. Confirm the `rm -f` ran.

- [ ] **Step 3: Proof (3) — the structural facts no suite can assert.**

```bash
API=app/src/main/kotlin/com/loosecannon/servicetag/api
MERGE=core/src/main/kotlin/com/loosecannon/servicetag/core/merge
USE=core/src/main/kotlin/com/loosecannon/servicetag/core/usecase
MAN=app/src/main/AndroidManifest.xml

# --- the listener: one socket, one address, one blocking hop ------------------------------
git grep -n 'ServerSocket(' -- app/src/main              # 1 line: LoopbackApiServer.kt
git grep -nE '"127\.0\.0\.1"' -- app/src/main            # 1 line: the LOOPBACK_ADDRESS constant
git grep -nE '0\.0\.0\.0' -- app core tools              # no output
git grep -cE '^\s+runBlocking\(' -- app/src/main        # 1 file, 1 occurrence: ApiRouter.kt (anchored to the call's indentation; the KDoc that quotes the call cannot match)
git grep -cE '^import android' -- "$API"               # no output (anchored to the line start: a KDoc that quotes the pattern cannot match)
git grep -nE 'LifecycleResumeEffect\(' -- app/src/main    # 3 lines: ServiceTagRoot, ReaderMode, the screen

# --- R1: the permission is there, and nothing outbound is --------------------------------
git grep -c 'android:exported' -- "$MAN"                 # 3
git grep -c 'uses-permission' -- "$MAN"                  # 2
git grep -n 'android.permission' -- "$MAN"               # NFC and INTERNET, nothing else
git grep -c '<service\|<receiver\|<provider' -- "$MAN"   # 1
git grep -n 'uses-permission' -- app/src/debug/AndroidManifest.xml     # no output
git grep -c 'android:scheme' -- "$MAN"                   # 4, unchanged from 0a582f8
git grep -nE 'HttpURLConnection|HttpsURLConnection|OkHttp|okhttp3|openConnection|URLConnection|java\.net\.URL|loadUrl|WebView' -- app/src/main     # no output
git grep -nE '[^r]Socket\(' -- app/src/main              # no output: the only socket is the ServerSocket above
git grep -c 'no outbound networking and the Developer API listens only on localhost' -- "$MAN" docs/api/v1.md README.md     # 1 each

# --- Task 1: the merge's rules, as facts about the source --------------------------------
git grep -nE '\.delete\(|deleteAll\(' -- "$MERGE" "$USE/ApplyBackupMergePlan.kt" "$USE/BuildBackupMergePlan.kt" "$USE/ImportBackupMerge.kt"     # no output
git grep -n 'updatedAt' -- "$MERGE"                      # no output
git grep -n 'physicalUid' -- "$MERGE"                    # no output
git grep -cE '\.upsert\(' -- "$MERGE"                    # no output
git grep -cE '\.upsert\(' -- "$USE/ApplyBackupMergePlan.kt"            # 7
git grep -c 'uow.write' -- "$USE/ApplyBackupMergePlan.kt"              # 1
git grep -nE 'if \(!fresh\.applicable\)|if \(fresh\.fingerprint' -- "$USE/ApplyBackupMergePlan.kt"     # two lines, in THAT order
git grep -n 'UPDATE' -- "$MERGE/MergePlan.kt"            # 1 line, the MergeVerdict KDoc
git grep -c 'unique = true' -- app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities | awk -F: '{s+=$2} END {print s}'   # 5 (three files: 1 + 3 + 1)
git grep -nE 'ImportBackupMerge\(' -- core/src/main app/src/main       # 2: the class, and AppGraph's one construction
git grep -nE '"/v1/import-merge' -- app/src/main         # 2 lines: the two path constants

# --- the surface, and the ratified strings -----------------------------------------------
git grep -nE 'ImportBackupReplace|ExportBackupSet|RestoreArtifacts|BindTag|ProvisionTag|DeleteAsset|DeleteDefinition|DeleteProfile|AddAttachment|UpdateAttachment|DeleteAttachment' -- "$API"     # no output
git grep -n 'links\.all(' -- "$API"                    # 1 line: links.all().size in status() (the MergeTally field `links = links.dto()` is not a repository call)
git grep -nE 'label = "Developer API"' -- app/src/main   # 1 line: the Settings row
git grep -nE 'Text\("Developer API"\)' -- app/src/main   # 1 line: the screen's title
git grep -nE '"The Developer API could not start' -- app/src/main      # 1 line: S6
git grep -nE '\bFORMAT_VERSION = ' -- core/src/main      # 1 line, still 5 (\b keeps ARTIFACT_FORMAT_VERSION in ArtifactsCodec.kt out)
git grep -n 'SCHEMA_VERSION = ' -- app/src/main          # 1 line, still 5
grep -c '"archive_definition"' tools/servicetag-mcp/src/servicetag_mcp/server.py tools/servicetag-mcp/tests/test_tools.py     # 1 each
grep -c 'len(EXPECTED_TOOLS) == 21' tools/servicetag-mcp/tests/test_tools.py           # 1

git diff --stat HEAD~5 -- libs core/src/main/kotlin/com/loosecannon/servicetag/core/ports app/schemas .github/workflows/release.yml app/src/main/kotlin/com/loosecannon/servicetag/nfc app/src/main/kotlin/com/loosecannon/servicetag/MainActivity.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/scan app/src/main/kotlin/com/loosecannon/servicetag/ui/backup docs/design docs/architecture
git -C libs/nfc-tag-core status --porcelain | wc -l      # 0
git ls-tree HEAD libs/nfc-tag-core                       # 160000 commit 7e0377ac99d7a4fee95ca6b88551daaa6330e52f
bash tools/check-submodule-pin.sh
```

Expected: the counts as annotated; **no output** from the `git diff --stat` across all five commits (the library, the repository ports, the schemas, the release workflow, the NFC trampoline, `MainActivity`, the scan screens, the backup screen, every design document and every architecture document are unchanged); the gitlink still at `7e0377a`; the pin script passing.

**Every pattern above is narrowed to the code shape it means, and that is the point of this step.** An earlier draft grepped bare words — `runBlocking`, `delete`, `upsert`, `updatedAt`, `127.0.0.1`, `Developer API`, `import-merge` — and every one of them was **wrong in the same direction**, because this release's own KDoc and comments discuss those concepts by name. A grep that a correct implementation cannot pass is a grep that always looks red, and a step that always looks red is a step nobody reads. Eleven of them are worth spelling out:

- `"127.0.0.1"` **with the quotes**: 1 line, the constant. The manifest comment deliberately says "the loopback address" in prose so it cannot pad this count, and `LoopbackApiServer`'s KDoc discusses the address without quoting it. The bare word would be 2 lines over 2 files. At `0a582f8` it was **0**, quoted or not.
- `^\s+runBlocking\(` **anchored to the call's indentation**: 1 line. The bare word is 4 — an import, two KDoc lines (one of which itself says "the only `runBlocking` in `app/src/main`") and the call — and `runBlocking(` alone is 2, because `ApiRouter`'s KDoc quotes the call; only the anchored form counts code. At `0a582f8` it was **0**.
- `LifecycleResumeEffect(` **with the parenthesis**: 3 lines. Five bare-word matches already exist at `0a582f8` in `ui/nav/ServiceTagRoot.kt` and `ui/nfc/ReaderMode.kt`, so the bare word would be 8 lines over 4 files and would say nothing about what this release added.
- `android:scheme` is **4 and was already 4**: `content` in the `<queries>` block (`AndroidManifest.xml:10`), `servicetag` twice, `vnd.android.nfc` once. The claim is "1.1.0 adds no scheme", not "there are three".
- The long form `no outbound networking and the Developer API listens only on localhost` is grepped, not the short one, and the expectation is **1 in each of three files**. The short phrase would be 2, because the owner's sentence is one physical line in the manifest and in `docs/api/v1.md` precisely so that this grep works — re-wrapping either of them drops the count and fails the step, which is the intended alarm.
- `^import android` (anchored to the line start, so `ApiHandlers`' KDoc, which names this grep, cannot match) in `api/` returning **nothing** is what makes the JVM suite able to run the parser, the router and the handlers. It does **not** catch a transitive dependency: `ApiHandlers.kt` imports `AppGraph`, whose constructor takes a `Context`. That is written into `ApiHandlers`' own KDoc rather than credited to this grep.
- **The outbound greps are R1's other half.** `HttpURLConnection`, `OkHttp`, `openConnection`, `java.net.URL`, `loadUrl`, `WebView` → nothing; and `[^r]Socket(` → nothing, which is the pattern that finds a *client* `Socket(` while excluding the one `ServerSocket(` this release adds. 1.1.0 has the network capability and makes no outbound call, and that is now checkable in five seconds rather than believable.
- `\.delete\(|deleteAll\(` across the four merge sources → **no output**. The bare word `delete` would match a KDoc; these four KDocs are written to say "never removes one" for that reason, so the *code* is what is being checked.
- `updatedAt` in `core/merge` → **no output**, bare word and all. Not one line of the planner names a timestamp: the whole-row `==` is the only thing that sees one, and the planner's KDoc says "the last-modified stamp" instead of the field name so that this grep stays a check on code. This is what makes "never chooses by `updatedAt`" verifiable at a glance. `physicalUid` → **no output**, the same shape, and that is #44 acceptance 7.
- `\.upsert\(` is **absent from `core/merge`** — the planner writes nothing — and exactly **7 lines** in `ApplyBackupMergePlan.kt`, with `uow.write` exactly once: the plan-before-write separation in three numbers. The bare word `upsert` would be 8 there, because the KDoc says "before the first row is written"… which is also why that sentence no longer says "before a single `upsert`".
- The two guard lines come back **in order**: `if (!fresh.applicable)` first, `if (fresh.fingerprint` second. Read the line numbers, not just the count. That order is the fix for the one functional bug a reviewer found in this release, and an edit that swaps them makes a conflict-on-rebuild answer with a stale-plan report that carries no conflict list.
- `unique = true` across the Room entities is **5**, which is the number the planner is written against (Task 1 decision 6). A future migration that adds a sixth makes this count the thing that says the planner has a gap.
- The two `Developer API` patterns are grepped **separately** — `label = "Developer API"` → 1 and `Text("Developer API")` → 1 — because one count of the bare words would be 6 lines over 4 files once the two new KDoc paragraphs and the manifest comment are included. `"The Developer API could not start` is anchored on its opening quote for the same reason, so a KDoc that discusses S6 cannot pad it.

- [ ] **Step 4: Proof (4) — the version in the built artifact.**

```bash
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk \
  | grep -oE "versionCode='[^']*'|versionName='[^']*'"
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk \
  | grep -E "^uses-permission"
```

Expected: `versionCode='12'` and `versionName='1.1.0'`. And exactly two `uses-permission` lines, `android.permission.NFC` and `android.permission.INTERNET`, which is the same fact as Step 3's manifest grep read off the built artifact instead of the source. Record all four lines.

- [ ] **Step 5: Proof (5) — the release signing dry run.**

The expected fingerprint is the ServiceTag release signer's, already published in the evidence file, so it is read from there rather than typed:

```bash
RELEASE_CERT_SHA256=$(sed -n 's/.*certificate `\([0-9A-F]\{64\}\)`.*/\1/p' docs/architecture/product-split-evidence.md | tail -1) \
  bash tools/release-dry-run.sh
echo "exit: $?"
```

Expected: `RELEASE DRY RUN: PASS` with exit 0, `fingerprint compare: matches`, the built `versionName` compared against `app/build.gradle.kts` and found to be **1.1.0**, and exactly one signer. Record the verdict line, the version-comparison line and the exit code **only** — never a fingerprint, never a keystore path, never a password. A `PARTIAL` is **not a pass** for this release. If the script exits 3 (no signing material on this machine) that is a **BLOCKED** proof, not a pass, and the controller stops and says so. (The `sed` pattern has exactly one match in the evidence file at `0a582f8`, so `tail -1` returns the one 64-character uppercase fingerprint.)

- [ ] **Step 6: Proof (6) — hygiene.**

```bash
git diff --stat HEAD~5
git log --oneline -5
git diff HEAD~5 | grep -nE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' || echo 'no e-mail address'
git diff HEAD~5 | grep -nE '/home/|/Users/' || echo 'no absolute home path'
git diff HEAD~5 | grep -nE 'emulator-[0-9]+' | grep -v 'emulator-5554' || echo 'no device but emulator-5554'
git diff HEAD~5 | grep -nE '\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b' | grep -v '123e4567-e89b-12d3-a456-426614174000' || echo 'no UUID but the invented one'
git diff HEAD~5 | grep -nE 'Bearer [A-Z2-9]{8}' | grep -vE 'Bearer \$|Bearer ABCD2345|Bearer NOPENOPE|Bearer <' || echo 'no pairing code but the invented ones'
```

Expected: the five commits of Tasks 1–5 and no other path; each of the last five commands printing its `no …` line. The five commit subjects are single lines with no body and no trailer. `ABCD2345` and `NOPENOPE` are the invented test tokens; `123e4567-…-426614174000` is the invented canonical UUID `ResolveTagTest` and `PreSplitLinkTagSheetTest` already use.

Nothing in this change installs on, reads from or writes to the phone: every `adb` and every Gradle device command in this plan carries `-s emulator-5554` or `ANDROID_SERIAL=emulator-5554` **on the same command line**, `adb devices` is not consulted for the phone, and the MCP's own forward in Step 7 is aimed at the emulator by an explicit `SERVICETAG_ADB_SERIAL`. **Do not hand the owner a manual checklist**: 1.1.0 asks for no physical NFC row at all, because a loopback socket is not an NFC behaviour and the runbook's R1 and R2 are untouched.

- [ ] **Step 7: Proof (7) — the end-to-end round trip on the emulator.**

This is the one proof that exercises all three parts at once: the screen starts the listener, the code is read off the screen the way a person reads it, the MCP server sets up its own forward and writes a row, and the *app* shows the row. Emulator only.

**(a) Install the debug build and put the app in a known state.**

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug --console=plain
adb -s emulator-5554 shell pm clear com.loosecannon.servicetag
adb -s emulator-5554 shell am start -n com.loosecannon.servicetag/.MainActivity
```

`pm clear` is safe here and only here: this is `emulator-5554`. Expected: `Success`, then `Starting: Intent {...}` and the Dashboard.

**(b) Two helpers, because the screen is driven through the UI — there is no deep link to it, deliberately (Task 3 decision 8).**

```bash
# `uiautomator dump` emits the whole hierarchy as ONE line, so every grep over it pipes through
# `tr '<' '\n'` first. Without that, `grep -c` answers 0 or 1 whatever the content and any count
# taken from it proves nothing.
dump() {
  adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb -s emulator-5554 exec-out cat /sdcard/ui.xml | tr '<' '\n'
}
# Finds a node, scrolls once if it is not in the dump, then taps its centre. The scroll is not
# optional: Settings is a `verticalScroll` column and Developer API is its third Utilities row,
# where the About header used to sit — off-screen on a phone, and an off-screen Compose node is not
# in the hierarchy at all. The retry is what turns "not found" into "not found after a scroll".
tap() {   # tap() 'text' | tap() --desc 'content description'
  local attr="text" needle bounds nums try
  if [ "$1" = "--desc" ]; then attr="content-desc"; shift; fi
  needle="$attr=\"$1\""
  for try in 1 2; do
    bounds=$(dump | grep -F "$needle" \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
    [ -n "$bounds" ] && break
    adb -s emulator-5554 shell input swipe 540 1600 540 800
    sleep 1
  done
  [ -n "$bounds" ] || { echo "no node with $needle, even after a scroll"; return 1; }
  nums=$(printf '%s' "$bounds" | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $nums
  adb -s emulator-5554 shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 1
}
```

**(c) Open the screen and read the code off it.**

```bash
tap --desc 'Settings'
tap 'Developer API'

# The screen, before the code: `dump` already splits on '<', so these counts are real counts.
dump | grep -c 'text="PAIRING CODE"'            # 1
dump | grep -c 'text="REQUESTS THIS SESSION"'   # 1

# Exactly one candidate in the whole hierarchy, then read THAT one. The alphabet is the one
# `PAIRING_ALPHABET` declares, which already excludes `SETTINGS` (it has an `I`) and the port
# (five characters, and a `1`) — but "the first eight-capital node in document order" is not the
# same claim as "the pairing code", so the count is asserted before anything is read.
CANDIDATES=$(dump | grep -cE 'text="[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"')
[ "$CANDIDATES" = 1 ] || { echo "expected one code-shaped node, found $CANDIDATES"; exit 1; }
# And read it from the node that follows the PAIRING CODE label, so the one candidate is also
# provably the right one.
CODE=$(dump | grep -A4 'text="PAIRING CODE"' \
  | grep -oE 'text="[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"' | head -1 \
  | sed 's/^text="//; s/"$//')
echo "code length: ${#CODE}"
[ "${#CODE}" = 8 ] || { echo "no pairing code under the label"; exit 1; }
```

Expected: `1` for each label, `CANDIDATES` exactly `1`, and `code length: 8`. Together those say: this is the Developer API screen, there is exactly one code-shaped node on it, and the value read came from under the `PAIRING CODE` label rather than from wherever document order happened to put it. **Do not echo `$CODE`, and do not write it into any report** — it is a session secret, and the recorded facts are its length and that the round trip worked with it.

**(d) Drive the MCP server's own tools, with its own `adb forward`.**

```bash
cd tools/servicetag-mcp
CODE="$CODE" SERVICETAG_ADB_SERIAL=emulator-5554 uv run --frozen python - <<'PY'
import json
import os

from servicetag_mcp import server as s
from servicetag_mcp.client import NotPaired

s.pair(os.environ["CODE"])

before = s.status()
created = s.create_asset(name="Round trip", category="Proof")
asset_id = created["asset"]["id"]
fetched = s.get_asset(asset_id=asset_id)
after = s.status()

assert fetched["asset"]["id"] == asset_id, fetched
assert fetched["asset"]["name"] == "Round trip", fetched
assert fetched["asset"]["category"] == "Proof", fetched
assert after["counts"]["assets"] == before["counts"]["assets"] + 1, (before, after)
assert after["appVersion"] == "1.1.0", after
assert after["apiVersion"] == 1, after
assert after["schemaVersion"] == 5, after

try:
    s.pair("NOPENOPE")
    s.status()
    raise SystemExit("a wrong pairing code was accepted")
except NotPaired:
    pass

print(json.dumps({
    "asset_id": asset_id,
    "assets_before": before["counts"]["assets"],
    "assets_after": after["counts"]["assets"],
    "app_version": after["appVersion"],
}))
PY
cd -
```

Expected: one JSON line with `assets_before: 0`, `assets_after: 1`, `app_version: "1.1.0"`, and an `asset_id` that is a UUID the app minted. Record the whole line **except** nothing — an asset id the API just created on a wiped emulator is not the owner's data, so it may be recorded. The `adb forward` ran inside that process, against `emulator-5554` and nothing else; confirm with `adb -s emulator-5554 forward --list`, which must show `tcp:17337 tcp:17337` and nothing else.

**(e) The app shows the row the API wrote, and leaving the screen stops the listener.**

```bash
adb -s emulator-5554 shell input keyevent KEYCODE_BACK
adb -s emulator-5554 shell input keyevent KEYCODE_BACK
sleep 1
dump | grep -c 'text="Round trip"'
# Remove the forward FIRST, so a stopped listener produces a genuine connection refusal. With the
# forward still installed, adb itself accepts the local connection and then closes it, which httpx
# reports as a protocol or read error rather than a ConnectError.
adb -s emulator-5554 forward --remove tcp:17337
cd tools/servicetag-mcp
CODE="$CODE" SERVICETAG_ADB_SERIAL=emulator-5554 uv run --frozen python - <<'PYEOF'
import os

import httpx

from servicetag_mcp import server as s

# Pair first: an unpaired client refuses before it reaches the transport, and the transport is
# what is under test.
s.pair(os.environ["CODE"])
try:
    s.status()
except httpx.TransportError as e:
    # The transport failed: nothing is listening. The one acceptable outcome.
    print(f"refused after leaving the screen: {type(e).__name__}")
else:
    raise SystemExit("the listener is still answering after the screen was left")
PYEOF
cd -
unset CODE
```

Expected: `1` — the Dashboard lists **Round trip**, which is the independent confirmation that the API wrote a real row through the real use case and not into a corner of its own. Then `refused after leaving the screen: ConnectError`, or another `httpx.TransportError` subclass; the class name is recorded and the transport failure is the point.

Three things there are deliberate, because the obvious versions of each pass while proving nothing. The grep goes through `dump`, which splits the hierarchy on `<` — `uiautomator dump` emits it as **one line**, so a `grep -c` over the raw output returns 0 or 1 whatever the content. The catch is `httpx.TransportError` and **not** bare `Exception`: a listener that is up but rejecting the code raises `NotPaired`, a `RuntimeError`, and a bare catch would print "refused after leaving the screen: NotPaired" and call it a pass — the exact failure this step exists to find, so `NotPaired` and `ApiError` must both fail it. And the forward is removed before the attempt, for the reason in the comment.

Record: the code's length (never the code), the two asset counts, the app version from `/v1/status`, that the Dashboard showed the row, and the refusal after leaving the screen.

**What this proof deliberately does not do: the merge.** `import_merge` is not exercised here, and the reason is that this proof's job is the *transport and the screen* — the permission granted, the effect bound, the code read off a real display, `adb forward` run for real, a write landing in the app's own UI. The merge's semantics are forty-two `:core` cases and five endpoint cases, which is where a rule belongs; adding a zip round trip here would test the same planner a third time while making the one thing only a device can prove harder to read. If the controller wants a device-level merge check anyway, the honest shape is a follow-up: export a set from the emulator, `pm clear`, seed one asset, `import_merge` the export, and assert the report is one `INSERT` and one `CONFLICT` — and that is a proof of its own, not a step of this one.

- [ ] **Step 8: Record and hand back.**

Assemble one table — proof, command, expected, observed, verdict — for (1)–(7), plus the per-task gate counts, the connected run's class list and pytest's count. State, from the code:

- **The listener's lifetime**: bound by `LifecycleResumeEffect` in `DeveloperApiScreen`, started on resume, stopped on pause or dispose. No `Service`, no `WorkManager`, no boot hook — checked by the manifest greps in Step 3 and demonstrated by Step 7(e).
- **The security minimums, and which test pins each**: the loopback bind and the second peer check (`LoopbackApiServerTest.itBindsTheLoopbackAddressAndNothingElse`, plus the `127.0.0.1`-is-one-line and `0.0.0.0`-is-absent greps); the bearer token and its constant-time compare (`PairingCodeTest`); the empty-bodied 401 before routing (`ApiRouterTest`'s three token cases, `LoopbackApiServerTest.anUnauthorisedRequestGetsAnEmptyBodyOverTheWire`, `DeveloperApiListenerTest.onlyTheCodeOnTheScreenIsAcceptedAndEveryTryIsCounted`); the fresh per-visit code (`PairingCodeTest.twoCodesAreNotTheSame`, and `DeveloperApiListenerTest` asserting the screen shows the code the listener accepts); the caps and the framing refusals (`HttpWireTest`, fifteen cases in all, including the trailing-slash case); nothing destructive reachable (`ApiRouterTest.theDestructiveUseCasesHaveNoRoute` and Step 3's grep).
- **The merge import's rule, in one sentence**: plan first, insert only, never update and never remove; an id already here is a no-op when identical and a **conflict** when not; NFC payload identity is checked independently of the row id; every uniqueness constraint the schema declares is checked before any mutation — **five** unique indices and the **four** aggregate child-row primary keys; one conflict anywhere means nothing at all is written; and an attachment row is written only when the store holds bytes whose size **and** sha256 are the row's. Pinned by forty-two `:core` cases, by the apply rebuilding its plan inside its own transaction with the conflict check *before* the staleness check, and by `\.delete\(`, `updatedAt`, `physicalUid` and `\.upsert\(` each grepping to nothing in `core/merge`.
- **The one manifest change and why**: `android.permission.INTERNET`, because Android gates AF_INET socket *creation* on the group that permission grants, whatever address the socket is bound to. No component was added; `android:exported` is still 3.
- **What 1.1.0's evidence section must say about versioning**: the automation API took **1.1.0 / code 12**, and `docs/versioning.md`'s forward-looking row for **schedules and reminders moved to 1.2.0**. The 1.0.0 entry and the retired-2.x note are history and are not rewritten.
- **Nothing is left open for the owner.** S1–S6 are ratified, **R1** approved the TCP loopback transport and the `INTERNET` permission with the no-outbound documentation, and **R2** made the MCP mirror every API operation. What remains is the release authorisation itself.

The phase ends **"ServiceTag 1.1.0 complete locally; a loopback JSON API that lives only as long as the Developer API screen, a workstation MCP server that pairs with the code on it, and a merge import that plans before it writes and refuses anything ambiguous; push and `servicetag-v1.1.0` authorized once CI is green on the exact tip."** No push, no tag, no `gh` mutation, until the owner says so.

---

## Where the tree forced a deviation from the approved design

**D1 — a loopback TCP socket does need a permission on Android, and the owner approved it.** The design said the manifest gains nothing, "because a loopback socket needs no permission and no component". The second half is true and the first is not: Android gates the *creation* of an `AF_INET`/`AF_INET6` socket on membership of the `inet` group, which is what `android.permission.INTERNET` grants, and that check is on the socket, not on the address it is later bound to — without the permission `ServerSocket(…)` fails with a `SocketException` before it can bind `127.0.0.1`. **R1 approved the TCP transport and the permission**, on the reasoning that ServiceTag's cloud roadmap will need it anyway, so Task 3 Step 7 adds exactly one line with the owner's sentence as its comment, no component is added, `android:exported` stays at 3, and Task 6 Step 3 proves there is no outbound call site at all. *For the record only:* an abstract `AF_UNIX` socket would have avoided the permission (`adb forward tcp:17337 localabstract:servicetag` speaks it natively) and was declined with the transport decision.

**D2 — `docs/versioning.md` had 1.1.0 reserved for schedules.** At `0a582f8` its forward-looking rows read `next compatible fix | 12 | 1.0.1`, `schedules and reminders | after that | 1.1.0`. The automation API is a MINOR by the same document's own rubric and is shipping now, so Task 5 Step 5 gives it 1.1.0 / code 12 and moves schedules to **1.2.0**, with the next patch at 1.1.1 / code 13. The rubric, the five rules and the retired-2.x historical note are untouched.

**D3 — `ci.yml` is not SHA-pinned; `release.yml` is.** The brief describes the workflow as SHA-pinned with `persist-credentials: false`; that is `release.yml` (`release.yml:16`–`35`). `ci.yml` at `0a582f8` uses floating major tags (`actions/checkout@v5`, `actions/setup-java@v5`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`) and no `persist-credentials`. Task 4 Step 9 adds its one new step **SHA-pinned in `release.yml`'s form** — `astral-sh/setup-uv@c18668ad3cf93ea998bef934396af7bb5c839dc7 # v10.2.0`, with `persist-credentials: false` on the new job's checkout — and **does not re-pin the five existing steps**, because that is a workflow-hardening change with its own blast radius and it is not this release's subject. Offered as a separate one-commit follow-up.

**D4 — WITHDRAWN.** An earlier draft of this plan left a bind failure silent on screen, to avoid a sixth user-visible string. The owner ratified **S6** instead — `The Developer API could not start. Leave this screen and open it again.` — so the failure is said out loud, `DeveloperApiViewModel` carries it as a `StateFlow<Boolean>` rather than as a thrown exception, and `DeveloperApiViewModelTest.listeningOnAnOccupiedPortReportsTheFailure` pins it **on the JVM** by occupying the port first. Nothing is left silent and nothing is left to the emulator.

**D5 — the MCP SDK renamed `FastMCP` to `MCPServer`.** The design says "the `mcp` package's FastMCP". In the SDK's 2.x line (current release **2.2.0**, 2026-09-07) the high-level server class is `MCPServer`, imported from `mcp.server`; the SDK's own migration note is *"Use MCPServer instead of FastMCP for the high-level server class in v2. The previous import path under fastmcp has been replaced with mcpserver."* Task 4 uses `MCPServer` and pins `mcp>=2.2,<3`. Pinning `<2` to keep the old name would have pinned a superseded major to honour a word. Related and worth one line: the 2.x SDK carries its own HTTP stack (`httpx2`) for its own transport, and `tools/servicetag-mcp` depends on `httpx` directly for its own calls, so the locked environment holds both. That is a lock-file fact, not a code one.

**D6 — `FakeGraph` is not an `AppGraph`, so the API layer takes its collaborators one by one.** The design says the API handlers work "over `AppGraph`" and are "JVM-testable against `FakeGraph`". Those two cannot both be literally true: `FakeGraph` (`app/src/test/.../testing/FakeGraph.kt`) is a separate class with the same member names and no supertype. `ApiHandlers` therefore has a twenty-parameter primary constructor and a `constructor(graph: AppGraph)` beside it — which is not a workaround but this app's stated pattern for exactly this situation (`AssetViewModels.kt:59`–`61`), and is what `BackupViewModel` already does.

**D7 — RESOLVED by R2.** The design's API half required `archive` for definitions and profiles; its enumerated MCP tool list did not name `archive_definition` or `archive_profile`. The owner ruled that the MCP mirrors every API operation, so both are tools and the count is **21** (`pair` plus twenty operations). `TOOL_NAMES`, `EXPECTED_TOOLS`, `test_the_archive_tools_post_their_flags`, the README's tool list and Task 6 Step 3's grep all say 21.

**D8 — NanoHTTPD failed the "maintained" half of the dependency rule, so there is no new dependency at all.** Argued in full under *The dependency decision*. The consequence for the plan's shape: `gradle/libs.versions.toml` and `app/build.gradle.kts`'s `dependencies` block are **untouched**, there is nothing to show for "the exact lines for any new dependency" on the Kotlin side, and the strict limits that were the owner's condition for a hand-rolled server are `HttpWireTest`'s fifteen cases in the JVM gate rather than a promise in prose.

**D9 — #44 made Task 1 a different task, and a much larger one.** The brief's part (3) was "upserts rows whose ids exist, inserts rows whose ids are new, never deletes". #44 forbids the first clause: an id that is already here is a **conflict**, never an overwrite, and the merge must be *planned* before it writes. So Task 1 is now two `:core` classes and a pure planner rather than one use case, its verdict set has no `UPDATE` at all, and its test count went from 10 to **42**. Two consequences worth naming. **(a)** The report shape changed: `ImportMergeReport`/`MergeCounts` (inserted/updated/skipped) became `MergeReport`/`MergeTally` (insert/identical/conflict/skipped) plus a conflict list and a duplicate-candidate list, so Task 2's wire DTOs and Task 4's tool result changed with it. **(b)** The single `POST /v1/import-merge` became `/v1/import-merge/plan` and `/v1/import-merge/apply`, because plan-before-write is only real if a client can ask for the plan without the write.

**D11 — `profile_field(profile_id, definition_id)`'s destination arm is unreachable, and is written anyway.** The fifth unique index's key contains the aggregate root's id, and a 1.1.0 merge only ever inserts a *new* root — so a collision with a row already on this phone cannot happen while the verdict set has no `UPDATE`. The live case is an archive whose quick action lists one reading twice, which `BackupCodec.decode` accepts. The check is still written against the union of the destination and the archive, because the cost is one map lookup and a future loosening of the key would otherwise go unnoticed; Task 1 decision 6 says so, and the two tests are the archive-internal collision and a **negative control** (two different quick actions may each offer the same reading) rather than a destination collision that cannot be constructed. That is the one place the ruling's "checked against the destination and within the archive" is met in code but testable only on one side, and it is called out here rather than papered over. The **four child-row primary keys** are a different matter and *are* reachable from the destination, which is why they have their own code and two real tests.

**D12 — the attachment bytes are read just outside the apply's transaction, not inside it.** The review's nit asked for `presentLocators` inside the transaction and its own correction said to document it instead; this plan documents it, and refuses to move it, for a stated reason. `AttachmentStore.open` is a document-provider IPC, and `storedBytesOf` streams every named file to hash and size it — putting either inside a Room write transaction would hold a database lock across an IPC and across megabytes of IO, which is the boundary `ImportBackupReplace` is already careful about in the other direction (`ImportBackupReplace.kt:55`, `:87`). There is no transaction that spans Room and a document provider, so the window cannot be closed, only moved somewhere worse. The consequence — a file deleted or replaced between the check and the write yields an inserted row whose hash no longer describes the bytes on disk, which the next `RestoreArtifacts` reports — is stated in `ApplyBackupMergePlan`'s KDoc and in `docs/api/v1.md`. Everything the *database* can guarantee is still guaranteed: the seven-table snapshot **is** re-read inside the transaction, and that is what the rebuild is for.

**D10 — the schema, not the design, decided which uniqueness rules the planner checks.** #44 names NFC payload identity and `source`+`sourceRef` as collision detectors. Reading the Room entities turned that into a closed list of **five** — `nfc_tag(payload_format, payload_key)` (`NfcTagEntity.kt:26`), `measurement_definition(asset_id, key)` (`JournalEntities.kt:45`), `profile_field(profile_id, definition_id)` (`JournalEntities.kt:113`), `asset_event(source, source_ref)` (`JournalEntities.kt:167`) and `attachment(storage_provider, storage_locator)` (`AttachmentEntity.kt:43`), all five confirmed in `app/schemas/*/5.json` — plus the **four** aggregate child-row primary keys, which are durable identity (`core/model/Journal.kt:28`–`30`) and which `BackupCodec.decode` only checks within one file. The planner checks exactly those nine. Three of them — the definition key, the attachment locator and the profile-field pair — the design did not mention, and the child-row keys it did not either; every one of them is a constraint the database would otherwise have enforced with a failed transaction and an opaque 500 instead of a report. An earlier draft of this plan said "four", having read four entity files and not the fifth. A profile's name is **not** checked, because `event_profile` has no unique index on it (`JournalEntities.kt:81`): a merged quick action may share a name with a local one on the same asset, which is a display oddity with no constraint behind it, recorded as a known limit in `docs/api/v1.md`.

---

## Self-review

Every sentence of the approved design, against the step that implements it.

| Requirement (design) | Where |
|---|---|
| One MINOR release: `versionName` 1.1.0, `versionCode` 12, per `docs/versioning.md` | Task 5 Step 1 (the bump, with the classification argued), Step 5 (the history row); Task 6 Step 4 (`aapt2` reads 12 / 1.1.0 off the artifact) |
| **(1)** A Settings › Utilities entry beside "Backup and restore" and "Read / inspect tag" | Task 3 Step 4 (the third `UtilityRow`, `ServiceTagIcons.Speed`, label `Developer API`); Task 3 Step 8 (`SettingsBackupEntryTest.developerApiRowIsPresentAndInvokesOnDeveloperApi`) |
| …opening a new route/screen | Task 3 Step 4 (`Route.DeveloperApi`, `entry<Route.DeveloperApi>`), Step 3 (`DeveloperApiScreen`), Step 5 (`RouteTest`: it holds no reader mode) |
| Listens on 127.0.0.1, fixed port 17337, while the screen is on top and the activity is resumed | Task 2 Step 14 (`LOOPBACK_ADDRESS`, `DEVELOPER_API_PORT`, the peer re-check); Task 3 Step 3 (`LifecycleResumeEffect`); Task 6 Step 3 (the two greps) and Step 7(c)–(e) |
| Shows the port and a per-session pairing code, fresh every time the screen opens, 8 characters, unambiguous alphabet, shown large | Task 2 Step 3 (`PAIRING_ALPHABET` — 32 characters, no `I`/`O`/`0`/`1`; `PAIRING_CODE_LENGTH` 8; `SecureRandom`); Task 3 Step 3 (`MeasurementHeroText`, and the code owned by a per-visit `ViewModel`); Task 2 Step 1 (`PairingCodeTest`, four cases) |
| Leaving the screen, or the activity pausing, stops the listener; no service, no background work | Task 3 decision 1 and Step 3; Task 2 Step 14 (`stop()`); `LoopbackApiServerTest.stopRefusesFurtherConnections`; `DeveloperApiListenerTest`'s first case; Task 6 Step 3's manifest greps (no `<service>`, no `<receiver>`) and Step 7(e) |
| Every request must carry `Authorization: Bearer <code>`, constant-time compare | Task 2 Step 3 (`tokenMatches` over `MessageDigest.isEqual`), Step 9 (`handle` checks it first) |
| Anything else → 401 with an empty body | Task 2 Step 9 (`ApiResponse.empty(401, …)`), Step 3 (`writeResponse` omits `Content-Type` for an empty body); `HttpWireTest.anEmptyResponseCarriesNoContentTypeAndNoBody`; `ApiRouterTest`'s three token cases; `LoopbackApiServerTest.anUnauthorisedRequestGetsAnEmptyBodyOverTheWire` |
| JSON only; 64 KiB body cap; kotlinx.serialization already in use | Task 2 Step 3 (`ApiJson`, strict in / complete out), Step 9 (`MAX_BODY_BYTES`), Step 8 (`decode` 415s a non-JSON type); no new dependency — `app/build.gradle.kts:123` already has it |
| Endpoints under `/v1`, one per existing use case, nothing that bypasses one | Task 2's route table (20 rows / 18 shapes), Step 8 (`ApiHandlers`, one method each), Step 9 (`route`) |
| assets — list (top-level + components, the shape the dashboard/detail use) | Task 2 Step 7 (`AssetListResponse`), Step 8 (`listAssets` over `AssetTree.children`); `ApiRouterTest.listReturnsTopLevelAssetsAndTheirComponents` |
| assets — get, create (`CreateAsset`), update (`UpdateAsset`), retire, archive, create component (parent id) | Task 2 Step 8 (six methods); `ApiRouterTest` cases 8–12 |
| definitions — list per asset, save (`SaveDefinition`), archive | Task 2 Step 8 (`listDefinitions`, `saveDefinition`, `archiveDefinition`); `ApiRouterTest.saveListAndArchiveADefinition` |
| profiles — list per asset, save (`SaveProfile`), archive | Task 2 Step 8 (`listProfiles`, `saveProfile`, `archiveProfile`); `ApiRouterTest.saveListAndArchiveAProfile` |
| events — list per asset, log (`LogEvent`, with measurements and consumables), update (`UpdateEvent`), delete (`DeleteEvent`) | Task 2 Step 7 (`EventRequest`, `ConsumableRequest`, `values` keyed by definition id), Step 8; `ApiRouterTest.logListUpdateAndDeleteAnEvent` |
| tag bindings — read only (`graph.tags`) | Task 2 Step 8 (`listTagBindings`), Step 9 (only a `GET` route exists); `ApiRouterTest.tagBindingsAreReadableAndNotWritable` |
| `GET /v1/status` (version, counts) | Task 2 Step 8 (`status`), Step 7 (`StatusResponse`); `ApiRouterTest.statusReportsTheVersionsAndTheCounts` |
| **Never** exposed: wipe, replace import, export, NFC write/bind, attachments bytes | Task 2's "Never exposed" list, Step 9 (no route), `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` (sixteen paths), Task 6 Step 3 (the eleven-name grep returns nothing) |
| Read `di/AppGraph.kt` and every use case for the exact signatures | Task 2's Interfaces block cites each one with its file and line; Task 1's likewise for the seven ports and the codec |
| Call the use cases the way the UI does | Task 2 decision 4 and Step 8's KDoc; the call shapes match `AssetViewModels.kt:153`, `EventEntryViewModel.kt:402`, `EventDetailViewModel.kt:93`, `AssetSetupViewModel.kt:134`/`:138`, `DefinitionEditViewModel.kt:279`, `ProfileEditViewModel.kt:288`, `AssetViewModels.kt:314`/`:335` |
| Decide NanoHTTPD vs hand-rolled, and state the decision and its reason | *The dependency decision* section, in full, with four rejected alternatives; **D8** |
| A hand-rolled server only with strict limits and tests for malformed input | Task 2 Step 3 (`METHODS`, `MAX_REQUEST_LINE`, `MAX_HEADER_BYTES`, `MAX_HEADERS`, the `Transfer-Encoding` refusal, `readExactly`), Step 1 (`HttpWireTest`, 15 cases) |
| The server runs on its own thread | Task 2 Step 14 (one daemon thread, `servicetag-developer-api`), decision 7 |
| Handlers call the suspend use cases with `runBlocking` on the graph's dispatcher | Task 2 Step 9 (`runBlocking(Dispatchers.IO)`, one call site), decision 6; Task 6 Step 3 (`runBlocking` is 1 file / 1 occurrence, and was 0 before) |
| Nothing sensitive in logs (no token, no request bodies) | Task 3 Step 3 (`Log.w` with a fixed string and nothing else — the only log line the feature has), Global Constraints' security minimum 8; Task 6 Step 7(c) forbids echoing the code |
| JVM-testable against `FakeGraph` with a real loopback socket in `:app` unit tests | Task 2 Step 5 (`ApiRouterTest` over `FakeGraph`), Step 12 (`LoopbackApiServerTest` over a real socket), Step 10 (`FakeGraph.importBackupMerge`) |
| State whether Robolectric is needed — it is not | Global Constraints ("Robolectric is not used, and is not in the tree"), enforced by Task 6 Step 3's `import android` grep over `api/` |
| Malformed-request and 401 tests | `HttpWireTest` (15), `ApiRouterTest` (3 token cases), `LoopbackApiServerTest` (its unauthenticated, peer and half-open cases) |
| **(2)** MCP at `tools/servicetag-mcp/`, Python 3.12, `uv`, FastMCP, `httpx`, `pytest` | Task 4 Steps 1, 4, 7 (`pyproject.toml`, `.python-version`, `client.py`, `server.py`); `MCPServer` is the 2.x name for FastMCP — **D5** |
| It runs `adb -s <serial> forward tcp:17337 tcp:17337` itself | Task 4 Step 4 (`Device.ensure_forward`), decisions 3–4; Task 6 Step 7(d) runs it for real against `emulator-5554` |
| Serial from `SERVICETAG_ADB_SERIAL` or the MCP config, never a file in the repository | Task 4 Step 4 (`SERIAL_ENV`), Step 10 (the config snippet's `env`); Task 4 Step 12's `emulator-` grep over `tools/servicetag-mcp` returns nothing |
| Takes the pairing code once via a `pair` tool | Task 4 Step 7 (`pair`), decision 5; `test_pair_stores_the_code_upper_cased`, `test_an_unpaired_client_refuses_to_call_and_says_where_the_code_is` |
| The named tools, mirroring the API | Task 4 Step 7 (twenty-one: `pair` plus one per operation), `TOOL_NAMES`; `test_every_tool_the_design_names_is_registered` asserts the literal and the count. **R2** added `archive_definition` and `archive_profile`; **D7** records why |
| `import_merge` takes a local path to a format-5 zip | Task 4 Step 7; `test_import_merge_plans_first_and_applies_when_the_plan_is_clean`, `…plan_only_stops_after_the_plan`, `…never_applies_a_plan_with_conflicts`, `test_import_merge_says_so_when_the_file_is_not_there` |
| pytest against a fake HTTP server, no device | Task 4 Step 2 (`conftest.py`: stdlib `ThreadingHTTPServer`, `SERVICETAG_API_BASE_URL` set, `SERVICETAG_ADB_SERIAL` deleted), decision 8; `test_an_explicit_base_url_means_no_adb_forward` |
| A CI job running `uv run pytest` beside the Gradle job, keeping the workflow's conventions | Task 4 Step 9 (the exact `mcp` job; `ubuntu-24.04`, `persist-credentials: false`, `uv run --frozen pytest`); the pin-vs-tag mismatch is **D3** |
| The official `astral-sh/setup-uv`, SHA-pinned at a current release, version and SHA stated | Task 4 Step 9: **v10.2.0**, `c18668ad3cf93ea998bef934396af7bb5c839dc7`, with v10.1.0 (`bec219d24cd3e171d82865faccec33120bb574f4`) named as the drop-in fallback |
| `tools/servicetag-mcp/README.md` with the Claude Code MCP snippet (command, args, env with `<serial>`) | Task 4 Step 10 (the JSON block uses the literal placeholder `<serial>`) |
| **(3)** #44: plan before write, split into a pure planner and an apply | Task 1 Steps 3–4 (`merge/MergePlan.kt`, `merge/MergePlanner.kt`) and Step 8 (`BuildBackupMergePlan`, `ApplyBackupMergePlan`, the `ImportBackupMerge` façade), decision 1 |
| #44 identity precedence: UUID, then `(payloadFormat, payloadKey)`, then make+model+serial as a *candidate only*, never name/category/description; physical UID informational | Task 1's rules table and Step 4's five-step KDoc; `MergePlannerTest`'s six NFC and hint cases (`…IDENTICAL`, `…BOUND_TO_ANOTHER_ASSET`, `…DIVERGED_LOCAL_TAG`, `…DUPLICATED_IN_ARCHIVE`, `the physical uid is never identity`, `manufacturer model and serial produce a duplicate candidate`, `a matching name alone is neither identity nor a hint`); Task 6 Step 3 greps `physicalUid` to nothing in `core/merge` |
| Absent id → INSERT; same id + identical canonical content → IDENTICAL; same id + different → CONFLICT; never by `updatedAt` | Task 1 Step 4 (the first two branches of every pass), decision 2 and 3; `MergePlannerTest`'s four same-id cases; Task 6 Step 3 greps `updatedAt` to nothing in `core/merge` |
| "Canonical content" defined explicitly, per table | Task 1 decision 3 and Step 4's KDoc: **every** backup-format field, `createdAt` and `updatedAt` included, as `incoming.toDto() == local.toDto()`; the only normalisation is child lists in `sortOrder` for the two aggregate tables, with the reason; `MergePlannerTest.a row that differs only in updatedAt is still a CONFLICT` |
| NFC: equivalent binding → no-op; different assets → CONFLICT; no coalescing or remapping in 1.1.0 | Task 1 decision 5 and Step 4's tag pass (`copy(id = …)` equivalence, then the asset comparison, then the catch-all); three `MergePlannerTest` cases |
| Uniqueness and FK conflicts discovered while planning; parents before children; a child whose parent is nowhere is a CONFLICT not an orphan | Task 1 decisions 6, 8, 9, 10 and Step 4; `MergePlannerTest`'s four unique-index cases and three reference cases; Task 6 Step 3's `upsert`-absent-from-`core/merge` grep |
| Any unresolved conflict → apply refuses, destination byte-for-byte unchanged, proved by a before/after snapshot | Task 1 decision 11 and Step 8 (`MergeRefused`, and `MergeWrites` empty by construction); `ImportBackupMergeTest.a conflicting archive is refused and the destination is byte for byte unchanged`, which snapshots all seven fakes with `Fakes.everything()` |
| Repeated import of the same archive is idempotent | `ImportBackupMergeTest.importing the same archive twice is idempotent`, and `ApiRouterTest.importMergeApplyWritesTheUnionAndIsIdempotent` at the endpoint |
| TOCTOU between plan and apply is first-class: rebuild-in-transaction or a fingerprint, one chosen and stated, with a test | Task 1 decision 12 and Step 8: the **rebuild inside `uow.write`** is the mechanism; `MergePlan.fingerprint` names a stale plan rather than guarding it. Two tests: `a destination that gained a conflicting row between plan and apply is refused` and `a plan whose verdicts no longer hold is refused as stale and writes nothing` |
| Attachments keep the bytes-exist rule, expressed inside the plan | Task 1 decision 2 and 10, Step 4's attachment pass (locator check **before** bytes check, with the reason); `MergePlannerTest.an attachment row is SKIPPED when its bytes are not in the store, and INSERTed when they are` |
| No partial merge; deterministic conflict reporting with a stable code per kind | Task 1 decisions 11 and 13; `MergeReason`'s **fifteen** reason codes (sixteen members, `NONE` included); `MergePlannerTest.conflicts are reported in table order then id, and the plan is reproducible`, which runs the planner twice and compares both the decisions and the fingerprint, and `…the report accounts for every row exactly once` |
| Every uniqueness constraint the schema has is checked before mutation | Task 1 decision 6 and Step 4: the **five** unique indices — including `profile_field(profile_id, definition_id)` (`JournalEntities.kt:113`), which `BackupCodec.decode` does not check and which the product itself guards at `SaveProfile.kt:67` — and the **four** aggregate child-row primary keys, whose durable identity `core/model/Journal.kt:28`–`30` states. Six `MergePlannerTest` cases (`a definition whose key is taken`, `two definitions in one archive with the same key`, `a profile listing one definition twice`, `two profiles may each offer the same definition`, `a profile whose field id is held by another local profile`, `an event whose measurement id is held by another local event`), plus Task 6 Step 3's `unique = true` → 5 |
| #44 acceptance 12: verify size and SHA-256 before writing | Task 1 decision 11 and `storedBytesOf` (one streaming pass per locator, the same 64 KiB loop as `AttachmentSweep.kt:45`–`65`); `MergePlannerTest.an attachment whose stored bytes do not match the row is a CONFLICT` (both the hash and the size arm) and `…with no attachment folder every attachment row is SKIPPED with its own reason`; `ImportBackupMergeTest.an attachment row is written only when the store holds the bytes the row claims` covers all three answers end to end |
| A conflict found on rebuild is reported as a conflict, not as staleness | Task 1 decision 14 and `ApplyBackupMergePlan`'s order; `ImportBackupMergeTest.a destination that gained a conflicting row between plan and apply is refused as a conflict` and `…refused as stale and writes nothing` are the two halves, and Task 6 Step 3 greps the two guard lines **in order** |
| The conflict-resolution UI is out of scope; 1.1.0 ships the automatic union plus a report | Task 1's "Out of scope for 1.1.0" list, with #44's slice letters and the reason for each, including why the pre-merge safety snapshot is not needed yet |
| `POST /v1/import-merge/plan` returns the plan summary with per-table counts and the conflict list | Task 2's route table, Step 7 (`MergeReportResponse`), Step 8 (`importMergePlan`); `ApiRouterTest.importMergePlanReportsWhatWouldHappenAndWritesNothing` |
| `POST /v1/import-merge/apply` applies only a conflict-free plan, else 409 with the same body and no mutation | Task 2 Step 8 (`importMergeApply`, the `conflict()` helper); `ApiRouterTest.importMergeApplyIs409WithTheConflictListAndNoWrites` |
| The 4 MiB cap covers both import paths and nothing else | Task 2 Step 9 (`bodyCapFor`); `HttpWireTest.theImportPathsHaveTheirOwnCap`, `ApiRouterTest.onlyTheTwoImportPathsHaveTheBiggerCap` |
| MCP `import_merge(path, plan_only=False)` plans, and applies only when clean | Task 4 decision 10 and Step 7; three pytest cases (`…plans_first_and_applies_when_the_plan_is_clean`, `…plan_only_stops_after_the_plan`, `…never_applies_a_plan_with_conflicts`) |
| No UI for the merge in 1.1.0 | Task 3's file list touches no backup or import screen; Task 6 Step 3's `import-merge` grep finds it only in `ApiRouter.kt` |
| Owner ratifies every new user-visible string; listed in one block; nothing else changes | *Ratified before execution* (S1–S6); Task 3 Steps 3–4 are the only places they appear; `DeveloperApiListenerTest` asserts S1–S5 on a device, S6 absent while the listener runs and S6 present byte-exactly in `theScreenSaysSoWhenTheListenerCannotStart`, and `DeveloperApiViewModelTest` asserts S6's trigger on the JVM |
| `libs/nfc-tag-core/**` untouched at `7e0377a` | Global Constraints; Task 6 Step 3 (`git ls-tree`, submodule status, pin script) |
| No schema and no backup-format bump | Global Constraints; Task 6 Step 3 (`FORMAT_VERSION = 5`, `SCHEMA_VERSION = 5`, `app/schemas` unchanged) |
| The 2.6 tombstones untouched | Global Constraints; the merge inserts a new `externalLinks` row and never modifies one, and nothing displays a link. Note that `ApiHandlers` *does* hold `links` as one of its collaborators, for `GET /v1/status`'s row count and nothing else — Global Constraints forbids a link **endpoint**, and Task 6 Step 3's grep is written for that (`links\.\w+\(` inside `api/`, expecting only the one `links.all().size`) |
| The security minimums each map to a test | Global Constraints' eight numbered minimums, each with its test named; re-stated in Task 6 Step 8 |
| Commit style: single casual subject, no body, no trailers, author GonzRon, address derived | Global Constraints (the two-line `AUTHOR_EMAIL` form); the five commit steps; Task 6 Step 6 (`git log --oneline -5`, and the e-mail grep) |
| Device rule: `ANDROID_SERIAL=emulator-5554` on the same line; the phone never addressed | Global Constraints; every device command in Tasks 3, 4 and 6 carries it, including the MCP's own forward in Task 6 Step 7(d) |
| The JVM fixture convention for Room-backed view-model tests, quoted | Global Constraints, quoting `BackupViewModelTest.kt:63`–`69`, `TagWriteControllerTest.kt:39`–`42` and `TestDb.kt:9`–`15`, **and arguing the one documented exception** (a blocking router cannot be driven by a virtual clock) |
| Hygiene: no e-mail, no absolute home path, no device but emulator-5554, no tag UID, no note id | Global Constraints; Task 6 Step 6's five greps, plus one for a stray pairing code; `~` used for the home directory in Task 6 Step 2 |
| Exact file paths, Interfaces blocks, failing-test-first steps, exact commands, expected outputs | every task; each commit step is preceded by a `git add -A` staging step **before** the verification greps |
| Show the exact `libs.versions.toml` / `build.gradle.kts` lines for any new dependency | There is no new Gradle dependency: both files' dependency blocks are in the *File map* as UNTOUCHED, and the decision that makes that true is argued in full — **D8** |
| Show the exact `ci.yml` job | Task 4 Step 9 |
| Show the exact `AndroidManifest.xml` state | Task 3 Step 7 shows the file's first thirteen lines verbatim, with the owner's sentence as the comment; **R1** approved the line and **D1** is why it is unavoidable; Task 6 Steps 3 and 4 check the counts from both the source and the built APK |
| **R1** — the loopback bind, the fixed port, the peer check, the forward, the screen-bound lifetime, the per-session code, the bearer token, the empty 401, and no service / receiver / WorkManager / exported component / LAN bind | Global Constraints (the eight security minimums and the lifetime item); Task 2 Steps 3, 9 and 14; Task 3 Step 3; Task 6 Step 3's manifest and outbound greps |
| **R1** — no outbound networking introduced in 1.1.0 | Global Constraints security minimum 9; Task 6 Step 3 (`HttpURLConnection`/`OkHttp`/`URL`/`openConnection`/`WebView` → nothing, and `[^r]Socket(` → nothing) |
| **R1** — the owner's sentence in three places, verbatim | Global Constraints (quoted once, as the source of truth); Task 3 Step 7 (the manifest comment), Task 5 Step 3 (the README), Task 5 Step 4 (`docs/api/v1.md`); Task 6 Step 3 greps the long form of the sentence to exactly 1 line in each of the three files |
| **R2** — the MCP mirrors every API operation | Task 4's Interfaces block (21 names), decision 9, Step 7 (`archive_definition`, `archive_profile`), `test_the_archive_tools_post_their_flags`, the README's tool list, Task 6 Step 3's `"archive_definition"` grep |
| **S6** — the bind-failure line, and a test for it | Task 3 decision 5, Step 3 (`failedToStart`, the error-coloured `Text`), Step 5 (`DeveloperApiViewModelTest`, **on the JVM**, by occupying the port), and `DeveloperApiListenerTest` asserting it absent while the listener runs and present byte-exactly in `theScreenSaysSoWhenTheListenerCannotStart` |
| Controller proofs mirroring the 2.8 plan's Task 4 | Task 6 Steps 1–6: gate from scratch (both halves), connected suite with the preserved set staged, seventeen structural greps in three groups, `aapt2` badging, dry run with the fingerprint read from the evidence file, hygiene |
| No absolute home path in the plan | Global Constraints' hygiene item: `/home/` and `/Users/` appear only as the two hygiene grep *patterns* (Task 4 Step 12, Task 6 Step 6) and in the two sentences that account for them; every real path uses `~` |
| The external facts are cited | *Where the external facts in this plan were read*: the setup-uv release and tag SHA, and the `mcp` SDK 2.x rename, each with the `gh api` command a reviewer runs to check it |
| Plus an end-to-end proof on the emulator: install, open the screen, read the code with `uiautomator dump`, `create_asset` then `get_asset`, assert the round trip | Task 6 Step 7, (a) through (e), with the exact `dump`/`tap` helpers, the alphabet-constrained grep that extracts the code, the `uv run` block that pairs and round-trips, the independent UI confirmation that the Dashboard lists the row, and the refusal after leaving the screen |
| The phase-end sentence | Task 6 Step 8 |

**Placeholder scan:** no "TBD", no "TODO", no "implement later", no "add appropriate…", no "similar to Task N", and no code step without its code. Every test named in an expectation is written out in full in the step that creates it.

**Every type, function and file named in a later task is defined by an earlier one or exists in the tree at `0a582f8`.**

**New in this plan, with the task that creates it.** Task 1: `MergeTable`, `MergeVerdict`, `MergeReason` (sixteen members), `MergeHint`, `MergeDecision`, `DuplicateCandidate`, `MergeTally`, `MergeWrites`, `MergeSnapshot` (+ `storedBytes`, `attachmentStoreConfigured`), `MergeReport`, `MergePlan` (+ `conflicts`, `applicable`, `fingerprint`, `tally`, `report`), `mergePlanOf`, `storedBytesOf`, `mergeSnapshotOf`, the private `firstTaken`, `firstTakenPair` and two `ordered()` helpers, `MergeRefused`, `MergePlanStale`, `BuildBackupMergePlan`, `ApplyBackupMergePlan`, `ImportBackupMerge` (+ `plan`, `run`), `MergePlannerTest`, `ImportBackupMergeTest`. Task 2: `PAIRING_ALPHABET`, `PAIRING_CODE_LENGTH`, `newPairingCode`, `tokenMatches`, `API_VERSION`, `ApiJson`, `ApiErrorBody`, `ApiErrorDetail`, `ApiFailure` (+ `badRequest`, `notFound`, `methodNotAllowed`, `unsupportedMediaType`), `errorResponse`, `mapDomainFailure`, `ApiRequest`, `bearerToken`, `mediaType`, `ApiResponse` (+ `json`, `empty`), `MalformedRequest`, `parseRequest`, `writeResponse`, `isAcceptablePeer`, `SOCKET_TIMEOUT_MILLIS`, `StatusResponse`, `AssetListResponse`, `AssetResponse`, `DefinitionListResponse`, `DefinitionResponse`, `ProfileListResponse`, `ProfileResponse`, `EventListResponse`, `EventResponse`, `TagListResponse`, `MergeTallyDto`, `MergeDecisionDto`, `DuplicateCandidateDto`, `MergeReportResponse`, `toResponse`, `AssetCommandRequest` (+ `toCommand`), `RetireRequest`, `ArchiveRequest`, `SaveDefinitionRequest` (+ `toCommand`), `ProfileFieldRequest`, `ProfileConsumableRequest`, `SaveProfileRequest` (+ `toCommand`), `ConsumableRequest`, `EventRequest` (+ `toCommand`), `enumOr400`, `ApiHandlers` (+ its twenty-one handler methods and the private `asset`, `ok`, `createdResponse`, `conflict`, `requireZip`, `decode`), `MAX_BODY_BYTES`, `IMPORT_MERGE_PLAN_PATH`, `IMPORT_MERGE_APPLY_PATH`, `MAX_IMPORT_BYTES`, `ApiRouter` (+ `bodyCapFor`, `handle`, `route`), `DEVELOPER_API_PORT`, `LoopbackApiServer` (+ `start`, `stop`, `boundPort`, `boundAddress`, `requests`, its `readTimeoutMillis` parameter), `AppGraph.importBackupMerge`, `FakeGraph.importBackupMerge`, `PairingCodeTest`, `HttpWireTest`, `ApiRouterTest`, `LoopbackApiServerTest`. Task 3: `Route.DeveloperApi`, `DeveloperApiViewModel` (+ `pairingCode`, `requests`, `failedToStart`, `boundPort`, `listen`, `stopListening`), `DeveloperApiScreen`, `SettingsScreen`'s `onDeveloperApi` parameter, `DeveloperApiViewModelTest`, `DeveloperApiListenerTest`. Task 4: `servicetag_mcp`, `client.py` (`DEFAULT_PORT`, `BASE_URL_ENV`, `SERIAL_ENV`, `ADB_ENV`, `ApiError`, `NotPaired`, `Device` with `report_statuses`, `_detail`), `server.py` (`mcp`, `device`, `TOOL_NAMES`, `_body`, the twenty-one tools, `main`), `conftest.py` (`Recorded`, `FakeApi`, `api`, `paired`), `test_client.py`, `test_tools.py`, `pyproject.toml`, `.python-version`, `uv.lock`, `tools/servicetag-mcp/README.md`, the `mcp` CI job. Task 5: `docs/api/v1.md`.

**Existing, read in the tree at `0a582f8`:** `BackupCodec` (`FORMAT_VERSION`, `decode`, both `encode` overloads, `MANIFEST_ENTRY`, `DATA_ENTRY`), `Backup`, `BackupManifest`, `BackupData`, `AssetDto`, `NfcTagDto`, `ExternalLinkDto`, `MeasurementDefinitionDto`, `ProfileFieldDto`, `ProfileConsumableDto`, `EventProfileDto`, `MeasurementDto`, `ConsumableUsageDto`, `AssetEventDto`, `AttachmentDto`, every `toDto`/`toDomain` pair, `BackupException`, `BackupCorrupt`, `BackupNewerFormat`, `ArtifactsCodec`, `ExportBackupSet`, `ImportBackupReplace`, `ImportReport`, `ProvisionTag.begin`, `RestoreArtifacts`, `ArtifactsReport`, `StoreIsEmpty`, `AssetRepository`, `TagRepository`, `LinkRepository`, `DefinitionRepository`, `ProfileRepository`, `EventRepository`, `AttachmentRepository`, `AttachmentStorage`, `AttachmentStore` (`put`, `open`, `exists`, `delete`), `StoreState`, `StoreIoException`, `ByteSource`, `StoredBytes`, `UnitOfWork` (`write`, `read`), `Clock`, `IdGenerator`, `UuidGenerator`, `Asset`, `AssetStatus`, `AssetId`, `AssetTree` (`parentsFirst`, `children`, `descendants`, `wouldCycle`), `ExternalLink`, `LinkId`, `LinkKind`, `MeasurementDefinition`, `DefinitionId`, `DefinitionKind`, `ValueType`, `DerivedFormula`, `DerivedSpec`, `EventProfile`, `ProfileId`, `ProfileField`, `ProfileConsumable`, `AssetEvent`, `EventId`, `EventKind`, `EventSource`, `Measurement`, `ConsumableUsage`, `Attachment`, `AttachmentId`, `AttachmentKind`, `AttachmentMode`, `AttachmentOwner`, `AttachmentLocator`, `StorageProvider`, `StoredBytes`, `ProfileFieldEntity`, `AssetEntity`, `NfcTagEntity`, `ExternalLinkEntity`, `AttachmentEntity`, `MeasurementDefinitionEntity`, `EventProfileEntity`, `AssetEventEntity`, `TagBinding`, `TagId`, `TagStatus`, `TagTarget`, `PayloadFormat`, `AssetCommand`, `AssetProblem`, `AssetValidation`, `AssetCycle`, `AssetHasChildren`, `validateAsset`, `NoSuchAsset`, `CreateAsset`, `UpdateAsset`, `ArchiveAsset`, `RetireAsset`, `DeleteAsset`, `ApplyTemplate`, `UnknownTemplate`, `SeedTemplates`, `DefinitionCommand`, `DefinitionProblem`, `DefinitionValidation`, `DefinitionInUse`, `DefinitionReferenced`, `DefinitionWouldBreakDerived`, `DefinitionWouldBreakProfiles`, `NoSuchDefinition`, `SaveDefinition`, `ArchiveDefinition`, `slugify`, `ProfileCommand`, `ProfileFieldInput`, `ProfileConsumableInput`, `ProfileProblem`, `ProfileValidation`, `NoSuchProfile`, `SaveProfile`, `ArchiveProfile`, `EventCommand`, `ConsumableInput`, `FieldProblem`, `EventValidation`, `EventOwnership`, `NoSuchEvent`, `LogEvent`, `UpdateEvent`, `DeleteEvent`, `ResolveTag`, `BindTag`, `ProvisionTag`, `AppGraph` (`assets`, `tags`, `links`, `definitions`, `profiles`, `events`, `attachments`, `uow`, `ids`, `clock`, `prefs`, `attachmentStorage`, `thumbnails`, `db`, `appScope`, `tagIdentity`, `ndefCodec`, and every use-case member), `AppGraph.SCHEMA_VERSION`, `AppDatabase`, `RoomUnitOfWork`, the seven `Room*Repository` classes, `inMemoryDb`, `FakeGraph`, `FakeAttachmentStorage` (both the `:core` and `:app` ones), `InMemoryAssetRepository`, `InMemoryTagRepository`, `InMemoryLinkRepository`, `InMemoryDefinitionRepository`, `InMemoryProfileRepository`, `InMemoryEventRepository`, `InMemoryAttachmentRepository`, `InMemoryAttachmentStore`, `FakeUnitOfWork`, `BuildConfig` (`VERSION_NAME`, `APPLICATION_ID`, `NDEF_*`), `Route` (and `Dashboard`, `Assets`, `AssetDetail`, `AssetEdit`, `AssetSetup`, `DefinitionEdit`, `ProfileEdit`, `EventEntry`, `EventDetail`, `Scan`, `TagResult`, `WriteTag`, `Backup`, `Settings`), `TopLevelRoutes`, `Route.readsTags`, `Route.isSupported`, `ServiceTagRoot`, `BottomBar`, `SettingsScreen`, `BackupScreen`, `BackupViewModel`, `ScanScreen`, `TagResultSheet`, `WriteTagScreen`, `AssetDetailScreen`, `AssetsScreen`, `AssetEditScreen`, `AssetSetupScreen`, `DefinitionEditScreen`, `ProfileEditScreen`, `EventEntryScreen`, `EventDetailScreen`, `DashboardScreen`, `LabelValue`, `QuietLine`, `SectionHeader`, `ServiceTagIcons` (`Backup`, `Contactless`, `Speed`, …), `ServiceTagTheme`, `ControlShape`, `Eyebrow`, `MonoText`, `MeasurementHeroText`, `ReaderMode`, `MainActivity`, `NfcDispatchActivity`, `ServiceTagApp`, `AppPrefs`, `app`, `clearInstall`, `awaitText`, `exportedDataArchive`, `createComposeRule`, `createAndroidComposeRule`, `SettingsBackupEntryTest`, `RouteTest`, `PreservedSetRestoreTest`, `AppSmokeTest`, `NavigationSmokeTest`, `RemovedSurfacesTest`, `EmptyStoreRestorePromptTest`, `InspectNamesABoundTagTest`, `InspectBackDismissesTheAnswerTest`, `PreSplitLinkTagSheetTest`, `ReadScopedSheetOwnerTest`, `ReaderModeHoldTest`, `DashboardSearchTest`, `TestCoroutineScheduler`, `UnconfinedTestDispatcher`, `StandardTestDispatcher`, `tools/release-dry-run.sh`, `tools/check-submodule-pin.sh`, `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `docs/versioning.md`, `docs/architecture/product-split-evidence.md`.









