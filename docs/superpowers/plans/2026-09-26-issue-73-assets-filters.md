# #73 — compact Type, Components and Archived filters on the Assets list: plan and brief (rev 2, reviewed 2026-09-26; re-review fixes N1–N9 applied)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review
> budget in `docs/superpowers/planning-policy.md`. One brief (§9), one implementer, one task review, one
> batched fix round, at most one scoped re-review. Planned from issue #73's body under the owner's GO of
> 2026-09-26, with #71 audited alongside for the shared Assets-list seam (§8). Rev 2 folds in the
> independent brief review (eight majors: the blank-query empty state, the empty-reason rule, the device
> and JVM re-anchorings, the stale-type write-back, the Type chip's semantics, three vacuous REDs, the
> Type-hidden case). Nothing is dispatched until the owner has ratified every string in §6 and read §7.

**Goal:** the Assets tab gains one compact filter row under the search box — `Type ▼`, `Components`,
`Archived` — that fits one line at ordinary phone width, wraps rather than clips elsewhere, and composes
with the search: visible rows = archived policy ∩ component policy ∩ selected Type ∩ query. Type reads the
durable Category catalog (#74) and never a second taxonomy; Components and Archived default OFF; changing
one control never resets another or the query; search narrows only the admitted set; an empty list always
says which control is in the way.

**Spec:** issue #73 (AC 1–9, the composition formula, the layout intent); issue #71 (the seam); #74's
`CategoryCatalog.choices` / `CategoryKey` (the Type source); the Assets list's standing rules in
`AssetsViewModel`'s KDoc (order active → retired → archived; the B07 fix-round-1 ruling that a blank query
lists components — **retired by this plan**, since the owner now asks for an explicit Components control
that defaults OFF); owner ruling §18.23 (an empty list says why it is empty; a match hidden by a filter is
named, never reported as "nothing matches" — R73-7 extends it to every filter).

## Global constraints

- **One dimension.** Type filters by `Asset.category` against the catalog's choices by key; no
  `equipment_type`, no second list of names, no derivation of choices from Asset strings.
- **Composition, never replacement.** Four predicates AND together; toggling one leaves the other two and
  the query as they are; the sort is unchanged; nothing toggles by itself.
- **Defaults.** Type = All, Components = OFF, Archived = OFF (the semantics `Show archived` has today).
- **Strings.** Every user-visible word is in §6, verbatim, ratified before dispatch.
- **Layout.** Compact Material chips in a wrapping row; no label clipped or truncated at any width or font
  scale (the #68 rule); accessibility over one line at all costs.
- **The tombstone rule for comments:** no comment keeps the retired blank-query-lists-components rule
  (§9 names the four sites to rewrite).
- Tests JVM-first, Compose instrumented on `emulator-5554` only, one class per invocation. Hygiene,
  gitlink `7e0377a`, one-line commits.

## 1. Audit (controller, read-only, done 2026-09-26; corrected by the review)

**The screen today** (`ui/asset/AssetsScreen.kt`, 216 lines): `TopAppBar("Assets")` + "Add asset";
`SearchBox` (placeholder "Search assets and components"); one `FilterChip("Show archived")` on
`ControlShape`; the empty states — the §18.23 hint "Matching assets are archived. Turn on Show archived to
see them.", "Nothing matches that." under a non-blank query, and under a blank one `onlyArchivedLeft =
!showArchived && archivedCount > 0` → "No active assets · <n> archived" + `Add asset` + an outlined `Show
archived` button, else "No assets yet" + `Add asset`; then the `LazyColumn` of `AssetListRow` (name,
category subtitle, "Part of <parent>", the badges Retired / Out of season / Archived — the badge draws its
label upper-cased and keeps the word in its content description, so `onNodeWithText("Archived")` never
finds a badge).

**The view model** (`AssetsViewModel`): `state = combine(seasonal, showArchived, queries)` (three flows —
the typed `combine` overloads stop at five); `visible = archived ? rows : rows.filter { ACTIVE }`;
`matching = visible.filter { it.matches(query) }` over six fields (`AssetSearch.kt`); sorted by lifecycle
rank then name; `AssetRow(asset, parentName, outOfSeason)`; `archivedCount`; `showArchivedOnlyHint`.
Components are listed under a blank query today. Eighteen JVM cases use `listModel()`
(`AssetViewModelsTest`), three device cases in `AssetsSearchTest`; `AssetModelDeviceProofTest` and
`SeasonReconciliationNavigationTest` also drive this screen (§3 names what each re-anchors).

**What #74 gives Type.** `CategoryCatalog.choices(rows)`: built-ins in compiled order, then the owner's
categories by `CASE_INSENSITIVE_ORDER`, deduplicated by key; `CategoryKey.of(text)`; every write path
(editor, API, migration, replace, merge) promotes, an in-use delete is refused and a rename rewrites the
assets, so every stored `Asset.category` is a catalog spelling and "any category currently used by an
Asset remains filterable" holds by construction. `graph.categories.observeAll()` is the live source. Only a
test fixture that calls `assets.upsert` directly can store an uncatalogued spelling — which §3 uses on
purpose to prove matching by key.

**Precedents.** `FilterChip` on `ControlShape` (the chip being replaced); the editor's template chips in
a `FlowRow`; `ExposedDropdownMenuDefaults.TrailingIcon(expanded)` as the decorative arrow (eight sites);
the dashboard's menu draws no mark on the chosen row; the #68 `draw` technique for a fixed frame width and
font scale (`ActionGridTest.kt`).

**#71's seam** (audited, not planned here): #71 adds `hasWrittenTag` and a health band to `AssetRow`,
needs a new `TagRepository.observeAll(): Flow<List<TagBinding>>` port and a reactive health source —
`AssetHealthReadModel` exposes no `Flow`, only suspend `forAsset` / `resultFor` — and draws two indicators
at the row's trailing end. The changes are additive and orthogonal to #73's (inputs and predicates versus
row fields and flows), so they serialize, #73 first, with the seams in §8.

## 2. Behaviour (the brief's contract)

- **C1, the state.** One `data class AssetFilters(type: String? = null, showComponents: Boolean = false,
  showArchived: Boolean = false)` held in one `MutableStateFlow` updated by `copy` (so "one toggle never
  resets another" is structural, and the `combine` stays within its typed arity: seasonal, filters,
  categories, query). `AssetsState` gains `filters: AssetFilters` (and keeps `showArchived` as a mirror of `filters.showArchived`), `typeLabel: String?` (the chosen
  category's display, null while All), `typeChoices: List<CategoryChoice>` (from
  `CategoryCatalog.choices(categories.observeAll())`), and `emptyReason: EmptyReason` in place of
  `showArchivedOnlyHint`; `archivedCount` and `query` keep their meaning.
- **C2, the predicates.** `visible` = rows where (`showArchived` || `status == ACTIVE`) && (`showComponents`
  || `parentAssetId == null`) && (`type == null` || `CategoryKey.of(category) == type`); `items` = `visible`
  filtered by `matches(query)`, sorted as today. A component's admission depends on its own status only.
- **C3, search within the admitted set (R73-1).** A query never widens the set and never toggles a control.
- **C4, the Type menu and the stale key.** Rows: P73-2 `All` first, then `typeChoices` by display, no mark
  on the chosen row (the dashboard's shape). Picking sets `filters.type` to the key (`All` → null). When
  the catalog no longer holds `filters.type` (renamed to a new key or deleted in Settings), the view model
  **writes the control back to null** in the same recomputation — a compare-and-set, `filters.update { if (it.type == stale) it.copy(type = null) else it }`, so a type the owner picked between two emissions is never erased, and the state emitted by that recomputation is already built with `type = null` — never a derived "effective type" that would silently re-apply if the key came back (#74's accepted resurrection, or the owner re-typing the category): once reset, Type stays All until the owner picks again. Room's asset and category flows re-query separately after a rename, so one emission may pair a new list with an old catalog; that is harmless (a spelling-only rename keeps the key, a new-key rename is meant to reset; at worst one frame of an empty list).
- **C5, the empty reason — one enum, the view model's, decided by this ordered table** (every
  combination of query, Type and the hiding controls lands on exactly one member; `hits` = all rows
  matching the query alone; `T` = the hits that pass the type):

  ```text
  items non-empty                                        → NONE
  query blank and Type All:
    no rows at all                                       → NO_ASSETS
    no row passes the archived policy                    → NO_ACTIVE_ASSETS   ("No active assets · <n> archived")
    else                                                 → ONLY_COMPONENTS    (P73-9)
  else (query non-blank, or Type set):
    T empty:  query non-blank and hits non-empty         → TYPE_HIDDEN        (P73-10)
              otherwise                                  → NOTHING_MATCHES    ("Nothing matches that.")
    T non-empty, the controls hiding T:  {Components}    → COMPONENTS_HIDDEN  (P73-6)
                                         {Archived}      → ARCHIVED_HIDDEN    (P73-5)
                                         {both}          → BOTH_HIDDEN        (P73-7)
  ```

  The union over `T`, never a precedence, so a mix (an active component and an archived root) reads
  P73-7 and never a sentence false for one of its rows; a blank query with a Type set whose hits are
  hidden by Components reads P73-6. The screen switches on the enum and computes nothing.
- **C6, the row of controls.** Directly under the search box, a `FlowRow` (8dp horizontal and vertical
  spacing — chosen over the editor's 6dp; 16dp side padding) holding, in order: the Type chip, the Components chip, the Archived chip, all on `ControlShape`. The Type chip is an **`AssistChip`** — the one Material 3 chip with a trailing-icon slot and no selection semantics (a `FilterChip` draws through `Surface(selected, onClick)` and `Role.Checkbox`, which TalkBack would read as "checkbox, checked"; `SuggestionChip` has no trailing slot; `InputChip` is selectable): label P73-1 while All and the chosen display while set; trailing `ExposedDropdownMenuDefaults.TrailingIcon(expanded)` (decorative, no content description); `Modifier.semantics { role = Role.DropdownList; contentDescription = P73-1; stateDescription = the chosen display or P73-2 }` (the outer role overrides the chip's `Role.Button`); the set-type look through `AssistChipDefaults.assistChipColors` / `assistChipBorder`. Tapping opens a `DropdownMenu` anchored to it. The Components and Archived chips are
  ordinary `FilterChip`s (`selected` = the control). The Archived chip replaces `Show archived` and reuses
  the screen's `ARCHIVED` word (the same literal the badge draws). Chips size to their labels and never
  truncate; at 380dp of content width and font scale 1.0 the three share one line; a long chosen type or a
  larger font wraps the row onto more lines, never over the list.
- **C7, the row itself is untouched** — name, category, "Part of", the three badges — so #71's trailing
  cluster lands on an unchanged composable (§8).
- **C8, nothing else moves.** The search box and its placeholder (unless P73-11 is ratified), the sort,
  `archivedCount`, the add action, navigation, the dashboard.

## 3. Test matrix (hazards; the brief fixes names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| defaults (AC 3–5) | `AssetViewModelsTest` · `theFiltersDefaultToAllOffOff`: a root, its component, an archived root → items = the active root; `filters` all default | default Components ON |
| composition (AC 7) | · `theFourPredicatesCompose`: a table over (archived, components, type, query) × rows; toggling any one leaves the other two and the query as they were | reset the query on a toggle |
| type by key (AC 3, 9) | · `typeFiltersByTheCatalogKey`: one asset created through `createAsset` as `Hot tub`, one seeded through `graph.assets.upsert` as `hot TUB` (a pre-promotion spelling); `type = "hot tub"` lists both; `All` lists all | compare by display string |
| the stale key writes back (C4) | · `aRenamedOrDeletedTypeResetsToAllAndStaysThere`: pick an owner category, rename it to a new key → `filters.type == null`, `typeLabel == null`; re-create the same key → Type stays All | derive the effective type without writing back |
| choices (AC 9) | · `typeChoicesAreTheCatalogInItsOrder`: built-ins first in compiled order, then the owner's rows alphabetically; an owner row **no asset uses** is offered; no duplicate | `choices = distinct Asset.category` |
| search within the admitted set (R73-1) | · `aSearchNeverWidensTheAdmittedSet`: Components OFF, a query matching only a component → items empty, `emptyReason == COMPONENTS_HIDDEN`, `filters` unchanged; Components ON → listed | widen on a match |
| the empty reasons (C5) | · `theEmptyReasonIsTheUnionOfTheControlsInTheWay`: components-only → `COMPONENTS_HIDDEN`; archived-only → `ARCHIVED_HIDDEN`; an archived component → `BOTH_HIDDEN`; **a mix (one active component + one archived root)** → `BOTH_HIDDEN`; a match with another type → `TYPE_HIDDEN`; no hit → `NOTHING_MATCHES`; a blank query with a Type set and no row of that type → `NOTHING_MATCHES`; a blank query with a Type set whose hits are components → `COMPONENTS_HIDDEN`; blank query, Type All: an archived root with two active components (the `AssetModelDeviceProofTest` fixture) → `ONLY_COMPONENTS`; only archived rows → `NO_ACTIVE_ASSETS`; Archived ON with only archived components → `ONLY_COMPONENTS`; nothing → `NO_ASSETS` | a precedence instead of the union (the mix reads `COMPONENTS_HIDDEN`); the blank branch before the `items` check |
| re-anchored JVM cases | `AssetViewModelsTest` · `listModel()` gains the categories repository; `assetsRowsCarryPartOf`, `aComponentIsListedUnderABlankQueryAndASearchNarrowsToIt` (→ "…WithComponentsOn…") and `clearRestoresTheList` turn Components ON before waiting for a component; the five `showArchivedOnlyHint*` cases become `emptyReason` cases (`ARCHIVED_HIDDEN` / `NONE` / `NOTHING_MATCHES`; `…IsFalseUnderABlankQuery` → `NO_ACTIVE_ASSETS`) keeping their Q4 discriminators; `AssetsState.showArchived` **stays** as a mirror of `filters.showArchived`, so `theListEmitsAfterACreateAndHidesArchivedRowsUntilTheChipIsOn` and `showArchivedStillComposesWithAQuery` read as they do; the controls are `toggleArchived()` (kept, six callers), `toggleComponents()` and `pickType(key: String?)` | — |
| the screen (AC 1, 2, 6) | `AssetsFiltersTest` (Compose, emulator) · `theThreeChipsShareOneRowInAPhoneFrame`: the filter row composable drawn alone in a 380dp frame at scale 1.0 (the #68 `draw` technique) — three chips, equal tops, each inside the row's bounds; `componentsOnRestoresThePart`: the full screen over a Room-backed graph — Components ON lists the component with "Part of <parent>"; `theTypeMenuListsAllAndTheCatalogAndFilters`: open the menu (items scoped with `hasAnyAncestor(isPopup())`), `All` first, pick a category → only its rows, the chip shows the display, its `stateDescription` is the display; `theTypeChipIsADropdownNotACheckbox`: role `DropdownList`, `contentDescription` P73-1, `stateDescription` the choice, and `keyNotDefined(SemanticsProperties.Selected)` (a `FilterChip` would set `Selected`); `theArchivedChipKeepsTheOldSemantics`: the archived-only reason reads P73-5 and the Archived chip lists the row | drop the FlowRow; put All last; `Role.Checkbox` |
| no clipping (AC 8) | `AssetsFiltersTest` · `aLargeFontWrapsTheRowWithoutClipping`: the row in a 380dp frame at scale 2.0 — at least two distinct chip tops; every chip inside the row's bounds; every one-word label `lineCount == 1`; no `didOverflowHeight` | fixed-height `Row` |
| re-anchored device cases | `AssetsSearchTest.aComponentIsListedAndNamesItsSystemAndASearchNarrowsToIt` asserts P73-6 first, then turns Components ON; its archived case reads P73-5 and taps `Archived`; `AssetModelDeviceProofTest.deletingAParentIsRefusedByNameAndArchivingItLeavesTheChildrenActive` turns Components ON before asserting the children and taps `Archived` (the chip) where it tapped `Show archived`; `SeasonReconciliationNavigationTest` anchors on `Archived` where it anchored on `Show archived` | — |
| regression | `AssetDetailConditionHealthSeasonTest` unchanged; the dashboard tests unchanged | — |

## 4. Files (indicative; the implementer owns the placement)

**Modify** `ui/asset/AssetViewModels.kt` (`AssetFilters`, `AssetsState`, `EmptyReason`, `AssetsViewModel`;
its KDoc rewritten — the retired rule goes), `ui/asset/AssetsScreen.kt` (the control row, an internal
`AssetsFilterRow` composable for the layout tests, the empty states; the P73 consts; the comment at
`:93–110` rewritten), `ui/asset/AssetSearch.kt` (the placeholder only if P73-11 is ratified). Tests:
`AssetViewModelsTest`, `AssetsSearchTest` (its class KDoc rewritten), `AssetModelDeviceProofTest`,
`SeasonReconciliationNavigationTest`, a new `AssetsFiltersTest`. **Untouched:** `AssetListRow`'s content
(C7), `DashboardFilters.kt` / `DashboardViewModel.kt` (R74-9), the catalog, the repositories.

## 5. Layout and font-scale behaviour

The `FlowRow` places the three chips left to right with 8dp gaps; each chip is as wide as its label plus
Material's chip padding (8dp each side) and, for Type, an 18dp trailing icon, so a label is never cut. At
380dp of content width and font scale 1.0 the row is one line (about 300dp: `Type ▼`, `Components`,
`Archived`). It wraps near font scale 1.5, and for a long chosen type such as a built-in with three words;
nothing scrolls horizontally and nothing overlaps the list. Chip touch targets are Material's 48dp.

## 6. Strings — for ratification

| id | where | text | note |
|---|---|---|---|
| P73-1 | the Type chip while All; its accessible name always | `Type` | the issue's word. **Note:** the app's ratified word for this dimension elsewhere is *Category* (the editor's field, the Categories screen, the dashboard's `All categories`), and `Type` already names other things in the app (a share intake's kind, a definition's kind); the owner ratifies knowing the clash |
| P73-2 | the Type menu's first row; the chip's state while All | `All` | |
| P73-3 | the Components chip | `Components` | |
| P73-4 | the Archived chip (replaces `Show archived` on the row) | `Archived` | the screen's existing word; no collision with the badge (it draws upper-case) |
| P73-5 | empty: hidden by Archived only | `Matching assets are archived. Turn on Archived to see them.` | rewords the ratified §18.23 hint to the chip's new name |
| P73-6 | empty: hidden by Components only | `Matching assets are components. Turn on Components to see them.` | new |
| P73-7 | empty: hidden by both | `Matching assets are hidden. Turn on Components and Archived to see them.` | new; true for a mix and for an archived component |
| P73-8 | the blank-query empty state's outlined button | `Show archived` | **recommended: keep** (a button names its action; already ratified) |
| P73-9 | blank query, every active asset is a component, Components OFF | `Only components here. Turn on Components to see them.` | new (the review's finding 1) |
| P73-10 | empty: the matches have another type | `Matching assets have another type. Set Type to All.` | new; R73-7 — **recommended**, keeping §18.23 whole (no "to see them": those hits may also be hidden by Components or Archived, and the next empty state names that control); the alternative is `Nothing matches that.` with the chip showing the type |
| P73-11 | the search box placeholder | keep `Search assets and components` **or** `Search assets` | owner's choice; with Components OFF by default a search finds no component until the chip is on, and P73-6 says so |
| — | unchanged | `Assets`, `Add asset`, `Nothing matches that.`, `No active assets · <n> archived`, `No assets yet`, `Part of <parent>`, the badges | |

New sentences: P73-1, P73-2, P73-3, P73-4, P73-6, P73-7, P73-9, P73-10 (eight); the P73-5 rewording; the
P73-8 and P73-11 choices.

## 7. Rulings (controller, 2026-09-26; the owner reads these before dispatch and may override any)

- **R73-1, search never widens the admitted set.** Components OFF defines the set; a query searches within
  it; the empty state names the control in the way (C5). Rejected: auto-exposing hidden component matches
  (it changes the meaning of a visible control without changing its state — the owner's stated expectation).
- **R73-2, the filters are session state**, like `Show archived` today: in the screen's view model
  (surviving rotation), reset in a fresh process, not persisted.
- **R73-3, the Type chip shows the chosen category's display** while set and `Type` while All; it is a
  dropdown opener to accessibility (name `Type`, state = the choice), never a checkbox. A category renamed
  or deleted from under the filter writes the control back to All, and it stays All (C4).
- **R73-4, chips in a wrapping row.** An `AssistChip` for Type (a dropdown opener to accessibility) and `FilterChip`s for Components and Archived, all on `ControlShape` in a `FlowRow`, 8dp gaps; no outlined field, no truncation; wrapping is the adaptation.
- **R73-5, matching by key**, so a spelling stored before promotion (a fixture, or a device that has not
  run #74's migration) still matches.
- **R73-6, #71 after #73, no shared brief.** Additive and orthogonal changes; #73 leaves `AssetListRow`
  and `AssetRow` untouched and names the seams (§8).
- **R73-7, every hidden match is named (§18.23 kept whole).** The reason comes from the union of the
  controls hiding the matches; a match with another type reads P73-10 (recommended) rather than "Nothing matches that." — worded without a promise, since those hits may still be hidden by Components or Archived and the next empty state names that control; the blank-query states gain `ONLY_COMPONENTS` (P73-9) so an archived system with active
  parts is never reported as "No active assets". **Owner: confirm P73-10, or choose the alternative.**
- **R73-8, the retired rule leaves the comments.** The B07 "blank query lists components" paragraph and the comment at `AssetViewModels.kt:222`, the `showArchivedOnlyHint` KDoc, `AssetsSearchTest`'s class KDoc and its `:61` comment, the `AssetViewModelsTest` KDocs at `:1024–1028` and `:1053–1057`, and `AssetsScreen.kt:93–110` are rewritten to the new rule; nothing keeps the old one (the gate greps `components included` and `hide-components` to 0).

## 8. The #71 seam (for the #71 plan; nothing here is implemented in #73)

- `AssetRow` gains `hasWrittenTag: Boolean` and `health: HealthBand?` in #71; #73 adds no field to it.
- `AssetsViewModel.state`'s `combine` after #73 is (seasonal, filters, categories, query); #71 adds
  `tags.observeAll()` (a new `TagRepository` port method, a Room DAO query and the in-memory double) and a
  reactive health source — `AssetHealthReadModel` today exposes only suspend `forAsset` / `resultFor`, so
  #71 designs its own flow (or a per-asset `flatMapLatest` as `seasonal` does); at five flows #71 nests or
  pairs them, since the typed `combine` stops at five.
- `AssetListRow`'s trailing region: the badges stay; #71 appends its two indicators — #73 does not touch
  the row.
- Test files shared: `AssetViewModelsTest` (both add cases), `AssetsSearchTest` (#73 re-anchors; #71 leaves
  it), `AssetModelDeviceProofTest` and `SeasonReconciliationNavigationTest` (#73 re-anchors two strings),
  and each adds its own device class.

## 9. The brief (one implementer)

**Read first:** §1–§8; issue #73's body; the `AssetsViewModel` KDoc (to rewrite); `CategoryCatalog.kt` and
`CategoryKey.kt`; `ActionGridTest.kt`'s `draw` technique; `DashboardFilters.kt`'s menu (the no-mark
precedent). **Lane:** alone, branched from master after this plan's ratified commit. **Blocked on:** §6 and §7.

### Contracts
C1–C8 of §2, §5, the strings of §6 as ratified, the rulings of §7.

### Test matrix
§3 verbatim; each case's RED mutation shown and quoted in the report; the expected strings and enum
members hard-coded.

### Gate
- `:app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`.
- Connected on `emulator-5554`, one class per invocation: `AssetsFiltersTest`, `AssetsSearchTest`,
  `AssetModelDeviceProofTest`, `SeasonReconciliationNavigationTest`, `AssetDetailConditionHealthSeasonTest`.
- Anchored `git grep -nF` over `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetsScreen.kt`,
  each literal on one line: `"Type"` → 1; `"All"` → 1; `"Components"` → 1; `"Archived"` → 1 (the chip and
  the badge share the const); P73-5, P73-6, P73-7, P73-9, P73-10 → 1 each; `"Show archived"` → 1 (P73-8
  kept) or 0; `Show archived to see them` → 0 across `app/src/main`; P73-10 → 1 if ratified, else 0; if P73-11 is reworded, `"Search assets"` → 1 in `AssetSearch.kt` (a const) and the old literal → 0 with `AssetsSearchTest.kt:66` re-anchored; `components included` (2 today) and `hide-components` (1 today) → 0 across `app/src` (the retired rule's comments gone).
- `git diff <base> --stat -- core tools app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard app/src/main/kotlin/com/loosecannon/servicetag/ui/settings app/src/main/kotlin/com/loosecannon/servicetag/data` → empty.
- The assert sweep; gitlink `7e0377a`; `git status` clean.

### Must NOT
Derive Type choices from Asset strings; auto-toggle any control on a search; give the Type chip a checkbox
role or a `contentDescription` that hides the chosen value; derive an "effective type" without writing the
control back; add a field to `AssetRow` or change `AssetListRow`; persist the filters; touch the
dashboard; truncate or clip a label; add a string outside §6; keep the retired rule in any comment; use
any device but `emulator-5554`.

### Size
Medium: one view model, one screen (plus an internal row composable), five test files.
