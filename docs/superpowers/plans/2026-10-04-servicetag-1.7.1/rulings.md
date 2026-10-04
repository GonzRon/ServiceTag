# ServiceTag 1.7.1 — the owner's rulings in force, and the decisions still open (2026-10-04)

Recorded from #104 (the roadmap, its 1.7.1 section and the owner's release-assignment comment of 2026-10-04) and from #103's body (the owner's layout ruling of 2026-10-04). Binding on every brief of `plan.md`. Where a later owner message revises this file, the later message stands and this file is amended at the same commit.

## In force

### R171-1 — the release vehicle and its scope (#104)

#94, #99 and #103 constitute **ServiceTag 1.7.1, versionCode 21**, a PATCH. Scope rule, verbatim: *"1.7.1 stays a PATCH release — bug fixes, test/reliability debt, documentation corrections and bounded UI cleanup only. Do not add a new user-facing capability to this train."* #101 completed before the train. #90 is promoted only on its own timing threshold, never as a step of this train.

### R171-2 — #94's fix shape

The issue offers two: the #91 shape (a strict read through the shipped 400 path) or widening `decodeOr400` to catch `IllegalArgumentException` (one line, but the 400 then carries kotlinx's internal text). **P1 took the #91 shape**, keeping `decodeOr400`'s catch as it is: a widened catch would also have turned every future bare `IllegalArgumentException` on every route into a 400 with internal wording, which is more than #94 asks. "One route case per body shape, nothing written" is honoured. If the owner prefers the one-line widening, P1's change reverts to it in a fix round; the route case stands either way.

### R171-3 — #99's fix shape

The issue offers `encodeDefaults = true` **or** an explicit list of names, and asks that `estate()` get a row in every list. **P2 did both encoding checks** (defaults encoded, and the key set asserted equal to `BackupData`'s element names, so a list added later cannot pass in silence) and put the rows **on the identity test's own fixture rather than in `estate()`**: `estate()` is read by six other suites as the sender's estate, and a transfer record or a succession on a fixture asset changes what `select` and `droppedBy` answer for all of them. The identity check now means something for every list, which is the issue's purpose. If the owner wants the rows in `estate()` itself, that is a P2 fix round with every dependent suite re-pinned.

### R171-4 — #103's layout (the owner's ruling, verbatim in its body)

1. Maintenance tab: Due work, Schedules, then a sub-menu (a grouped section or a second-level list, to be designed) holding **Maintenance groups**, **Supplies** and **Installed components** as peers. **Reminders** leaves the tab.
2. Settings › Utilities: a **Reminder health** row opening the same page by its existing route (`Route.ReminderHealth`).
3. Reminder health page: a visible healthy state — the time of the last check and the list of checks that passed — instead of an empty screen; findings and repairs unchanged.
4. Dashboard: the health badge that appears when a finding is WARN or worse keeps navigating to the page; nothing else changes.

Not in scope: any change to the checks, the repairs, the schedules, groups, supplies or installed-components screens themselves; no new capability; every new user-visible string is ratified before it ships.

## Owner adjudication (2026-10-04, later) — the gate is passed; P3 authorized

- **Q1 APPROVED:** a grouped section of three peer rows in the Maintenance tab — Maintenance groups · Supplies · Installed components; the inline maintenance-group list moves to its own pushed screen.
- **Q2 APPROVED:** a read-only, cross-asset Installed components pushed list; each row identifies enough parent-asset context to be unambiguous; tapping a row opens that Asset's detail; no second component-management surface.
- **Q3 RATIFIED:** all thirteen strings of plan.md §4, exactly as written.
- **Q4 APPROVED:** Last checked is the Reminder Health run's own execution/completion instant, cached alongside that run's findings; never screen-open time, and it does not advance because the screen was viewed.
- The navigation ruling stands: Reminders leaves the Maintenance tab and moves to Settings › Utilities › Reminder health; a healthy Reminder health screen shows the last-check time and the checks that passed.
- No version bump yet: the bump, the `versioning.md` row and the 1.7.1 release notes are P4, after #103 has merged.

## Decisions as they were put to the owner (resolved above)

### Q1 — the sub-menu's shape

**Recommended: a grouped section in the tab.** Under one heading (P171-1) three `NavigatingRow`s — `Maintenance groups`, `Supplies`, `Installed components` — each opening a pushed list. The inline `GroupList` (B15) leaves the tab for its own pushed screen, titled `Maintenance groups`, which hosts the same composable with its create row, so the groups' create affordance is still reachable from the tab in one tap and C5's reachability ruling of 2026-09-22 still holds. The tab is then five rows plus the schedules, however many groups the owner has — which is what cures "below the fold". No new route for the three rows themselves.

Alternative: a second-level list — one row in the tab (P171-1 as its label) opening a pushed screen of the three rows. One more tap to everything; the tab is shortest. The planner does not recommend it: Supplies would be two taps from the tab where it is one today.

### Q2 — the Installed components entry

There is **no existing list of installed components**: they are drawn per asset (`InstalledComponentsSection`), and the one cross-asset list is the share picker's (#69 C30), which is share-shaped. **Recommended:** a new read-only pushed destination, `Route.InstalledComponents`, listing every **current** installed component (`removedOn == null`) of every asset, ordered by asset name then the component's `sortOrder`, each row the component's name with the asset's name as its quiet second line (the share picker's path wording, P69-20, no new string), tapping a row opening the **asset's detail**, where the component is edited, replaced and removed. Empty, it draws `NO_INSTALLED_COMPONENTS`. It writes nothing and adds no action — a navigation entry over existing rows, within the PATCH scope. Its view model is JVM-tested.

Alternative: the row opens `Route.InstalledComponentDetail` (the component's files and links, #69 C27). The planner does not recommend it: the owner looking for "what is fitted where" wants the asset, and the detail reaches the asset only through its back stack.

### Q3 — the strings

`plan.md` §4, P171-1 … P171-7g. Alternatives recorded there. Nothing ships unratified.

### Q4 — the healthy state's "last check" instant

**Recommended:** the instant of the run whose result the page shows — recorded by `ReminderHealth` beside its cached findings when a run publishes (`refresh` and `runAndRepair` alike), read by the view model into `HealthState`, kept in no preference and no table. It is then exactly the run the rows came from, it survives nothing it should not (a process restart re-runs the check at launch, decision 32), and it needs no clock seam the view model does not already have. Formatted on the screen by `displayDate` and the phone's short time format (P171-5).

Alternative: a preference written at every run. Not recommended: it is a second record of the same fact, and a restore would carry it to a phone that never ran the check.

## The 1.7.1 proof sequence

(1) the unit, JVM, Python and Android gates; (2) P3's task review, then the whole-branch review at the final tip; (3) the signed candidate installed in place over 1.7.0 on `emulator-5554`, `/v1/status` 1.7.1 / 21, schema 21, format 20, every count identical, the three #103 surfaces looked at once; (4) release, tag, artifact verification; (5) the development phone, in place, R7; (6) the production phone, in place, R7. No STOP for data: 1.7.1 writes nothing new on install. **Neither phone is touched during implementation.**
