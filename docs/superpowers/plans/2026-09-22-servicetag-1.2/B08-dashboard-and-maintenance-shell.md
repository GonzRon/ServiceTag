# B08 — #5 the dashboard, and the Maintenance destination shell

**Read first:** the master plan's §1, §11 (navigation, the Maintenance destination and the dashboard) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.6 and the navigation ruling; D-15, D-22; D12 §5 (`docs/design/12-visual-design-apollo-service-binder.md:274-296`) and §10 (`:700-712`); issue snapshot `issue-5.md`.

## Purpose

Two things the rest of the UI stands on. First, the **due read model**: the dashboard extended from "active assets, filtered by search" to "what needs attention, ordered by `effective_due_on`, with status computed at read time", with every one of the seven statuses in its D12 §10 section, the component-promotion rule that stops a part's due work from being invisible, the group row that expands to its members and counts once, and the health badge. Second, the **Maintenance shell**: the bottom bar becoming **Dashboard · Assets · Maintenance**, and the destination that hosts due work, schedules (including the paused ones the dashboard deliberately omits), maintenance groups and reminder health — the surfaces B15, B10, B14 and B09 land inside. The shell is why this brief gates three others.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/MaintenanceScreen.kt` — the shell: four sections, each routing onward.
- `app/.../ui/maintenance/MaintenanceViewModel.kt` — the shell's state: the due items, the schedule list including PAUSED, the group list, the health summary.
- `app/.../ui/maintenance/DueReadModel.kt` — the shared projection: schedules + state + membership + `Today` → attention-ordered items with their section and rank. **Used by the dashboard, the Maintenance shell and B09's sheet**, so the order is one rule.
- `app/.../ui/maintenance/DueItemRow.kt` and `SchedulesSection.kt` — the composables the shell and the dashboard share.
- `app/.../ui/maintenance/QuickActions.kt` — F4's three persistent actions on the Maintenance destination. **No write of its own**; "Log maintenance" calls into B14's `CompletionFlow`.
- `app/.../ui/dashboard/DashboardFilters.kt` — F2's two filter controls and their state.
- Tests: `app/src/test/kotlin/.../ui/maintenance/DueReadModelTest.kt`, `MaintenanceViewModelTest.kt`, `app/src/test/kotlin/.../ui/dashboard/DashboardViewModelMaintenanceTest.kt`; connected `app/src/androidTest/kotlin/.../ui/maintenance/MaintenanceShellTest.kt`, `app/src/androidTest/kotlin/.../ui/dashboard/DashboardAttentionTest.kt`.

**Modify**

- `app/.../ui/nav/Route.kt` — `TopLevelRoutes` becomes three (`Route.Dashboard, Route.Assets, Route.Maintenance`); the new route keys of master plan §11 added. `Route.readsTags()` (`Route.kt:90-93`) is **unchanged** — no new screen reads tags.
- `app/.../ui/nav/BottomBar.kt` — `iconFor` and `labelFor` gain the Maintenance row; the label is the ratified **"Maintenance"**. The KDoc at `BottomBar.kt:15-19` ("Dashboard · Assets and nothing else") is updated to say three and why, and the same for `Route.kt:58-63`.
- `app/.../ui/nav/ServiceTagRoot.kt` — the Maintenance destination and the onward keys wired into the back stack.
- `app/.../ui/dashboard/DashboardViewModel.kt` — `DashboardState` gains the attention sections and the health badge; the blank-query filter at `DashboardViewModel.kt:121` gains the component-promotion exception. The existing `SEARCHED_FIELDS` (`:60-67`), the `needsBackup` logic and the `refresh` seam are **unchanged**.
- `app/.../ui/dashboard/DashboardScreen.kt` — the sections drawn in D12 §10 order.
- `app/.../di/AppGraph.kt` — the read model and the shell's view-model factory.

**Untouched:** `core/**` (this brief reads B02's and B03's output and adds no domain rule); `api/**`; `data/**` except nothing; `reminders/**` (it reads B10's summary through an interface); `nfc/**`; `ui/theme/**` (the semantic tokens are shipped); `tools/`; `libs/`.

## Interfaces

**Consumes from B02:** `MaintenanceSchedule`, `ScheduleState`, `DueStatus`, `statusOf`, `Today`, `ScheduleRepository`, `ScheduleStateRepository`. **From B03:** `GroupOccurrence`, `GroupRepository`. **From B10** (through an interface this brief declares so it can land first): a health summary.

**Produces, for B09, B10, B14 and B15:**

```kotlin
data class DueItem(                                // the one projection; nothing here is stored
    val scheduleId: ScheduleId, val title: String,
    val target: ScheduleTarget, val assetName: String, val parentName: String?,
    val status: DueStatus, val section: AttentionSection,
    val effectiveDueOn: LocalDate?, val computedDueMeter: Double?, val currentMeter: Double?,
    val lastCompletedOn: LocalDate?, val completionMode: CompletionMode,
    val membersRequired: Int?, val membersComplete: Int?,
    val snoozedUntil: Long?, val rank: Int,
)
enum class AttentionSection { ATTENTION, UPCOMING, CURRENT, OUT_OF_SEASON }

class DueReadModel(/* schedule, state, group, asset repositories + Today */) {
    suspend fun items(): List<DueItem>                       // attention-ordered, rank assigned
    suspend fun forAsset(assetId: AssetId): List<DueItem>     // B09's sheet, B15's asset section
}
interface HealthSummary { suspend fun worstSeverity(): Severity? }   // B10 implements
```

`Plan decision:` `DueReadModel` is a single shared projection rather than a query per screen. Spec §2.6 fixes the ordering and the section placement and spec §2.8 says the sheet uses "§2.6's attention ordering"; one projection is the only way those two cannot drift, and it is what gives B12's `/v1/due` its `rank` (master plan §9.3).

`Plan decision:` `HealthSummary` is declared here and implemented in B10, so this brief's badge can land and be tested against a fake before B10 exists. B10 consumes the interface rather than B08 importing B10.

**Three scope additions the owner ruled IN at the gate** (2026-09-22, master plan §1.2). All three are B08's, all three are one rendering or filtering change over data this brief already holds, and **every string they need is RATIFIED**:

| # | what this brief adds | the rule that constrains it |
|---|---|---|
| **F2** | filtering by **category** and by **maintenance status**, with labels **"Category"**, **"Maintenance status"**, **"All categories"**, **"All statuses"**. The existing category values supply one option set; the **ratified status words** supply the other | applied **after** the lifecycle and search filtering the shipped view model already does (`DashboardViewModel.kt:109-136`), and **§11.1's attention-section ordering is unchanged** — a filter narrows what is listed, never how it is ordered, sectioned or ranked |
| **F3** | a row whose schedule carries a **meter rule** shows the due threshold and the current reading, as **"Due at \<n\> \<unit\>, now \<n\>."** | `DueItem` already carries `computedDueMeter` and `currentMeter`, so this is **rendering, not a contract change**. A row with no meter rule renders **no** meter line; a meter rule with **no baseline** renders no numbers and appears as `NO_DATA` with its repair |
| **F4** | the Maintenance destination's three **persistent quick actions**: **"Scan tag"**, **"Add asset"**, **"Log maintenance"** | "Scan tag" and "Add asset" reuse the shipped routes. **"Log maintenance" routes through B14's canonical `CompletionFlow` and is never a second completion path:** it opens the same flow the schedule detail and B09's sheet use — the ratified "When was this done?" affordance, the meter prompt where a rule requires a reading, the profile form for a `FORM` schedule — and **writes nothing of its own**. A quick action that inserted an event directly would be exactly what #50 forbids |

**The section placement, the ordering and the promotion rule** are master plan §11.1's tables and are the authority. Restated as the three facts a reader of this brief needs: `NO_DATA` goes in **ATTENTION**; `PAUSED` appears in **no dashboard section at all** and is listed under Maintenance → Schedules with the PAUSED label; and a **component** asset with an actionable schedule is **promoted to its attention rank with its parent named**, while the blank-query "parts live on their systems" rule still governs every non-actionable row.

`Plan decision:` "actionable" for the promotion rule means a status in **ATTENTION or UPCOMING** — `OVERDUE`, `DUE`, `NO_DATA`, `DUE_SOON`. Spec §2.6 says "an actionable schedule" without enumerating; promoting `OK` and `INACTIVE_SEASON` components would undo #39's blank-query rule entirely, and the two sections are exactly the ones the dashboard exists to surface.

**Tie-breaking:** `Plan decision:` items sort by (section ordinal, `effectiveDueOn` ascending with nulls last, `title` case-insensitively, `scheduleId`). Spec §2.6 fixes the sort key and D12 §10 the sections but neither makes the order total, and `rank` must be deterministic for B12's clients and for a repeatable test.

## Invariants this brief must hold

**22, 74, 75** (master plan §13), and it must not make **18** unholdable: `DueItem.status` is computed in the projection and **never** persisted or cached to disk.

## Test matrix

One test per hazard class. Unit tests for the projection; a small connected set for what only a real Compose tree shows.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| **a state with no home** | one test placing all seven statuses: `OVERDUE` and `DUE` in ATTENTION, **`NO_DATA` in ATTENTION**, `DUE_SOON` in UPCOMING, `OK` in CURRENT, `INACTIVE_SEASON` in OUT OF SEASON, and **`PAUSED` in no section at all** — while the same `PAUSED` schedule **is** present in the Maintenance → Schedules list (master plan §11.1, T8) | the natural implementation drops `NO_DATA` and `PAUSED` on the floor, so a repairable schedule and a paused one both become invisible |
| section order drifting | the sections render in the fixed order **ATTENTION · UPCOMING · CURRENT · OUT OF SEASON** and an empty section is **omitted** (D12 §10 `:706-707`) | ordering by asset category instead of attention is the exact thing D12 §10 forbids |
| a non-deterministic order | two runs over the same data produce identical `rank` values; two items sharing a due date break the tie by title then id | an unstable sort makes B12's `rank` meaningless and the test flaky |
| **the two kinds of `NO_DATA` conflated** | one test asserting the split of master plan §11.1: a **meter-baseline** `NO_DATA` on a **component** asset is **promoted** to its attention rank and **counted**; an **empty-required-set** `NO_DATA` on the same component is **not promoted, not counted and in no section** (invariant 74). `DueReadModel` exposes the status **and** the empty-required-set flag, and carries **no** single `isActionable` boolean | treating `NO_DATA` as one status makes a group whose members were all removed permanently occupy ATTENTION, which invariant 74 forbids — and because the projection feeds four surfaces, one mis-specified predicate lands on the dashboard, the shell, the sheet and `/v1/due` at once |
| **a part's due work hidden** | an **OVERDUE** schedule on a **component** asset is visible with a **blank** query, at its attention rank, with its parent named; and a **non-actionable** component row is still hidden by the blank query (invariant 75, #5 AC 1) | the shipped filter at `DashboardViewModel.kt:121` hides every component while the query is blank, so a part's overdue maintenance never appears |
| a group row fanning out or double-counting | a group-targeted schedule with five required and three complete is **one** row that expands to its members' completion state, carries the ratified progress form, and is counted **once** in the due total (D-15) | one row per member inflates the total and makes "what needs attention" unreadable |
| an empty group counted | a group-targeted schedule whose required set is **empty** is **not** offered as actionable and is **not** counted as due; it reports `NO_DATA` (invariant 74) | counting it puts an unactionable obligation in ATTENTION forever |
| a paused or out-of-season schedule in a due total | neither `PAUSED` nor `INACTIVE_SEASON` contributes to any due count (invariant 22) | a total that includes them makes the badge and the counts lie |
| archived data | archived assets are absent from the default view and their history is intact when opened directly (#5 AC 3); an archived **schedule** appears in no section and no count | a filter on lifecycle that misses one of the two loses history or shows retired work |
| the badge threshold | the health badge appears when the worst finding is **≥ WARN** and not for an `INFO`-only set, tested against a fake `HealthSummary` (#27, D3 §7.3) | a badge on any finding turns permanent and stops meaning anything |
| status cached | a structural assertion: no `DueStatus` value is written to a preference, a database column or a serialised state (invariant 18) | caching the section for scroll performance is how a stale status reaches the screen |
| **the bottom bar** | `TopLevelRoutes` is exactly `[Dashboard, Assets, Maintenance]`; the bar draws three items with the ratified label **"Maintenance"**; and `Route.readsTags()` still returns true for exactly `Scan` and a supported `WriteTag` — **no new screen holds reader mode** | adding the destination to `readsTags()` would hold reader mode over a screen that reads nothing, re-introducing the #37 re-dispatch the 2.7 work removed |
| the shell not reaching its four surfaces | the Maintenance destination routes to due work, the schedules list, the groups list and reminder health, asserted as four navigations from one screen | a shell with three sections leaves B10 or B15 unreachable, which is how 1.2 ships an engine nobody can drive |
| **grayscale legibility** | with colour removed, each of the seven statuses is still distinguishable by **wording plus icon plus position**, and `INACTIVE_SEASON`, `PAUSED` and `NO_DATA` each read differently from `OVERDUE` (#5 AC 2, D12 §5, the D12 acceptance) | colour-only differentiation fails the D12 acceptance and is unusable for a colour-blind owner |
| **F2: a filter that reorders** | one test: filtering by a category and by a status narrows the listed rows correctly, is applied **after** lifecycle and search, and leaves the **section order and the `rank` values of the surviving rows unchanged**; "All categories" / "All statuses" restore the unfiltered list | a filter applied inside the projection would re-derive `rank` over the survivors and silently renumber them, breaking the order `/v1/due`'s clients and B09 share |
| **F3: the meter numbers missing or invented** | one composed row: a meter-rule row renders the ratified line from `computedDueMeter` and `currentMeter`; a row with no meter rule renders **no** meter line; a meter rule with no baseline renders **no numbers** and appears as `NO_DATA` with its repair | reading the threshold off the wrong field, or synthesising a current reading where the journal holds none, states a number the data does not support |
| **F4: a second completion path** | the three quick actions are present and persistent; "Scan tag" and "Add asset" reach the shipped routes; **"Log maintenance" opens B14's `CompletionFlow` and writes nothing itself** — asserted behaviourally **and** by a structural check that this brief's files contain no event or closure write | a quick action that inserted an `asset_event` directly is the second completion path #50 forbids, and this is the one place in 1.2 where one could plausibly appear |
| the search regressing | the shipped search still matches on exactly the six fields of `DashboardViewModel.kt:60-67`, and the "parts live on their systems" message and the backup nudge behave as before | a rewrite of the view-model quietly changes #39's behaviour |

**Connected set (`emulator-5554` only, `ANDROID_SERIAL` pinned, never a phone):** one class for the bottom bar and the four shell navigations; one class for the dashboard's sections with a seeded store — the promoted component row, the group row's expansion, and the badge. Everything else is a unit test over `DueReadModel` and the view models.

## Strings

**Every string this brief draws is RATIFIED; there is nothing left to draft.** Quote them verbatim (master plan §17, §17.1f, §1.2).

- From spec §9.1 and D12: **"Maintenance"** (the navigation label), the dashboard section labels **ATTENTION · UPCOMING · CURRENT · OUT OF SEASON**, the status terms **OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE**, the progress form **"3 of 5 complete"**, **"Reminders are off because notifications are blocked."** (the one-line dismissible line shown when notifications are denied — D-22), and **"Log meter reading"** where a **repairable meter-baseline** `NO_DATA` row offers its repair.
- Ratified at the gate (owner, 2026-09-22) — the **Maintenance destination's four section labels** **"Due work"**, **"Schedules"**, **"Maintenance groups"**, **"Reminders"**, and its **empty state** **"No maintenance schedules yet. Add one from an asset or a maintenance group."**
- Ratified with §1.2's rulings — **"Category"**, **"Maintenance status"**, **"All categories"**, **"All statuses"** (F2); **"Due at \<n\> \<unit\>, now \<n\>."** (F3); **"Scan tag"**, **"Add asset"**, **"Log maintenance"** (F4).

**"Log meter reading" is for the repairable form only.** An **empty-required-set** `NO_DATA` row gets **no** repair label and appears in **no** section (master plan §11.1, §17.1a; invariant 74).

## Ordering

**After B02 and B03.** **Its shell gates B15 and B10's placement, and B09 builds on its read model.** Runs in lane B of wave 4 beside **B06**. It declares `HealthSummary` so it does not wait for B10, and it may declare `Route.ScheduleDetail` if B07 has not.

## Review gate

- Unit: `./gradlew :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Connected on **`emulator-5554`**: `./gradlew :app:connectedDebugAndroidTest --tests '…ui.maintenance.*' --tests '…ui.dashboard.*' --console=plain` → zero failures, zero skips; the review records the class and test counts.
- Structural, anchored:
  - `grep -n 'val TopLevelRoutes' -A 2 app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` → three members, in the ruled order.
  - `grep -n 'fun Route.readsTags' -A 6 app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` → unchanged: `Scan` and a supported `WriteTag` only.
  - `grep -rnE '\bDueStatus\b' app/src/main/kotlin/com/loosecannon/servicetag/prefs app/src/main/kotlin/com/loosecannon/servicetag/data` → no match.
  - `grep -rn 'parentAssetId == null' app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard/DashboardViewModel.kt` → the one filter, now carrying the promotion exception.
  - `grep -c 'Asset::' app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard/DashboardViewModel.kt` → the shipped six searched fields, unchanged.
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui/theme` → empty.

## Estimated size

Medium to large: a projection, a shell, two view models and a navigation change. If it exceeds what one review holds, split at **B08a the due read model and the dashboard** / **B08b the bottom bar and the Maintenance shell** and say so in the ledger — but note that **B15, B10 and B09 wait on the shell half**, so if it is split, the shell half goes first.
