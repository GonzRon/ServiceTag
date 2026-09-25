# Phase 1a relevance audit: #66 and #51

Read-only audit, 2026-09-25, against master `6a7a161` (1.4.0 released, versionCode 16). Nothing was
edited, committed or changed on GitHub. On the devices, only `getprop`, `dumpsys package` and
`pm list permissions` were read. Paths are relative to the repository root; `app/.../` means
`app/src/main/kotlin/com/loosecannon/servicetag/`.

## Platform observations

The two attached devices were the **development phone** and the **emulator**. The phone was
identified by its software state: ServiceTag 1.4.0 / versionCode 16, and a `firstInstallTime` that
matches the 1.4 ledger's R7 record. The emulator runs Google's stock `sdk_gphone16k_x86_64` image.
The production phone was not attached. What this audit says about it comes from the issue's own
record of 2026-09-24.

| Fact | Development phone | Emulator (stock image) |
|---|---|---|
| `ro.build.version.sdk` / release / `sdk_full` | 37 / 17 / 37.0 | 37 / 17 / 37.1 |
| Build identity | build number `2026091901` (date-stamped, also used as `ro.build.display.id`); system fingerprint `Android/generic_system/…` | Google build ID `CP31.260623.012` |
| `dumpsys package permission android.permission.INTERNET` | `gids=[3003] prot=dangerous\|instant` | `gids=[3003] prot=normal\|instant` |
| INTERNET in `pm list permissions -g -d` (dangerous list) | **yes**, under `android.permission-group.UNDEFINED` | **no** (ungrouped, not dangerous) |
| Extra platform definitions | `android.permission-group.NETWORK`, `android.permission-group.OTHER_SENSORS`, dangerous `android.permission.OTHER_SENSORS` | none of the three |
| ServiceTag's INTERNET record | under **runtime** permissions: `granted=true, flags=[ USER_SET\|USER_SENSITIVE_WHEN_GRANTED\|USER_SENSITIVE_WHEN_DENIED]`; user gids `[3003]` | ServiceTag is not installed (only the test sender is). A preinstalled Google app targeting 36 shows INTERNET under **install** permissions, `granted=true` |
| `ACCESS_LOCAL_NETWORK` | defined `prot=dangerous`; the OS adds it to ServiceTag's requested list and grants it implicitly (`REVOKE_WHEN_REQUESTED`) | same definition, and the same implicit grant on the Google app |
| ServiceTag's requested list | the APK's 8 (NFC, INTERNET, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, plus the library-merged FOREGROUND_SERVICE, WAKE_LOCK, ACCESS_NETWORK_STATE and AndroidX's signature-level DYNAMIC_RECEIVER_NOT_EXPORTED permission) + 2 the OS adds (ACCESS_LOCAL_NETWORK, OTHER_SENSORS) | n/a |

What these facts establish:

- **Stock Android 17 keeps INTERNET a normal, install-time permission.** The emulator's API 37.1
  platform defines it `normal|instant`, so the user cannot revoke it. It has been a normal permission
  on stock Android since API 23's runtime model, and below that every permission was granted at
  install. That history comes from platform knowledge, not from this audit; only the API 37 image was
  read. With `minSdk = 26` (`app/build.gradle.kts:41`), no supported stock release can deny it.
- **The user-revocable INTERNET comes from the phones' OS build, not from Android 17.** That build
  redefines INTERNET as `dangerous`, and it carries a NETWORK permission group and an OTHER_SENSORS
  permission that the stock image lacks. Those names match the Network and Sensors toggles of a
  hardened AOSP-based OS (a hardened Android distribution). That identification is an inference from the permission names
  and the shape of the build number; no OS-name property was read.
- **The production phone must run the same kind of build.** The issue records INTERNET there as
  `granted=false` with `USER_SENSITIVE_*` flags. That is the shape of a runtime-permission record, which
  can exist only where INTERNET is a runtime permission. How it came to be denied there is not
  observable from here: the owner sideloaded that install, while the development phone was installed
  over adb and has INTERNET granted with `USER_SET`.
- **Not observed, and so not asserted:**
  - whether that OS shows a dialog for `requestPermissions(INTERNET)`. On the phone INTERNET's
    platform group is `UNDEFINED`, and the NETWORK group lists no members.
  - whether `checkSelfPermission(INTERNET)` reports the true state there.
  - which exception and errno the bind throws when the permission is denied. The 2026-09-24 record
    kept only the log line.
  - whether a grant made in Settings takes effect for a process that is already running.

  Proving any of these needs a revoke on a phone, which this audit may not do.
- **`ACCESS_LOCAL_NETWORK` does not bear on #66 today.** For an app that targets 36 and requests
  INTERNET, Android 17 adds this permission and grants it implicitly, on both devices. It belongs to
  the `targetSdk 37` move (Phase 7, `app/build.gradle.kts:42`–`44`). Whether a loopback bind needs it
  at 37 is not verified here.

---

## #66: Developer API, handle Android 17 INTERNET permission

### Classification

**STILL RELEVANT BUT NEEDS REFRAMING**

### Original purpose

This is what was observed on 2026-09-24, on the production phone running signed 1.3.0:

- `android.permission.INTERNET` was `granted=false`.
- `LoopbackApiServer.start()` could not bind, and the log carried
  `the developer API could not bind its port`.
- Every MCP call failed with "the Developer API is not answering".
- Per the issue, nothing in the app told the owner why.
- The workaround was `pm grant` for the load and `pm revoke` afterwards.

The owner asked that the app request every permission it needs. The issue proposed three things:

1. A runtime request when the Developer API screen opens.
2. On a refusal, an explanation plus `ACTION_APPLICATION_DETAILS_SETTINGS`.
3. A permission inventory in `docs/api/v1.md` and in the README's install notes.

It offered two placements: a carry-in to 1.4.0's API brief, or a 1.3.1 PATCH.

### Current reality (evidence)

- **Nothing has changed since the issue was filed.** The screen and the view model last changed
  behaviour in `531c449` (2026-09-21). The server's last behaviour change was `ef0a346` (2026-09-21).
  Later commits that touch these files (`0a77de2`, `bbb9bcf`, `fc8cb40`) change only test fakes and
  comments. 1.4.0 shipped the same behaviour.
- **The cause of a bind failure is thrown away.** `LoopbackApiServer.start()`
  (`app/.../api/LoopbackApiServer.kt:85`–`101`) catches `IOException` around the inline
  `ServerSocket(...)` at `:89` and returns `false` at `:90`–`92`, discarding the exception. Downstream,
  "port already taken" and "network permission denied" therefore look the same. A denied socket
  creation surfaces as a `SocketException`, which is an `IOException`. It lands in this catch rather
  than crashing, which matches the log line quoted in the issue.
- **The screen does show a message, but it names no cause and gives the wrong remedy.**
  `DeveloperApiViewModel.listen()` (`app/.../ui/api/DeveloperApiViewModel.kt:61`–`68`) sets
  `failedToStart = !bound` and logs its one line on that same `false`. `DeveloperApiScreen.kt:87`–`95`
  then draws **S6** in the error colour: "The Developer API could not start. Leave this screen and open
  it again." The issue's claim that the screen "shows the code and nothing else" is therefore wrong.
  The real deficiency is that S6 names no cause, and its remedy (leave and reopen) cannot fix a denied
  permission.
- **Nothing detects or requests INTERNET.** Nothing under `ui/api/` calls `checkSelfPermission`, a
  request contract or a settings intent.
  - The only runtime-permission plumbing in the app is `NotificationPermission`
    (`app/.../reminders/NotificationPermission.kt:39`–`49` for the interface, `:84` onward for
    `AndroidNotificationPermission` using `ActivityResultContracts.RequestPermission`). It is reached
    only from the schedule editor and the maintenance models.
  - A settings-redirect precedent does exist: `ReminderHealthScreen.kt:202` (`notificationSettings`),
    `:210` (`appDetails` = `ACTION_APPLICATION_DETAILS_SETTINGS`) and `:217` (`Context.open`, which
    swallows `ActivityNotFoundException`). All three are private to that file.
- **The docs state a stock-only fact as if it held everywhere.**
  - The comment at `app/src/main/AndroidManifest.xml:4`–`8` says INTERNET "is a normal, install-time
    permission with no runtime prompt".
  - `docs/api/v1.md:28`–`34` gives the same reason for the permission. Its **If it cannot start**
    paragraph (`:35`–`39`) names only a taken port.
  - `README.md:156`–`160` repeats the claim.
  - The README has no install-notes section, and no permission inventory exists anywhere.
- **The MCP client's message points the owner to the phone.**
  `tools/servicetag-mcp/src/servicetag_mcp/client.py:38`–`42` says to open the Developer API screen.
  That is where the cause should be explained, so this needs no change beyond an optional hint.
- **Tests cover only the port-in-use failure.**
  `DeveloperApiViewModelTest.listeningOnAnOccupiedPortReportsTheFailure` (`:68`–`82`) and
  `DeveloperApiListenerTest.theScreenSaysSoWhenTheListenerCannotStart` (androidTest `:186`–`219`) both
  squat the port. No seam lets a test inject any other bind failure.
- **The platform attribution is wrong** (see Platform observations). Stock Android 17 cannot deny
  INTERNET. The revocable INTERNET belongs to the phones' OS build, so "Android 17" in the title is
  the wrong cause.
- **Both proposed placements have passed.** 1.4.0 is released, and a 1.3.1 would now sit behind it.

### Remaining work

1. **Keep the cause of a bind failure.** `start()` should return a typed outcome instead of a
   `Boolean`, at least *port in use* / *network permission denied* / *other*. The outcome is classified
   from the caught exception (the `ErrnoException` cause: `EADDRINUSE` against `EACCES`/`EPERM`), and
   may be corroborated by `checkSelfPermission(INTERNET)`. On stock Android the denied branch can never
   fire, so behaviour there does not change.
2. **Explain the denial on the screen.** The screen names the denial and offers the app's settings
   page (`ACTION_APPLICATION_DETAILS_SETTINGS`), reusing ReminderHealthScreen's `appDetails`/`open`
   pattern hoisted into a shared helper. A taken port keeps S6.
3. **Do not add a runtime request unless one is proven to work.** A request when the screen opens
   should ship only after the development phone proves that it shows a dialog and that it re-grants
   (revoke under the snapshot/restore rule). Until then the Settings redirect is the dependable route.
   The same proof must also show whether a grant made in Settings reaches the running process.
   - If it does, `LifecycleResumeEffect` already re-runs `listen()` on the way back from Settings.
   - If it does not, the new sentence must also say to restart the app.
4. **Correct the docs and write the inventory.** Fix the manifest comment, `v1.md:28`–`39` and
   `README.md:156`–`160` so they say: install-time on stock Android, user-revocable on some builds, and
   needed only by the Developer API. Add a short **Permissions** subsection to the README (it has no
   install notes today) listing:
   - POST_NOTIFICATIONS: a runtime permission on API 33+, asked on first schedule creation.
   - NFC and RECEIVE_BOOT_COMPLETED: install-time.
   - The attachments folder: a SAF tree grant from the folder picker, not a permission.
   - The library-merged permissions.
   - The implicit ACCESS_LOCAL_NETWORK on Android 17.
5. **Ratify the new strings.** Two need ratifying: the denial sentence and the settings button. The
   rationale string the issue anticipated is not needed unless item 3 proves a request works.
6. **Tests.**
   - JVM: the outcome classification (a pure function over the exception), and the view model mapping
     outcome to state.
   - Compose instrumented: the denied state renders the sentence and the button. The failure has to be
     injected, because the stock emulator cannot deny INTERNET (its definition is `normal`). That needs
     a bind or `ServerSocket`-factory seam at `LoopbackApiServer.kt:89`.
   - One development-phone proof, gated on the owner. It records the real exception and answers
     item 3, with a snapshot taken first. Its unique OS boundary is the build's revocable INTERNET.

### Size + files

**Small.** Production: `app/.../api/LoopbackApiServer.kt` (typed outcome and bind seam),
`app/.../ui/api/DeveloperApiViewModel.kt`, `app/.../ui/api/DeveloperApiScreen.kt`, and a shared
settings-intent helper hoisted from `app/.../ui/maintenance/ReminderHealthScreen.kt:202`–`224`.
Changes elsewhere are to comments and docs only: `app/src/main/AndroidManifest.xml`, `docs/api/v1.md`,
`README.md`. Tests: `LoopbackApiServerTest`, `DeveloperApiViewModelTest`, `DeveloperApiListenerTest`.
Nothing changes in the API contract, the schema or the manifest's declarations.

### Dependencies / combination

See **Combined pass** below. #66 and #51 edit the same three production files and the same three
test classes, and both change the screen's single failure state.

### Recommendation

**GitHub disposition: rewrite-narrow.**

- Retitle it to something like "Developer API: explain a denied network permission and link to app
  settings".
- In the body, correct the attribution: the cause is an OS build where INTERNET is revocable, not
  Android 17.
- Correct "shows the code and nothing else": S6 is shown, but it names no cause and gives the wrong
  remedy.
- Make proposal 1 conditional on the phone proof.
- Keep proposal 2, adding the typed start outcome.
- Recast proposal 3 as a docs correction plus the inventory.
- Drop the placement section.
- Execute it together with #51.

---

## #51: Developer API screen, protect pairing code and report dead listener

### Classification

**STILL RELEVANT AS WRITTEN** (both halves are open; the brief should carry two precision notes).

### Original purpose

Both findings are nits from the 1.1.0 Task 3 review (`.superpowers/sdd/2026-09-21-servicetag-1.1.0-automation-api/task-3-review.md`,
findings 4 and 5), which the owner deferred.

- **(a) Recents.** The window sets no `FLAG_SECURE`, so the pairing code (drawn at 30sp) can be read
  from the recents thumbnail. The issue asks to set the flag while the screen is resumed and to prove
  it with a connected test.
- **(b) Dead listener.** `acceptLoop` returns on *any* `IOException` from `accept()`, not only after
  `stop()`. The worker then dies while the `ServerSocket` stays open, and the screen goes on showing
  port and code as if the API were up. The issue asks to show S6 or restart, and to add a test that
  kills the loop.

### Current reality (evidence)

**(a) Recents and screenshots are still unprotected.**

- A grep of `app/src/main` and `app/src/androidTest` finds no `FLAG_SECURE`,
  `setRecentsScreenshotEnabled`, `WindowManager.LayoutParams`, window `addFlags`/`setFlags`, or
  `SecureFlagPolicy`. The only matches are `Intent` flags.
- No theme resource mentions "secure".
- `MainActivity.onCreate` (`MainActivity.kt:37`–`40`) only enables edge-to-edge and sets the content.
- The app has a single activity, so the Developer API screen shares MainActivity's window. The screen
  therefore has to set and clear the flag itself.
- `DeveloperApiScreen.kt:97`–`101` still draws the code with `MeasurementHeroText`.

This half is fully open.

**(b) The dead listener.**

- **The per-connection catch predates the issue.** The `catch (t: Throwable)` in `acceptLoop`
  (`LoopbackApiServer.kt:137`–`141`) was not added later. A pickaxe search for it finds only the
  original listener commit, `247ab40` (2026-09-21), a day before #51 was filed (2026-09-22). Two later
  commits refined the loop: `22a8bf4` (the review S1 compare-and-clear of `inFlight`, `:142`–`151`) and
  `ef0a346` (a wall-clock limit on the drain). Nothing a single connection throws can kill the worker:
  not a hung-up client, not a handler's `RuntimeException`, not an out-of-memory error on a body. #51
  was filed knowing this; its scenario is the *other* catch.
- **Exactly one silent exit remains.** It is `acceptLoop`'s `catch (e: IOException) { return }` around
  `bound.accept()` (`LoopbackApiServer.kt:129`–`133`), taken when `accept()` fails while the socket is
  still open (not a `stop()`). Candidates are file-descriptor exhaustion (`EMFILE`/`ENFILE`),
  `ENOMEM`/`ENOBUFS` and `ECONNABORTED`. On that exit:
  - the worker thread ends;
  - `socket` stays non-null, because only `stop()` nulls it (`:117`–`125`), so `boundPort` (`:80`)
    still reads 17337;
  - the kernel keeps completing up to `ACCEPT_BACKLOG` (4) handshakes that nobody reads.
- **Nothing tells the screen.** `DeveloperApiViewModel` exposes only `failedToStart`, which is set
  solely from `start()`'s return value (`:61`–`68`), and `requests`. It has no listening or health
  state. The screen keeps showing port and code without S6. The only signs are that "Requests this
  session" stops moving and that the MCP client times out.
- **No other path leaves a dead listener looking healthy:**
  - Anything other than an `IOException` from `accept()` (an out-of-memory error, say) goes uncaught on
    that thread, and Android's default handler crashes the process.
  - A close started by `stop()` is the intended exit.
  - A pause and resume already recovers: `stop()` nulls the socket and `start()` binds afresh. S6's
    existing advice ("Leave this screen and open it again") is therefore the correct remedy for this
    case.
- **It is rarely reachable.** The listener handles one connection at a time and closes each one
  (`client.use`), so it cannot exhaust file descriptors by itself. The failure needs the rest of the
  process to be at its descriptor limit, or the kernel to fail an accept. It is reachable in principle,
  and the fix is cheap hardening.
- **No test covers it.**
  - `LoopbackApiServerTest` covers a stopped listener refusing connections
    (`stopRefusesFurtherConnections`, `:129`), half-open and trickling clients (`:173`, `:216`), and a
    stop during a request (`:292`).
  - `DeveloperApiViewModelTest` covers only bind outcomes.
  - `DeveloperApiListenerTest` covers the screen shown and answering, code acceptance, and the
    cannot-start case.
  - Nothing can make `accept()` throw, because the `ServerSocket` is built inline at `:89`.

### Remaining work

- **(a)** Set `FLAG_SECURE` on the activity window while the Developer API screen is **in
  composition**, and clear it when the screen is disposed.
  - *Precision note:* the issue says "while resumed", but clearing the flag on pause would race the
    recents snapshot that the system takes as the task leaves the foreground. Tie the flag to the
    screen's composition lifetime (a `DisposableEffect`), not to `LifecycleResumeEffect`'s pause edge.
  - Side effect to accept: the screen also goes black in screenshots and screen recordings, including
    `adb screencap` evidence.
  - `setRecentsScreenshotEnabled(false)` needs API 33+ (`minSdk` is 26) and does not cover screenshots,
    so `FLAG_SECURE` is the portable choice.
  - Proof: a Compose instrumented assertion on `rule.activity.window.attributes.flags`, set while the
    screen is shown and clear after leaving it. The class already uses
    `createAndroidComposeRule<ComponentActivity>()`.
  - No new string.
- **(b)** When the accept loop exits with `!bound.isClosed`, close and null the socket (so `boundPort`
  reads 0) and publish a terminal "stopped" state.
  - The view model folds that state into the screen's failure state, and the screen shows S6. Its
    remedy already fits, so no new string is needed.
  - Showing S6 is simpler and more honest than restarting in place.
  - Tests: a seam that makes `accept()` throw (a `ServerSocket` factory), used by a JVM case in
    `LoopbackApiServerTest` (the state flips and a later connect is refused) and a case in
    `DeveloperApiViewModelTest`.
  - Optionally, one sentence ("or stops") in `v1.md`'s **If it cannot start**.

### Size + files

**Small.** (a) is tiny: `app/.../ui/api/DeveloperApiScreen.kt` and one case in
`DeveloperApiListenerTest`. (b) is small: `app/.../api/LoopbackApiServer.kt` (terminal state and seam),
`app/.../ui/api/DeveloperApiViewModel.kt`, `app/.../ui/api/DeveloperApiScreen.kt`,
`LoopbackApiServerTest`, `DeveloperApiViewModelTest`, and optionally `docs/api/v1.md:35`–`39`.

### Dependencies / combination

These are the same files as #66, and both halves change what the screen's single failure state means
(see below).

### Recommendation

**GitHub disposition: leave as-is.** Keep it as its own record and execute it in the combined pass
with #66. The two precision notes (the flag lasts for the screen's composition, and only the narrow
`accept()` path remains) belong in the brief, not in an edit to the issue.

---

## Combined pass (#66 + #51, with #52 as a sibling)

One brief, "the Developer API screen tells the truth about its listener", would do all of the
following:

- **`LoopbackApiServer`**
  - One injectable bind or `ServerSocket` factory, which the tests for both issues need.
  - `start()` returns a typed outcome (bound / port in use / network denied / other) instead of a
    `Boolean` (#66).
  - A `StateFlow` that goes terminal when the accept loop exits with the socket still open, closing and
    nulling the socket (#51 b).
- **`DeveloperApiViewModel`**: replace `failedToStart: StateFlow<Boolean>` with one listener-status
  flow that folds both (listening / could not start *(reason)* / stopped). The single `Log.w` stays
  single and still carries no token, path or body.
- **`DeveloperApiScreen`**
  - Maps status to text: S6 for a taken port and for a stopped listener; the new ratified sentence and
    the settings button for a denied permission.
  - Holds `FLAG_SECURE` for its composition lifetime (#51 a).
- **Tests**: one extension to each of the three existing classes, and no boundary journey. For #66
  only, one development-phone proof gated on the owner, for the real denial.
- **Docs, edited once**: the `v1.md` transport paragraphs (the permission, and **If it cannot start**),
  the README's permission paragraph and new inventory, and the manifest comment.
- **Strings**: one ratification round covering #66's sentence and button; #51 reuses S6.

**#52 (readable 422 problems) is a sibling, not part of the merge.**

- **Different code path.** The strings come from `ApiJson.kt:205`–`216`
  (`e.problems.map { it.toString() }`), and the change also touches the domain's problem types, the
  MCP pass-through and `v1.md`'s **Errors** section.
- **No Kotlin overlap.** #52 shares no Kotlin file with the pair above.
- **What it does share** is `docs/api/v1.md` (a different section) and an MCP release.
- **Scheduling.** Put it in the same release as a separate brief or lane, and give one lane ownership
  of `v1.md` (or split ownership by section) so that each section is edited once.

**Versioning**

- **#66 and #51** are fixes, so PATCH-class.
- **#52** keeps the envelope but changes what each `problems` entry contains. Its class is the owner's
  call under the SemVer policy, and it sets the release number if it is bundled with the other two.
