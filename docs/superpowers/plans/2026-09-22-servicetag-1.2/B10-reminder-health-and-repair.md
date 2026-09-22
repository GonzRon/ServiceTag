# B10 — #27 reminder health and idempotent repair

**Read first:** the master plan's §1, §12.2 (health findings shipped in 1.2), §8 (`HealthFinding` and `RepairAction`) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §5.8; rulings D-22, D-20; D3 §7.3; issue snapshot `issue-27.md`.

## Purpose

Because maintenance reminders are a reliability mechanism, the app must be able to say when a reminder path is broken — and repair the unambiguous cases itself. This brief ships the seven local findings, each with a plain sentence and, where it is safe, a one-tap repair; the Health section inside the Maintenance destination; and the badge threshold the dashboard reads. It is also where the platform realities are **explained rather than hidden**: an OEM that restricts the app, and Android 17 not dispatching NFC to an app in the stopped state. **Repair is only ever the unambiguous and idempotent — re-arm an alarm, re-enqueue a worker — and no conflict is ever auto-repaired.**

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt` — `run()` returning the findings, and `repair(finding)` applying the safe ones.
- `app/.../ui/maintenance/HealthScreen.kt` and `HealthViewModel.kt` — the Health section inside Maintenance, and the `HealthSummary` implementation B08 declared.
- Tests: `app/src/test/kotlin/.../reminders/ReminderHealthCheckTest.kt`, `app/src/test/kotlin/.../ui/maintenance/HealthViewModelTest.kt`; connected `app/src/androidTest/kotlin/.../ui/maintenance/HealthScreenTest.kt`.

**Modify**

- `app/.../reminders/LocalReminderProvider.kt` (B06's) — its `health()` returning the provider-specific findings this check folds in.
- `app/.../ui/maintenance/MaintenanceScreen.kt` (B08's) — the Health section's row wired to the screen.
- `app/.../ServiceTagApp.kt` — the check run at launch; `app/.../reminders/BackstopWorker.kt` (B06's) — the check run inside the worker.
- `app/.../di/AppGraph.kt` — the check and the view-model factory.

**Untouched:** `core/**` — the `HealthFinding` type is B04's and is used as it is; `api/**`; `data/**`; `nfc/**`; `tools/`; `libs/`; the manifest (B05 owns it).

## Interfaces

**Consumes from B04:** `HealthFinding`, `Severity`, `RepairAction`, `ReminderProvider.health()`. **From B05:** `PlatformState.notificationsEnabled()`, `.channelImportance()`, `.appRestricted()`. **From B06:** `DigestAlarm.armed()`/`.arm()`, the backstop's unique work name and its enqueue, the reminders-enabled preference. **From B02/B03:** `ScheduleRepository`, `ScheduleStateRepository` (for `SCHEDULE_NO_PROVIDER` and `NO_DATA`). **From B08:** the `HealthSummary` interface, which this brief implements, and the Maintenance shell's Health row.

**Produces:** the `HealthSummary` implementation the dashboard badge reads. Nothing else another brief consumes.

**The seven findings, as contract** (spec §5.8, #27's table). Detection and repair are fixed; only the **sentences** are open.

| code | detection | severity | repair |
|---|---|---|---|
| `NOTIFICATIONS_BLOCKED` | notifications disabled **or** either channel's importance is none | ERROR | `OpenSystemSettings` |
| `DIGEST_ALARM_MISSING` | `DigestAlarm.armed()` false **while reminders are enabled** | WARN | **`Automatic`** — re-arm (safe, idempotent) |
| `BACKSTOP_WORK_MISSING` | `getWorkInfosForUniqueWork` empty | WARN | **`Automatic`** — re-enqueue |
| `APP_RESTRICTED` | standby bucket `RESTRICTED` **or** battery optimisation "restricted" | WARN | `OpenSystemSettings`, with the explanation |
| `REMINDERS_GLOBALLY_OFF` | the global reminders preference is off | INFO | `OpenInApp` — one tap to enable |
| `SCHEDULE_NO_PROVIDER` | an **ACTIVE** schedule with `remindersEnabled` and **no enabled `schedule_provider` row** | WARN | `OpenInApp` — the editor |
| `NO_DATA` | a schedule with a meter rule and **no baseline** — neither a completion nor `anchorMeter`. **This form only** — see the constraint below | WARN | `OpenInApp` — the completion / anchor form, offered as the ratified **"Log meter reading"** |

`Plan decision:` the severities above. Spec §5.8 names the seven codes and #27 the badge threshold ("≥ WARN") but neither assigns a severity per finding; without one the badge is undefined. `NOTIFICATIONS_BLOCKED` is ERROR because nothing can be delivered at all; `REMINDERS_GLOBALLY_OFF` is INFO because it is the owner's own choice and a badge for it would be a nag; the rest are WARN because a reminder path is broken but recoverable.

**The `NO_DATA` finding is constrained to one of the two `NO_DATA` conditions** (owner ruling at the gate, 2026-09-22; master plan §17.1a). The ratified sentence **"\<n\> schedules need a meter reading before they can come due."** and its repair **"Log meter reading"** apply **only** to the **repairable missing-meter-baseline** form. The other condition — a **group occurrence whose required set is empty** — reports the same status enum and is **never given this finding, this sentence or this repair**: it stays **non-actionable**, with **no due count, no scan-sheet offer, no promotion and no close action** (master plan §11.1; invariants 74, 77). **A detector that keys off the status enum alone would raise the meter-baseline wording for a group nobody can service**, which is why this brief keys off the *baseline*, not the status.

**Repair policy, as contract** (#27's "Repair policy"): repair only what is unambiguous and idempotent. **No conflict is ever auto-repaired** — the owner chooses (invariant 50). Every Todoist finding (`TODOIST_DISCONNECTED`, `PROJECTION_MISSING`, `PROJECTION_CONFLICT`, `PROJECTION_DUE_DRIFT`, `SYNC_STALE`, `OUTBOX_FAILING`) is **Phase 5** and must not appear in 1.2, even as a placeholder.

**Where it runs** (#27 "Where it runs") — three points, and a reason each:

| point | why | what consumes the result |
|---|---|---|
| app launch (`ServiceTagApp`) | the cheapest moment to notice an alarm the platform dropped while the app was away | the dashboard badge |
| inside the backstop worker | the worker already runs on a schedule and already re-arms the alarm, so the finding and its repair are one pass | the badge, and the automatic repairs |
| the Health screen itself | the owner is looking at it and expects it to be current | the screen |

`Plan decision:` **not** "after every sync" — there is no sync in 1.2 — and **not** on every dashboard emission, which would read the standby bucket on every keystroke.

**What the screen must explain, not hide** (spec §5.8, D3 §9). Two platform realities that read as ServiceTag defects if the app stays silent about them, and which therefore need screen content even when no finding is active:

| reality | the screen says |
|---|---|
| an OEM standby bucket of `RESTRICTED`, or battery optimisation "restricted" | that the system is holding the app back, what that costs (late or missed reminders), and a way to the setting — **without nagging for an exemption the app does not need** (#24) |
| **Android 17 does not dispatch NFC to an app in the stopped state** after a force-stop | that a force-stopped ServiceTag will not respond to a tag until it is opened once — a platform behaviour, not a bug |

## Invariants this brief must hold

**50, 51, 61** (master plan §13), and it must not weaken **53**: a muted-channel check reads the two channels B05 creates and must not create or assume a third.

## Test matrix

**Every finding gets a positive test *and* a negative control** — that is #27 AC 1 and invariant 51, and it is the one place the proportionality ruling does *not* let a row collapse, because a detector that is never silent is worse than none. All off-device against B05's `PlatformState` interface and fakes for the alarm, the worker and the repositories.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| `NOTIFICATIONS_BLOCKED` | **positive:** notifications disabled → the finding, ERROR, with an `OpenSystemSettings` repair; **and** a muted **channel** with notifications otherwise enabled → the same finding. **Control:** both enabled and both channels at their importances → **silent** | reading only the app-level toggle misses the per-channel mute; a detector with no control fires forever and the screen is permanently red |
| `DIGEST_ALARM_MISSING` | **positive:** the alarm deliberately cancelled with reminders enabled → the finding; **Repair re-arms it**, and running Repair **twice changes nothing the second time** (#27 AC 3). **Control:** armed → silent; and **cancelled with reminders disabled → silent** | without the reminders-enabled condition the finding fires for an owner who turned reminders off on purpose |
| `BACKSTOP_WORK_MISSING` | **positive:** no unique work → the finding, and Repair re-enqueues **exactly one** request. **Control:** enqueued → silent | a repair that enqueues without checking produces duplicate periodic work, which is the non-idempotent repair the policy forbids |
| `APP_RESTRICTED` | **positive:** each of the two restricted states → the finding with its explanation and an `OpenSystemSettings` repair. **Control:** `NORMAL` → silent | with no detector an OEM's restriction looks like a ServiceTag bug; with no control it nags every launch |
| `REMINDERS_GLOBALLY_OFF` | **positive:** the preference off → the finding, **INFO**, with a one-tap enable. **Control:** on → silent | as WARN it would light the dashboard badge for the owner's own deliberate choice |
| `SCHEDULE_NO_PROVIDER` | **positive:** an ACTIVE schedule with `remindersEnabled` and no enabled provider row → the finding naming that schedule (#27 AC 4). **Control:** the same schedule with an enabled row → silent; and a **PAUSED** or **ARCHIVED** schedule with no row → silent | without the lifecycle condition every archived schedule raises a finding |
| `NO_DATA` | **positive:** a meter rule with no completion and no `anchorMeter` → the finding with a repair that opens the anchor form (#27 AC 5). **Control:** the same schedule with `anchorMeter` set → silent. **Second control, the gate's constraint:** a **group-targeted** schedule whose **required set is empty** — reporting the same status enum — produces **no** finding, **no** meter sentence and **no** repair | a schedule that can never become due sits in ATTENTION with nothing offered to fix it; and a detector keyed off the status enum would tell the owner to log a meter reading for a group that has no members |
| **a conflict auto-repaired** | a merge conflict present on the phone produces **no** finding with an `Automatic` repair, and nothing in `repair()` resolves a conflict (invariant 50, #27 AC 6) | "canonical wins" applied to a conflict is the silent data loss #44 exists to prevent |
| a Phase-5 finding shipped early | a structural assertion: none of the six Todoist codes appears anywhere in `app/src/main` | a placeholder finding tells the owner about an integration that does not exist |
| repair not idempotent | **one** composed test: each `Automatic` repair run twice leaves the platform state identical the second time (#27 AC 3's second half) | a repair that stacks alarms or workers degrades the thing it was fixing |
| the badge threshold | `HealthSummary.worstSeverity()` returns ERROR/WARN/INFO correctly, and B08's badge appears at **≥ WARN** and not for an INFO-only set | a badge on any finding is permanent and stops carrying information |
| **denied permission disabling something** | with the permission denied, one test asserts the scheduling state, the alarm, the backstop and the reminder settings are **all still working**, and **exactly one** finding is produced (invariant 61, D-22) | the natural "if notifications are off, stop" guard is precisely what D-22 forbids |
| the platform realities hidden | the Health screen carries the `APP_RESTRICTED` explanation and the Android 17 stopped-state note (spec §5.8, D3 §9) | without them a force-stopped phone not dispatching NFC reads as a ServiceTag defect |
| grayscale and colour-only signalling | each finding renders with an **icon plus explicit wording plus position**, never colour alone (D12 §5's `REMINDER FAILED` treatment) | colour-only severity fails the D12 acceptance |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone):** one class asserting the Health section is reachable from Maintenance, lists findings, and that tapping an `Automatic` repair clears its finding on the next run.

## Strings

**Ratified, verbatim** (master plan §17): **"Reminders are off because notifications are blocked."** (B08 draws it on the dashboard; this brief may reference the same constant), **"Log meter reading"** as the `NO_DATA` repair label, and the D12 treatment words **REMINDER FAILED** for the failure family.

**Ratified at the gate, and therefore this brief's *inputs* rather than its obligation** (owner, 2026-09-22; master plan §17.1a): **the seven finding sentences and their seven repair labels**, tabled in §17.1a and to be used **verbatim**. `NO_DATA`'s label is the D-24 string **"Log meter reading"**; the other thirteen were ratified at the gate. **This brief no longer drafts any string and its execution is no longer blocked on one** — the register the drafts were written in (what is wrong in the owner's terms, the consequence rather than the mechanism, a verb for the repair) is now a property of the ratified text rather than an instruction. A string this brief finds it needs that §17 does not list is a **finding for the controller**.

## Ordering

**After B05, B06 and B08** (spec §7 as corrected by T9: B10 consumes all three). Its **screen** needs B08's shell; its **detections** need B05's `PlatformState` and B06's alarm and worker. Runs in lane B of wave 7 beside **B12**. **Its execution waits on the owner's ratification of the fourteen strings, not on S4.**

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; the review confirms **fourteen** positive/control pairs — seven findings × (one positive, one control) — are present and that no control is a vacuous assertion.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.maintenance.HealthScreenTest --console=plain`.
- Structural, anchored:
  - `grep -rniE '\b(TODOIST_DISCONNECTED|PROJECTION_MISSING|PROJECTION_CONFLICT|PROJECTION_DUE_DRIFT|SYNC_STALE|OUTBOX_FAILING)\b' app/src/main core/src/main` → no match.
  - `grep -rn 'RepairAction.Automatic' app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt` → exactly **two** sites: the alarm re-arm and the worker re-enqueue.
  - `grep -rniE '\bconflict\b' app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt` → no match, or only a comment stating that conflicts are never repaired.
  - `grep -c 'HealthFinding(' app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt` → **7** construction sites, one per code.
  - `grep -rn 'ReminderHealthCheck' app/src/main | grep -vE '(ServiceTagApp|BackstopWorker|HealthViewModel|AppGraph|ReminderHealthCheck)\.kt'` → no match (the three run points and the wiring, and nothing else).

## Estimated size

Medium. One check, one screen, one view model, fourteen test pairs. No split expected; if it grows, the likely cause is repair logic that belongs in B06.
