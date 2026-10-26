# #116 — the Assets tab: the Home Assistant link, the condition with its filter, and the season mark: plan (rev 1, 2026-10-26)

> **Rev 1 (2026-10-26).** #116 joined ServiceTag **1.9.0** (owner rulings 2026-10-23 and 2026-10-26, #104) beside
> #113, #114 and the fixes #119, #121, #123. The owner: "a separate feature from #113/#114. It needs its own acceptance
> and UI proof." The controller's rulings of 2026-10-26: its own plan document; every new input screen joins
> `InsetsContractTest`'s group B with `formContentInsets` (this plan adds none); any new string goes to a pending table
> for one small owner string ruling, and nothing else returns to the owner. This plan **specifies**
> (`docs/superpowers/planning-policy.md`): contracts, invariants, the test matrix, file ownership and proofs; the
> implementer authors the code and the tests.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget (one
> task review per brief, at most one bounded fix round, one whole-branch review — the 1.9.0 one).
> **Ledger:** `.superpowers/sdd/2026-10-22-issues-113-114/progress.md` (the controller's; implementers never write it).
> **Branch:** `issue-113-114-ha-publishing` (one 1.9.0 integration branch), worktree `../noteNFC-wt-113`.
> **Base:** master after the merge of `issue-121-keyboard-insets` (read on `16768cde` + `42647492`), then the #113/#114
> master plan's waves (`docs/superpowers/plans/2026-10-22-issues-113-114-home-assistant-publishing.md`, rev 6; "the
> master plan" below, rev 6.1, its constraints G1–G31). **Two briefs:** U1 (wave 8, lane 2) and U2 (wave 14, lane 1), the
> master plan's empty slots, so the train keeps two lanes.
> **Paths:** as the master plan's: `C/`, `CT/` core main/test, `A/`, `T/`, `AT/` app main/test/androidTest under
> `com/loosecannon/servicetag/`. Every `file:line` was read on `42647492`.

**Goal (the issue's words, abridged):** on the Assets tab, each asset row shows (1) a small Home Assistant mark when
the asset has a Home Assistant binding — the inbound season sync (#16), and the outbound equipment-status (#113) or
Season Start date (#114) publication; (2) its operational condition — Operational, Degraded or Down — from its current
condition row, the same canonical source #113 publishes, and the list can be filtered by condition; (3) an in-season
or out-of-season mark when the asset is seasonal, nothing when it is year-round. Every mark is derived from the
canonical read models; nothing new is stored. The fictional assets of the master plan ("Example Heater", "Example
Boiler", a year-round "Example Indoor Pool") stand in for real ones.

## Global constraints

Each line is a sentence a reviewer can test against the diff.

1. **Nothing new is stored** (the issue): no table, column, index, preference, DataStore key or archive key; the
   filter is session state held by the view model across a rotation and dropped with it (#73's R73-2). No schema,
   format, `/v1` or MCP change; no `AppGraph`, `FakeGraph`, core or `A/hapublish` edit.
2. **Seasonality is configured per asset** (the master plan's G22 and G27, verbatim from the owner: "Seasonality is
   configured per asset. Never infer it from equipment category, name or shared group membership."): an asset is
   seasonal exactly when its own `seasonMode` is not `YEAR_ROUND`, and its phase today is the shipped
   `SeasonContext` phase over its own activations — the computation the list already makes for "Out of season"
   (`AssetViewModels.kt`, `AssetsViewModel`'s 1.4 B14 rule). No category, type, template, name, title or group
   membership takes part.
3. **Three facts, kept apart** (the master plan's G8 and §1a): the Home Assistant link, the condition and the season are
   three marks from three sources; none is derived from another, and none from health, due dates, readiness or a
   publication's delivery state. Readiness ("ready for the season") is **not** drawn on the row: the issue does not ask
   for it, and it lives on the season card (C33 of the master plan).
4. **One canonical source per fact.** Condition = `ConditionHistory.current` of the asset's own rows
   (`C/condition/ConditionHistory.kt:17-35`), the value B1's `equipmentStatusOf` publishes (the master plan's C15),
   never the newest `createdAt`; the Home Assistant link = an **enabled** `season_sync_binding` row or an **enabled**
   `ha_publish_binding` row of either kind for the asset; the season = constraint 2.
5. **Cheap on a 50-asset list** (the issue): each source is collected **once** per list — `conditions.observeAll()`,
   `SeasonSyncRepository.observeAll()`, `PublishBindingRepository.observeAll()` — and joined to the rows by asset id in
   the view model in one pass, O(assets + rows); no per-row flow beyond the shipped MANUAL activation flows; no
   network, Home Assistant read, projector call or health computation is added.
6. **Palette and signifiers** (#71's R71-2, D12 §5, the G1 gate report: "OK is Signal blue, never green"): no green, no
   raw `Color(…)` at a call site; the condition badge keeps 1.4's ratified colours (`conditionColors`,
   `A/ui/condition/ConditionBadge.kt:41-47`), word and glyph; the in-season mark is in the `maintenanceOkay` family;
   the Home Assistant mark is neutral (`onSurfaceVariant`: a link, not a status). Colour is never the only signifier:
   every mark carries its glyph and a ratified accessible name.
7. **Glyphs are generic.** The Home Assistant mark is a house with a link mark, never the Home Assistant logo; the
   season mark is a calendar with a tick, never a sun or a snowflake (a season may fall in any month, constraint 2).
8. **#71's and #73's ratified rules hold**, but where this issue's text amends one, named in U-C4: the lifecycle and
   loan badges, the health group, the NFC disc at the far right, the "Out of season" badge, the three controls, their
   TalkBack roles and every shipped empty reason are unchanged.
9. **Insets (#121; the master plan's G28).** No `Scaffold`, sheet or dialog is added. `AssetsScreen.kt` stays in
   `InsetsContractTest`'s group B (kind L) and keeps `contentWindowInsets = formContentInsets`; the Condition control is
   a dropdown menu with no typed input; `InsetsContractTest` is unchanged. The share picker (`ShareIntakeScreen`, group
   F) draws the same rows and controls (#93: "the Assets tab's own list") and its insets are untouched.
10. **Every user-visible string and accessible name is a resource in all ten packs**, ratified before U2 runs (§5),
    held by `LocalizationCoverageTest` and `UiLiteralGuardTest`; no developer log line is added.
11. **Exhaustive `when`, never `else`,** over `EmptyReason` (one new member) and `OperationalCondition` wherever this
    plan adds a branch.
12. **Standing rules** (the master plan's G19 and G21): fictional names only in fixtures and docs; commits one casual
    lowercase subject line, no body, no trailer, no tool name; JVM first; every counted RED a real mutation
    (`--no-build-cache --rerun-tasks`), quoted, reverted; no rerun-until-green; time boxes 1 h target, 2 h hard.

## 1. Scope

**In scope — the issue's acceptance, each on its contracts and rows:**

| AC | the issue's words (abridged) | contracts · rows |
|---|---|---|
| AC116-1 | a small Home Assistant mark on each asset with a Home Assistant binding: the inbound season sync, later #113's and #114's publications, beside the NFC/QR indicators | U-C1, U-C2, U-C4 · u1, u7, u9 |
| AC116-2 | an operational condition indicator per row (Up / Degraded / Down) from the current condition row, the source #113 publishes | U-C1, U-C4 · u2, u9, u10 |
| AC116-3 | filtering the Assets list by condition | U-C3, U-C4 · u5, u6, u9 |
| AC116-4 | an in season / out of season mark per seasonal asset; year-round shows nothing | U-C1, U-C4 · u3, u9 |
| AC116-5 | derived from canonical read models; nothing new stored | constraints 1, 4 · u1–u3, R6 |
| AC116-6 | every string and accessibility label ratified, all packs | §5 · u11 |
| AC116-7 | #71's palette rules; the condition colours a design decision to ratify | constraint 6 (1.4's ratified condition colours reused, no new colour) · u9 |
| AC116-8 | cheap on a 50-asset list; the connected suite covers the rows on `emulator-5554` | constraint 5 · u4, u9 |

**Out of scope:** readiness on the row (constraint 3); a publication's state, error, copied installation or pending
invalidation on the row (the asset's publishing blocks say it, the master plan's C36); tapping a mark (no mark takes a
tap of its own; the row opens the asset as today); a "no condition recorded" filter choice; sorting by condition;
persisting the filter; any `/v1` or MCP change — every fact is already readable there (the shipped condition and season
reads, #16's season-sync read, and the master plan's `GET /v1/ha-publishing`, B5r).

**Recorded limits:**

1. **"Linked" is configuration, not delivery.** The mark means an enabled binding row exists. A binding restored from
   another phone's backup (inert until Enable, the master plan's C27), or one on a retired asset that sends nothing,
   still shows it; the asset's publishing block says which.
2. **Condition only while in service.** A retired, archived or transferred-out asset shows no condition mark, as
   #113 publishes nothing for an asset not maintained here; a condition choice in the filter therefore never admits
   one.
3. **Staleness is the shipped list's.** Condition and binding changes move the rows through their flows; the season
   phase follows the list's shipped rule (activation flows for MANUAL assets, the resume refresh for a calendar day
   passing).

## 2. Contracts

- **U-C1, the row facts (U1).** `AssetRow` (`A/ui/asset/AssetViewModels.kt:207`) gains three fields, appended and
  defaulted so every shipped construction compiles unchanged: `haLinked: Boolean = false`, `condition:
  ConditionView? = null`, `inSeason: Boolean = false`. They come from one pure function in a new
  `A/ui/asset/AssetRowFacts.kt`:

```kotlin
internal data class RowFacts(val haLinked: Boolean, val condition: ConditionView?, val inSeason: Boolean)

internal fun rowFactsOf(
    asset: Asset, held: Boolean, outOfSeason: Boolean,       // outOfSeason: the list's shipped phase rule
    conditionRows: List<AssetCondition>,                     // this asset's rows only
    inboundEnabled: Boolean, publishingEnabled: Boolean,     // any enabled binding of each direction
): RowFacts
```

  - `haLinked` ⇔ `inboundEnabled || publishingEnabled`; the kind and direction do not change the mark (one mark, one
    accessible name, P116-1).
  - `condition` ⇔ the asset is in service (`asset.inService`, active and not retired — #71's E1) and not held, and
    `ConditionHistory.of(conditionRows).current` exists: a `ConditionView` built **purely** from `history.current` and
    `history.since` field for field as the health read model's `conditionViewOf` builds it
    (`A/ui/health/AssetHealthReadModel.kt:347-358`), but with `eventExists = false` — the row never opens the event, so
    no event read per row (constraint 5) — so the badge reads the same word, glyph and "since" date as the detail and
    #71's health group; else null.
  - `inSeason` ⇔ `asset.seasonMode != SeasonMode.YEAR_ROUND && !outOfSeason`. A year-round asset gets neither mark.
  - Pure: no clock, no repository; reads no category, type, name, title or group (constraint 2).
- **U-C2, the sources (U1).** `AssetsViewModel` gains three trailing nullable constructor parameters (the #72/#77
  precedent: null observes nothing, for a test not about them): `conditions: ConditionRepository? = null`,
  `inbound: SeasonSyncRepository? = null`, `publishing: PublishBindingRepository? = null`; the secondary constructor
  passes `graph.conditions` (`AppGraph.kt:360`), `graph.seasonSyncBindings` (`:474`) and B3's member for
  `PublishBindingRepository` (its name is B3's). Each is collected once through `observeAll()` (constraint 5) and
  combined with the shipped row flow; the join groups conditions by asset id once per emission. **No `AppGraph` or
  `FakeGraph` edit:** the repositories exist there after the master plan's B3.
- **U-C3, the filter (U1: state and predicate; U2: the control).** `AssetFilters` (`AssetViewModels.kt:265`) gains
  `condition: OperationalCondition? = null` (null = All), set through the view model's one filter update, so changing
  it never resets Type, Child assets or Archived (#73's `copy` rule). The list's predicates are ANDed: Archived,
  Child assets, Type, **Condition**, the query; Condition admits a row exactly when `row.condition?.condition` equals
  the choice. The share picker (`activeOnly`) applies it the same way after its own first drop.
- **U-C4, the drawing (U2).** In `AssetListRow` (`A/ui/asset/AssetsScreen.kt:336`):
  - **The condition badge** — the shipped `ConditionBadge(row.condition)` — on **every** row whose `condition` is
    non-null, in #71 E2's place: after the lifecycle and loan badges, before the health badge. A health surface no
    longer draws its own condition badge: one badge per row, never two, and a DOWN asset's condition is still drawn
    before its health (inv. 119). **This amends #71's R71-11 and E2 for the condition badge only, by the issue's own
    text** ("an operational condition indicator per asset row (Up / Degraded / Down)"): Operational is now drawn, and
    on rows that are not health surfaces too. The health badge, its lines and its gate are unchanged.
  - **Two trailing marks**, vertically centred, left of the NFC disc, which stays at the far right: the in-season mark
    (`inSeason`), then the Home Assistant mark (`haLinked`). Each sits in the disc's box size, takes no tap of its own
    and carries its accessible name (P116-3, P116-1). Glyphs: two new vectors in `ServiceTagIcons`
    (`A/ui/components/ServiceTagIcons.kt:12`), `ic_in_season` and `ic_ha_link` (constraint 7).
  - **"Out of season" stays the ratified badge** (`assets_badge_out_of_season`, #71): an out-of-season asset shows the
    badge and no in-season mark; an in-season one shows the mark. So every seasonal asset carries exactly one season
    signifier, and the badge's tests stand.
  - **The Condition control** in `AssetsFilterRow` (`:185`), after Type: `TypeChip`'s shape and semantics (an
    exposed dropdown with `Role.DropdownList` and the chosen value as its state description, never a `FilterChip`,
    `:218-250`): label P116-4 while All, else the chosen condition's word; menu rows All (P73-2), Operational,
    Degraded, Down (the Change condition sheet's ratified option words). The row is a `FlowRow` (`:195`): the fourth
    control may wrap on a narrow phone; nothing else in the header moves.
  - **The empty state.** `EmptyReason` (`AssetViewModels.kt:272`) gains `CONDITION_HIDDEN`, decided **last**: when the
    other controls, Type and the query admit at least one row and the Condition choice alone removes every one, the
    list draws P116-2; in every other case the shipped reasons decide exactly as before (the condition does not
    take part in them).
- **U-C5, the ownership of the consumed facts.** This plan reads, and never writes, the master plan's:
  - **B1:** `equipmentStatusOf(history: ConditionHistory): String?` (C15) — the oracle row u2 holds the row's
    condition against (OPERATIONAL ↔ `UP`, DEGRADED ↔ `DEGRADED`, DOWN ↔ `DOWN`, none ↔ null);
  - **B3:** `PublishBindingRepository.observeAll()` (C24) and its `AppGraph` member, and rev 6's
    `SeasonSyncRepository.observeAll()` (C24, §0 r13) — the two binding sources;
  - **S1:** the `ha_publish_binding` table (C4.2), through B3.
  **B3 must land before U1** (B3 needs S1 and B1, the master plan's waves 1, 2 and 4). U2 needs U1 and the ratified
  strings; it reads nothing more of the master plan's. Nothing of A1 (readiness) or the runner is read.

## 3. Test matrix

Rows: id · layer (**JVM**; **Compose** = instrumented, R2) · class · cases · what it proves · **the mechanism by which it
fails without the change** (the counted RED). "pin" = a shipped test re-run unchanged.

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| u1 | JVM | `T/ui/asset/AssetRowFactsTest` (new, U1) · `haLinked` for an enabled #16 binding, an enabled status binding and an enabled date binding; not for a disabled publish binding or a disabled #16 one; one mark whatever the kinds | U-C1, AC116-1 | the publish source not joined (a status-only asset reads unlinked) |
| u2 | JVM | same · the current condition per row; a backdated non-current row changes nothing; none recorded → null; a retired, an archived and a held asset → null; agreement with B1's `equipmentStatusOf` over a table of histories (the oracle, U-C5), and with `conditionViewOf` on every field but `eventExists` | U-C1, constraint 4, limit 2 | the newest `createdAt` taken instead of `ConditionHistory.current` (the backdated row wins) |
| u3 | JVM | same · `YEAR_ROUND` → neither mark; a `CALENDAR` asset inside its window → `inSeason`; a `MANUAL` asset after an END → out of season, no mark; the same inputs with the asset's name ("Example Heater", "Example Hot Tub"), category and a group membership varied answer alike | U-C1, constraint 2 | `inSeason` from `seasonMode != YEAR_ROUND` alone (an out-of-season asset gets the mark too) |
| u4 | JVM | `T/ui/asset/AssetViewModelsTest` (+1, U1) · `fiftyAssetsCollectEachSourceOnce` (counting repositories: conditions, #16 bindings, publish bindings each collected once; fifty rows carry their facts) | constraint 5, AC116-8 | a per-asset `observeForAsset` per row (fifty subscriptions) |
| u5 | JVM | same (+3, U1) · each Condition choice admits exactly its rows and All admits every row; ANDed with Type, Child assets, Archived and the query; changing it leaves the other three; a rebuilt view model starts at All; the share picker applies it after its own drop | U-C3, AC116-3 | the predicate ORed with Type (a Degraded row of another type admitted) |
| u6 | JVM | same (+2, U2) · `CONDITION_HIDDEN` only when the condition alone empties a non-empty admitted list; every shipped empty reason re-derived unchanged with the condition at All and at a choice | U-C4 empty state | `CONDITION_HIDDEN` decided before the shipped reasons (an archived-only match reads condition-hidden) |
| u7 | JVM | same (+1, U1) · `theFactsFollowTheirSourcesLive`: a condition recorded, a publish binding enabled then disabled, a #16 link removed and an END recorded each move the row with no resume | U-C2 | the sources read once at construction |
| u8 | JVM | `ShareIntakeViewModelTest`, `AssetPickerModelTest` green unchanged (pins) | constraint 9, #93 | none: pins |
| u9 | Compose | **`AT/ui/asset/AssetsRowFactsTest` (new, U2; the UI proof, ~12 cases)** · each mark drawn with its accessible name (P116-1, P116-3) and no tap of its own; left to right: in-season mark, Home Assistant mark, NFC disc at the far right; the condition badge on an untracked in-service row in all three conditions; one condition badge on a tracked DOWN row, before its health badge; none on a retired row; an out-of-season asset shows the badge and no mark, a year-round one neither; the Condition control's role `DropdownList`, its state, its four rows, and P116-2 for `CONDITION_HIDDEN`; no word clipped at 320/1.0, 360/1.0 and 412/2.0 (#71's C5 widths) | U-C4, AC116-1…4, 7, 8 | a mark hidden behind a missing fact (`assertExists` fails) |
| u10 | Compose | `AT/ui/asset/AssetsIndicatorsTest` (U2, **one named amendment**) · `anOkTrackedRowShowsOnlyTheHealthBadge` becomes `anOkTrackedRowShowsOperationalThenHealth`; every other case unchanged | U-C4's R71-11 amendment | none counted: the amended case is the issue's text (it is red against U2 without the amendment) |
| u11 | JVM | `LocalizationCoverageTest`, `UiLiteralGuardTest` with P116-1…4 in all ten packs; `AssetsFiltersTest`, `AssetsSearchTest` (Compose), `InsetsContractTest` green unchanged (pins) | constraints 9, 10 | none: pins |

**Hazards:** H1 seasonality inferred from a name, category or group (u3, R6); H2 condition drawn from the wrong row or
for an asset not maintained here (u2); H3 a per-row subscription on a long list (u4); H4 two condition badges on one
row, or condition after health (u9); H5 a mark announced by colour alone (u9's accessible names); H6 the filter
silently resetting another control (u5); H7 a new surface outside #121's insets contract (constraint 9, u11's
`InsetsContractTest` pin).

## 4. Files, fences, order

| brief | wave · lane (the master plan's §4) | touches | never touches |
|---|---|---|---|
| **U1** the row facts and the filter state (JVM) | 8 · L2 (beside B5; after B3, the master plan's wave 4; base: lane 2's tip after A4w) | `A/ui/asset/AssetRowFacts.kt` (new: `RowFacts`, `rowFactsOf`); `A/ui/asset/AssetViewModels.kt` (`AssetRow`'s three fields, `AssetFilters.condition`, the three trailing sources, the join and the Condition predicate); `T/ui/asset/AssetRowFactsTest.kt` (new), `T/ui/asset/AssetViewModelsTest.kt` (+5) — rows u1–u5, u7, u8; ~55 min | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `AssetsScreen.kt`, `res/`, `share/`, every `AT/` file, the insets files, `EmptyReason` |
| **U2** the drawing, the control, the empty state and the UI proof | 14 · L1 (rev 6.1 of the master plan; after B7; base: the integrated tip after wave 13, U1 in it) | `A/ui/asset/AssetsScreen.kt` (`AssetListRow`'s badge and marks, `AssetsFilterRow`'s Condition control, the empty state's arm); `A/ui/asset/AssetViewModels.kt` (`EmptyReason.CONDITION_HIDDEN` and its decision, only); `A/ui/components/ServiceTagIcons.kt` (two vectors); `app/src/main/res/drawable/{ic_in_season,ic_ha_link}.xml` (new); `app/src/main/res/values*/strings_asset_detail.xml` (×10: P116-1…4, ratified first); `AT/ui/asset/AssetsRowFactsTest.kt` (new), `AT/ui/asset/AssetsIndicatorsTest.kt` (the one named case), `T/ui/asset/AssetViewModelsTest.kt` (+2) — rows u6, u9, u10, u11; ~60 min | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `share/`, `InsetsContractTest.kt`, `AssetsFiltersTest.kt`, `AssetsSearchTest.kt`, every other `AT/` file, the condition package |

**Why concurrent is safe:** U1 beside B5 (wave 8) — B5 owns the graph's last edit and `A/api`, `A/hapublish`, `M/`;
U1 neither. U2 runs alone in wave 14. #124's N3 (wave 12) edits `ScheduleEditScreen` and the due rows, never the
Assets list. `AssetViewModels.kt` is also
A5b's (wave 6, the editor's strand causes): sequential, by wave.

**Between-brief gap:** U1 → U2, the facts and the Condition predicate exist and nothing draws them; the filter is set
only by tests.

**Untouched (R6 checks):** every core file; `AppGraph.kt`, `FakeGraph.kt`; `ConditionBadge.kt`, `HealthBadge`, the
health read model; `share/**`; `InsetsContractTest.kt`, `FormInsets.kt`; `app/schemas/**`; `docs/api/**`; `tools/**`.

## 5. Strings

**Pending — for the owner's one small string ruling (the controller's ruling 4, 2026-10-26); U2 runs only once they
are ratified.** Homes: `strings_asset_detail.xml` beside P73-1…11, ids in the XML comments.

| id | proposed wording | where |
|---|---|---|
| P116-1 | "Linked to Home Assistant" | the Home Assistant mark's accessible name (`assets_ha_linked`) |
| P116-2 | "Matching assets have another condition. Set Condition to All." | `EmptyReason.CONDITION_HIDDEN` (`assets_condition_hidden`; P73-10's pattern) |
| P116-3 | "In season" | the in-season mark's accessible name (`assets_in_season`; a resource of its own, not the editor's answer S36, so each translates for its place) |
| P116-4 | "Condition" | the Condition control's label while All, and its accessible name always (`assets_filter_condition`; P73-1's pattern) |

**Reused, ratified:** P73-2 `assets_filter_all` ("All", the menu's first row); `condition_option_operational`,
`condition_option_degraded`, `condition_option_down` (the Change condition sheet's option words, the menu's rows and
the chosen state); the condition badge's own words (S1–S3) and "since" line (S22), drawn by the shipped
`ConditionBadge`; `assets_badge_out_of_season`; `assets_nfc_tag_written` (P71-4). **Count:** four new strings, no
plural.

## 6. Plan positions (taken from the issue's text; no owner question but the strings)

| # | position | why |
|---|---|---|
| U-1 | The condition badge is drawn on every in-service row with a recorded condition, Operational included; a health surface draws it once, in the same place (U-C4) | the issue: "an operational condition indicator per asset row (Up / Degraded / Down)"; #71's R71-11 is amended for this badge only |
| U-2 | An in-season asset gets a mark; an out-of-season one keeps the ratified badge and no mark | each seasonal asset shows exactly one season signifier, and #71's badge and its tests stand |
| U-3 | One Home Assistant mark for any enabled binding, either direction or kind | the issue asks for "a small Home Assistant icon"; the detail tells the bindings apart |
| U-4 | No condition mark for an asset not maintained here | #113 publishes nothing for it (the master plan's C15, K12) |
| U-5 | The filter's choices are All and the three conditions | the issue's three values; "not recorded" is out of scope |
| U-6 | Generic glyphs, neutral Home Assistant mark, no new colour | constraints 6 and 7; the condition colours were ratified with 1.4 (§10.6) |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; no device run inside a brief.
- **At the 1.9.0 merged-tip gate (the master plan's §7, one run for the release):**
  - **R1** includes rows u1–u8, u11; **R2** the connected suite on `emulator-5554` includes `AssetsRowFactsTest` and
    the amended `AssetsIndicatorsTest` (84 classes / ~483 tests with the master plan's two);
  - **the UI proof (the owner: "its own acceptance and UI proof"):** `AssetsRowFactsTest` green in R2, and its
    `captureToImage()` of four fictional rows — "Example Heater" (`MANUAL`, linked, DOWN, in season), "Example
    Boiler" (`MANUAL`, linked, Operational, out of season), "Example Indoor Pool" (`YEAR_ROUND`, Degraded), a retired
    asset — in light and dark, attached to the gate evidence for the owner's look; no UI driving (#62);
  - **R-K**, the keyboard sweep (the master plan's §7): the Assets screen re-observed in portrait and landscape with
    the Condition control (kind L; a landscape list of height 0 is recorded, as ruled for #121);
  - **R6 anchored greps (`git grep -nE`), each hit read:** `'\.category|\.name\b|\.title\b|groups?\b'` in
    `A/ui/asset/AssetRowFacts.kt` → 0 (constraint 2); `'Color\(0x'` in the files U2 touches → 0;
    `'observeForAsset'` in `AssetsViewModel` → the shipped activation flow only; `'contentWindowInsets = formContentInsets'`
    in `AssetsScreen.kt` → 1; `git diff <base> -- app/schemas core docs/api tools app/src/main/kotlin/com/loosecannon/servicetag/di app/src/test/kotlin/com/loosecannon/servicetag/testing app/src/test/kotlin/com/loosecannon/servicetag/ui/nav`
    over U1's and U2's commits → empty; `'^\s*else ->'` in the `when`s over `EmptyReason` and `OperationalCondition`
    the range adds → 0.
- **Release:** #116 ships in 1.9.0 (versionCode 23); the release task writes its note beside the other five (the
  master plan's §7). Tagging is not authorized by this plan.

## 8. Relationships

- **#113, #114 (the master plan):** consumed facts and the landing order in U-C5; the master plan draws nothing on the
  Assets tab (its G17) and this plan draws nothing on asset detail.
- **#71:** the row indicators, the disc, the health group and its palette; R71-11 amended for the condition badge
  only (U-1). **#73:** the controls, their TalkBack shape and the empty reasons; one control and one reason added.
  **#82:** the condition words, colours and `ConditionBadge`. **#93:** the share picker is the tab's own list.
  **#16:** the inbound binding, read only. **#121:** the insets contract and the sweep. **#104:** 1.9.0.
