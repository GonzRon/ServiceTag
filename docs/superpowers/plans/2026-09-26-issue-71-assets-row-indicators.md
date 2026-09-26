# #71 — health and NFC-tagged indicators on the Assets list: plan and brief (rev 2, reviewed 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review
> budget in `docs/superpowers/planning-policy.md`. One brief (§9), one implementer, one task review, one
> batched fix round, at most one scoped re-review. Planned from issue #71's body under the owner's GO of
> 2026-09-26, on the post-#73 Assets-list surface. Rev 2 folds in the independent brief review (verdict
> REJECT on rev 1: invariant 119 was not addressed; the layout arithmetic was wrong by about 100dp against
> the real badge widths; the refresh state sat in the wrong place; the NFC check collided with D12's OK
> signifier; icon-only health dropped D12's wording channel). Nothing is dispatched until the owner has
> ratified every string in §6 and answered the questions in §7.

**Goal:** each Assets-list row answers two independent questions at a glance: what the Asset's current
derived health is (the canonical 1.4 band, when tracked and in service) and whether it physically carries a
verified, written, active ServiceTag NFC tag. Both facts come from the row read model, react to the store
without restarting the app, carry distinct glyphs and accessibility semantics with colour only
supplementary, appear on components by their own state, and can never clip, overlap or overflow the row at
any width or font scale. Operational condition stays a separate concept; NOT TRACKED draws nothing.

**Spec:** issue #71 (the semantics, the seam, AC 1–13); the 1.4 spec §6.3 (the band), §6.5 / inv. 119
("every CRITICAL contributor and every DOWN or DEGRADED in-service component is shown on every surface
that shows health; a DOWN asset's health is never shown without its condition" — §7 R71-6 puts the row's
relation to it to the owner), §10.6 (accessibility: the bar glyphs on "detail, badges, rows"; NOT TRACKED on
detail only), §10.7 (S95–S98); D12 §5 (position + wording + icon + colour, the non-negotiable; the OK family
is cool blue, "not green"; `check_circle` is D12's OK signifier), D12 §5's palette rule (no improvised
`Color.Green`), the G1 gate report ("OK is Signal blue, never green"), the 1.4 archaeology (#61's
green→amber→red was overruled to cool blue, I-15); `AssetHealthReadModel` (one computation for every
surface; inv. 82/111: computed at read time, stored nowhere); `ProvisionTag.complete` (`writtenAt` is set
only by the verified write); #73's plan §8.

## Global constraints

- **The NFC predicate is exact** and lives in one place: tagged ⇔ some `TagBinding` with
  `target == AssetTarget(asset.id)`, `status == ACTIVE`, `writtenAt != null` (a pure core function beside
  `TagBinding`, with its own JVM cases). Never "a row exists"; one qualifying tag is enough.
- **Health is the canonical model's.** The band is `AssetHealthReadModel.resultFor(asset, today).aggregate?.band`;
  nothing in the Assets screen or its view model scores, thresholds, aggregates or calls the engine. NOT
  TRACKED (a null aggregate) draws nothing. Condition is never folded into health.
- **Reactive, explicit, in the view model.** Both facts are fields of `AssetRow`, derived in `AssetsViewModel`
  from named flows; the list never waits for or dies with the health pass (C2's failure contract).
- **Nothing can clip.** The lifecycle badges and the health badge wrap under the name; the NFC disc anchors
  the right edge; every element stays inside the row (C4, C5).
- **#73's semantics untouched.** The filters, the empty reasons, the search, the sort, the chips.
- **Strings.** Every visible or accessibility string is in §6, verbatim, ratified before dispatch.
- Tests JVM-first, Compose instrumented on `emulator-5554` only, one class per invocation. Hygiene,
  gitlink `7e0377a`, one-line commits, the tombstone rule.

## 1. Audit (controller, read-only, done 2026-09-26; corrected by the review)

**The row after #73** (`AssetListRow`, private, `AssetsScreen.kt`): a clickable `Row` (56dp minimum,
16/10dp padding, 8dp gaps) — a weighted `Column` (name in `titleSmall`, the category, "Part of <parent>"),
then the badges in a single line: Retired (`paused`, `PauseCircle`), Out of season (`seasonInactive`,
`CalendarMonth`), Archived (`seasonInactive`). A `StatusBadge` is a pill of 7dp padding, a 16dp glyph, a 4dp
gap and its label upper-cased in bold `labelSmall` (11sp, 0.6sp tracking); measured with Roboto Bold
metrics that is about **RETIRED 82dp, OUT OF SEASON 125dp, ARCHIVED 72dp** at font scale 1.0 and about
**130 / 216 / 130dp at 2.0**. Three badges plus the row's padding and gaps already overflow a row below
about 335dp today — a pre-existing crowding this plan removes rather than worsens. The row merges its
descendants for accessibility. `AssetRow(asset, parentName, outOfSeason)`, `AssetsState`, `AssetFilters`,
`EmptyReason` are #73's; `AssetsViewModel(assets, categories, activations, today)` — in that order — with
`state = combine(seasonal, filters, categories.observeAll(), queries)`; the tests build it twice
(`listModel()` and one direct construction).

**Tags.** `TagRepository` has `observeForAsset` and suspend `forAsset` / `all`, no `observeAll` flow;
`NfcTagDao` has suspend `all()` (`ORDER BY created_at`); `InMemoryTagRepository` bumps a `version`
`MutableStateFlow` on every write. `writtenAt` is set only by `ProvisionTag.complete`; a provisioned row
before the write is ACTIVE, targets the asset and has `writtenAt == null`; an UNBOUND row targets `None`.
`TagTarget.AssetTarget` is a data class over the value class `AssetId`, so equality is structural.

**Health.** `AssetHealthReadModel(assets, subjects, schedules, events, profiles, activations, conditions,
recompute, today, zone)` — **no `ScheduleStateRepository`**; it reads states through
`recompute.readState` (one `states.get`, or an in-memory rebuild when stale; never a write). Per asset,
`resultFor` costs one read for an untracked asset (the subjects, then the early return) and, for a tracked
one, the subjects, the MANUAL activations, the profiles, the events and, per linked schedule, `schedules.get`
+ `readState` — a few hundred row reads for ~50 assets, the attention list's cost. The aggregate
`SubjectValue.Scored?` carries the band; null is NOT TRACKED. `AssetHealthResult.critical` lists CRITICAL
contributors, and the detail's `healthBlocks` shows them and the condition **ungated by `inService`** —
a retired asset can read CRITICAL on its detail. The dashboard keeps `refreshes` in its own view model and
its screen calls `refresh()` from `LaunchedEffect(Unit)`; the resume precedent is `ScheduleDetailScreen`'s
`LifecycleResumeEffect`. `HealthSubjectRepository` has `observeForAsset` and suspend `all()`, no
`observeAll`. `Asset.inService` = ACTIVE and not retired. The read model's own tests use **AGE subjects
over a REPLACEMENT event with thresholds 0/40/75** (`ConditionHealthFixtures`), and
`AssetHealthReadModelTest.anAverageNominalAssetStillListsItsCriticalSubject` pins inv. 119 on the detail.

**The 1.4 vocabulary.** `HealthBadge(band, score)` = `healthGlyph(band)` (three / two / one bars; 16dp) +
`healthBadgeLabel` (the S95–S97 word, plus the score when given) in `healthNominal` / `healthWarning` /
`healthCritical`; `HealthWords.kt` exists so that surfaces "cannot say it three ways". The OK family
`maintenanceOkay` and `healthNominal` carry the same values but different meanings. D12's OK signifier is a
check (`check_circle`; the dashboard's plain rows draw `CheckCircle` for "nothing needs attention"), so a
check in the OK family beside a health badge reads "OK", not "tagged". `ServiceTagIcons.NfcTag` already
means "an NFC tag" on the plate and the scan sheets.

**Tests today.** `AssetViewModelsTest`; `AssetsFiltersTest` / `AssetsSearchTest` / `AssetModelDeviceProofTest`;
`TagUseCasesRoomTest` (Room-backed `FakeGraph`, so no view-model case reaches the in-memory doubles);
`ReadPathsWriteNothingTest` (reads no tags today; constructs the read model at one site);
`AssetHealthReadModelTest`; `DashboardAttentionTest`; `HealthWordsTest`. `FakeGraph` exposes
`assetHealthReadModel`, `tags`, `todayPort`; `AppGraph` exposes `assetHealthReadModel` and `today`.

## 2. Behaviour (the brief's contract)

- **C1, the NFC seam and predicate.** `TagRepository` gains `fun observeAll(): Flow<List<TagBinding>>`;
  `NfcTagDao` gains `@Query("SELECT * FROM nfc_tag ORDER BY created_at") fun observeAll(): Flow<List<NfcTagEntity>>`;
  `RoomTagRepository` maps it; `InMemoryTagRepository` derives it from `version` as its `observeForAsset`
  does. The predicate is one pure core function beside `TagBinding` — `fun TagBinding.isWrittenFor(assetId):
  Boolean` — with its own JVM cases; the view model folds the flow once per emission into
  `taggedAssets: Set<AssetId>`. Rejected: a per-asset fan-out and a Compose-side read.
- **C2, the health seam — one explicit reactive source.** `AssetHealthReadModel` gains a
  `ScheduleStateRepository` collaborator (its three construction sites: `AppGraph`, `FakeGraph`,
  `ReadPathsWriteNothingTest`) and `fun observeBands(refreshes: Flow<*>): Flow<Map<AssetId, HealthBand?>>` =
  `combine(assets.observeAll(), subjects.observeAll(), schedules.observeAll(), states.observeAll(), refreshes)`
  → `mapLatest { pass }` → `conflate()`, with `onStart { emit(emptyMap()) }` so the list never waits for the
  pass (the discs and badges follow within the first emission). The pass: for every **in-service** asset with
  at least one live subject (the read model's own early return, mirrored), `resultFor(asset, today)` inside
  `runCatching`; a failure means that asset is absent from the map and is logged at warn — one bad merged
  row never takes the Assets list down (the read model's own "belt" principle); a `CancellationException`
  is rethrown. `HealthSubjectRepository` gains `observeAll(): Flow<List<HealthSubject>>` (DAO
  `@Query("SELECT * FROM health_subject ORDER BY sort_order, id")`, Room and in-memory). Events, activations,
  profiles and the passing of midnight are not observed — the same accepted staleness as the dashboard —
  so `AssetsViewModel` owns `refreshes: MutableStateFlow<Int>` and `fun refresh()`, and the screen calls
  `model.refresh()` from `LifecycleResumeEffect(model)` (the `ScheduleDetailScreen` precedent). The whole
  computation is `resultFor`, so every surface still reads one computation; the path writes nothing.
- **C3, ownership.** `AssetRow` gains `hasWrittenTag: Boolean = false` and `health: HealthBand? = null`.
  `AssetsViewModel(assets, categories, activations, tags, health: AssetHealthReadModel, today)`; the `graph`
  constructor passes `graph.tags` and `graph.assetHealthReadModel`; the two test construction sites gain the
  two collaborators. The `combine` nests to stay within the typed arity:
  `facts = combine(seasonal, tags.observeAll(), health.observeBands(refreshes))` → then
  `combine(facts, filters, categories.observeAll(), queries)`. Every row — root or component — reads its own
  two facts by its own id; nothing is inherited or summed.
- **C4, the row's shape (R71-9, a carve-out from C9).** `AssetListRow` becomes: a `Row` of a weighted
  `Column` and the NFC disc. The `Column` holds the name, the category, "Part of <parent>", then a `FlowRow`
  (4dp gaps, from the next line down when the text above is present) of the badges **in today's order** —
  Retired, Out of season, Archived — **followed by the health badge** (`HealthBadge(band, score = null)`: the
  ratified bar glyph plus the S95–S97 word, in the band's family; drawn only when `row.health != null`). The
  badges' look and order are unchanged; only their home moves from the row's trailing line to a wrapping
  line under the name, so they can never overflow (that also ends today's overflow below ~335dp). The NFC
  disc stays at the far right, vertically centred: `Box(22dp, CircleShape)` in the family's container colour
  with the glyph (§7 R71-2: the owner picks the glyph and family) centred at 14dp inside an inner `Box`
  (a bare `Surface` would stretch the glyph to the disc, `propagateMinConstraints`). Health first (in the
  badge flow), NFC last (the right-edge anchor) — R71-3.
- **C5, layout at the edges.** The name column keeps `weight(1f)` and is at least the row's content width
  minus the disc's 22dp and the 8dp gap — about 258dp at a 320dp frame; the badge flow wraps within that
  column, so no badge is ever cut, and the row grows when the flow wraps. At font scale 2.0 the badges
  double and wrap onto more lines; the disc stays 22dp (an informational glyph, not a control). Frames in
  §3 are the row's own frame including its 16dp padding, so a 360dp frame is a 360dp phone's full width.
- **C6, components.** A component row (Components ON in #73) carries the indicators computed from its own
  tags and its own subjects; a parent's row never reflects a child's tag or health.
- **C7, condition stays out of the band.** No condition is derived from health or health from condition.
  What the row shows beside the band for a DOWN or DEGRADED asset is R71-6, the owner's ruling.
- **C8, NOT TRACKED and out of service.** No health badge for a null aggregate (§10.6 puts NOT TRACKED on
  detail only). No health badge for an asset that is not in service (archived or retired) — a ruling the
  owner reads (R71-10): the detail still shows such an asset's health; the row's health is the glance at
  the in-service estate.
- **C9, nothing else moves** except what C4 carves out: #73's chips, reasons and search; the badges' words,
  families, glyphs and order; row navigation (a tap on the disc opens the asset as a tap anywhere does);
  the dashboard; the detail; the engine.

## 3. Test matrix (hazards; the brief fixes names)

Fixtures: the tag rows are built as `ProvisionTag` builds them (a provisioned row is ACTIVE, targets the
asset, `writtenAt == null`; a completed one has `writtenAt`; an UNBOUND row targets `None`); health uses the
read model's own fixture — an AGE subject over a REPLACEMENT event, thresholds 0/40/75, so a replacement
10 / 50 / 100 days before `today` reads NOMINAL / WARNING / CRITICAL; every move of `today` is followed by
`refresh()` (moving the clock emits nothing on its own — accepted staleness, C2). Device cases either draw
the row alone with a synthetic `AssetRow` or seed dates relative to `LocalDate.now()`.

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the predicate (AC 1–5) | core `TagBindingTest` · `isWrittenForRequiresTargetActiveAndWritten`: a table — target/ACTIVE/written → true; unwritten → false; UNBOUND (target None) → false; LOST and RETIRED rows that still target the asset with `writtenAt` set → false; another asset's written ACTIVE row → false for this asset and true for that one | drop each clause in turn |
| the list reads it (AC 1–5) | `AssetViewModelsTest` · `aWrittenActiveTagShowsTheIndicator`; `noTagRowsNoIndicator`; `aProvisionedUnwrittenTagDoesNotCount`; `oneQualifyingTagAmongLostAndRetiredOnesIsEnough`; `anotherAssetsTagCountsForThatAssetOnly` (both rows asserted) | treat any row as a tag |
| reactivity (AC 6) | · `losingTheLastQualifyingTagRemovesTheIndicatorWithoutRestart`: mark LOST → `false` on the next emission; retire, unbind, re-target → same | read tags once |
| components (AC 7) | · `aComponentCarriesItsOwnIndicators` (Components ON; only the component tagged and tracked) | inherit from the parent |
| the band is the read model's (AC 10, 11) | · `theHealthBadgeIsTheReadModelsAggregate`: 10 → NOMINAL, then `today` + 40 and `refresh()` → WARNING, + 50 → CRITICAL | the gate grep (no behavioural RED: the view model has no way to compute a band) |
| not tracked (AC 12) | · `notTrackedDrawsNoHealth`: no subject → null; all archived → null; a screened TRACK_ONE primary → null | map null to NOMINAL |
| condition separate (AC 13) | · `conditionNeverBecomesHealth`: DOWN and no subject → null; DOWN plus a nominal subject → NOMINAL (and whatever R71-6 adds beside it) | derive from condition |
| in service only (R71-10) | · `aRetiredOrArchivedAssetDrawsNoHealth`: each with a live subject that scores while in service; Archived ON | ignore `inService` |
| inv. 119 (R71-6) | · one case pinning the owner's choice: (a) `anAverageNominalRowShowsTheAggregateAlone` or (b) the condition badge / CRITICAL treatment the owner chose | the other choice |
| the reactive source (C2) | `AssetHealthBandsTest` (JVM, Room-backed `FakeGraph`) · `observeBandsCoversEveryInServiceTrackedAsset`; `theFirstEmissionIsEmptyAndTheListNeverWaits`; `aFailingAssetIsAbsentAndTheOthersStay` (a subject repository throwing for one asset); `aScheduleStateChangeReemits`; `aScheduleRowChangeReemits` (a schedule written with no recompute); `aSubjectArchiveReemits`; `refreshReemits`; `anArchivedAssetLeavesTheMap`; `observeBandsWritesNothing` (`ReadPathsWriteNothingTest` gains the path, incl. the tag flow) | drop a trigger; let a failure propagate |
| the flows (C1, C2) | `TagUseCasesRoomTest` · `observeAllEmitsOnEveryWrite`; core `InMemoryRepositoriesTest` (or beside the doubles) · `theInMemoryTagAndSubjectFlowsEmitOnUpsertDeleteAndSnapshotRestore` | a one-shot flow |
| the row (AC 1, 2, 8, 9) | `AssetsIndicatorsTest` (Compose, emulator; `AssetListRow` made `internal`; lookups with `useUnmergedTree = true`) · `aTaggedNominalRowCarriesTheBadgeAndTheDisc` (the S95 word visible; the disc's description present; the badge left of and above the disc); `anUntaggedUntrackedRowCarriesNeither`; `theThreeBandsAreDistinct` (S95/S96/S97 on three rows); `theDiscTakesNoTapOfItsOwn` (clicking the disc node opens the asset) | swap the order; reuse one description |
| layout (C5) | `AssetsIndicatorsTest` · `nothingClipsAtNarrowWidthOrLargeFont`: the row drawn alone (the #68 `draw` technique) in the two reachable worst cases — (i) in service: OUT OF SEASON + a CRITICAL badge + the disc; (ii) out of service: RETIRED + OUT OF SEASON + ARCHIVED + the disc — at 320/1.0, 360/1.0 and 412/2.0: every badge's `TextLayoutResult` unclipped (the #73 technique), every badge and the disc inside the row's bounds, none overlapping, the badge flow wrapped onto more lines where the width demands it, the name column ≥ 200dp | a single-line badge `Row` |
| regression | `AssetsFiltersTest`, `AssetsSearchTest`, `AssetModelDeviceProofTest`, `SeasonReconciliationNavigationTest`, `DashboardAttentionTest`, `HealthWordsTest`, `AssetHealthReadModelTest` unchanged and green | — |

## 4. Files (indicative; the implementer owns the placement)

**Modify** `core/model/TagBinding.kt` (the predicate), `core/ports/Repositories.kt` (the two `observeAll`s),
`app/…/data/room/dao/NfcTagDao.kt` and `dao/SeasonHealthDaos.kt`, `RoomRepositories.kt` and
`SeasonHealthRepositories.kt`, `core/src/test/…/testing/InMemoryRepositories.kt` (the two flows),
`app/…/ui/health/AssetHealthReadModel.kt` (`states`, `observeBands`), `AppGraph.kt` and `FakeGraph.kt` (the
read model's new collaborator), `app/…/ui/asset/AssetViewModels.kt` (`AssetRow`, `AssetsViewModel`,
`refreshes`), `app/…/ui/asset/AssetsScreen.kt` (`AssetListRow` — the badge flow, the health badge, the
disc; `LifecycleResumeEffect`; the P71 const in `HealthWords.kt` if the owner picks icon-only health).
Tests: core `TagBindingTest` (new), `AssetViewModelsTest`, `AssetHealthBandsTest` (new),
`TagUseCasesRoomTest`, `ReadPathsWriteNothingTest`, the in-memory flow cases, `AssetsIndicatorsTest` (new).
**Untouched:** #73's filters and reasons, the dashboard, the detail screen, the engine, the API, the MCP,
the schema and the backup format.

## 5. Layout and font-scale behaviour (measured)

The row reads: name / category / part-of / a wrapping line of badges — `[RETIRED] [OUT OF SEASON]
[ARCHIVED] [▂▄▆ NOMINAL]` — and the NFC disc at the far right, centred. The name column is the content
width minus 30dp (the disc and its gap): about 258dp at 320dp, 298dp at 360dp, 350dp at 412dp. Badges (82 /
125 / 72dp, and a health badge of about 90dp at 1.0) wrap within it: at 360/1.0 case (ii) takes two lines,
case (i) one or two; at 412/2.0 (badges 130–216dp) every case wraps to one badge per line and the row grows.
Nothing scrolls horizontally, nothing clips, nothing overlaps. The disc adds 30dp to the fixed width, not
the rev-1 "52dp" of two discs.

## 6. Strings — for ratification

| id | where | text | note |
|---|---|---|---|
| P71-4 | the NFC disc's accessibility description | `NFC tag assigned` **or** `NFC tag written` | the issue's example versus the predicate's exact meaning (an assigned-but-unwritten tag does **not** count; the detail already says "Tag written"); **owner's choice**, recommended `NFC tag written` |
| — | the health badge | S95 `NOMINAL` / S96 `WARNING` / S97 `CRITICAL` with the bar glyph, the detail's own badge without its score | **reused**; nothing new |
| — | (only if the owner chooses icon-only health, R71-7) | `Health nominal` / `Health warning` / `Health critical` | then three new strings, homed in `HealthWords.kt` |
| — | unchanged | the badges' words, every #73 string | |

With the recommended R71-7, exactly **one** new string.

## 7. Rulings and owner questions (controller, 2026-09-26)

- **R71-1, the exact predicate, one home, one flow.** `TagBinding.isWrittenFor(assetId)` in core; one
  `observeAll` on the port, one DAO query, one in-memory derivation; folded once into a set.
- **R71-2, the NFC disc: family and glyph — owner's choice.** The family is the OK family `maintenanceOkay`
  (cool blue): green is excluded by D12 §5, the G1 report ("OK is Signal blue, never green") and the I-15
  precedent (#61's green was overruled), and the palette has no green token. The glyph is the open
  question, because a **check** in the OK family is D12's own OK signifier (the dashboard's plain rows draw
  `CheckCircle` for "nothing needs attention") and would read "OK" beside a health badge: (a) a check in
  the OK family, accepting the collision; **(b, recommended)** `ServiceTagIcons.NfcTag` in the OK family —
  the app's existing "an NFC tag" glyph, unambiguous and distinct from the bars and from OK; (c) a check in
  a neutral family. **Owner: pick a, b or c.**
- **R71-3, health first, NFC last.** The health badge closes the badge line; the disc anchors the right
  edge — the binary identification fact is the stable anchor, the operational fact sits with the lifecycle
  badges it belongs with.
- **R71-4, the reactive source mirrors the dashboard and never blocks the list.** `observeBands(refreshes)`
  on the read model, triggered by the asset, subject, schedule and schedule-state tables plus the view
  model's `refresh()` on resume; an empty first emission; a per-asset failure logged and absent. Accepted
  staleness: events, activations, profiles and midnight until the next resume — exactly the attention list's.
- **R71-5, the health glyphs are §10.6's bars, with their word.** See R71-7.
- **R71-6, invariant 119 — owner's ruling.** The row becomes a surface that shows health, and inv. 119 says
  every CRITICAL contributor and every DOWN/DEGRADED in-service component is shown on such a surface, and a
  DOWN asset's health never without its condition. The issue itself says the row reflects the canonical
  aggregate and invents no list-only rule, and keeps condition separate. Options: **(a, recommended)**
  exempt the Assets-list row as a glance surface — the aggregate band alone, contributors and condition on
  the detail one tap away — recorded as a ratified amendment to §6.5 and pinned by
  `anAverageNominalRowShowsTheAggregateAlone`; (b) comply without folding: a DOWN/DEGRADED asset's row also
  draws the existing condition badge (S2/S3 words, `conditionDown`/`conditionDegraded`, the `block` /
  `trending_down` glyphs — one more badge in the wrapping line) and a CRITICAL contributor behind a better
  aggregate is shown by a means the owner names (a CRITICAL badge in place of the aggregate is a list-only
  rule the issue forbids; a second small mark needs a new string). **Owner: a or b.**
- **R71-7, health as the ratified badge, not an icon-only disc — owner's confirmation.** D12's
  non-negotiable is position + wording + icon + colour, and §10.6 lists the word with the bars for rows; the
  CRITICAL single bar is a 1.75 × 3.5dp stub at 14dp. The existing `HealthBadge(band, score = null)` gives
  the glyph and the word at about 90dp, which the wrapping badge line absorbs, and needs no new string.
  Alternative: icon-only discs (24–26dp, an 18dp glyph) with three new accessibility strings. **Owner:
  confirm the badge, or ask for icon-only.**
- **R71-8, components by their own state**, never inherited or summed.
- **R71-9, the badges move under the name into a wrapping line** (a carve-out from C9): the only shape that
  cannot clip; look and order unchanged; the disc stays at the far right. Rejected: keeping the single
  trailing line (three badges already overflow below ~335dp today; with a health badge no phone fits at
  font scale 2.0) and truncating (forbidden by the #68 rule). **Owner: confirm** — issue AC 9 says the lifecycle badges
  remain unchanged, and position is one of D12's four channels, so the move is yours to ratify.
- **R71-10, the row's health is gated on in service; the detail's is not.** A retired or archived asset
  draws no health badge on the list (the glance at the in-service estate; the attention list gates the
  same way) while its detail still shows everything. **Owner: confirm.**

## 8. What this plan does not do

No change to #73's filters or reasons; no condition derived from health or health from condition; no NOT
TRACKED badge; no score on the row; no persisted health; no dashboard, detail or engine change; no new
glyph; no version bump.

## 9. The brief (one implementer)

**Read first:** §1–§8; issue #71's body; the 1.4 spec §6.3, §6.5 and §10.6; `AssetHealthReadModel.kt`,
`AssetHealthReadModelTest.kt` and `ConditionHealthFixtures.kt` (the fixture), `DashboardViewModel.kt`
(`refreshes`), `ScheduleDetailScreen.kt` (`LifecycleResumeEffect`), `HealthBadge.kt`, `StatusBadge.kt`,
`AssetsScreen.kt` (`AssetListRow`), `AssetViewModels.kt` (the post-#73 `AssetsViewModel`),
`AssetsFiltersTest.kt` and `ActionGridTest.kt` (the `draw` technique and the unclipped-text check).
**Lane:** alone, branched from master after this plan's ratified commit. **Blocked on:** §6 and the owner's
answers to R71-2, R71-6, R71-7, R71-9, R71-10.

### Contracts
C1–C9 of §2, §5, the strings of §6 as ratified, the rulings of §7 as answered.

### Test matrix
§3 verbatim; each case's RED mutation shown and quoted; expected words, bands and descriptions hard-coded.

### Gate
- `:core:test :app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`.
- Connected on `emulator-5554`, one class per invocation: `AssetsIndicatorsTest`, `AssetsFiltersTest`,
  `AssetsSearchTest`, `AssetModelDeviceProofTest`, `DashboardAttentionTest`.
- Anchored greps: `git grep -nE 'HealthScore\.|AssetHealthEngine\.|CRITICAL_AT_MOST|WARNING_AT_MOST' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/asset`
  → 0; `git grep -niE 'Color\(0x|Color\.Green' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/asset` → 0;
  `git grep -nF '"NFC tag '` over `app/src/main` → 1 (the ratified P71-4, on one line); `isWrittenFor(` → 1 in
  `core/src/main` and ≥ 1 in `AssetViewModels.kt`.
- `git diff <base> --stat -- core/src/main/kotlin/com/loosecannon/servicetag/core/health tools docs/api app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt app/src/main/kotlin/com/loosecannon/servicetag/api` → empty.
- The assert sweep; gitlink `7e0377a`; `git status` clean.

### Must NOT
Score, aggregate or call the engine outside the read model; store a band; count a tag row that fails the
predicate; fan out per-asset flows; read tags or health from Compose; let the health pass block or fail the
list; add a NOT TRACKED badge, a score or a green; change a badge's word, family, glyph or order; touch
#73's chips or reasons, the dashboard or the detail; use any device but `emulator-5554`.

### Size
Medium: one core predicate, two ports, two DAO queries, two Room mappings, two in-memory flows, the read
model's flow and collaborator, the row read model and view model, the row composable; seven test files
touched, three new.
