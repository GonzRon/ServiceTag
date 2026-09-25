# B03 — the Developer API screen tells the truth about its listener (#66 + #51)

**Read first:** plan.md §2–§4, §6, §8; `audit/audit-66-51.md`; the rulings on #66 and #51; #66's body and the #51 comment.
**Lane:** third, branched from master after B02 merged. B04 may run beside it (plan.md §2).
**Blocked on:** the owner ratifying P1A-1 and P1A-2 (plan.md §4).

## Goal

`LoopbackApiServer.start()` collapses every bind failure to `false`, so the screen draws S6 whatever the cause, and S6's remedy cannot fix a network permission revoked on the owner's hardened Android build. **#66:** `start()` returns a typed outcome; a denied permission gets P1A-1 and a P1A-2 button to the app's settings; a taken port or any other failure keeps S6. **#51 (a):** `FLAG_SECURE` for the screen's whole **composition**, so the pairing code never reaches Recents or a screenshot. **#51 (b):** an `accept()` failure on a socket `stop()` did not close is terminal — the socket is closed and forgotten, the state published, S6 shown. **Docs:** the manifest comment, `v1.md` and the README stop claiming INTERNET cannot be denied; the README gains a permission inventory.

**No runtime `requestPermissions(INTERNET)`.** Any device permission proof is owner-controlled and outside this brief: stop before anything that would change a phone's permission state.

## Files

**Modify:** `app/src/main/kotlin/com/loosecannon/servicetag/api/LoopbackApiServer.kt`; `app/.../ui/api/DeveloperApiViewModel.kt`, `DeveloperApiScreen.kt`; `app/.../ui/maintenance/ReminderHealthScreen.kt` (hoisted helpers only, behaviour unchanged); `app/src/main/AndroidManifest.xml` (the INTERNET comment only); `docs/api/v1.md` (`## Where it is, and when` only); `README.md`; tests `LoopbackApiServerTest`, `DeveloperApiViewModelTest` (JVM) and `DeveloperApiListenerTest` (connected).

**Create**
- `app/.../ui/components/SystemSettings.kt`, holding the `internal` helpers `appDetails` and `Context.open`, hoisted from `ReminderHealthScreen.kt:210` and `:217`.

**Untouched:** `tools/servicetag-mcp/**`, the rest of `v1.md`, `ApiJson.kt` and the routes, `AppGraph.kt`, `MainActivity.kt`, `ServiceTagRoot.kt` (its call stays valid), every manifest declaration.

## Interfaces

Names are binding; placement within the files is the implementer's.

```kotlin
internal sealed interface StartOutcome {
    data object Bound : StartOutcome
    data object PortInUse : StartOutcome
    data object NetworkPermissionDenied : StartOutcome
    data object Other : StartOutcome
}
internal sealed interface ListenerState {
    data object Stopped : ListenerState                                 // never started, or stop() ran
    data object Listening : ListenerState
    data class CouldNotStart(val outcome: StartOutcome) : ListenerState // outcome != Bound
    data object Died : ListenerState                                    // accept() failed; stop() did not close it
}
internal enum class BindErrno { ADDRESS_IN_USE, ACCESS_DENIED, OTHER, UNKNOWN }
internal fun classifyBindFailure(errno: BindErrno, isBindException: Boolean, permissionGranted: Boolean): StartOutcome
```

**`LoopbackApiServer`** gains a bind seam (`javax.net.ServerSocketFactory`, default `getDefault()`) and `networkPermissionGranted: () -> Boolean`, exposes `state: StateFlow<ListenerState>`, and `start()` returns `StartOutcome`. **The errno adapter** (thin, in `api/`) finds an `android.system.ErrnoException` in the cause chain: `EADDRINUSE` → `ADDRESS_IN_USE`, `EACCES`/`EPERM` → `ACCESS_DENIED`, another errno → `OTHER`, none → `UNKNOWN`.

**`DeveloperApiViewModel`**
- `listener: StateFlow<ListenerState>` (the server's `state`) replaces `failedToStart`;
- the `(graph, …)` constructor takes the permission probe with **no default**. The screen builds it from `ContextCompat.checkSelfPermission(applicationContext, INTERNET)`.

**`DeveloperApiScreen(graph, onBack, onOpenAppSettings: (() -> Unit)? = null)`.** When the callback is null, the button opens `appDetails` through the hoisted `open`, which swallows `ActivityNotFoundException`.

**`developerApiNotice(state)`.** A pure mapping from state to one of three notices: none, S6, or P1A-1 with P1A-2.

## Contracts

**Classification is conservative.** The rules are applied in order:
1. `ADDRESS_IN_USE` → `PortInUse`.
2. Permission not granted, with `ACCESS_DENIED` or `UNKNOWN` → `NetworkPermissionDenied`.
3. `UNKNOWN` with a `java.net.BindException` → `PortInUse`. This is the JVM's shape; Android always carries the errno.
4. Anything else → `Other`.

A permission that reads as granted never produces the denial sentence, so on stock Android it never shows.

- **`start()`** is idempotent (`Bound` while `Listening`), catches `IOException` **and `SecurityException`** and nothing broader, and publishes `Listening` or `CouldNotStart(outcome)`.
- **`stop()`** always publishes `Stopped`, even with the socket already gone, so a pause clears the notice (1.1.0: the message belongs to a visit).
- **`accept()` failure:** under the server's lock, if `socket === bound` the worker closes and nulls it (`boundPort` reads 0) and publishes `Died`; otherwise it exits silently. A stale worker never closes, nulls or re-labels a later generation (review S1's compare-and-clear, applied to the socket). **No restart** after `Died`: S6's leave-and-return is the remedy.
- **Logging:** the one `Log.w` line stays single and verbatim, for any `CouldNotStart`, never carrying a token, path or body.
- **Screen:** `PortInUse`, `Other` and `Died` → S6; Denied → P1A-1 in the error colour with a `TextButton` P1A-2 beneath; the three rows stay in every state (1.1.0 decision 5). Returning from Settings re-runs `listen()` through the existing resume effect.
- **`FLAG_SECURE`** is added to the `LocalActivity` window on entering composition and cleared in the same `DisposableEffect`'s `onDispose` — never tied to `LifecycleResumeEffect`, because clearing on pause races the Recents snapshot. Nothing else in the app touches the flag.

## Docs

- **Manifest comment and `v1.md`'s permission bullet.** INTERNET is install-time on stock Android and revocable on some hardened builds. The screen explains a denial and links to the app's settings. There is no runtime request. The `v1.md` bullet also points to the README inventory.
- **`v1.md`, If it cannot start.** A taken port, or a listener that stops, shows S6. A denied permission shows P1A-1 and the button.
- **`README.md`.** Correct lines 156–160; add `### Permissions` before `## Building`: notifications (asked at the point of need since 1.2); NFC and RECEIVE_BOOT_COMPLETED (no runtime request); INTERNET (as above, used only by the Developer API); the attachments folder (a folder-picker grant); a shared file (a temporary read grant); the library-merged FOREGROUND_SERVICE, WAKE_LOCK, ACCESS_NETWORK_STATE and DYNAMIC_RECEIVER_NOT_EXPORTED; ACCESS_LOCAL_NETWORK (implicit on Android 17, never requested). Name no distribution and no device.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| wrong cause, wrong advice | `LoopbackApiServerTest` · `theBindFailureTableIsConservative` (every combination) | granted + `ACCESS_DENIED` → Denied |
| taken port | `LoopbackApiServerTest` · `aTakenPortIsPortInUse` (real squatter) | drop rule 3 |
| denial through the seam | `LoopbackApiServerTest` · `aDeniedBindIsDeniedOnlyWhenThePermissionSaysSo`: seam throws `SocketException`; probe false → Denied, probe true → Other | ignore the probe |
| a crash on opening | `LoopbackApiServerTest` · `aSecurityExceptionFromTheBindIsClassified` | catch only `IOException` |
| double start | `LoopbackApiServerTest` · `startWhileListeningBindsNothingNew` | always rebind |
| dead listener (#51) | `LoopbackApiServerTest` · `anAcceptFailureOnAnOpenSocketIsTerminal`: `Died`; `boundPort` is 0; a later connect is refused | restore the bare `return` |
| stop read as death | `LoopbackApiServerTest` · `anAcceptFailureAfterStopIsSilent` | publish `Died` without the identity check |
| stale generation | `LoopbackApiServerTest` · `aStaleWorkerCannotTouchTheNextGeneration`: generation 2 stays `Listening` and answers `/v1/status` | null `socket` unconditionally |
| model wiring | `DeveloperApiViewModelTest` · `listeningOnAnOccupiedPortReportsTheFailure` (adapted; then `stopListening` → `Stopped`), `aDeniedPermissionReachesTheModel`, `aDeadListenerReachesTheModel` | keep the Boolean |
| notice mapping | `DeveloperApiViewModelTest` · `theNoticeForEveryState` | `Died` → none |
| real errno on Android | `DeveloperApiListenerTest` · `thePlatformsOwnBindFailureCarriesAddressInUse`: the adapter over a real squat gives `ADDRESS_IN_USE` | the adapter always returns `UNKNOWN` |
| S6 kept | `DeveloperApiListenerTest` · `theScreenSaysSoWhenTheListenerCannotStart` (adapted) | P1A-1 for `PortInUse` |
| denial UI | `DeveloperApiListenerTest` · `aDeniedPermissionExplainsAndOffersSettings`: P1A-1 and P1A-2 shown; S6 absent; rows kept; one click → one callback | S6 for Denied |
| dead listener UI | `DeveloperApiListenerTest` · `aListenerThatDiesShowsS6` | ignore `Died` |
| Recents (#51) | `DeveloperApiListenerTest` · `thePairingCodeIsSecureForTheWholeComposition`: flag set while shown, **still set** after `moveToState(STARTED)`, clear after leaving composition | tie the flag to resume/pause; never clear it |
| settings target | `DeveloperApiListenerTest` · `theSettingsButtonOpensThisAppsDetailsPage`: action `ACTION_APPLICATION_DETAILS_SETTINGS`, data `package:<packageName>`, resolves | the notification-settings action |

**JVM trap:** `ErrnoException` and `OsConstants` are stubs on the JVM (`isReturnDefaultValues = true`; every errno reads 0), so no JVM test fabricates an `ErrnoException`; the adapter is proven only by the connected case.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain`: zero failures and zero skips.
- `./gradlew :app:compileDebugAndroidTestKotlin`.
- Connected, on `emulator-5554` only, one class per invocation: `com.loosecannon.servicetag.ui.api.DeveloperApiListenerTest`, then `com.loosecannon.servicetag.ui.maintenance.ReminderHealthScreenTest`.
- Anchored `git grep -nE` over `app/src/main`:
  - `'"The Developer API could not start\. Leave this screen and open it again\."'` → 1 line;
  - `'"ServiceTag is not allowed to use the network'` → 1 line;
  - `'"Open app settings"'` → 1 line;
  - `'(addFlags|clearFlags)\(.*FLAG_SECURE'` → 2 lines, both in `DeveloperApiScreen.kt`;
  - `'requestPermissions|RequestPermission\('` → the same as at the base.
- `git diff <base> -- tools core app/src/main/kotlin/com/loosecannon/servicetag/di` → empty; the `assert(` sweep over `app/src/androidTest` → no output.

## Strings

S6 as plan.md §4 defines it (1.1.0's), and P1A-1 / P1A-2 as ratified, all verbatim; nothing else. A state that seems to need words means NEEDS_CONTEXT.

## Must NOT

- request INTERNET at runtime, add a rationale, or change a manifest declaration;
- run `pm grant` or `pm revoke`, toggle a permission, or use any device but `emulator-5554`;
- restart the listener automatically, add a log line, or log a token, path or body;
- set `FLAG_SECURE` anywhere else, or substitute `setRecentsScreenshotEnabled` for it;
- add a dependency, espresso-intents included;
- touch the MCP, the routes, or `v1.md` outside its own section.

## Review focus

Generation safety proved deterministically (a bounded wait on a latch or the flow, never a lone sleep); the denial sentence reachable only from a permission that reads as denied; the flag surviving a pause.

## Size

Small to medium: one server, model and screen, a hoisted helper, three test classes, doc corrections.
