# B09 — #50 the NFC scan completion sheet

**Read first:** the master plan's §1, §11.2 (the scan completion sheet), §11.1 (the attention ordering it reuses) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.8; rulings D-18a, D-18b, D-25; D5 §7A (`docs/design/05-scheduling-semantics.md:196-239`); issue snapshots `issue-50.md`, `issue-49.md`.

## Purpose

Make a scan the fastest way to quiesce maintenance when the owner is standing at the equipment — without the scan ever mutating anything. Scanning an active tag resolves to its Asset as it does today; if that Asset has actionable maintenance, a sheet titled **"Maintenance"** appears **before** the ordinary detail path, listing what is actually actionable, showing per item why it is actionable now, and offering completion, review, snooze, postpone, and a way out. Completion delegates **entirely** to #4's and #55's use cases through B14's flows: this brief invents no second completion path, fabricates no structured data, and never claims a group's other members were serviced. The tag proves proximity; the owner's explicit selection is the mutation.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/MaintenanceSheet.kt` — the sheet.
- `app/.../ui/maintenance/MaintenanceSheetViewModel.kt` — its state: which asset, which tag's placement label, the actionable items, the selection, the sequential-form queue.
- Tests: `app/src/test/kotlin/.../ui/maintenance/MaintenanceSheetViewModelTest.kt`; connected `app/src/androidTest/kotlin/.../ui/maintenance/ScanSheetTest.kt`.

**Modify**

- `app/.../ui/nav/ServiceTagRoot.kt` — the `Route.MaintenanceSheet(assetId, tagId?)` destination, reached **only** from a resolution that is `Resolution.OpenAsset` **and** has actionable work; every other resolution routes exactly as it does today.
- `app/.../ui/scan/ScanViewModels.kt` and `app/.../ui/scan/TagResultSheet.kt` — the branch that asks "is there actionable work?" after a successful resolve, and the placement label carried through. **The resolve itself is unchanged.**
- `app/.../nfc/NfcDispatchActivity.kt` / `app/.../MainActivity.kt` — the ambient-dispatch landing gains the same branch. **No second NFC session, no change to `ReaderMode`.**
- `app/.../di/AppGraph.kt` — the sheet's view-model factory.

**Untouched:** `core/**` in its entirety — `ResolveTag` (`core/.../core/usecase/ResolveTag.kt`), the `Resolution` type, `DeepLinkRoute`, every use case. `app/.../ui/nfc/ReaderMode.kt`. `api/**`, `data/**`, `reminders/**`, `tools/`, `libs/`. `Route.readsTags()` — **the sheet reads no tags and must not be added to it**.

## Interfaces

**Consumes from B08:** `DueReadModel.forAsset(assetId)`, `DueItem`, `AttentionSection`. **Plus one scoped read-only seam of its own** (see below). **Amended at B07's review (2026-09-22):** from B07, the `QuickActionTarget` for "Done" on a FORM/meter schedule, which currently opens the schedule detail — this brief redirects it to B14's `CompletionFlow` (the one-line target change B07 prepared).

**The last completion's readings need a read this brief must declare.** The sheet must show "the last completion's date and its **key readings**" (D5 §7A `:219-221`). `DueItem` carries `lastCompletedOn` but **not** the completion's measurement values, and it structurally cannot: profile values are heterogeneous per schedule, so a generic projection field would be the wrong shape. This brief therefore declares one **read-only** collaborator:

| seam | shape | rule |
|---|---|---|
| `LastCompletionReadings` | `suspend fun forEvent(eventId: EventId): List<Measurement>` | **reads only.** It is backed by the shipped `EventRepository.get` (`core/.../core/ports/Repositories.kt:86`) and exposes **no write method at all**, so the sheet cannot reach a write path even by accident |

`Plan decision:` the seam rather than handing the sheet the whole `EventRepository`. The brief's own gate forbids the sheet from touching a write surface, and a read-only collaborator is how the display fact D5 §7A requires and that prohibition can both hold — handing over the full port would make the gate grep either vacuous or wrong. **From B14:** the completion flow (the "When was this done?" affordance, the form route, the meter prompt), `PostponeSchedule`'s UI entry, and `Route.ScheduleDetail`. **From B06:** `ReminderSnooze`, and `reconcile` after a completion. **From B03:** `GroupOccurrence`, `CompleteGroupMembers`. **From B11:** the tag's placement label. **From the shipped code:** `Resolution` and `ResolveTag`, used as they are.

**Produces:** nothing another brief consumes. It is a leaf.

**Which items appear, as contract** (D-18a — the reconciliation of #50 with D5 §7A):

| status | on the sheet? |
|---|---|
| `OVERDUE`, `DUE` | **always** **Amended at B09's review (2026-09-22):** shown whenever the row has a date or a meter line; a repairable `NO_DATA` row has neither and shows its repair label only — §17 ratifies no 'needs a baseline' sentence. |
| `NO_DATA` | **only when it has a repair action** — the missing meter baseline, offered as **"Log meter reading"** |
| `DUE_SOON` | **only as a passenger**: when the sheet is already open for another actionable item. **Never alone** |
| `OK`, `INACTIVE_SEASON`, `PAUSED` | **never** |
| an archived or reminders-disabled schedule | **never** |

Items are ordered by master plan §11.1's attention ordering, through `DueReadModel` — not by a second rule.

**What the sheet shows** (D5 §7A `:219-221`) — the header, then one row per item. Every field comes from `DueItem` or the item's schedule; **nothing here is a new query**:

| where | field | source | shown when |
|---|---|---|---|
| header | which asset was scanned | `Resolution.OpenAsset.asset` | always |
| header | the scanned tag's **placement label** | `Resolution.OpenAsset.tag.label` (B11) | present and non-blank |
| item | the **state word** | `DueItem.status` → the ratified status term | always |
| item | the **due date** and/or the **meter threshold** | `effectiveDueOn`, `computedDueMeter` | whichever the rule has |
| item | the **current meter value** | `currentMeter` | the rule has a meter side |
| item | the **last completion's date** and its **key readings** | `lastCompletedOn` + that event's measurements | a completion exists |
| item | **one concise line on why it is actionable now** | composed from the item's own status, dates and numbers — **no new sentence template** | always |
| item | **quick versus form**, visible **before** selection | `completionMode` | always |
| item | the group's **progress** | `membersRequired` / `membersComplete` → the ratified form | a group target |

**What the sheet can do** — five semantically distinct actions, **none of them an alias for completion** (D5 §7A `:230-232`): **"Complete selected"**; **"Review maintenance"** (the schedule detail); **"Snooze"** (notification only); **"Postpone"** (this occurrence only); **"Not now"**. And **"Open asset"** always reaches the ordinary detail.

**Completion delegation, as contract:**

- `QUICK` writes its minimal event after **explicit selection**; `FORM` opens the profile form and **fabricates nothing**; a meter schedule **cannot** complete without its required reading.
- Completion asks **"When was this done?"**, defaulting to today, with an optional time, so a **backdated** completion is first class (D7 Phase 3 exit criterion, `docs/design/07-implementation-sequence.md:114`).
- Several selected forms complete **sequentially and explicitly**, never partially and silently.
- Completion then runs `reconcile`, so the notification is quiesced **by canonical state** and not by deleting a notification (#50 AC 8).
- For a scanned **member** of a due group occurrence the sheet offers that occurrence and completing it marks **this member only** (#50 comment, 2026-09-21).

## Invariants this brief must hold

**5, 28, 29, 57, 58, 74** (master plan §13). **74** is this brief's at the UI boundary: master plan §11.1 promises that an empty-required-set `NO_DATA` is **never offered on the scan sheet**, and this is the surface that must honour it — master plan §13 credits this brief as **B09 (UI)** on 28 and 29, meaning it proves the narrower fact that *the sheet cannot violate* them while B03 proves them where they are enforced. **Invariant 5** ("a group never holds an NFC identity; no tag resolves to one") is proved here **structurally, not by a dedicated test**: `TagTarget` has no group case (`core/.../core/model/TagBinding.kt:7-11`) and `Resolution` has no group variant (`core/.../core/usecase/ResolveTag.kt:16-30`), so the exhaustive `when` over `Resolution` this brief asserts is what forecloses it — a reviewer should not have to re-derive that connection. It must not weaken **20, 21, 32**: its Snooze and Postpone call B02's and B06's operations unchanged, and a repeat scan after a completion cannot re-offer the occurrence.

## Test matrix

One test per hazard class; unit tests over the view model with a seeded read model, plus a small connected set.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the sheet appearing when nothing is due | a scan of an asset with **no** actionable work opens the **ordinary asset screen**, exactly as today (#50 AC 1) | showing an empty sheet turns every scan into a dismissal |
| one due quick item, and the reminder quiesced by state | one due `QUICK` schedule shows the sheet; explicit completion creates **exactly one** event, the schedule is **no longer offered for that occurrence** (#50 AC 2), **and `reconcile` runs** so the standing notification is quiesced **by canonical state and not by deleting a notification** (#50 AC 8) | offering it again after completion means a second scan double-logs; and clearing the notification directly instead of reconciling makes the notification the source of truth, which is the inversion #50 AC 8 forbids |
| two selected together | two due `QUICK` schedules can be selected and completed **without duplicating either event** (#50 AC 3) | a shared completion path that reuses one occurrence key collides on the unique index |
| a form silently completed | a `FORM` schedule routes through its canonical form and is **not** marked complete until the form is saved (#50 AC 4) | fabricating a minimal event invents the structured data the form exists to collect |
| a meter schedule completed without its reading | a meter schedule **cannot** be completed without the required reading (#50 AC 5) | a completion with no reading leaves `last_completed_meter` unknown and the next threshold wrong |
| **the quiet states appearing** | one test asserting the whole D-18a set: `DUE` and `OVERDUE` appear; `NO_DATA` appears **with "Log meter reading"**; `DUE_SOON` appears **only** alongside another actionable item and **never alone**; `OK`, `INACTIVE_SEASON` and `PAUSED` never appear; an archived schedule never appears; **and a group schedule whose required set is empty — reporting `NO_DATA` with *no* repair action — never appears** (master plan §11.1, invariant 74) | listing everything turns a scan into "a completion checklist for every future maintenance item", which #50 `:58` forbids; excluding `NO_DATA` loses the one repair the owner is standing next to |
| a backdated completion computing the wrong next date | completing with **yesterday's** date yields the next due date derived from the **backdated** event, not from today (D-25, the D7 exit criterion) | defaulting silently to today makes "when was this done?" cosmetic |
| several forms completing partially | selecting two `FORM` items runs the forms **sequentially**, and abandoning the second leaves the first's event written and the second's **absent** — never a partial silent write | a batch write that starts both leaves half-finished structured data |
| **an action aliasing completion** | one test per action asserting it does **only** its own thing: **"Review maintenance"** navigates and writes nothing; **"Snooze"** writes only the device-local instant, changes no date and creates no event; **"Postpone"** changes only `postponedDueOn`, creates no event and changes no rule; **"Not now"** and the system back gesture write **nothing at all** (#50 AC 6) | collapsing these into "reschedule" is the failure D5 §7A names explicitly |
| "Open asset" not escaping | **"Open asset"** reaches the ordinary detail from the sheet, with no mutation (#50 AC 7) | a sheet with no way out traps the owner |
| **the display facts missing** | **one composed assertion** that an item shows its state word, its due date and/or meter threshold, its current meter value, its last completion's date and key readings, and its why-now line; and that quick versus form is visible before selection (D5 §7A, per the proportionality ruling this is one test, not five) | a bare title-and-status row cannot answer "is this the thing I am standing next to and should I do it now" |
| a second tag on one asset | a second active tag bound to the same Asset shows the **same** work, with **its own** placement label as the context (#50 AC 9, #49 AC 3) | per-tag schedule state, or showing the first tag's label, misidentifies the scan point |
| **a bad tag reaching completion** | **one test per non-`OpenAsset` resolution** — `Unbound`, `Revoked`, `UnknownV1`, `NeedsNewerApp`, `NotOurs`, `PreSplitLink` — asserting the sheet is **unreachable** and the shipped routing is unchanged (invariant 58, #50 AC 10) | a resolution switch with an `else ->` branch lets a revoked or foreign tag into the completion path, which is the #35 input-safety boundary |
| **the scan mutating** | a scan, an NFC dispatch and `servicetag://tag/<id>` each **navigate only**: no completion, no snooze, no postpone, no closure. `ResolveTag`'s `lastScannedAt` write is the **only** write, and it is informational (invariant 57, #50 AC 11) | a sheet that "helpfully" completes a single due item on open breaks #19's navigation-only contract |
| repeat-scan idempotence | scanning again **after** a successful completion does not re-offer that occurrence (#50 AC 12) | reading due state from a cached list rather than from canonical state re-offers completed work |
| **a scanned group member completing everyone** | a scanned member of a due group occurrence is offered **that occurrence**, and completing it marks **this member only** — every other member's history byte-identical (invariants 28, 29) | a group-level "complete" from the sheet is the false history #55 AC 5 forbids |
| a group's progress misread | the offered group item shows the ratified progress form and, after this member completes, the occurrence is still **open** if others remain | closing the round on one member's completion loses the outstanding work |
| reader mode handed back | a structural assertion: `Route.readsTags()` is unchanged and `Route.MaintenanceSheet` is **not** in it; and the sheet installs no tag sink | adding the sheet to `readsTags()` holds reader mode over a screen that reads nothing and re-opens the #37 re-dispatch |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone — the emulator has no NFC, so the resolution is injected):** one class covering the sheet appearing for a seeded due schedule, "Complete selected" on a `QUICK` item, "Not now" writing nothing, and "Open asset" reaching detail. The tag-resolution rows are unit tests.

## Strings

**Ratified, verbatim** (master plan §17): the sheet title **"Maintenance"**; **"Complete selected"**; **"Open asset"**; **"Not now"**; **"Review maintenance"**; **"Snooze"**; **"Postpone"**; **"When was this done?"**; **"Log meter reading"**; **"Tag placement"** (the label's caption, from B11); the progress form **"3 of 5 complete"**; and the status terms **DUE · OVERDUE · NO BASELINE**.

**This brief drafts nothing.** The why-now line is composed from the item's own data — its status word, its dates, its meter numbers — and **must not** introduce a new sentence template. If one is needed, that is a finding for the controller before this brief executes, not a string to invent.

## Ordering

**After B02, B03, B08 and B14** — B14 owns the completion flow and the "When was this done?" affordance this sheet delegates to, which is why B14 gates it. Runs in lane A of wave 6 beside **B15**.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.maintenance.ScanSheetTest --console=plain` → zero failures, zero skips.
- Structural, anchored:
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/ui/nfc/ReaderMode.kt` → **empty**.
  - `grep -n 'fun Route.readsTags' -A 6 app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` → unchanged; `grep -c 'MaintenanceSheet' app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` counts the route key and no `readsTags` entry.
  - `grep -rn 'Resolution\.' app/src/main/kotlin/com/loosecannon/servicetag/ui/scan app/src/main/kotlin/com/loosecannon/servicetag/ui/nav` shows an **exhaustive** `when` over `Resolution` with no `else ->` on the path that reaches the sheet.
  - `grep -rn 'enableReaderMode\|NfcReaderModeSession' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance` → no match.
  - `grep -rnE '(CompleteSchedule|CompleteGroupMembers|CloseRound|ReminderSnooze|PostponeSchedule)' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/MaintenanceSheetViewModel.kt` shows the use cases **called**. **Amended at B09's review (2026-09-22):** completion is `CompletionFlow`'s by contract — restate against `CompletionFlow.kt`; on non-comment lines the sheet's own matches are the `PostponeSchedule` import and constructor field only.
  - **The write-surface grep, narrowed to the write methods** so the read-only `LastCompletionReadings` seam is not caught by it: `grep -rnE '(events|closures)\.(upsert|insert|delete)' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/MaintenanceSheetViewModel.kt` → **no match** (no second completion path). A bare `EventRepository` identifier is **not** the pattern: the sheet legitimately reads an event's measurements through the declared seam, and the earlier form of this grep would have failed a correct implementation.
  - `grep -rn 'CloseRound' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/MaintenanceSheet.kt` → no match: **"Close this round" is not on the sheet** (spec §1.2 offers it in the editor only).

## Estimated size

Medium to large. One sheet, one view model, two routing branches, one connected class. If it exceeds what one review holds, split at **B09a the sheet, its item set and its display** / **B09b the completion, snooze and postpone actions and the sequential form flow** and say so in the ledger.
