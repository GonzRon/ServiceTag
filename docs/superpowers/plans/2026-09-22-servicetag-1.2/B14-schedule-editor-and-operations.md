# B14 — the schedule editor and the five operations

**Read first:** the master plan's §1, §5.2 (the recurrence contract), §7 (round closure), §11 (navigation), §12 (the permission request) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.1, §2.9, §5.1, §4.1's PATCH note; rulings D-9, D-11, D-12, D-25, D-28, D-24; issue snapshots `issue-4.md`, `issue-24.md`.

## Purpose

The surface without which 1.2 ships an engine nobody can drive: create and edit a schedule against **one asset or one group**, and perform the five in-app operations — **complete**, **snooze**, **postpone**, **close this round**, **edit recurrence** — each doing exactly its own thing and never collapsing into a generic reschedule. It is also where `POST_NOTIFICATIONS` is requested, **on first schedule creation with a rationale**, which is the only place #24 AC 1 can be asserted at all. Every rule the editor enforces is B02's or B03's use case, called; the editor's job is to make the illegal states unreachable through the UI and to say the ratified words.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditScreen.kt` and `ScheduleEditViewModel.kt` — create and edit, in the shape of the shipped editors (`app/.../ui/setup/DefinitionEditScreen.kt`, `ProfileEditScreen.kt`).
- `app/.../ui/maintenance/ScheduleDetailScreen.kt` and `ScheduleDetailViewModel.kt` — the schedule's own screen: its state, its history, and the five operations.
- `app/.../ui/maintenance/CompletionFlow.kt` — the shared completion affordance: **"When was this done?"** with today's default and an optional time, the meter prompt where a meter rule requires a reading, and the route into the profile form for a `FORM` schedule. **B09 delegates to this**, which is why B14 gates B09.
- `app/.../ui/maintenance/CloseRoundDialog.kt` — the confirmation and the date picker bounded to the occurrence's open date through today.
- Tests: `app/src/test/kotlin/.../ui/maintenance/ScheduleEditViewModelTest.kt`, `ScheduleDetailViewModelTest.kt`, `CompletionFlowTest.kt`; connected `app/src/androidTest/kotlin/.../ui/maintenance/ScheduleEditorTest.kt`, `ScheduleOperationsTest.kt`.

**Modify**

- `app/.../ui/nav/ServiceTagRoot.kt` — `Route.ScheduleEdit` and `Route.ScheduleDetail` wired into the back stack.
- `app/.../ui/maintenance/MaintenanceScreen.kt` (B08's) — the "add a schedule" entry point and the schedules list's rows routing to the detail.
- `app/.../ui/asset/AssetDetailScreen.kt` — the "add a schedule for this asset" entry point. **B15 owns the schedules *section*** on that screen; this brief adds only the create entry.
- `app/.../di/AppGraph.kt` — the two view-model factories and the completion-flow collaborator.

**Untouched:** `core/**` — every rule is B02's or B03's use case, **called, never re-implemented**; `api/**`; `data/**`; `reminders/**` except the one call into B05's permission seam; `nfc/**`; `tools/`; `libs/`; `ui/theme/**`.

## Interfaces

**Consumes from B02:** `SaveSchedule` and the schedule command with its problem types, `PostponeSchedule`, `PauseSchedule`, `ArchiveSchedule`, `CompleteSchedule`, `statusOf`, `Today`, `ScheduleRepository`, `ScheduleStateRepository`. **From B03:** `CloseRound` and its four refusals, `CompleteGroupMembers`, `GroupOccurrence`, `GroupRepository`. **From B05:** `NotificationPermission` — and **this brief is its only caller**. **From B06:** `ReminderSnooze`. **From B08:** the Maintenance shell's entry points and `Route.ScheduleDetail`/`ScheduleEdit`.

**Produces, for B09:**

```kotlin
class CompletionFlow(/* CompleteSchedule, CompleteGroupMembers, definitions, Today */) {
    // Opens the "When was this done?" affordance, collects an optional time, requires a meter
    // reading where the rule has one, and routes a FORM schedule into its profile form.
    // Returns only after the event is written, or not at all if the owner backs out.
    suspend fun complete(scheduleId: ScheduleId, assetId: AssetId?): CompletionOutcome
}
```

`Plan decision:` the flow is a shared collaborator rather than duplicated in the sheet and the detail screen. Spec §2.8 says the sheet "delegates entirely to the #4 use cases" and spec §2.9 gives the in-app close the **same** date affordance as the API; one collaborator is how the surfaces cannot diverge, and it is why this brief gates B09 rather than the other way round.

**It now has a third caller.** The owner ruled F4 IN at the gate (master plan §1.2), so the Maintenance destination's **"Log maintenance"** quick action — **B08's** affordance — also routes through this flow. **`CompletionFlow` is therefore the only completion mechanism in 1.2**, reached from the schedule detail (this brief), the scan sheet (B09) and the quick action (B08), and **none of those three writes an event of its own**. B08 states it and proves it; this brief states it because the flow is where it is true.

**The editor's fields**, with the **ratified** wording (D-24) it must use verbatim:

| field | wording / rule |
|---|---|
| target | one asset **or** one group. **Both set must be unreachable through the UI** — the choice is made once, at creation, and is not editable afterwards |
| interval | **"Every N"** + the unit (`DAY｜WEEK｜MONTH｜YEAR`) |
| basis | **"Repeats from"** → **"the scheduled date"** (FIXED) or **"when I complete it"** (COMPLETION) |
| anchor | the date the series is computed from; required whenever an interval is set |
| lead | **"Remind me N days early"** |
| meter rule | the meter definition, the interval, and **`anchor_meter`** ("last serviced at …"). **Offered only for an asset target** (D-12) |
| season | `FOLLOW_ASSET` or `IGNORE`. **A group target is `IGNORE` only** and `FOLLOW_ASSET` must be unreachable for one (D-28) |
| completion mode | `QUICK` or `FORM`, with the profile chosen for `FORM`. **A group target is QUICK-only and carries no profile** (D-12) |
| providers | a **single-choice** row. The table permits several; the editor writes **one** (#4 "Provider selection"; the multi-provider UI is #25) |
| reminders | the per-schedule toggle |

**The five operations, and what each may do** — master plan §5.2 and §7 are the authority; the UI's obligations:

| operation | offered when | does |
|---|---|---|
| **complete** | the schedule is actionable | `CompletionFlow`, then `reconcile`. Clears the postponement **only if it was set** |
| **"Snooze"** | a notification could be suppressed | the device-local instant **only**: no date changes, no event |
| **"Postpone"** | there is a current occurrence | `postponedDueOn` **only**: no rule change, no event; the **next** occurrence still comes from the rule |
| **"Close this round"** | **only** on a group-targeted schedule with an **open**, **non-empty**, **partially complete** round | one `occurrence_closure` row and nothing else |
| **edit recurrence** | always, on an ACTIVE or PAUSED schedule | the rule; clears the postponement; abandons an open partial occurrence (D-9); moves the D-27 pin's floor to the edit date |

**The permission request** (spec §5.1, #24 AC 1): on the **first** schedule creation, with the ratified rationale, through B05's seam. **Not at launch**, **not on a later creation** once the answer is known, and a **denial is not fatal** — the schedule is created, the editor completes, and the denial becomes B10's finding (D-22).

**The D-11 warning** (non-blocking): when the asset being scheduled already has a similar operation through a group, the editor shows the ratified sentence and **allows both schedules**. `Plan decision:` "similar" is a **case-insensitive match on the schedule `title`** between the asset's own schedules and the schedules of the groups it is an open member of. D-11 rules the warning in and rules semantic deduplication out but does not define the trigger; a title match is the cheapest signal that cannot itself become a deduplication rule, and it is non-blocking, so a false positive costs a dismissed line and a false negative costs nothing.

## Invariants this brief must hold

**1, 2, 3, 19, 20, 21, 25, 27, 35, 36, 43, 74, 77, 78** (master plan §13) at the UI boundary — **74 and 77** because the "Close this round" gate is where an empty required set must be unreachable through the UI, which is what that row's test proves, and it must not weaken **17, 18** — the editor reads `statusOf` and never stores a status.

## Test matrix

One test per hazard class; unit tests over the two view models, plus a small connected set for what only a real Compose tree shows.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| a schedule cannot be created at all | one test each: a schedule is created against **an asset** and against **a group**, and both appear in the Maintenance list | this is the brief's reason to exist — spec §7 records that 1.2 would otherwise ship an engine with no way to create a schedule |
| **both targets set** | a structural and behavioural assertion: the UI offers the target choice **once**, and no interaction sequence produces a command with both `targetAssetId` and `targetGroupId` (invariant 1) | an editor with two independent pickers makes the illegal state reachable, and the 422 becomes a user-visible dead end |
| **an illegal group schedule** | one test asserting all three: a group target offers **no** meter rule, **no** profile, and **no** `FOLLOW_ASSET` season option; and if such a command is constructed anyway the use case refuses it (invariants 2, 3, 27; D-12, D-28) | the natural editor shows every field for every target and the owner builds a schedule the engine rejects on save |
| **the permission asked at the wrong time** | one test asserting both halves: the request fires on the **first** schedule creation with the ratified rationale, and **not at launch** — asserted by a structural check that `NotificationPermission.request` has no call site outside this brief; and a **denial** still creates the schedule and disables nothing (#24 AC 1, D-22, invariant 61) | asking at launch gives no context and is the reflex; treating a denial as fatal is what D-22 forbids |
| the permission asked repeatedly | a second creation after an answered prompt does **not** re-ask | re-prompting on every creation is the nag #24 avoids |
| **the five operations collapsing** | one test per operation asserting what it changes **and what it does not**, against master plan §5.2's table: complete writes the event and clears the postponement **only if set**; snooze changes no date and creates no event; postpone changes only `postponedDueOn` and the **next** occurrence still comes from the rule; close writes one closure row, **no schedule column** and **no event**; a recurrence edit changes the rule, clears the postponement and abandons an open partial round (invariants 19, 20, 21, 35, 36) | a single "reschedule" action, or a shared "save" that writes everything, breaks one of the five "does not" assertions — and the postpone-then-complete case is where a rule-from-postponed-date implementation surfaces |
| **"Close this round" offered wrongly** | one test asserting the whole gate: the action appears **only** on a group-targeted schedule whose round is **open**, **non-empty** and **partially complete**; and it is **absent** for an asset target, a fully complete round, an already-closed round and an **empty** required set (spec §1.2, invariants 74, 77) | offering it on an asset schedule or an empty group produces a 409 the owner cannot act on, and offering it on a complete round invites a closure that is inert |
| **the close reading as "mark all done"** | the confirmation states that the outstanding members are **not** being recorded as serviced, and confirming writes **no** member event — every member's history byte-identical (invariant 36) | without the sentence the action reads as "complete everything", which is the false history D-8 exists to avoid |
| the close date unbounded | the date picker offers **only** the occurrence's **open date through today**; the day before the open date and tomorrow are **unreachable**; the default is **today** (invariant 78) | an unbounded picker permanently moves the schedule's future on a row that can never be amended |
| a closure amended or deleted | a structural assertion: no screen in this brief offers an edit or a delete for a closure; the closure history is read-only (invariant 43) | a "fix that date" affordance on immutable history is the amendment the whole design forbids |
| **a backdated completion** | completing with **yesterday's** date yields the next due date derived from the backdated event; the affordance defaults to **today** and accepts an optional time (D-25, the D7 Phase 3 exit criterion) | a silent today-only completion makes "When was this done?" cosmetic and loses the real service date |
| a meter completion without its reading | a meter-rule schedule **cannot** be completed until the reading is entered; a `FORM` schedule routes into its profile form and **fabricates nothing** | completing without the reading leaves the next threshold wrong; fabricating form data invents history |
| the duplicate-operation warning | the ratified sentence appears when the asset already has a similar operation through a group, and it is **non-blocking** — the schedule saves; it does **not** appear when there is no such schedule; **and the comparison set is exactly the asset's own schedules plus the schedules of the groups it is an open member of** — a same-titled schedule on an *unrelated* asset raises nothing (D-11, and the decision recorded above) | a blocking warning is the semantic deduplication D-11 rules out; a missing control makes the line permanent; and a global title comparison makes two owners both scheduling "Replace filter" warn each other forever |
| **an edit clearing a postponement** | editing the recurrence of a postponed schedule clears `postponedDueOn`, abandons an open partial occurrence, keeps the recorded member completions as **history**, and moves the pin's floor to the **edit date** (invariants 19, 25; D-9) | keeping the postponement across a rule change makes the schedule due on a date neither the old nor the new rule implies |
| a pause or archive losing state | pause and resume round-trip; archiving removes the schedule from the active lists and **keeps** its history and its closures | a destructive archive deletes the exported history |
| a provider row multiplying | the editor writes **at most one** enabled provider row (#4 "Provider selection") | a multi-select here pre-empts #25 and creates subject lists 1.2 cannot deliver |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone):** one class for the editor — create against an asset, create against a group, and the group target's three hidden fields; one class for the operations — the five actions on a seeded schedule, the "Close this round" gate, and the bounded date picker.

## Strings

**Ratified, verbatim** (master plan §17): **"Every N"**, **"Repeats from"**, **"the scheduled date"**, **"when I complete it"**, **"Remind me N days early"**; **"Close this round"** (the action label); **"When was this done?"**; **"Snooze"**; **"Postpone"**; **"This asset already has a similar schedule through another group."**; **"ServiceTag needs notification permission to remind you when maintenance is due."**; **"Log meter reading"**; **"Complete all"** and **"Complete selected"** where the detail screen shows a group round; the progress form **"3 of 5 complete"**; and the status terms **OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE**.

**Ratified at the gate** (owner, 2026-09-22; master plan §17.1b) — the **"Close this round" confirmation body**, as spec §12 drafted it, to be used verbatim:

> "Close this round? The members not marked done will not be recorded as serviced."

The **action label** is already ratified; this is the confirmation sentence, and it is the sentence that keeps the action from reading as "mark everything done". Hand it to the controller with this brief's plan review. **Do not execute the close dialog until the owner has ratified it.**

**Also ratified at the gate** (master plan §17.1c) — the **editor's remaining field labels and options**: the target picker **"This applies to"** ("One asset" · "A maintenance group"), the season-behaviour choice **"Out of season"** ("Pause with the asset's season" · "Remind me year round"), the completion-mode choice **"Completing this takes"** ("One tap" · "The full form"), the profile picker **"Use this form"**, and the four meter-rule fields **"Also due by use"**, **"Every \<n\> \<unit\> of use"**, **"Last done at"**, **"Remind me \<n\> \<unit\> early"**, plus the provider row **"Remind me through"**.

**This brief now has nothing left to draft.** Every string it draws is ratified and is used verbatim. It builds the largest new surface in 1.2 and is therefore the one most able to invent copy by accident, so the guard stands in its new form: **a string this brief finds it needs that master plan §17 does not list is a finding for the controller, never a string to invent.**

## Ordering

**After B02, B03 and B05.** **It gates B09**, which delegates to `CompletionFlow`. It also needs B08's shell for its entry points; if B08's shell half has not landed, this brief may add a temporary entry from asset detail and B08 adopts it — the review records which happened. Runs in lane A of wave 5 beside **B07**.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest --tests '…ui.maintenance.ScheduleEditorTest' --tests '…ui.maintenance.ScheduleOperationsTest' --console=plain` → zero failures, zero skips.
- Structural, anchored:
  - `grep -rn 'NotificationPermission' app/src/main | grep -v 'reminders/NotificationPermission.kt'` → **only** this brief's view model.
  - `grep -rnE '(SaveSchedule|CompleteSchedule|PostponeSchedule|CloseRound|CompleteGroupMembers|ReminderSnooze)' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` shows the use cases **called**; `grep -rn 'EventRepository\|events.upsert\|ClosureRepository' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` → **no match** (no rule re-implemented, no direct write).
  - `grep -rniE '\b(reschedule|rescheduleSchedule)\b' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` → no match.
  - `grep -rn 'CloseRound' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` shows the call reachable **only** from `CloseRoundDialog`'s confirmed path.
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/data` → **empty**.

## Estimated size

Large. An editor with eleven fields and three target-dependent hides, a detail screen with five operations, a shared completion flow, a bounded dialog, and two connected classes. If it exceeds what one review holds, split at **B14a the editor and the create flow (including the permission request)** / **B14b the detail screen, the five operations and the close dialog** and say so in the ledger — and note that **B09 waits on the `CompletionFlow` half**, so that half goes first.
