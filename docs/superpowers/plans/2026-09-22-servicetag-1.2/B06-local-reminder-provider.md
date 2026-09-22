# B06 — #21 the local reminder provider

**Read first:** the master plan's §1, §8 (the port and delivery state), §12 (the Android delivery platform), §12.3 (**S4 gates nothing and this brief is not gated**) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.5, §5.2, §5.3, §5.6, §5.7, §5.9; rulings D-5, D-6, D-13, D-21, D-22; issue snapshot `issue-21.md`.

## Purpose

Deliver ServiceTag's own reminders reliably with no account, no cloud and **no exact alarms**: one inexact daily digest alarm at the owner's hour, a 12 h / 4 h-flex WorkManager backstop that recomputes every schedule's state, posts what was missed and re-arms the alarm if it is gone, the four platform receivers wired to that re-arm, the `ReminderProvider` implementation whose `reconcile` is its whole write surface, the device-local `schedule_local_delivery` bookkeeping behind the snooze and the digest policy, the immediate notification when a saved meter reading crosses DUE, and the two preferences #21 cannot work without. **This brief is written now, on the default design of master plan §12** — the S4 observation is informational and gates nothing; a REVISIT amends this brief afterwards.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/LocalReminderProvider.kt` — the `ReminderProvider` implementation: `reconcile`, an always-empty `pullChanges`, and the `health()` findings this provider owns.
- `app/.../reminders/DigestAlarm.kt` — arming, re-arming and cancelling the inexact alarm; the local-date-to-instant conversion.
- `app/.../reminders/DigestReceiver.kt` — the alarm's own receiver: run the digest, then re-arm.
- `app/.../reminders/BackstopWorker.kt` — the periodic worker.
- `app/.../reminders/DigestPolicy.kt` — the pure decision layer: given subjects, delivery rows and `T`, what to post, what to clear, what to leave alone.
- `app/.../reminders/Notifications.kt` — building the summary and the per-item notifications on B05's two channels, and clearing them.
- `app/.../data/room/LocalDeliveryRepository.kt` — the Room adapter for this brief's port over B01's `ScheduleLocalDeliveryDao`.
- Tests: `app/src/test/kotlin/.../reminders/DigestPolicyTest.kt`, `DigestAlarmTest.kt`, `LocalReminderProviderTest.kt`, `BackstopWorkerTest.kt`, `ReminderPrefsTest.kt`.

**Modify**

- `gradle/libs.versions.toml` and `app/build.gradle.kts` — **`androidx.work:work-runtime-ktx`, this brief's own dependency decision**, added to the catalog and to `:app` only. The S4 probe's WorkManager version is **not** this decision (spec §5.9).
- `app/.../prefs/AppPrefs.kt` — **two** values and no more: the digest hour (default **09:00 local**) and the global reminders switch. In the shipped shape, with keys beside `AppPrefs.kt:41-44`.
- `app/.../reminders/ReminderReceivers.kt` (B05's declarations) — the single `ReminderTrigger` implementation.
- `core/.../core/ports/Repositories.kt` — `ScheduleLocalDeliveryRepository` (see Interfaces).
- `app/.../di/AppGraph.kt` — the provider, the alarm, the policy, the delivery repository, the trigger.
- `app/.../ServiceTagApp.kt` — the backstop enqueued as unique periodic work at process start, idempotently.
- `core/.../core/usecase/LogEvent.kt` / `UpdateEvent.kt` — **no change**; the meter-crossing evaluation hangs off B02's `RecomputeSchedules` result, not off the event use case (see Interfaces).

**Untouched:** all of `core/src/main` but the one port; `app/.../ui/**` (B08 draws the "reminders are off" line, B10 the health screen); `api/**`; `nfc/**`; the manifest (B05 owns it; this brief adds **no** manifest element); `tools/`; `libs/`.

## Interfaces

**Consumes from B04:** `ReminderProvider`, `ReminderSubject`, `SubjectState`, `ProviderId.LOCAL`, `ReconcileReport`, `HealthFinding`, `Severity`, `RepairAction`, `BuildReminderSubjects`. **From B05:** `NotificationChannels.DUE`/`.OVERDUE`, `PlatformState`, `NotificationPermission.granted()`, `ReminderTrigger`/`PlatformEventKind`. **From B02:** `RecomputeSchedules`, `Today`. **From B01:** `ScheduleLocalDeliveryDao`.

**Produces, for B07 and B10:**

```kotlin
interface ScheduleLocalDeliveryRepository {        // core/.../core/ports/Repositories.kt
    suspend fun get(id: ScheduleId): ScheduleLocalDelivery?
    suspend fun upsert(row: ScheduleLocalDelivery)
    suspend fun all(): List<ScheduleLocalDelivery>
    suspend fun deleteAll()
}
interface DigestAlarm {                            // B10's DIGEST_ALARM_MISSING repair calls arm()
    fun armed(): Boolean                           // PendingIntent.getBroadcast(FLAG_NO_CREATE) != null
    fun arm(); fun cancel()
}
class ReminderSnooze { suspend fun snooze(id: ScheduleId, until: Long) }   // B07's "Snooze 1 day", B09's "Snooze"
class NonceStore {                                 // B07 checks and consumes; this brief issues and clears
    suspend fun issue(id: ScheduleId): String
    suspend fun consume(id: ScheduleId, nonce: String): Boolean   // false for stale, missing or already used
    suspend fun clear(id: ScheduleId)
}
```

`Plan decision:` `NonceStore` and `ReminderSnooze` are declared **here** and consumed by B07, rather than declared in B07. D-21 makes the nonce part of the delivery row this brief owns, and the issuing side is the notification build; splitting the store from the table would give two briefs write access to one column.

**The digest policy, as contract** (D-5, fixed for 1.2; fatigue controls are #26):

| rule | statement |
|---|---|
| one summary per run | each digest run posts **one** summary notification listing DUE and OVERDUE subjects |
| per-item notifications | only for DUE/OVERDUE subjects that are **not snoozed** |
| DUE_SOON | announced **only on first entry**, tracked by `first_entry_seen` |
| overdue re-notification | every **3 days**, tracked by `last_notified_at` |
| channel | **`Plan decision:`** OVERDUE subjects on `maintenance_overdue` (HIGH), DUE and DUE_SOON on `maintenance_due` (DEFAULT). Spec §5.5 names the two channels and their importances but not which subject state uses which; the mapping follows the channel names, and the alternative — everything on one channel — would waste the HIGH importance D-20 created. Master plan §18 decision 40 |
| parking | a `Parked` subject posts nothing **and clears any standing notification** |
| withdrawal | a `Withdrawn` subject clears its notification and its delivery row's nonce |
| snooze | suppresses **this provider's** notifications only; it never touches a due date and creates no event (invariant 20) |
| meter-only | evaluated **when a reading is saved**, with an immediate notification if it crosses DUE, because there is no date to alarm on (#21 AC 6) |
| group | a group-targeted schedule's notification offers **"Open"** only (D-7); its body carries the progress |
| denied permission | the alarm, the backstop, the recompute and the preferences all keep working; nothing is posted, and the denial is a **finding**, not a disabled path (D-22, invariant 61) |

**The alarm, as contract** (§5.2, D-6): one `setAndAllowWhileIdle(RTC_WAKEUP)` — or `setWindow` with a 30-minute window — at the owner's hour, **re-armed by its own receiver after firing**. The instant is `ZonedDateTime.of(T_next, reminderTime, deviceZone).toInstant()`; a non-existent spring-forward local time resolves **forward** per `java.time`, an ambiguous fall-back time to the **earlier** offset. On a zone or DST change day the digest may fire twice or not at all, and **the backstop is what guarantees it eventually fires** — this is a stated consequence, not a defect to engineer around.

**The backstop, as contract** (§5.3): a `PeriodicWorkRequest` every **12 h, flex 4 h**, enqueued as **unique** work, that calls `RecomputeSchedules.all()`, runs `reconcile`, and **re-arms the alarm if `armed()` is false**. WorkManager survives reboot and force-stop; alarms do not.

**The meter-crossing seam:** `RecomputeSchedules` already returns the states it recomputed; this brief's `reconcile` runs after it, so a saved reading that moves a schedule to DUE produces a notification through the ordinary path. `Plan decision:` no branch in the event use case. Spec §5.7 says meter-only schedules are "evaluated when a reading is saved"; wiring that through the existing recompute-then-reconcile sequence rather than a special case in `LogEvent` is what keeps the engine free of a delivery concern.

## Invariants this brief must hold

**20, 22, 44, 45, 47, 60, 61, 64, 65** (master plan §13), and it must leave **55, 56** provable by B07.

## Test matrix

One test per hazard class. **No row waits on a wall clock**: every timing case is a deterministic test against an injected `Today`, a fake delivery repository and a shadow `AlarmManager` / a WorkManager test driver (master plan §1's no-overnight rule, spec §8).

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the alarm lost after a platform event | the alarm is re-armed after each of `BOOT_COMPLETED`, `TIME_SET` and `TIMEZONE_CHANGED`, asserted with a shadow `AlarmManager` (invariant 60, #21 AC 2) | alarms are cleared on shutdown, so without the receivers the phone stops reminding after a reboot and nothing says so |
| the alarm never re-arms after firing | the digest receiver re-arms for the next day as part of handling the fire | a one-shot alarm reminds once and never again |
| the backstop does not backstop | with the alarm deliberately cancelled, one backstop run **re-arms it** and posts the subjects that were missed (#21 AC 3) | a worker that only recomputes leaves a force-stopped phone permanently silent |
| the digest shouting | a schedule due tomorrow with a 14-day lead produces **exactly one** notification for the run (#21 AC 1's off-device half) | a per-subject post with no summary, or a summary plus a duplicate per-item post for the same subject, doubles it |
| a snoozed schedule notified | a snoozed schedule produces **nothing** while snoozed, **its due date is unchanged**, and its status is still OVERDUE (invariant 20, #21 AC 4) | writing the snooze onto a `*_on` column changes the due date, which is the one thing a snooze may not do |
| a parked schedule left standing | a schedule that becomes `Parked` — paused, or out of season — posts nothing **and its standing notification is cleared** (invariant 47, #21 AC 5) | leaving the notification up means a paused schedule keeps nagging with nothing to act on |
| `reconcile` posting twice | `reconcile` run twice with the same subject list posts nothing the second time and reports every subject `unchanged` (invariant 45, #21 AC 7) | a "post each subject" loop re-posts on every digest run |
| overdue re-notification drift | an overdue subject re-notifies at **3 days** and not before, driven by `last_notified_at`; a DUE_SOON subject is announced **once** on first entry and not again, driven by `first_entry_seen` (D-5) | re-notifying every run is the fatigue #26 exists to fix later and #21 promises not to cause now |
| **an empty delivery table** | with `schedule_local_delivery` **empty**, `reconcile` behaves correctly — at worst it re-announces — and never crashes, never skips a due subject, and never treats a missing row as "snoozed" (invariant 44's real meaning, spec §2.5) | treating the row as required makes a wiped table silence the app; treating a null `snoozed_until_at` as a snooze does the same |
| **the projection is not rebuildable** | with every notification cancelled and the alarm cancelled and the delivery table cleared, one `reconcile` restores the posted set and the armed alarm from schedule state alone (invariant 44) | a provider that keeps its desired state anywhere but the schedules cannot recover from a cleared app |
| DST edge days | one test covering both: a digest hour that does not exist on a spring-forward day resolves **forward**; an ambiguous hour on a fall-back day resolves to the **earlier** offset; and the computed instant is asserted, not the delivery (D-6, D5 §11) | naive `LocalDateTime.toInstant()` throws or silently shifts an hour, and the digest lands at the wrong time twice a year |
| a meter reading not noticed | saving a reading that crosses the meter threshold notifies **immediately**, without waiting for the next digest run (#21 AC 6) | a date-only trigger never fires for a meter-only schedule, which has no date |
| a group notification claiming completion | a group-targeted schedule's notification offers **"Open"** only — no "Done" (D-7) | "Done" on a group either completes nothing or falsely completes everyone |
| delivery state leaking into a backup | a structural assertion: no `schedule_local_delivery` column appears in any DTO, in `BackupData`, or in a `MergeTable` member; an export of a phone with snoozes and nonces carries **none** of it (invariants 64, 65) | exporting the snooze makes a re-import `CONTENT_DIFFERS` and moves a device-local preference across phones |
| denied permission disabling things | with the permission denied, one test asserts the alarm is still armed, the backstop still enqueued, the preferences still writable, the recompute still running, and **exactly one** finding produced (invariant 61, D-22) | a guard that skips arming when notifications are off means granting the permission later leaves the machinery dead |
| too many preferences | a structural assertion: `AppPrefs` gained **exactly two** keys (D-5, §5.6) | 1.2 is not #18; a preferences screen's worth of keys here pre-empts a design the owner has not made |

## Strings

**Ratified, verbatim** (master plan §17): the notification action labels **"Done"**, **"Snooze 1 day"**, **"Open"** (B07 attaches them; the constants may live here if that is where the notification is built), **"Snoozed until <date>"**, and the status terms **DUE**, **OVERDUE**, **DUE SOON**, **PAUSED**, **OUT OF SEASON**, **NO BASELINE**.

**PROPOSED, drafted by this brief and returned for ratification before it executes** (master plan §18 decision 12): the **digest summary notification's title and body** (the one-per-run summary listing DUE and OVERDUE), and the **per-item notification's title and body form**. Draft one title and one body form for each, built from the ratified status vocabulary and the schedule's own `title`, plus the plural forms the summary needs. Hand them to the controller with the brief's plan review. **Do not ship an unratified notification string.**

## Ordering

**After B04 and B05.** **Before B07** and B10's alarm/worker findings. Runs in lane A of wave 4 beside **B08**. **It is not gated on S4** (master plan §12.3): it is designed and written on the default alarm-plus-backstop design, and a REVISIT verdict returns as an amendment to this brief after the fact.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected, on **`emulator-5554`** only, with `ANDROID_SERIAL` pinned and **never a phone**: the classes that need a real `NotificationManager` and a real WorkManager — at minimum one class proving the two channels exist with their importances after first launch, and one proving the unique periodic work is enqueued exactly once across two launches. `./gradlew :app:connectedDebugAndroidTest --tests '…reminders.*' --console=plain`.
- Structural, anchored:
  - `grep -rn 'setExact\|setAlarmClock\|setExactAndAllowWhileIdle' app/src/main` → no match.
  - `grep -rn 'setAndAllowWhileIdle\|setWindow' app/src/main` → the single site in `DigestAlarm.kt`.
  - `grep -rn 'enqueueUniquePeriodicWork' app/src/main` → one site.
  - `grep -rn 'schedule_local_delivery\|ScheduleLocalDelivery' core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/main/kotlin/com/loosecannon/servicetag/core/merge` → no match.
  - `grep -rn 'startActivity' app/src/main/kotlin/com/loosecannon/servicetag/reminders` → no match.
  - `grep -c 'const val KEY_' app/src/main/kotlin/com/loosecannon/servicetag/prefs/AppPrefs.kt` → the shipped 4 **+ 2**.
  - `grep -n 'androidx.work' gradle/libs.versions.toml app/build.gradle.kts core/build.gradle.kts` → present in the first two, **absent from `core`**.
- **No acceptance step waits on a wall clock or an overnight run.**

## Estimated size

Large. Alarm, worker, receivers, provider, policy, notifications, two preferences and an adapter. If it exceeds what one review holds, split at **B06a alarm, backstop, receivers and preferences** / **B06b the provider, the digest policy and the notifications**, and say so in the ledger; the interface between them is `DigestAlarm` and `ReminderTrigger`.
