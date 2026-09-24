# B07 — the read models every surface shares

**Read first:** master plan §1, §8.6 (reads never write), §10.2 (the engine's result), §11.1–§11.2 (the shapes `/v1` will serialise), §13.1, §14, §15.
**Spec:** §4.5 (the Deferred section), §4.7, §6.5 ("nothing hides behind an aggregate"), §9.1 (the attention item, the health response), §10.1 (one predicate), §10.2; inv. 105, 119, 122, 123, 131.
**Wave 5, lane A,** beside B08. **After B06.** Gates B09, B12, B13, B14.

## Goal

One projection per question, so the dashboard, the Maintenance tab, the scan sheet, asset detail and `/v1` can never disagree. Extend `DueReadModel` to read through `readState`, sort on the actionable date, carry the policy facts a why-line needs and the band of the subject a schedule drives, and place DEFERRED rows in their own **Deferred** section. Add `AttentionReadModel` — the asset-level rows (DOWN, DEGRADED, independent CRITICAL and WARNING health) that no schedule stands behind. Add `AssetHealthReadModel`, the one place the health engine's result meets the asset's condition and its DOWN or DEGRADED components. And replace the scan's routing with `scanSheetContent`, **one predicate** that decides both whether the sheet opens and what it lists, in which condition may open it and health never can alone. Everything here reads; nothing here writes.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/AttentionReadModel.kt` — `AttentionKind` (`CONDITION`, `HEALTH`), `AttentionItem`, `AttentionReadModel`.
- `app/.../ui/maintenance/ScanSheetContent.kt` — `ScanSheetContent`, `scanSheetContent(...)`.
- `app/.../ui/health/AssetHealthReadModel.kt` — `AssetHealthView`, `ConditionView`, `ComponentCondition`, `AssetHealthReadModel`.
- Tests under `app/src/test/kotlin/com/loosecannon/servicetag/`: `ui/maintenance/AttentionReadModelTest`, `ui/maintenance/ScanSheetContentTest`, `ui/maintenance/DueReadModelPolicyTest`, `ui/health/AssetHealthReadModelTest`, `ui/ReadPathsWriteNothingTest`.

**Modify**

- `app/.../ui/maintenance/DueReadModel.kt` — `DueItem` gains `actionableDueOn: LocalDate?`, `policyReason: PolicyReason`, `policyPhase: PolicyPhase`, `quiet: Boolean`, `seasonMode: SeasonMode?` (the target asset's; null for a group), `dormantUntil: LocalDate?` (for a DORMANT row on a CALENDAR asset, `SeasonContext.cycleStartAt(today)`; else null) and `health: SubjectBandFact?`; `AttentionSection` gains `DEFERRED` between `CURRENT` and `OUT_OF_SEASON`; `sectionOf(DEFERRED)` → `DEFERRED`; states read through `RecomputeSchedules.readState` (no stored row used unless `computedForOn == today`); `ATTENTION_ORDER` sorts on `actionableDueOn` (nulls last), then title, then id.
- `app/.../ui/maintenance/DueItemRow.kt` — `sectionLabel(DEFERRED)` = S93, and nothing else in this file.
- `app/.../ui/maintenance/MaintenanceSheetViewModel.kt` — the admission set and `ScanSheetOffer` go through `scanSheetContent`; the sheet's drawing is B12's.
- `app/.../di/AppGraph.kt` — `attentionReadModel`, `assetHealthReadModel`; `dueReadModel` and `scanSheetOffer` re-wired; **`scheduleStateReader` re-wired to `readState`** (`AppGraph.kt:230`: `{ id -> schedules.get(id)?.let { recomputeSchedules.readState(it) } }`), so `ScheduleDeliveryFacts` and `ReminderHealthCheck` read fresh state too (master §8.6, dec. 47). `app/src/test/.../testing/FakeGraph.kt` — the same two fields and three re-wirings, mirrored (master §1).
- `app/src/test/.../ui/maintenance/DueReadModelTest.kt`, `StatusVocabularyTest.kt` (five section labels) — extended.

**Untouched:** `ui/maintenance/ScheduleEdit*` and `ScheduleDetail*` (B08's, same wave); `WhyLines.kt` (B13's, wave 8 — it does not exist yet); `MaintenanceSheet.kt` (the composable; B12); every `ui/dashboard/**`, `ui/asset/**`, `api/**` file; `core/**` main sources (a read model needing a `:core` change asks the controller).

## Interfaces

**Consumes:** `RecomputeSchedules.readState`, `ScheduleRecompute.policyInputsOf`, `SeasonContext` (B02); `AssetHealthEngine`, `AssetHealthResult`, `SubjectHealth`, `HealthBand` (B05); `ConditionHistory`, `ConditionRepository`, `HealthSubjectRepository` (B06, B01); shipped `AssetTree`, `targetInService`, `scanSheetItemsFor`.

**Produces** (B09 serialises these; B12–B14 draw them):

```kotlin
data class SubjectBandFact(val subjectId: HealthSubjectId, val subjectName: String, val band: HealthBand, val score: Int)
data class AttentionItem(val kind: AttentionKind, val section: AttentionSection /* ATTENTION|UPCOMING */,
    val assetId: AssetId, val assetName: String, val parentAssetId: AssetId?, val parentName: String?,
    val condition: OperationalCondition?, val reason: String?, val occurredOn: String?,
    val healthSubjectId: HealthSubjectId?, val subjectName: String?, val band: HealthBand?,
    val score: Int?, val rank: Int)
class AttentionReadModel { suspend fun items(): List<AttentionItem> }
data class AssetHealthView(val assetId: AssetId, val computedForOn: LocalDate, val condition: ConditionView?,
    val aggregation: HealthAggregation, val result: AssetHealthResult, val components: List<ComponentCondition>)
class AssetHealthReadModel { suspend fun forAsset(assetId: AssetId): AssetHealthView }
data class ScanSheetContent(val opens: Boolean, val condition: ConditionView?, val offersMarkOperational: Boolean,
    val components: List<ComponentCondition>, val critical: List<SubjectHealth>,
    val aggregate: Scored? /* only when WARNING or CRITICAL */, val maintenance: List<DueItem>)
fun scanSheetContent(items: List<DueItem>, condition: ConditionView?,
                     components: List<ComponentCondition>, health: AssetHealthResult?): ScanSheetContent
```

`ConditionView` is `{condition, since, reason, occurredOn, occurredTime, eventId, eventExists}`; `ComponentCondition` is `{assetId, name, condition, reason, occurredOn}`.

### The rules

1. **Reads never write** (inv. 105): every model here reads states through `readState` and never calls a use case, a recompute or an upsert. The delivery seam `scheduleStateReader` joins them in this brief (the one wave that owns `AppGraph` after `readState` exists).
2. **`AttentionReadModel`** covers **in-service** assets and components (status ACTIVE, not retired — the component's own lifecycle). Per asset: current DOWN → one `CONDITION` item; current DEGRADED → one `CONDITION` item; each **AGE** subject scoring CRITICAL → a `HEALTH` item in ATTENTION, WARNING → a `HEALTH` item in UPCOMING (master §20.21: a MAINTENANCE_OVERDUE subject rides its schedule row as `DueItem.health` instead). `rank` is dense over DOWN, DEGRADED, CRITICAL, WARNING; within each group by asset name (case-insensitive), then asset id, then subject `sortOrder` and id. Every field present, null when absent. A component names its parent (inv. 122).
3. **`AssetHealthReadModel`** builds the engine's inputs — the asset, its subjects, each linked schedule with `policyInputsOf(schedule, readState(schedule), zone)`, the asset's `SeasonContext` (activation rows only for MANUAL), its events, `profileExists` from its profiles — and adds the current condition and every DOWN or DEGRADED in-service component **at any depth**. It is the one source for `/v1/assets/{id}/health`, the scan sheet and asset detail (inv. 119).
4. **`scanSheetContent`** (spec §10.1, O-8): `opens` ⇔ `scanSheetItemsFor(...)` offers an item, **or** the asset is DOWN or DEGRADED, **or** a component is. Health never opens it alone. When it opens, `maintenance` lists D-18a's actionable items plus DUE SOON rows as passengers; **Plan decision:** a DUE SOON row rides along whenever the sheet is open for any reason, condition included (spec §10.1: health lines are passengers "like DUE SOON … when another reason opens it"). `critical` lists every CRITICAL subject; `aggregate` appears only when WARNING or CRITICAL; `offersMarkOperational` for DOWN or DEGRADED. `ScanSheetOffer.has(assetId)` is exactly `.opens` (inv. 123).
5. **`DueItem.health`** is the band fact of the non-archived subject the schedule drives, when it is tracked; null otherwise.
6. **Deferred** is its own section between CURRENT and OUT OF SEASON; DEFERRED never counts as due.

## Invariants this brief must hold

**Accountable** (master §14): **105** (`ReadPathsWriteNothingTest`), **119**, **122**, **123**. **Halves:** **82** (nothing here writes health), **103** (DEFERRED is not due, in its own section), **131** (a snooze leaves the health view unchanged), and the restated **23** (a stale row is derived fresh).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a read path writes | `ReadPathsWriteNothingTest` · `everyReadModelWritesNothing` — `DueReadModel.items/forAsset`, `AttentionReadModel.items`, `AssetHealthReadModel.forAsset`, `ScanSheetOffer.has` against recording repositories with a **stale** state row: zero writes anywhere | call `recompute.forSchedule` on a stale row |
| the delivery seam reads a stale row (M12) | `ReadPathsWriteNothingTest` · `theDeliverySeamReadsFreshStateAndWritesNothing` (the graph's `scheduleStateReader` over a row computed yesterday across a season boundary returns today's phase; zero writes) | keep `{ scheduleStates.get(it) }` |
| a stale row is shown | `DueReadModelPolicyTest` · `aStaleStateRowIsDerivedForToday` (a row computed yesterday across a season boundary reads today's status) | use any stored row |
| order ignores the policy | `DueReadModelPolicyTest` · `theOrderFollowsTheActionableDate` (a mower pulled to 1 Apr sorts before a CONTINUOUS row due 10 Apr) | sort on `effectiveDueOn` |
| DEFERRED misplaced | `DueReadModelPolicyTest` · `deferredRowsHaveTheirOwnSectionAndCountForNothing` | leave DEFERRED in CURRENT |
| the why-line facts | `DueReadModelPolicyTest` · `aRowCarriesReasonPhaseQuietModeAndDormantUntil` (a dormant meter-only CALENDAR row still carries its next season start) | drop `quiet` |
| overdue health not on its row | `DueReadModelPolicyTest` · `aScheduleRowCarriesTheBandOfTheSubjectItDrives` | read the subject by asset instead of schedule |
| a DOWN unit with nothing due is quiet | `AttentionReadModelTest` · `downAndDegradedAssetsAndComponentsAppearWithNoSchedule` (a component names its parent; inv. 122) | skip components |
| the rank | `AttentionReadModelTest` · `rankIsDenseDownDegradedCriticalWarning`, `tiesBreakByAssetNameThenId` | order WARNING before CRITICAL |
| overdue health doubled | `AttentionReadModelTest` · `onlyAgeSubjectsAreIndependentRows` | include MAINTENANCE_OVERDUE subjects |
| out-of-service rows | `AttentionReadModelTest` · `retiredOrArchivedAssetsAreNotListed` | drop the lifecycle bound |
| a critical hidden by an average | `AssetHealthReadModelTest` · `anAverageNominalAssetStillListsItsCriticalSubject` (inv. 119) | filter criticals by the aggregate's band |
| a DOWN component hidden | `AssetHealthReadModelTest` · `downOrDegradedComponentsAtAnyDepthAreListed` | only direct children |
| health without condition | `AssetHealthReadModelTest` · `theViewAlwaysCarriesTheCurrentCondition` | omit it for untracked health |
| snooze moves health | `AssetHealthReadModelTest` · `aSnoozeLeavesHealthUnchanged` (through the shipped `ReminderSnooze` writing a delivery row) | read the delivery row |
| **health opens the sheet** | `ScanSheetContentTest` · `criticalHealthAloneNeverOpensTheSheet`, `aDownAssetWithNothingDueOpensIt`, `aDegradedComponentOpensIt`, `anActionableItemOpensIt`, `healthRidesAlongWhenSomethingElseOpens` (inv. 123, O-8) | open on a CRITICAL subject |
| the lists | `ScanSheetContentTest` · `averageNominalPlusOneCriticalShowsTheCritical`, `theAggregateOnlyWhenWarningOrCritical`, `dueSoonRidesAlongWhenConditionOpensTheSheet`, `nothingDueWhenNoItem` | show the aggregate when NOMINAL |
| routing and content disagree | `ScanSheetContentTest` · `theOfferIsExactlyOpens` | a second predicate in `ScanSheetOffer` |
| the section words | `StatusVocabularyTest` · `theSectionLabelsAreTheRatifiedFive` (S93 for Deferred) | reuse "CURRENT" |

## Edge cases

- **No state row at all** (the first read after the 7 → 8 migration, which recreates `schedule_state` empty) is derived in memory, exactly as a stale row is; nothing is persisted by the read.
- **A MANUAL asset with no activation row** (only an import can make one) reads OUT_OF_SEASON, so its IN_SERVICE rows are DORMANT with `dormantUntil = null` (a manual start is never predicted).
- **A group-targeted row** carries `seasonMode = null`, `dormantUntil = null` and `health = null`: a group has no season and drives no subject.
- **A MAINTENANCE_OVERDUE subject whose schedule is paused, archived or rule-less** is NOT TRACKED: `DueItem.health` is null and it produces no attention item.
- **An AGE subject that is NOT TRACKED** (no replacement yet, or its quick action deleted) produces no attention item; asset detail still lists it (B14).
- **A DOWN asset that also has a CRITICAL age subject** yields two attention items — the DOWN one ranks first — so the health row never appears without the condition row.
- **A component of a retired or archived parent** is listed on its own lifecycle, naming the parent (whose name still resolves, as the shipped `World` reads every asset).
- **A grandchild component** that is DOWN appears in its own attention item (naming its immediate parent) and in the health view's `components` of **every** ancestor, because spec §6.5 says "at any depth".
- **A condition whose linked event was deleted** carries `eventExists = false`; it is never an error and never hidden.
- **An archived schedule** stays out of every projection (`listedForDue`), and an archived subject out of every result.

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each: `com.loosecannon.servicetag.ui.maintenance.ScanSheetTest`, `com.loosecannon.servicetag.ui.dashboard.DashboardAttentionTest` (still green; B12 and B13 extend them).
- Anchored: `grep -rnE '\.(upsert|insert)\(' app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueReadModel.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/AttentionReadModel.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScanSheetContent.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/health/AssetHealthReadModel.kt` → no output; `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard app/src/main/kotlin/com/loosecannon/servicetag/ui/asset` → empty.

## Strings

**S93** ("Deferred") as the section label. Nothing else — the words on rows and the sheet are B12's and B13's.

## Must NOT

- write anything, or call a recompute, from a read model;
- let health open the scan sheet alone, or let an aggregate decide what is shown;
- list an out-of-service asset or component;
- add a second routing predicate;
- draw anything (B12, B13, B14) or edit B08's files.

## Review focus

- Every read model is read-only: the grep in the gate is empty and `ReadPathsWriteNothingTest` runs over a **stale** row, the case that tempts a write.
- `ScanSheetOffer.has` is literally `scanSheetContent(...).opens`; there is no second predicate anywhere in `ui/scan` or `AppGraph`.
- The only ordering change is the sort key (`actionableDueOn`) and the new section; ranks stay dense and the shipped tie-breaks stand.

## Size

Medium: three new read models and one predicate over shipped projections, all JVM-tested. No split expected.

## Carry-forward from B02 (controller, 2026-09-24)

- `InMemoryScheduleStateRepository.observeAll` (the shared test fake) must mirror the Room DAO's `actionable_due_on` order that B02 introduced; add the fake to this brief's Files and one assertion in the read-model test that the fake and the DAO agree on order. Where convenient, take "today" for `DueReadModel` from the same `Today` port `readState` uses, so the two never disagree.

## Carry-forward from B02's review (controller, 2026-09-24)

- The maintenance sheet's `whyNow` still reads `effectiveDueOn` through `DueReadModel`. With B02, a reminder carries the actionable date; the sheet's why-line must agree with the status word the same way. Read the actionable date here too (this brief already routes `DueReadModel` through `readState`); one test row: an item pulled before its season shows the actionable date in the why-line (RED: read `effectiveDueOn`).
