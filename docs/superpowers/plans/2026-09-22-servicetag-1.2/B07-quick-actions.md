# B07 — #11 notification quick actions

**Read first:** the master plan's §1, §12.1 (the four actions and what each writes), §11 (the deep link) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §5.8, §2.4; rulings D-8, D-21; issue snapshot `issue-11.md`. **Citation corrected at implementation (2026-09-22, B13): D-7 is the no-backlog ruling and never carried the group-notification clause — §2.4's group completion semantics and D-8 do.**

## Purpose

Make the common maintenance actions one tap from a notification, without the notification layer owning any scheduling semantics: **"Done"** (a broadcast that completes a `QUICK` schedule, or an activity `PendingIntent` into the form for a `FORM` one), **"Snooze 1 day"** (a broadcast that writes the device-local snooze instant and nothing else), and **"Open"** (`servicetag://schedule/<uuid>`, navigation only). Every action carries a **random per-notification nonce persisted in `schedule_local_delivery`**, so a forged broadcast cannot complete a schedule and a real action still works after process death. The semantics themselves are #4's and are not restated in code here — this brief calls B02's and B03's use cases.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/QuickActionReceiver.kt` — the one non-exported receiver behind "Done" (QUICK) and "Snooze 1 day": nonce check, then the use case, then `reconcile`.
- `app/.../reminders/QuickActions.kt` — building the three actions for a notification: which appear for which schedule, and the `PendingIntent` for each.
- Tests: `app/src/test/kotlin/.../reminders/QuickActionReceiverTest.kt`, `QuickActionsTest.kt`, `app/src/test/kotlin/.../links/ScheduleDeepLinkTest.kt`.

**Modify**

- `core/.../core/links/DeepLinkRoute.kt` — `DeepLink` gains `Schedule(val id: ScheduleId)`; `parse`'s `when (host)` gains `"schedule"`, reusing the existing `single(pathSegments)` canonical-UUID check (`DeepLinkRoute.kt:29`) so the shape rule is one rule, not two.
- `app/src/main/AndroidManifest.xml`, **two additions and nothing else** (B05 owns the rest of this file):
  1. one `<data android:scheme="servicetag" android:host="schedule" />` line beside `AndroidManifest.xml:44-45`, inside the existing `intent-filter`;
  2. the **`QuickActionReceiver` `<receiver>` element** — a multi-line declaration, not a one-liner — carrying `android:exported="false"`, because the receiver is this brief's component. B05 declares the four *platform* receivers; this is the fifth and it is B07's.
- `app/.../ui/nav/ServiceTagRoot.kt` and `app/.../MainActivity.kt` — the new deep link routed to `Route.ScheduleDetail`, exactly as the two shipped links are routed, with **no** mutation on the way.
- `app/.../reminders/Notifications.kt` (B06's) — the actions attached to the built notification.
- `app/.../di/AppGraph.kt` — the quick-action builder field.
- `core/src/test/kotlin/.../links/DeepLinkRouteTest.kt` — the new host's cases.

**Untouched:** `core/.../core/schedule/**` and `usecase/**` (this brief calls them, it does not change them); `api/**`; `data/**`; `nfc/**`; `tools/`; `libs/`; every other `ui/**` screen.

## Interfaces

**Consumes from B06:** `NonceStore`, `ReminderSnooze`, `LocalReminderProvider` (to `reconcile` after a write), `Notifications`. **From B05:** the receiver declaration discipline. **From B02:** `CompleteSchedule`. **From B03:** `GroupOccurrence` (to know a schedule is group-targeted). **From B08/B14:** `Route.ScheduleDetail` and the completion-form route.

**Produces, for B06's notification build:**

```kotlin
class QuickActions(/* nonce store, completion mode lookup, route builders */) {
    suspend fun forSchedule(id: ScheduleId): List<QuickAction>   // issues the nonce as a side effect
}
data class QuickAction(val label: String, val intent: PendingIntent)
```

**Which actions appear, as contract** (§5.8, §2.4, D-8 — *not* D-7, corrected 2026-09-22):

| schedule | actions |
|---|---|
| asset-targeted, `QUICK` | **"Done"** (broadcast), **"Snooze 1 day"** (broadcast), **"Open"** (activity) |
| asset-targeted, `FORM` | **"Done"** (activity, into the completion form), **"Snooze 1 day"**, **"Open"** |
| **group-targeted** | **"Open"** only — "Done" on a group cannot honestly mean all members, so it opens the checklist |
| a meter schedule | "Done" routes through the canonical completion flow, which **requires** the reading; the notification never fabricates one |

**The nonce's life, as contract** (D-21, invariant 56) — every transition has a test row:

| moment | what happens to `action_nonce` / `nonce_issued_at` |
|---|---|
| a notification is built for a schedule | a fresh random value is **issued** and **persisted** in that schedule's `schedule_local_delivery` row, replacing any previous one |
| an action is delivered with the matching nonce | the use case runs, and the nonce is **cleared** in the **same transaction** as the write |
| an action is delivered with a missing, stale or already-cleared nonce | **nothing is read and nothing is written**; the broadcast is dropped |
| the notification is replaced by a later digest run | the new issue **overwrites** the old, so the previous notification's action no longer matches |
| `reconcile` clears the notification | the nonce is **cleared** |
| the process dies between issue and delivery | the nonce is **still in the table**, so the action still works |

**What each action writes** is master plan §12.1's table and is the authority. The two rules an implementer will otherwise get wrong:

1. **A receiver never starts an activity** (API 31+ trampoline rule, invariant 55). "Done" on a `FORM` schedule is `PendingIntent.getActivity` **directly** — it does not broadcast to a receiver that then launches the form.
2. **The nonce is checked before anything is read or written**, and consuming it is part of the same transaction as the write, so a replayed broadcast cannot land twice.

## Invariants this brief must hold

**20, 54, 55, 56, 57** (master plan §13).

## Test matrix

One test per hazard class; all off-device with a fake `NonceStore` and fake use cases, except the one connected row.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| "Done" completing more than once | "Done" on a `QUICK` schedule creates **exactly one** completion event, rebuilds, runs `reconcile`, and the notification clears (#11 AC 1) | a receiver that does not consume the nonce completes again on a redelivered broadcast |
| "Done" on a form fabricating data | "Done" on a `FORM` schedule creates **nothing** until the form is saved, and the action's intent is an **activity** intent (#11 AC 2, invariant 55) | a broadcast that writes a minimal event silently invents the structured data the form exists to collect |
| **a forged or replayed broadcast** | one test covering all three: a broadcast with a **missing** nonce, a **stale** one (a nonce issued for an earlier notification) and an **already-used** one each write **nothing** and leave the event count unchanged (invariant 56, #11 AC 4) | without the check any app on the phone can complete the owner's maintenance by guessing an intent |
| a nonce lost to process death | a nonce issued, the process's in-memory state discarded, and the action then delivered still **works** — because the nonce is in `schedule_local_delivery`, not in a field (D-21) | an in-process nonce makes every quick action fail after the app is swapped out, which is most of them |
| the nonce outliving its notification | replacing a notification, and running `reconcile`, each **clear** the nonce, so the previous notification's action stops working (D-21) | a nonce that persists lets an old notification act on a schedule whose state has moved |
| a snooze moving a date | "Snooze 1 day" writes `snoozed_until_at` **only**: no `*_on` column changes, no event is created, and the dashboard still shows the schedule OVERDUE badged **"Snoozed until \<date\>"** (invariant 20, #11 AC 3) | writing the snooze onto the due date is the exact confusion #11 and D-13 exist to prevent |
| a trampoline | a structural assertion plus a behavioural one: `QuickActionReceiver` never calls `startActivity` and never constructs an activity `Intent`; the two activity-backed actions use `PendingIntent.getActivity` (invariant 55) | Android 12+ silently drops the launch, so the action appears to do nothing |
| an exported receiver | `QuickActionReceiver` is declared `android:exported="false"` and is addressed with an **explicit** intent naming its component (invariant 54) | an implicit intent to a non-exported receiver simply fails, and an exported one is the forgery door |
| a mutable `PendingIntent` | every `PendingIntent` this brief builds carries `FLAG_IMMUTABLE` (invariant 54) | a mutable one lets another app rewrite the schedule id the action acts on |
| **a group notification completing everyone** | a group-targeted schedule's notification offers **"Open"** and **only** "Open"; no "Done" and no "Snooze 1 day" action is attached (§2.4, invariants 28-30; D-8) | a "Done" on a group either completes nothing (confusing) or completes every member (false history) |
| the deep link mutating | `servicetag://schedule/<uuid>` **navigates** to the schedule detail and writes nothing — no completion, no snooze, no postpone, no closure (invariant 57) | a link handler that "helpfully" completes turns a URL into a mutation, breaking #19's contract |
| a malformed deep link | a non-canonical uuid, extra path segments, a missing segment and a foreign scheme each parse to `Malformed` or `null` exactly as the two shipped hosts do, reusing `single()`'s regex | a second, looser shape check lets a malformed id reach a repository lookup |
| a group deep link appearing | a structural assertion: no `servicetag://group` host exists in `DeepLinkRoute` or in the manifest (D-17) | adding one now is a surface with no 1.2 consumer and a permanent contract |
| the action labels drifting | the three labels are exactly **"Done"**, **"Snooze 1 day"**, **"Open"** | a paraphrase ships an unratified string |

**Connected, one class:** on `emulator-5554`, one test that posts a real notification for a `QUICK` schedule, triggers its "Done" action through the real `PendingIntent`, and asserts one event exists and the notification is gone. This is the only row that needs a real `NotificationManager`; everything else is a unit test.

## Strings

**Ratified, verbatim** (master plan §17): **"Done"**, **"Snooze 1 day"**, **"Open"**, **"Snoozed until \<date\>"**.
**This brief drafts none.** If an action needs a word that is not one of these, that is a finding for the controller.

## Ordering

**After B06** (needs `NonceStore`, `ReminderSnooze` and the notification build) and therefore after B05 and B04. It also needs `Route.ScheduleDetail` to exist, which **B08** supplies — if B07 runs before B08's shell lands, it may declare the route key itself and B08 adopts it; the review records which happened. Runs in lane B of wave 5 beside **B14**.

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`** with `ANDROID_SERIAL` pinned, never a phone: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.loosecannon.servicetag.reminders --console=plain`.
- Structural, anchored:
  - `grep -rn 'startActivity' app/src/main/kotlin/com/loosecannon/servicetag/reminders` → no match.
  - `grep -rn 'PendingIntent\.getActivity\|PendingIntent\.getBroadcast' app/src/main/kotlin/com/loosecannon/servicetag/reminders/QuickActions.kt` → every occurrence on the same line as, or guarded by, `FLAG_IMMUTABLE`; `grep -rn 'PendingIntent\.' app/src/main | grep -v FLAG_IMMUTABLE` → no match. **Amended at B07's review (2026-09-22):** the binding form is master §16's `grep -rnE 'PendingIntent\.(getBroadcast|getActivity|getService)' app/src/main | grep -vc 'FLAG_IMMUTABLE'` → 0, held by `DigestAlarmTest.everyPendingIntentIsImmutableOnItsOwnLine`.
  - `grep -cE '<receiver[^>]*QuickActionReceiver' app/src/main/AndroidManifest.xml` → 1, and the element carries `android:exported="false"`. **Amended at B07's review (2026-09-22):** the element is multi-line by this brief's own instruction, so the proof is `grep -c 'QuickActionReceiver' app/src/main/AndroidManifest.xml` → 1 plus the per-element `exported="false"` assertion in `ManifestContractTest`.
  - `grep -cE 'android:host="schedule"' app/src/main/AndroidManifest.xml` → 1; `grep -ciE 'android:host="group"' app/src/main/AndroidManifest.xml` → 0.
  - `grep -n '"schedule"' core/src/main/kotlin/com/loosecannon/servicetag/core/links/DeepLinkRoute.kt` → 1; `grep -ci '"group"' …/DeepLinkRoute.kt` → 0.
  - `grep -rn 'canonicalUuid\|Regex(' core/src/main/kotlin/com/loosecannon/servicetag/core/links/DeepLinkRoute.kt` → the one shipped regex, not a second.

## Estimated size

Small to medium. One receiver, one builder, one deep-link host, one manifest line, three test classes. No split expected.
