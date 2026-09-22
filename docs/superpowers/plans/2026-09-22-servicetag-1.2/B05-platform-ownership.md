# B05 — #24 Android platform ownership

**Read first:** the master plan's §1, §12 (the Android delivery platform), §12.3 (S4 gates nothing) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §5.1, §5.4, §5.5, §5.8, §5.9; rulings D-20, D-22; issue snapshot `issue-24.md`.

## Purpose

Own every Android platform constraint the reminder path depends on, so no later brief re-derives one and none is discovered late: the `POST_NOTIFICATIONS` declaration and the request plumbing B14 invokes, the **two** notification channels and nothing else, the receiver declarations with their non-exported and `FLAG_IMMUTABLE` discipline, the assertion that the app declares **neither** exact-alarm permission, and the detection of the states #27 reports — notifications disabled, a channel muted, a restricted standby bucket. It deliberately contains **no alarm, no worker and no notification content**: those are B06's, and this brief is what B06 stands on. It touches no domain file, which is why it runs beside B02.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/NotificationChannels.kt` — the two channel ids, their importances, and the one idempotent creation call.
- `app/.../reminders/NotificationPermission.kt` — the permission state read and the request plumbing (see Interfaces).
- `app/.../reminders/PlatformState.kt` — the detectors: notifications enabled, per-channel importance, standby bucket, battery-optimisation state.
- `app/.../reminders/ReminderReceivers.kt` — the `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / `DATE_CHANGED` receiver **declarations and their dispatch seam**; the work each one triggers is B06's.
- Tests: `app/src/test/kotlin/.../reminders/NotificationChannelsTest.kt`, `PlatformStateTest.kt`, `app/src/test/kotlin/.../ManifestContractTest.kt`.

**Modify**

- `app/src/main/AndroidManifest.xml` — `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` and the five `<receiver>` elements, **every one `android:exported="false"`**. **Nothing else**: no `SCHEDULE_EXACT_ALARM`, no `USE_EXACT_ALARM`, no new activity, no new exported component. The `servicetag://schedule` `data` line is **B07's**.
- `app/.../ServiceTagApp.kt` — the channel creation called once at process start, idempotently.
- `app/.../di/AppGraph.kt` — the platform-state and permission fields.

**Untouched:** all of `core/`; every `app/src/main/kotlin/.../ui/**` but nothing at all in this brief (B14 calls into the permission seam, it is not called from a screen here); `api/**`; `nfc/**`; `data/**`; `tools/`; `libs/`; `app/build.gradle.kts` (`targetSdk` stays **36**; `targetSdk 37` plus `DISPATCH_NFC_MESSAGE` is **Phase 7** and is recorded, not done).

## Interfaces

**Consumes:** nothing from another 1.2 brief. **Produces, for B06, B07, B10 and B14** — three seams, exactly two channels, and one enum per detection.

`NotificationChannels`: an object holding the two ids as constants and one idempotent `ensure(context)`. The ids and importances are ruled and are not the implementer's to choose:

| constant | id | importance | carries |
|---|---|---|---|
| `DUE` | `maintenance_due` | `IMPORTANCE_DEFAULT` | DUE and DUE_SOON items |
| `OVERDUE` | `maintenance_overdue` | `IMPORTANCE_HIGH` | OVERDUE items |

`PlatformState`: an **interface** with three members and one Android-backed implementation. Each answer is a closed enum, never a boolean pair, so a caller cannot conflate two different facts:

| member | returns | meaning |
|---|---|---|
| `notificationsEnabled()` | `Boolean` | the app-level toggle only |
| `channelImportance(channelId)` | `ChannelImportance` = `DEFAULT｜HIGH｜MUTED｜ABSENT` | `MUTED` is the user setting a channel to none; `ABSENT` is an id never created — a different fact, and B10 reports them differently |
| `appRestricted()` | `AppRestriction` = `NORMAL｜STANDBY_RESTRICTED｜BATTERY_RESTRICTED` | the two OEM realities #24 names, distinguished because the explanation differs |

`NotificationPermission`: an **interface** with `granted(): Boolean`, `shouldExplain(): Boolean` and `suspend fun request(): Boolean` returning the owner's answer. **`request()` never throws on a denial** — a denial is an answer, not an error (D-22).

`Plan decision:` both are **interfaces with one Android-backed implementation each**, rather than static helpers over `NotificationManagerCompat` and `UsageStatsManager`. Spec §5 names the detections but not their shape; an interface is what lets B10's seven findings have a positive test **and** a negative control (invariant 51, #27 AC 1) without an emulator per case, which is also what keeps B10 inside the proportionality ruling.

`Plan decision:` **the request lives behind this seam and is invoked from B14's editor only.** Spec §5.1 rules where it is requested ("on first schedule creation with a rationale") but not who owns the call; splitting it — plumbing here, the call site there — is what makes #24 AC 1 assertable at all, since the AC is unassertable without a creation flow.

**The rationale string** B14 shows is the ratified **"ServiceTag needs notification permission to remind you when maintenance is due."** This brief carries the string constant; B14 draws it.

**The receiver seam** B06 implements — one functional interface and one enum:

```kotlin
fun interface ReminderTrigger { suspend fun onPlatformEvent(kind: PlatformEventKind) }
enum class PlatformEventKind { BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, DATE_CHANGED }
```

The manifest elements this brief declares, and what each is for: **Amended at B05's review (2026-09-22, controller ruling):** this brief declares **four** receivers (`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`); the quick-action receiver is B07's fifth, as B07's brief, master §16.4 and the reconciliation already say — the gate counts below read 4 for this brief and 5 once B07 lands. It also declares `RECEIVE_BOOT_COMPLETED` (install-time, no prompt) because invariant 60's re-arm after `BOOT_COMPLETED` is undeliverable without it; spec §5.1's "`POST_NOTIFICATIONS` only" governs the runtime request.

| receiver | action(s) it filters | why it exists | who implements the work |
|---|---|---|---|
| boot | `android.intent.action.BOOT_COMPLETED` | alarms are cleared on shutdown | B06 (re-arm) |
| time | `android.intent.action.TIME_SET` | a manual clock change moves the next digest instant | B06 (re-arm) |
| zone | `android.intent.action.TIMEZONE_CHANGED` | D-6 requires a re-arm on a zone change | B06 (re-arm) |
| date | `android.intent.action.DATE_CHANGED` | the device-local date `T` moved, so due state moved | B06 (recompute, then re-arm) |
| quick action | none — explicit intent only | the notification actions' target | **B07** declares the element's `<receiver>` body; its non-exported and immutable-intent discipline is asserted here |

`Plan decision:` the four platform receivers are declared here with **one** seam rather than four in B06, because their manifest declarations, their non-exported attribute and their explicit-intent discipline are this brief's invariants (54, and 60's mechanism) while what they *do* is B06's policy. B06 supplies the single implementation; the receivers are proved here to be non-exported and to call the seam, and in B06 to re-arm the alarm.

## Invariants this brief must hold

**52, 53, 54** (its manifest half), **61** (the "disables nothing" half that is platform-side), and it must leave **55, 60** provable by B06 and B07.

## Test matrix

One test per hazard class. All off-device; the merged-manifest assertions read the manifest the build produces.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| **an exact-alarm permission creeping in** | the **merged** manifest declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`, and declares `POST_NOTIFICATIONS` exactly once (invariant 52, #24 AC 2) | a library that requests an exact alarm, or an implementer reaching for one when the inexact alarm looks unreliable, makes the assertion fail — this is the test that protects the whole no-exact-alarm decision (ledger A12) |
| an exported receiver | every `<receiver>` in the merged manifest carries `android:exported="false"`, and the set of exported components is **exactly** the two shipped ones — `MainActivity` and `NfcDispatchActivity` (invariant 54) | an omitted attribute defaults differently across API levels and hands a forged broadcast a door |
| **a third channel** | exactly **two** channels are created, with ids `maintenance_due` at DEFAULT and `maintenance_overdue` at HIGH; **no** channel with id `supplies` or `sync_problems` exists anywhere in the source or is created at runtime (invariant 53, D-20 = B) | copying #24 AC 3's original four-channel list creates two channels a user sees in system settings for features that do not exist |
| channel creation on every launch | calling `ensure` twice leaves the channel set unchanged and does not reset a user's importance choice | recreating a channel with a fresh importance silently overrides the user's mute |
| a muted channel undetected | `channelImportance` returns `MUTED` for a channel the user set to none, `DEFAULT`/`HIGH` for the two live ones, and `ABSENT` for an id that was never created — with a negative control asserting `MUTED` is **not** reported for a normal channel (#24 AC 3, and B10's finding's precondition) | a detector that reads the app-level toggle only misses a per-channel mute, so `NOTIFICATIONS_BLOCKED` never fires for the case it was written for |
| notifications denied breaking the app | with `notificationsEnabled()` false, one test asserts that **nothing** this brief owns is disabled: the channels still exist, the permission seam still answers, the receivers are still declared, and `PlatformState` reports the denial as a **fact** rather than throwing (invariant 61, D-22) | a "bail out if notifications are off" guard is how the denial path ends up disabling scheduling, which D-22 forbids |
| a restricted bucket undetected | `appRestricted()` returns `STANDBY_RESTRICTED` and `BATTERY_RESTRICTED` under the simulated conditions and `NORMAL` otherwise — a positive case and a negative control | without it `APP_RESTRICTED` can never fire and the OEM behaviour looks like a ServiceTag bug |
| the permission asked at launch | a structural assertion: `NotificationPermission.request` has **no call site** in `app/.../ui/nav/**`, `MainActivity.kt` or `ServiceTagApp.kt` — its only caller is B14's editor (#24 AC 1) | requesting at launch is the default reflex and gives the user no context for the prompt |
| a trampoline door opened here | a structural assertion: no receiver in this brief constructs an `Intent` for an `Activity` or calls `startActivity` (invariant 55's mechanism, enforced where the receivers are declared) | a receiver that launches a screen is the API 31+ trampoline violation, and it is cheaper to forbid at the declaration than to find in B07 |
| a mutable `PendingIntent` | the rule is stated and asserted as an anchored grep over the whole module (invariant 54). **B05 constructs none**, so this row is a standing trip-wire that first bites in **B06** and **B07**; B05 owns the rule and the grep, those two briefs own the constructions | a default-flag `PendingIntent` lets another app rewrite the extras a quick action acts on |

## Strings

**Ratified, verbatim** (master plan §17): **"ServiceTag needs notification permission to remind you when maintenance is due."** — the constant lives here, the surface is B14.
**Ratified at the gate** (owner, 2026-09-22; master plan §17.1d) — the **display name and description of each channel**, as Android shows them in system settings, to be used verbatim: `maintenance_due` → **"Maintenance due"** / **"Reminders for maintenance that is due."**; `maintenance_overdue` → **"Maintenance overdue"** / **"Reminders for maintenance that is past due."** **This brief has nothing left to draft**; a channel name is permanent once created, which is why it was ratified rather than left to the implementation.

## Ordering

**Nothing precedes it** — it touches no domain file. It runs in **lane B of wave 2**, beside B02. **It gates B06, B07 and B14.** **It does not gate on S4 and neither does B06** (master plan §12.3); this brief is also where the S4 observation lands when it arrives — as a recorded note in the SDD workspace and a comment on #24, not as a code change.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- The merged-manifest assertions run inside that gate (they read the processed manifest, not the source file), so no connected run is needed. If the build's manifest-merger output is not reachable from a unit test, the review instead runs the anchored greps below over **both** `app/src/main/AndroidManifest.xml` and the merged manifest the debug build produced, and says which it used.
- Structural, anchored:
  - `grep -cE 'android:name="android\.permission\.(SCHEDULE|USE)_EXACT_ALARM"' app/src/main/AndroidManifest.xml` → 0, and the same over the merged manifest.
  - `grep -cE 'android:name="android\.permission\.POST_NOTIFICATIONS"' app/src/main/AndroidManifest.xml` → 1.
  - `grep -cE '<receiver' app/src/main/AndroidManifest.xml` → 5, and `grep -cE '<receiver[^>]*android:exported="false"' …` (allowing for attribute order, asserted per element by the test) → 5.
  - `grep -cE 'android:exported="true"' app/src/main/AndroidManifest.xml` → 2 (the two shipped activities).
  - `grep -rn 'createNotificationChannel' app/src/main` → the single site in `NotificationChannels.kt`.
  - `grep -rniE '"(supplies|sync_problems)"' app/src/main core/src/main` → no match.
  - `grep -rnE 'PendingIntent\.(getBroadcast|getActivity|getService)' app/src/main | grep -vc 'FLAG_IMMUTABLE'` → **0**. **A standing trip-wire, not a B05-specific proof:** this brief constructs no `PendingIntent` — the first one arrives with B06's alarm and B07's quick actions — so at B05's own review point the grep has nothing to check and is vacuously true. It is listed here because the rule is B05's to own, and it is re-run at **B06's and B07's** gates, where it has work to do, and again at the release gate (master plan §16.4).
  - `grep -rn 'NotificationPermission' app/src/main/kotlin/com/loosecannon/servicetag/ui/nav app/src/main/kotlin/com/loosecannon/servicetag/MainActivity.kt app/src/main/kotlin/com/loosecannon/servicetag/ServiceTagApp.kt` → no match.

## Estimated size

Small to medium. Four small files, one manifest change, three test classes. No split expected.
