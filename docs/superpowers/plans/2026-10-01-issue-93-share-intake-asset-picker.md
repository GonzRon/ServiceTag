# #93 — a searchable, filterable, single-select asset picker as Share intake's first step: plan and briefs (rev 1.2, 2026-10-01)

> **Rev 1.2** applies the independent plan review of rev 1.1 (`.superpowers/sdd/2026-10-01-issue-93/plan-review.md`,
> APPROVE WITH CONDITIONS). **C-1** (the picker nested in the form's scroll column) → C5 (the step is decided
> **above** the column; the title drawn once), C7, §3's device note, §7 and B2's structural check. **C-2** (the
> picker's D-20 rendering) → row 27's `storeReady = false` flip, §7, B2. Notes: N-1 → §7 and B1 (`excludeHeld`
> grep → 2; no new private helper); N-2 → §1, C3, §5, §8, B3 (SPEC:80-81); N-3 → C11, §5 G1, B3 (an Amendment (#93)
> line beside SPEC:566); N-4 → C11, B3 (SPEC:492 gains a clause; the vacuous `chip` check replaced); N-5 → C11, §3
> pins, §4, B3 (`…ReachesTheIntake`; `:56` and `:156` fenced in); N-6 → C9, B2 (three KDocs); N-7 → C4; N-8 →
> §6 R93-4, this header; N-9 → limit 1; N-10 → limit 7, C7, §7; N-11 → C10, row 21; N-12 → C1; N-13 → the pin rule;
> N-14 → C11; N-15 → C9. The review's concerns: (a) R93-4's text now states what ships (the handler is **off** while
> saving, so Back mid-save finishes as today); (b) limit 7; (c) B2's stop-and-split clause (§4, §10).

> **Rev 1.1** applied the owner's rulings of 2026-10-01 (issue #93): **R93-1…14 DECIDED** — R93-4 Back on the form
> returns to the picker (wording per N-8 since rev 1.2), R93-5 the name alone with **"Change"** (rev 1's "Change
> asset" withdrawn), R93-6 retired/replaced offered, R93-8 the tab's empty state, R93-9 "reaches the intake" with the
> byte form's `TYPE` **relocated, not dropped** (row 27); the six-field `Asset.matches` by the owner's ruling; R93-7
> and R93-10…14 controller defaults the owner did not object to; the HARD SCOPE approved as described.

> **HARD SCOPE (owner-approved 2026-10-01, recorded on issue #93):** "#93 = replace the intake's chip cloud with a
> dedicated, single-select Choose-asset step that reuses the Assets tab's search box, filters, read model and rows,
> then the existing form exactly as today. Nothing after the choice changes. No schema, backup-format, API, MCP,
> attachment, Reference, document-role, URL/file classification or Share URI/security change; no asset creation
> inside the share task; no second search or filter system; no new device class." The owner's point, verbatim: "make
> finding the destination Asset fast and familiar when sharing into ServiceTag."

> **Status: PLANNING ONLY** until the owner dispatches it (placement: #76). R93-1…14 DECIDED (§6); "Change" (G1)
> is the one new phone word, ratified.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-01-issue-93/progress.md` (the controller's). **Audit (inventory of record):**
> `.superpowers/sdd/2026-10-01-issue-93/audit.md`, read on `78c23a88`. **Three briefs on one branch `issue-93`** —
> B1 → B2 → B3 (B2′ only under §4's split clause), each `<base>` the previous accepted tip; **B1's `<base>`** is
> master at dispatch (`4b12c64c` holds rev 1.1 over `78c23a88`: 1.5.0 / code 18, schema 17 / format 17, MCP 76
> tools, gitlink `7e0377a`) plus this revision's commit. B3 is **not** folded into B2 (§4). One task review each, at
> most one bounded fix round each, one whole-branch review, the merge, one merged-tip gate.

**Goal:** a shared document, link or note first lands on a dedicated step that looks and behaves like the Assets
tab — its search box, Type / Components / Archived controls (both toggles off by default), rows, order and
empty-state sentences — then on today's form with the asset named on one line and a "Change" back. Held assets are
never offered; every save path receives exactly the `(id, name)` it receives today.

**Inputs:** issue #93 with the rulings (`.superpowers/sdd/2026-10-01-issue-93/issue-93.md`, AC1–AC10); the audit;
the review (`plan-review.md`); `SPEC` = `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`; the #91 plan;
`docs/release-proofs.md`; `docs/superpowers/planning-policy.md`. Paths: `A/`, `C/`, `T/`, `AT/` = `app/src/main`,
`core/src/main`, `app/src/test`, `app/src/androidTest` + `/kotlin/com/loosecannon/servicetag/` (`C/` adds `core/`).

## Global constraints

- **Reuse, never re-implement (AC8).** The picker's rows, order, filters, query and empty reasons are
  `AssetsViewModel.state` (`A/ui/asset/AssetViewModels.kt:427-467`) — the same instance class the tab builds
  (`A/ui/asset/AssetsScreen.kt:92`), switched only to drop held rows (C2). It draws `SearchBox`
  (`A/ui/asset/AssetSearch.kt:50-78`), `AssetsFilterRow` (`AssetsScreen.kt:178-207`), `AssetListRow` (`:326-401`)
  and `EmptyList` (`:274-310`), unchanged in behaviour. **Nothing under `A/share` filters, sorts, searches or
  explains an empty list**; no predicate, comparator or sentence is copied.
- **No contract change (AC10).** Nothing under `core/`, `tools/`, `app/schemas/`, `docs/api/` or the manifest is
  touched; schema 17 / format 17, routes, tools, use cases, repositories, tables, DTOs, the share read, the lift and
  its policies, I-3, the scheme confirmation and the three save arms are untouched (C10). The tombstone check
  (`git diff <base> -- . | grep -cE '^[-+].*(external_link|externalLinks)'`) → 0. **No personal data:** fictional
  fixtures only; no real name, host, serial or e-mail address in code, tests, docs or commits.
- **Strings.** Every user-visible string is ratified before it ships (§5). #93 adds **one** phone word, "Change" (G1,
  ratified by R93-5); every other word it draws is shipped and reused verbatim from its one home.
- **Tests.** JVM first. **No new device class and no new device case** (R91-13's precedent): every rule — the
  eligibility switch, the step a state draws, Back's gate, Change, the live `(id, name)` — is a JVM row over the view
  models or a derived getter on `ShareIntakeState`. What remains on a device is rendering of already-proven states,
  which three **shipped** `ShareIntakeScreenTest` cases carry by state flips (the shipped role case's
  `mutableStateOf` idiom, `AT/share/ShareIntakeScreenTest.kt:181-202`). A new case would name no new boundary
  (`planning-policy.md:88-91`). `ShareBoundaryTest` keeps three thin cases; its `TYPE` assertion is **relocated** to
  `ShareIntakeScreenTest`'s byte-form case, after a choice (R93-9, row 27). Every counted RED is a real mutation
  run with `--no-build-cache --rerun-tasks`, its failure quoted, reverted before the commit. **No rerun-until-green.**
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump** (`versionName = "1.5.0"`,
  `versionCode = 18`). Commits: one casual lowercase subject line; no body, no trailers, no AI attribution.
  **Implementers never write ledgers.**
- **Time boxes:** every brief 1 h target, 2 h hard stop; fix rounds 45 min, findings' files only, stop on the first
  finding they cannot close. **Mutation caps** per brief (each states its own). **Review budget:** one task review
  per brief, at most one bounded fix round, a scoped re-review only for a substantive correctness finding,
  mechanical fixes (< ~50 lines) by controller inspection, one whole-branch review, merge, one gate.

## 1. Scope

**HARD SCOPE (binding, see the header):** "#93 = replace the intake's chip cloud with a dedicated, single-select
Choose-asset step that reuses the Assets tab's search box, filters, read model and rows, then the existing form
exactly as today. Nothing after the choice changes. No schema, backup-format, API, MCP, attachment, Reference,
document-role, URL/file classification or Share URI/security change; no asset creation inside the share task; no
second search or filter system; no new device class."

**In scope — the ten acceptance criteria, each on its contracts:**

| AC | the issue's words (abridged) | contracts |
|---|---|---|
| AC1 | no scrolling the whole catalog to find a destination | C1, C3, C7 (search, filters, both toggles off by default) |
| AC2 | a text search/filter control narrows as the owner types | C3 (`SearchBox`, the model's own `query` flow, F3), C7 |
| AC3 | a distinctive prefix or substring makes the asset quickly selectable | C1 (`Asset.matches`, six fields, unchanged — wider than "by name"; reused as is by the owner's ruling, 2026-10-01), C3 |
| AC4 | Type/Category filtering where the model reuses cleanly | C1, C3 (`AssetsFilterRow`, the catalog's `typeChoices`) |
| AC5 | clearing restores the eligible list | C1, C3 (`clearQuery`, the toggles; row 10) |
| AC6 | selecting produces exactly today's downstream behaviour | C4 (the save path reads the same `AssetChoice(id, name)`), C8, C10 |
| AC7 | eligibility rules preserved | C2 (held always out, rm-5; R93-2, R93-3; R93-6), C4 (the read-time dead end unchanged) |
| AC8 | shared filtering logic and components; no divergent semantics | C1 (option A), C2 (parity, row 12), C3 |
| AC9 | empty results say nothing matches | C3, C7 (the tab's `EmptyList` sentences, no "Add asset"; R93-8) |
| AC10 | no schema/format/API/MCP/attachment/Reference/share-security change | Global constraints, C10, §7's untouched diff |

**Out of scope** (follow-up candidates): auto-skipping the picker when one asset is eligible (R93-11); any change
after the choice — the form's fields, Type, Role, Save, the three save arms, the confirmation, the refusals, the
saved line; the form step's own padding and insets (R93-1: "exactly as today"); the Assets tab itself beyond C2's
defaulted switch and C3's one visibility change (its screen, words, order and tests are untouched); a creation flow
in the share task (SPEC:80-81); multi-select; folding in the transfer selection (`A/ui/transfer/
TransferSelectionViewModel.kt:168-188`, a multi-select tree, audit §8); a version bump or release.

**Recorded limits (stated, not fixed):**
1. **The search box scrolls with the list** (C7: one `LazyColumn`, so a long prose share never pins the rows
   off-screen). On the tab it stays put. Once the box scrolls out of the list's composition, its focus and the
   keyboard go with it; the typed query survives in the model (N-9).
2. **The list is live, the dead end is decided once.** An asset added while the picker is open is offered; every
   eligible asset deleted or sent out while it is open leaves the picker saying the tab's "No assets yet" with no
   button (C7), not the NO_ASSETS dead end. Cancel and Back still leave.
3. **A target held after the tap** is refused at Save by the shipped guard with P77-35 (`C/transfer/
   HeldWriteGuard.kt:61`, caught at `A/share/ShareIntakeViewModel.kt:343`, `:407`, `:458`) — today's backstop.
4. **"Saved to <asset>" names the asset as it was at the tap**; a rename between tap and Save is not re-read — as
   today's snapshot.
5. **Retired and replaced assets sort after active ones** (R93-13): the one ordering change a person can see.
6. **Row indicators may arrive a moment after the rows** (the health read model's first emission is an empty map,
   `AssetViewModels.kt:393-405`), exactly as on the tab's first open.
7. **The keyboard on Android 8–10 (API 26–29)** (N-10). No activity sets `windowSoftInputMode` and the manifest is
   fenced; `minSdk` is 26, and there `WindowInsets.ime` arrives only under `adjustResize`. So on those versions the
   keyboard may still cover the picker's lower rows, with the system's own pan as on the form. Only a person on such
   a phone can check it (§7).

## 2. Contracts

### Common — the picker's model and composable (B1; C1–C3)

- **C1, the reuse decision (R93-14; option A).**
  - **Chosen: A — the intake hosts `AssetsViewModel` itself**, built with one new defaulted switch (C2). The rows,
    order, parent names, indicators, `archivedCount`, `typeChoices`, the C4 stale-type write-back and `emptyReason`
    are the tab's code, so AC8's "no divergent semantics" holds by construction; the tab's default is pinned by the
    list model's cases among the 144 shipped (`AssetViewModelsTest` 109, which also cover other view models — the
    list model is built at `:149`, `:1634`, `:1944`; `AssetTransferStateTest` 26; `AssetLoansStateTest` 9). Cost:
    about ten lines in `AssetViewModels.kt`; the tag, loan, season and health observers run inside the intake, none
    of which blocks (audit §2). Precedent: the intake already hosts a second, reused view model
    (`A/share/ShareIntakeActivity.kt:85-87`).
  - **Not taken: B — lift the `combine` into an `internal` pure `assetsStateOf(…)`.** Its gain (a flow-free test
    target) is small — the tab's tests already drive the model over fakes. Its costs: the body is not pure (C4's
    write-back into `filters`, `:430-436`, must be split out — a tab refactor), and the intake would wire categories,
    tags, health, loans and seasons a second time (`facts`, `:414-425`) — the divergence AC8 forbids.
  - **Not taken: C — rows from the intake's snapshot** with the predicates `internal`: a second sort and parent
    lookup, no indicators (audit §2) — divergent semantics.
  - **The fact that shapes C2 (audit §0.4):** the tab lists a held row when Archived is on (`admitsStatus`,
    `:512-513`) and counts it in `archivedCount` (`:461`) and the empty reasons (`:542`, `:552`). So held rows must
    leave **before** the controls, the count and the reason run; filtering `items` afterwards makes a held-only match
    say "Matching assets are archived. Turn on Archived to see them." and then show nothing.

- **C2, the merged eligibility rule — one switch.** `AssetsViewModel` gains a trailing constructor parameter
  `excludeHeld: Boolean = false`, carried by the `graph` constructor (`:348-352`) as a defaulted second parameter.
  - When true, inside the `combine` (`:428`), the asset list is reduced **once, first** — every row whose id is in
    `held` is dropped — and that reduced list feeds `matching`, `archivedCount` and `emptyReason`. `byId` (the parent
    lookup, `:438`, `:451`) keeps **every** row, so a listed component of a held parent still reads "Part of <parent>",
    as on the tab. Nothing else in the body reads the switch.
  - When false (the default; the tab, `AssetsScreen.kt:92`, unchanged): byte-for-byte today's behaviour.
  - The rule the picker then applies, in order: **held always excluded**, whatever Archived says (rm-5, AC7);
    ARCHIVED hidden until Archived is on (R93-2); components hidden until Components is on (R93-3); Type by
    `CategoryKey` from the catalog; the query searches only what the controls admit (R73-1). **Retired, replaced, lent,
    out-of-season and DOWN/DEGRADED assets are offered** like any ACTIVE asset (R93-6; no save path checks them,
    audit §0.5), drawn with the tab's badges.
  - The order is the tab's: active, then retired, then archived, by lower-cased name (`lifecycleRank`, `:564-568`;
    R93-13). `AssetFilters()` starts all off (`:256-260`), which is R93-2 and R93-3's default.

- **C3, the picker composable and the one visibility change.** A new file `A/ui/asset/AssetPicker.kt` holds one
  `internal`, stateless composable; it takes values and callbacks, never a view model, so it can be hosted by a later
  owner-picking flow (#69) without touching `A/share`:

  ```kotlin
  @Composable
  internal fun AssetPicker(
      state: AssetsState,
      query: String,                         // the model's own `query` flow (F3), never state.query
      onQueryChange: (String) -> Unit,
      onClearQuery: () -> Unit,
      onPickType: (String?) -> Unit,
      onToggleComponents: () -> Unit,
      onToggleArchived: () -> Unit,
      onPick: (AssetRow) -> Unit,
      modifier: Modifier = Modifier,
      contentPadding: PaddingValues = PaddingValues(0.dp),
      header: LazyListScope.() -> Unit = {},  // the host's leading items, inside the same list
  )
  ```

  - It draws **one `LazyColumn`**: `header()`, then `SearchBox` and `AssetsFilterRow` as items with the tab's
    paddings (`AssetsScreen.kt:119-133`), then either the rows
    — `AssetListRow` per `state.items`, keyed by `row.asset.id.value` with the tab's dividers (`AssetsScreen.kt:135-145`),
    `onClick = { onPick(row) }` — or, when `state.emptyReason != NONE`, one item drawing `EmptyList(reason,
    archivedCount, onNewAsset = null, onShowArchived = onToggleArchived)`.
  - **The only visibility change in `ui/asset`:** `EmptyList` (`AssetsScreen.kt:274-310`) goes from `private` to
    `internal`, and its `onNewAsset` becomes `(() -> Unit)?`; **null draws no "Add asset"** (the share task creates no
    asset, SPEC:80-81) and leaves every sentence and "Show archived" as they are (NO_ASSETS then draws its sentence
    over an empty button row). The tab's call (`:147-150`) passes
    its non-null lambda and is not edited. Every other `private` symbol stays private: the four `admits*`,
    `lifecycleRank`, `writtenTagsOf`, `TypeChip`, every word constant (`AssetsScreen.kt:435-468`,
    `AssetSearch.kt:84`).
  - Single-select is the host's: a tap reports the row and the picker holds no selection.

### B2 — the intake's two steps (C4–C9)

- **C4, the chosen asset is the tapped row, never a snapshot lookup (audit §0.1).**
  - `ShareIntakeState.chosen` becomes `AssetChoice?` (was `String?`, `ShareIntakeViewModel.kt:115`); `AssetChoice`
    (`:102`) is unchanged. `choose(assetId: String, name: String)` (was `choose(assetId)`, `:266`) stores
    `AssetChoice(assetId, name)` and clears `message`, as today.
  - `commit` (`:310-316`) reads `current.chosen` directly; **it never searches `assets`**. The three save arms
    receive the same `AssetChoice` they receive today and read only `choice.id` and `choice.name` (`:334`, `:394`,
    `:438`, `:468-469`) — AC6. A null `chosen` cannot pass `saveEnabled` (`:154-161`); `commit` keeps today's
    defensive null branch as it is — `saving = false`, `deadEnd = IntakeStrings.NO_ASSETS` (`:313-315`) — never a
    bare `return`, which would leave the screen saving; it is unreachable (`saveEnabled`; `changeAsset` is a no-op
    while saving or confirming) (N-7).
  - `assets` stays, unchanged in shape and computation (`:240-249`): the read-time eligible set that decides the
    NO_ASSETS dead end (`:524`) and nothing else. It is no longer drawn. Its KDoc says so.
- **C5, which step a state draws — a derived getter, JVM-proven.** `ShareIntakeState` gains, in the `noFolder`
  getter's style (`:144`): `val picking: Boolean get() = !loading && deadEnd == null && saved == null && chosen ==
  null && path != IntakePath.TRANSFER_PACK`. **The step is decided above the scroll column (C-1):**
  `ShareIntakeScreen`'s body becomes `if (state.picking) <the picker step, C7, a private composable of its own> else
  <today's Column, unchanged: title + when { loading / saved / deadEnd / form }>` (`ShareIntakeScreen.kt:54-84`),
  with the unknown-scheme dialog (`:87-102`) after both. `picking` already implies `!loading`, `deadEnd == null` and
  `saved == null`, so the `when` loses no arm; its `else` is the form (C8). **The title is drawn once:** by the
  picker's `header` on the picker step, by the column (`:62`) otherwise. A `LazyColumn` measured inside the
  `verticalScroll` column throws on first composition (an infinite max height), and no brief runs a device, so B2
  checks the structure by reading it (§10) and the merged gate's Compose run is the picker step's **first device
  composition** (§7).
- **C6, Back, Change and Cancel.**
  - **On the picker:** no handler — system Back finishes and writes nothing, as today (SPEC:92). The step draws a
    dismiss button with the form's rule: "Close" on a byte share with no folder, else "Cancel"
    (`ShareIntakeScreen.kt:195-200`), calling `onCancel`. **No Save is drawn on the picker.**
  - **Change:** `changeAsset()` sets `chosen = null` and `message = null` and keeps `name`, `description`, `kind`
    and `role` (each comes from the share, not the asset). It does **not** restore "That is not a link." (today a
    choice clears it for good). It is a no-op unless `backChangesAsset`.
  - **On the form (R93-4, DECIDED):** Back returns to the picker, the selection cleared and the typed fields kept.
    `val backChangesAsset: Boolean get() = !loading && chosen != null && !saving && saved == null && deadEnd == null
    && confirming == null`; the activity adds `BackHandler(enabled = state.backChangesAsset) { model.changeAsset() }`.
    **The handler is off while saving** (and while confirming, where the dialog takes Back): not the precedents'
    hold (`ReplaceAssetScreen.kt:94`, `TransferFlowScreen.kt:99`, the intake's MJ-1 at `ShareIntakeActivity.kt:91`),
    so Back mid-save finishes the activity exactly as today (audit §4; N-8).
  - Cancel on the form, every refusal and every Back still write nothing (I-8).
- **C7, the picker step's layout.**
  - The step is a `Column` filling the window, padded by `WindowInsets.safeDrawing` (system bars, cutout and IME —
    the form's fixed 24dp, `ShareIntakeScreen.kt:56-60`, does not clear edge-to-edge bars or the keyboard,
    `ShareIntakeActivity.kt:41`): `AssetPicker(… , modifier = Modifier.weight(1f), header = …)`, then the dismiss
    row (C6). A `LazyColumn` cannot nest in the form's `verticalScroll` column (`:54-60`), so the picker step is a
    sibling of that column, never inside it (C5, C-1). `WindowInsets` is new under `app/src/main` (0 uses at
    `78c23a88`); on API 26–29 its IME inset may not arrive (limit 7, N-10).
  - `header` contributes, as list items: the title; **the Received block** — RECEIVED and the received line, the
    no-folder line, and `message` (lifted from `IntakeForm`, `:116-122`, into one private composable both steps call);
    ATTACH TO; "Choose asset" (G4). The rows follow the search box and the controls.
  - Empty states (R93-8, DECIDED): the tab's `EmptyList` sentences, never "Add asset" (C3). NO_ACTIVE_ASSETS draws
    "No active assets · <n> archived" and "Show archived", which toggles Archived.
  - One eligible asset still shows the picker (R93-11). After a Change the query and the controls are as the person
    left them — the view model outlives the step (R93-12, C9).
  - The host maps `onPick = { row -> onChoose(row.asset.id.value, row.asset.name) }`.
- **C8, the form step — today's form, with one line changed.** `IntakeForm` (`:105-208`) keeps RECEIVED, Name,
  Description, Type (bytes), Role (`roleOffered`, R91-4 unchanged), Cancel/Close and Save / "Save as a note", in
  today's order and padding. **Only ATTACH TO's body changes:** the "Choose asset" prompt and the chip cloud
  (`:125-141`) are replaced by one compact row — the chosen asset's name alone (`state.chosen.name`, a
  `QuietLine`-weight text, G3) and a trailing `TextButton` reading **"Change"** (`IntakeStrings.CHANGE`, G1, R93-5)
  calling `onChangeAsset`. Type, on a byte share, is drawn here exactly as today — after a choice (R93-9, row 27).
- **C9, hosting.** In `ShareIntakeActivity`'s non-pack arm (`:100-113`) only: `val picker = viewModel(key =
  "share-asset-picker") { AssetsViewModel(graph, excludeHeld = true) }`, its `state` and its own `query` collected
  with lifecycle (the tab's F3 shape, `AssetsScreen.kt:92-97`), and the tab's resume refresh (`:100-103`) so the
  row's indicators are the tab's (R93-7). `ShareIntakeScreen` stays a **pure function**: it gains `picker:
  AssetsState`, `pickerQuery: String`, the five control callbacks, `onChoose: (String, String) -> Unit` (was
  `(String) -> Unit`) and `onChangeAsset: () -> Unit`, so every state stays one `setContent` away
  (`ShareIntakeScreen.kt:32-35`). The `"share-intake"` model (`:63-76`) is built exactly as today.
  - **When the picker model is built (N-15):** on the non-pack arm's first composition — during `loading`, whose
    initial path is `LINK` (`ShareIntakeViewModel.kt:111`) — so it is also built for a share that becomes a dead end
    or a pack. Harmless: `WhileSubscribed`, its read path writes nothing (C10), and each share is a fresh `standard`
    activity, so no query or control leaks between shares.
  - **Three KDocs become false and B2 amends them (N-6):** `ShareIntakeScreen.kt:32-40` ("One scrolling column…",
    "**Every word comes from [IntakeStrings]**") and `IntakeStrings`' KDoc (`ShareIntakeViewModel.kt:53-57`): the
    picker step's words come from `ui/asset`'s reused composables, ratified with #73/#71; the intake's own words
    still come from `IntakeStrings` alone. `assets`' KDoc (C4) is the fourth.

### Cross-cutting (C10–C11)

- **C10, what never moves (AC6, AC10; audit §7).** The picker reads asset-side repositories only. It never touches
  the share read (`ShareIntakeViewModel.kt:223-250`), `readShare` and its `streamPolicy` / `linkPolicy`
  (`ShareIntakeActivity.kt:47-54`), the lazily opened byte source, I-3's one-arm-per-path rule, the scheme
  confirmation, the exported entry (`app/src/main/AndroidManifest.xml:111-114`), `linkTakesRole` / `roleOffered`, or
  any save arm. The grant lives for the task (SPEC:92-94), so time on the picker changes nothing. Nothing logs or
  sends the query. I-8 holds with a second, hosted model: `AssetsViewModel`'s read path writes nothing
  (`T/…/ReadPathsWriteNothingTest.kt:506`, re-run; N-11).
- **C11, the documents and the boundary proof (B3).**
  - SPEC amended where it describes the chooser: SPEC:40-47, :64-70, :441-446 (§7's one column becomes two steps,
    naming "Choose asset"). **SPEC:492** (§8's Intake row) **gains** a clause — the spec never mentions the held
    exclusion today (N-4): transferred-out assets are never offered, and the picker's model drops them first.
    **"Change" lands as an "Amendment (#93, 2026-10-01)" line beside the #67 amendment at SPEC:566**, in its shape;
    §10's "All 50 RATIFIED" header (:536), its intake line (:541-542) and the No assets line (:557) stay as they are
    (N-3). SPEC:80-81 (no asset-creation flow inside the share task) is kept and cited by C3 (N-2).
  - `ShareBoundaryTest` case 2 (`AT/share/ShareBoundaryTest.kt:99-109`) per R93-9 (DECIDED): the `TYPE`
    assertion (`:107`) — which the picker step cannot satisfy, its control reading "Type" — is replaced by one
    asserting "Choose asset" is displayed (the intake reached its first step, not a dead end); the title, RECEIVED and
    the provider's own file name (`:103-106`) stay; the wait (`:140-145`) holds because the picker draws RECEIVED.
    Its KDoc (`:94-98`) stops saying "byte form", and the case is **renamed**
    `anExternalStreamWithAGenuineGrantReachesTheIntake` (`:99`), which keeps `NAME_ASCENDING` with the bad-grant case
    first (`:42-44`). `seedOneAsset`'s KDoc (`:56`, "draws its form") is re-worded to the intake and the companion's
    `TYPE` const (`:156`), dead once `:107` moves, is removed (N-5). The case still chooses nothing and stays thin.
  - **Nothing platform-only is weakened (N-14):** case 2's `hasTextExactly(FIXTURE_FILE)` on the Received line
    (`:106`) already implies the BYTES path — only `ShareContent.Bytes` sets `received` to the provider's name
    (`ShareIntakeViewModel.kt:499-502`) — so the boundary still proves the foreign stream was classified as bytes;
    row 27 carries the `TYPE` rendering in process.
  - **The `TYPE` coverage is relocated, not dropped (R93-9's qualification):** B2 grows the shipped byte-form case
    `theTypeControlIsDrawnOnBytesAndNeverOnALink` (`AT/share/ShareIntakeScreenTest.kt:165-172`) to start on the
    picker step of a byte share (no "TYPE"), choose, and then assert "TYPE" and the seven labels on the form (row 27).
  - The owner-ruled wording moves with it (G5): `docs/release-proofs.md:42` and `planning-policy.md:76-77`.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED (the mutation that must fail it). "Shared" = the mutation lives in
code the tab's shipped cases already fail on (recorded, not counted twice). "Pin" = a shipped assertion re-run or
moved, with no RED. Compose rows are edited and compiled in their brief and run first at the merged gate — which is
also the picker step's **first device composition** (C-1): a layout fault (a lazy list measured inside a scroll
column) can surface only there, so B2's structural check (§10) stands in for it until then.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C2 defaults: root ACTIVE rows only | `T/ui/asset/AssetPickerModelTest` (new) · `byDefaultOnlyActiveRootAssetsAreOffered` | shared |
| 2 | C2 the query narrows across the six fields | `aQueryNarrowsByNameAndByEveryOtherSearchedField` | shared |
| 3 | C2 Type by catalog key | `typeOffersOnlyThatCategory` | shared |
| 4 | C2 Archived hidden, then shown (R93-2) | `archivedAssetsAppearOnlyWithArchivedOn` | shared |
| 5 | C2 Components hidden, then shown (R93-3) | `componentsAppearOnlyWithComponentsOn` (still naming their parent) | shared |
| 6 | C2 held never offered (rm-5) | `aHeldAssetIsNeverOfferedArchivedOnOrOff` (held-ACTIVE and held-ARCHIVED, both toggles) | the switch ignored (the tab's rule runs) |
| 7 | C2 held leaves before the controls | `aHeldOnlyMatchSaysNothingMatchesAndIsNotCountedArchived` (`NOTHING_MATCHES`, not `ARCHIVED_HIDDEN`; `archivedCount` excludes held) | the switch filters `items` after `admits` instead of the rows before them |
| 8 | C2 an all-archived eligible set | `everyEligibleAssetArchivedSaysNoActiveAssets` (with a held one beside them: count excludes it) | shared |
| 9 | C2 empty matches carry their reason | `eachEmptyMatchCarriesTheTabsReason` (TYPE_HIDDEN, COMPONENTS_HIDDEN, BOTH_HIDDEN) | shared |
| 10 | AC5 clearing restores | `clearingTheQueryRestoresTheEligibleList` | shared |
| 11 | R93-6 retired, replaced, lent listed by default | `retiredReplacedAndLentAssetsAreOfferedWithTheirBadgesAfterActiveOnes` | shared |
| 12 | AC8 parity | `withNothingHeldThePickerStateEqualsTheTabs` (a fixture matrix × each control × a query) | the switch also drops ARCHIVED rows (the tempting "eligible = active" reading) |
| 13 | C2 parent names over every row | `aComponentOfAHeldParentStillNamesItsParent` | `byId` built from the reduced rows (→ no "Part of") |
| 14 | C2 the tab's default unchanged | `T/ui/asset/AssetTransferStateTest` · `heldButActiveIsHiddenWithoutTheArchivedControl` (`:181-200`), and the 143 other tab cases | the default flipped to `true` (counted: a shipped case fails) |
| 15 | C4 the save reads the tapped `(id, name)` | `T/share/ShareIntakeViewModelTest` · `theChosenRowsIdAndNameReachTheSave` (the asset renamed after the read; the written row's asset id, and "Saved to <the tapped row's name>") | `choose` keeps the id and takes the name from `assets` (→ the read-time name) |
| 16 | C4 a row the snapshot lacks (audit §0.1) | `anAssetAddedAfterTheReadIsSavedToNotRefused` | `commit` looks the id up in `assets` (→ the NO_ASSETS dead end) |
| 17 | C5 the step a state draws | `pickingIsTrueOnlyWithNoChoiceOnALoadedLiveIntake` (loading, dead end, saved, pack, chosen → false) | `picking` ignores `deadEnd` |
| 18 | C6 Change keeps the share's fields | `changeAssetClearsTheChoiceAndMessageAndKeepsNameDescriptionTypeAndRole` | `changeAsset` resets the fields to the loaded state's |
| 19 | C6 Back's gate (R93-4) | `backChangesAssetIsFalseWhileSavingAfterSavedOnADeadEndAndWhileConfirming`; `changeAssetIsANoOpThen` | the `!saving` term dropped |
| 20 | R93-10 the not-a-link sentence | `theNotALinkSentenceIsThereBeforeAChoiceAndGoneAfterAChange` (note path) | none counted: today's `choose` clearing (`:266`), re-pinned |
| 21 | I-8 nothing written | `cancelAtEveryStepWritesNothing` (`:577-600`) grows a choose → Change → cancel step; the hosted model's read path is `ReadPathsWriteNothingTest.kt:506`, re-run (N-11) | none: pin |
| 22 | C7/C8 the steps render | `AT/share/ShareIntakeScreenTest` · `theScreenDrawsTheRatifiedLabelsAndNothingElse` (`:89-103`) grows: picker labels (title, RECEIVED, ATTACH TO, "Choose asset", "Search assets", "Type", "Components", "Archived", the row; no Save, no Name) → flip to chosen → form labels, the chosen name and "Change", no "Choose asset"; a tap on "Change" reaches `onChangeAsset`. `show()` (`:65-86`) holds a mutable `AssetsState` beside the intake state; the title is drawn once on each step (C5) | not run in a brief (rows 17, 18) |
| 23 | C3/C7 pick, search, empty | `noAssetChosenDisablesSave` (`:223-227`) re-aimed and renamed `noAssetChosenOffersThePickerAndNoSave`: no Save node; a row tap reaches `onChoose` with id and name; a keystroke reaches the query callback; flip to NO_ACTIVE_ASSETS → the sentence and "Show archived", no "Add asset"; flip to NOTHING_MATCHES → "Nothing matches that." | not run in a brief (rows 7–9) |
| 24 | R91-4 unchanged | `theRoleControlIsDrawnOnBytesAndOnAWebLinkAndNeverOnANoteLink` (`:181-202`) — reaches the form because `form()` is pre-chosen | none: pin |
| 25 | R93-9 the boundary | `AT/share/ShareBoundaryTest` case 2 (`:99-109`) per C11: "Choose asset" in place of `TYPE`; still chooses nothing | none: device, merged gate |
| 26 | §5 strings | `everyIntakeSentenceIsOneSpecTenRatifies` (`T/…:781-813`) gains "Change" in both sets | none: pin |
| 27 | R93-9's qualification: the byte form's `TYPE`, after a choice | `AT/share/ShareIntakeScreenTest` · `theTypeControlIsDrawnOnBytesAndNeverOnALink` (`:165-172`) grows: a byte share with `chosen = null` draws the picker and no "TYPE" → **flip to `storeReady = false` (C-2): the no-folder sentence and "Close" displayed on the picker step, no "Cancel", no Save** (D-20 on the picker; no new string) → flip back and to chosen → "TYPE" and the seven kind labels displayed (the assertion relocated from `ShareBoundaryTest:107`) | not run in a brief (row 17) |

**Moving pins** (each moves, because …):

| site | brief | moves because |
|---|---|---|
| the 28 `choose(id)` sites in `T/share/ShareIntakeViewModelTest.kt` (`:177` … `:942`) | B2 | `choose` takes `(id, name)` (C4); each passes its fixture's name; no assertion changes |
| `ShareIntakeViewModelTest.kt:494-503`, `:928-936` (`state.assets`) | B2 | **re-read, not edited**: `assets` keeps its shape and still excludes held (C4); "never offered" is now also carried by row 6 |
| `ShareIntakeViewModelTest.kt:781-813` | B2 | "Change" joins `IntakeStrings` and SPEC §10 (row 26) |
| `AT/share/ShareIntakeScreenTest.kt:44-63` (`form()`), `:65-86` (`show`, its `onChoose` at `:73`) | B2 | `chosen: AssetChoice? = mower`; the screen takes the picker's values and two-argument `onChoose` (C9); `show` holds a mutable `AssetsState` too |
| `ShareIntakeScreenTest.kt:89-103`, `:223-227` | B2 | "Choose asset" and the rows move to the picker step (rows 22, 23) |
| `ShareIntakeScreenTest.kt:165-172` | B2 | **grows** to receive the byte form's `TYPE` after a choice, relocated from the boundary case (R93-9, row 27) |
| `ShareIntakeScreenTest.kt:117`, `:271` | B2 | **re-read, not edited**: a dead end and the saved line still draw no "Choose asset" |
| `AT/share/ShareBoundaryTest.kt:56`, `:94-99`, `:107`, `:156` | B3 | the picker step draws "Type", never "TYPE"; `:107` becomes "Choose asset" and its `TYPE` coverage lives in row 27; `:99` renamed `…ReachesTheIntake`; `:56`'s KDoc re-worded; the dead `TYPE` const removed (R93-9, C11, N-5) |

`AssetsFiltersTest`, `AssetsIndicatorsTest`, `AssetViewModelsTest`, `AssetLoansStateTest`, `SharedItemLiftTest`,
`ShareResolutionContractTest`, `SharedItemReaderTest` (its structural scans of `share/` included) and
`ReadPathsWriteNothingTest` are untouched and re-run.

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1 | `A/ui/asset/{AssetViewModels,AssetsScreen,AssetPicker (new)}.kt` (C2's switch; C3's `EmptyList` line and signature; the composable); `T/ui/asset/AssetPickerModelTest.kt` (new) | `A/share/**`, every other `A/ui/**` file, `C/**`, `tools`, `docs`, `app/schemas`, the manifest, every shipped test |
| B2 | `A/share/{ShareIntakeViewModel,ShareIntakeScreen,ShareIntakeActivity}.kt`; `T/share/ShareIntakeViewModelTest.kt`; `AT/share/ShareIntakeScreenTest.kt` | `A/share/SharedItem.kt`, `A/ui/**` (B1's files included), `C/**`, `tools`, `docs`, `AT/share/ShareBoundaryTest.kt` |
| B3 | `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`; `docs/release-proofs.md` (`:42` and its neighbours only); `docs/superpowers/planning-policy.md` (`:76-77` only); `AT/share/ShareBoundaryTest.kt` (`:56`, `:94-109`, `:156` only) | any `src/main` file, `tools`, `docs/api` |

**Order:** B1 → B2 → B3. B2 consumes C2's switch and C3's composable; B3 describes what B2 built, and B3's
boundary edit relies on row 27 having taken the `TYPE` coverage. **The owner's gate is passed** (R93-4…9 DECIDED
2026-10-01; "Change" and G5's text ratified); B1 goes on the controller's dispatch. **Why B3 is not folded into
B2:** B2 is about 55 minutes on its own (§10); folded, it crosses the 1-hour target. No two-lane split: B2
depends on B1, and B3 on B2.

**B2's stop-and-split clause (the review's concern c).** If B2 reaches the 1-hour target with the step itself not
green, it stops and reports (the common rule). If the step is green — C4, C5, C7, C8's chosen line, C9; rows 15–17,
20, 23, 26, 27 and row 22's labels — but **Change and Back** are not, B2 commits what is green and the controller
dispatches **B2′** on that tip for C6's `changeAsset()`, `backChangesAsset`, the `BackHandler`, the "Change" action's
callback and rows 18, 19, 21 and row 22's Change tap (about 25 min). The step is never split; B3 waits for B2′.

**Gate budget** at the merged tip (estimates; B1 records the base's exact counts): core unchanged; app JVM + ~20
(B1 13, B2 7); MCP and loader unchanged; device classes **55, unchanged**, **no new case**, three
`ShareIntakeScreenTest` cases grown (rows 22, 23, 27) and one `ShareBoundaryTest` assertion replaced, its case
renamed (row 25). The whole gate timed against #91's merged-tip record, reporting only, no rerun-until-green.

## 5. Strings

**Reused verbatim** (their reuse on the picker approved by R93-5 / R93-8, owner, 2026-10-01):

| string | resource (one home) | where #93 draws it |
|---|---|---|
| "Save to ServiceTag", "Received", "Attach to" | `IntakeStrings.TITLE`, `RECEIVED`, `ATTACH_TO` (`A/share/ShareIntakeViewModel.kt:59-61`) | both steps |
| "Choose asset" (G4: stays the prompt — the shipped string and the ruling's own words) | `IntakeStrings.CHOOSE_ASSET` (`:62`) | the picker step only |
| "Cancel", "Close" | `:70`, `:71` | the picker's dismiss row (C6), the form unchanged |
| "That is not a link.", the no-folder sentence | `:73`, `:75` | the Received block, both steps (R93-10) |
| "Add an asset in ServiceTag first, then share this again." | `:74` | the read-time dead end, unchanged |
| "Saved to <asset>" | `savedTo`, `:91` | unchanged; the tapped row's name |
| "Search assets" (P73-11), "Clear search" | `AssetSearch.kt:84`, `:67` | the picker's `SearchBox` |
| "Type" (P73-1), "All" (P73-2), "Components" (P73-3), "Archived" (P73-4); the catalog's display names | `AssetsScreen.kt:444`, `:447`, `:450`, `:453`; `typeChoices` | the picker's `AssetsFilterRow` |
| "No active assets · <n> archived", "Nothing matches that.", "Show archived"; P73-5, P73-6, P73-7, P73-9, P73-10 | `AssetsScreen.kt:284`, `:286`, `:305`; `:456`, `:459`, `:462`, `:465`, `:468` | the picker's empty states (R93-8) |
| "No assets yet" | `AssetsScreen.kt:283` | only live (limit 2), with no button |
| the row's words: name, category, "Part of <parent>", "Retired", "Out of season", "Archived", the loan, condition and health words, "NFC tag written" (P71-4) | `AssetListRow` (`AssetsScreen.kt:326-401`); `RETIRED` `:438`, `OUT_OF_SEASON` `:439`, `NFC_TAG_WRITTEN` `:435` | picker rows (R93-7) |

**Never drawn by #93:** "Add asset" (`AssetsScreen.kt:302`; SPEC:80-81); "Transferred" (P77-31; held rows are
excluded, C2); the chip cloud.

**Gaps (audit §5) — all closed (owner, 2026-10-01):**
- **G1 — the form's Change action label: RATIFIED "Change"** (R93-5), the owner's word. Rev 1 proposed "Change
  asset" by analogy with the shipped "Change condition" (`A/ui/condition/ChangeConditionSheet.kt:45`); "Change"
  stands, the ATTACH TO header and the name beside it giving the context. New member
  `IntakeStrings.CHANGE = "Change"`; joins SPEC §10 as an "Amendment (#93, 2026-10-01)" line beside SPEC:566
  (C11, N-3), and row 26.
- **G2, G3, G4 — DECIDED:** no accessibility name is added (a `TextButton`'s text is its name; no icon); the chosen
  line is the asset's name alone (R93-5); "Choose asset" stays the prompt and no picker title is added — the title
  stays "Save to ServiceTag" on both steps (controller default, owner did not object).
- **No empty-match gap.** The tab's "Nothing matches that." (`AssetsScreen.kt:286`) is AC9's sentence as it stands,
  and P73-5…P73-10 name the control in the way; the plan invents no share-specific sentence.
- **G5 — document text, not UI: RATIFIED (R93-9)**, written verbatim by B3: `release-proofs.md:42` "2. an
  external `content://` stream with a genuine temporary read grant reaches the intake, which draws the provider's
  own name for it;" and
  `planning-policy.md:76-77` "…a genuine temporary read grant reaches the intake." `:48` ("They choose no asset…")
  is unchanged and still true.

## 6. Owner rulings (owner, 2026-10-01 — R93-1…14 DECIDED, recorded on issue #93)

**Decided with the issue (binding):** R93-1 a dedicated first step, then the existing form; R93-2 archived behind
Archived, off by default; R93-3 components behind Components, off by default; transferred-out excluded as today;
reuse `SearchBox`, `Asset.matches` (**the six-field search, reused as is — the owner's ruling**), `AssetFilters`,
`AssetsFilterRow`, `AssetListRow`; single-select. The HARD SCOPE (§1) is approved as described.

**The owner's — DECIDED 2026-10-01, binding as written:**

| ruling | question | decision | lands in |
|---|---|---|---|
| **R93-4** (Q1) | Back on the form | **DECIDED as recommended:** returns to the picker, the selection cleared and Name, Description, Type, Role kept. **What ships while saving:** the new form `BackHandler` is **off** (`enabled = backChangesAsset`, false while saving or confirming), so Back mid-save finishes the activity exactly as today — not a hold that swallows Back (N-8). Back on the picker cancels as today | C6; rows 18, 19 |
| **R93-5** (Q2; G1–G3) | the chosen line and its Change action | **DECIDED:** the name alone under ATTACH TO with a trailing text action **"Change"** (the owner's ratified word, not "Change asset") | C8; §5; rows 22, 26 |
| **R93-6** (Q3) | retired and #86-replaced assets | **DECIDED as recommended:** offered by default with the existing Retired badge and the tab's order; only Archived needs the toggle | C2; row 11 |
| **R93-8** (Q5) | the picker's empty state when every eligible row is archived | **DECIDED as recommended:** the tab's "No active assets · <n> archived" and "Show archived", no "Add asset" | C3, C7; rows 8, 23 |
| **R93-9** (Q6; G5) | `ShareBoundaryTest` case 2 and its owner-ruled wording | **DECIDED with one qualification:** "reaches the intake" approved — case 2 chooses nothing and asserts "Choose asset" in place of `TYPE`; R4 and policy layer 4 re-worded (§5 G5). **The byte form's `TYPE` is relocated, not dropped:** it is asserted in `ShareIntakeScreenTest`'s byte-form case after an asset is chosen; the boundary case stays thin | C11; rows 25, 27 |

**Controller defaults — the owner did not object** (each follows from R93-1…3 and the reuse ruling):

| ruling | default | why it follows | lands in |
|---|---|---|---|
| **R93-7** (Q4; controller default, owner did not object) | picker rows are the tab's `AssetListRow`, indicators and all | the binding scope names `AssetListRow`; a lighter row would be a second presentation | C1, C9 |
| **R93-10** (Q7; controller default, owner did not object) | "That is not a link." shows in the Received block on the picker and clears on choosing, as today | today it is visible until a choice (`ShareIntakeViewModel.kt:266`, `:510`); on the form alone it would never be seen | C7; row 20 |
| **R93-11** (Q8; controller default, owner did not object) | one eligible asset still shows the picker; no auto-skip | R93-1 makes the picker the first step; today nothing is preselected either, so the tap count is unchanged | C7 |
| **R93-12** (Q9; controller default, owner did not object) | the query and controls survive a Change | the view model outlives the step (C9), as the tab's session state (R73-2) | C7, C9 |
| **R93-13** (Q10; controller default, owner did not object) | the tab's order (active, retired, archived; by name) | the read model is the tab's; a second order is divergent semantics | C2; limit 5 |
| **R93-14** (plan; controller default, owner did not object) | option A — host `AssetsViewModel` with `excludeHeld` | C1's costing | C1–C3 |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin` (B1 changes a composable's signature; B2 and B3 edit `AT/`). No device run in
  any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **55 classes,
  unchanged, no new case**, zero skips; rows 22, 23, 25 and 27 run here first, and the picker
  step is composed on a device for the first time (C-1) — a red there is one post-merge fix round, the #91 N-10
  shape), R3 (the three Python suites, unchanged), R5 (`ManifestContractTest`,
  `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps. Timed; the 14- and 15-minute lines are reporting
  only.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'excludeHeld: Boolean = false'` in `A/ui/asset/AssetViewModels.kt` → **2** (the primary constructor and the
    `graph` constructor, both defaulted; N-1) and `'excludeHeld'` there → ≥ 4 (the two parameters, the delegation,
    the one use); `'AssetsViewModel\(graph\)'` in
    `A/ui/asset/AssetsScreen.kt` → 1 (the tab, unchanged); `'AssetsViewModel\(graph, excludeHeld = true\)'` in
    `A/share/ShareIntakeActivity.kt` → 1; `'excludeHeld'` anywhere else under `app/src/main` → 0.
  - `'^internal fun AssetPicker\('` in `A/ui/asset/AssetPicker.kt` → 1; `'^internal fun EmptyList\('` in
    `AssetsScreen.kt` → 1; `'onNewAsset = null'` in `AssetPicker.kt` → 1.
  - **Nothing else changes visibility:** `'^private (fun|val|const val|data class|class|object) '` → **13** in
    `AssetsScreen.kt` (14 at `78c23a88`, less `EmptyList`), **22** in `AssetViewModels.kt`, **2** in
    `AssetSearch.kt` (both unchanged — so B1 adds no `private` top-level helper there; the reduction is inline, N-1).
  - `'fun choose\(assetId: String, name: String\)'` in `ShareIntakeViewModel.kt` → 1; `'fun changeAsset\(\)'` → 1;
    `'assets\.firstOrNull'` → 0; `'val picking: Boolean'` → 1; `'val backChangesAsset: Boolean'` → 1 (R93-4).
  - `'BackHandler\(enabled = state\.backChangesAsset\)'` in `ShareIntakeActivity.kt` → 1 (R93-4);
    `'"share-asset-picker"'` → 1.
  - **C-1's structure:** `'AssetPicker\('` in `ShareIntakeScreen.kt` → 1, inside the private picker-step composable;
    that composable contains no `verticalScroll`, and `ShareIntakeScreen`'s body is the `if (state.picking)` (read
    it — a lazy list inside the scroll column fails only on a device).
  - `'state\.assets'` in `A/share/ShareIntakeScreen.kt` → 0 (the chip cloud is gone); `'verticalScroll'` there → 2,
    as at `78c23a88` (the import and the form's one use); `'sortedWith|sortedBy|\.matches\(|admits'` over `A/share`
    → 1, as at `78c23a88` (the snapshot's `sortedBy`, `ShareIntakeViewModel.kt:247`, untouched) — no new filter or
    sort in the intake.
  - `'"Add asset"'` and `'"Transferred"'` over `A/share` → 0; `'const val CHANGE = "Change"$'` in
    `ShareIntakeViewModel.kt` → 1 (R93-5).
  - Untouched: `git diff <base> --stat -- core tools docs/api app/schemas app/src/main/AndroidManifest.xml
    app/src/main/kotlin/com/loosecannon/servicetag/share/SharedItem.kt` → empty; the tombstone check → 0; gitlink
    `7e0377a`; `versionName` / `versionCode` unchanged.
- **What the emulator proves:** the three grown `ShareIntakeScreenTest` cases (the picker's labels, a row tap
  carrying id and name, a keystroke, the empty sentences without "Add asset", the form's chosen line and "Change",
  and the byte form's `TYPE` after a choice — R93-9's relocation) and the 10 unchanged ones; `ShareBoundaryTest` case
  2 reaching the picker from another UID, choosing nothing; row 27's no-folder flip (D-20 on the picker, C-2).
  **What only a person sees:** the picker over a real sharing app with a large catalog — the keyboard and bars
  clearing the rows (C7's insets; on Android 8–10 the keyboard inset may not arrive, limit 7), the
  search box scrolling away (limit 1), the feel of narrowing as one types; system Back on each step. The
  development and production phones are untouched; no install.

## 8. Relationships

- **#43 — share intake:** I-8, D-20 (now on the picker too, row 27), rm-5 and I-3 hold; the spec's chooser sentences
  are amended (C11); SPEC:80-81's "no creation flow" is why `EmptyList` grows a null arm (C3).
- **#39 / #73 — search and filters:** `Asset.matches` (six fields, the owner's ruling), R73-1, R73-2 (R93-12), R73-7
  and F3 reused unchanged. **#71 / #72:** row indicators and loans drawn unchanged (R93-7; R71-2's disc).
- **#77 — transfers:** held excluded in the model (C2), so P77-31 is never drawn; `HeldWriteGuard` stays the
  backstop (limit 3). **#86 — replace:** a predecessor is retired, not archived (`C/usecase/ReplaceAsset.kt:342`),
  so it is offered under R93-6. **#91:** R91-4's chips still reach the form (row 24); R91-13 is the device precedent.
- **#69 — generalised owners:** `AssetPicker` and `excludeHeld` live in `ui/asset` for a later owner-picking flow
  (audit §8); the transfer selection is not folded in.
- **#62 — the testing hierarchy:** rows 22, 23, 27 are tier-2 Compose semantics; R4 stays three thin
  `ShareBoundaryTest` cases. **#76** places #93; **#90** untouched (no harness change; three grown cases' time).

## Briefs — common to all three

Read §1–§8, the audit, issue #93 with the rulings, and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`); the
untouched diff (`git diff <base> --stat` against the brief's "never touches"); the tombstone check → 0;
`git ls-tree HEAD libs/nfc-tag-core` → `7e0377a…`; each → 1: `'^\s*versionName = "1\.5\.0"$'`,
`'^\s*versionCode = 18$'` in `app/build.gradle.kts`; `git diff <base> -- app/build.gradle.kts core tools
app/schemas app/src/main/AndroidManifest.xml` → empty; `git status` clean.

**Pin rule.** A shipped assertion moves only where §3's moving-pins table names it for this brief. Confirm the set
first with `git grep -nE 'choose\(|chosen|Choose asset|onChoose|TYPE|AssetsViewModel\(|EmptyList\(' --
'app/src/test/**/share/**' 'app/src/test/**/ui/asset/**' 'app/src/androidTest/**/share/**'
'app/src/androidTest/**/ui/asset/**'` (scoped, N-13), and report any hit the table does not name before editing it.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each
grep with its count; the test counts before and after; the wall time against the box; what is done, proven and not
proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin list; a
JVM or build failure the brief did not cause (reported, not retried); a phone string not in §5 or not yet ratified;
any change under `core/`, `tools/`, `app/schemas/` or the manifest; an owner ruling the brief finds undecided; the
cap reached, the 1-hour target passed with under half the rows green, or the 2-hour hard stop.

**Must NOT, always:** step outside the **HARD SCOPE**: "#93 = replace the intake's chip cloud with a dedicated,
single-select Choose-asset step that reuses the Assets tab's search box, filters, read model and rows, then the
existing form exactly as today. Nothing after the choice changes. No schema, backup-format, API, MCP, attachment,
Reference, document-role, URL/file classification or Share URI/security change; no asset creation inside the share
task; no second search or filter system; no new device class." Also never: copy a predicate, comparator, sort or
sentence out of `ui/asset`; make any `ui/asset` symbol other than `EmptyList` less private; change the tab's
behaviour, words or call sites; touch the share read, the lift, its policies, a save arm, `roleOffered` or the
confirmation; delete a shipped assertion; add a device class or case, or run a device; bump a version; log the
query or a URI; add a dependency; write a ledger; write a real name, host, serial or e-mail address.

## 9. B1 — the picker's model switch and composable (C1–C3; app JVM)

**Read:** audit §0.2–§0.5, §2, §3, §8; `A/ui/asset/AssetViewModels.kt:256-303`, `:305-352`, `:414-568`;
`A/ui/asset/AssetsScreen.kt:84-170`, `:172-207`, `:268-310`, `:326-401`; `A/ui/asset/AssetSearch.kt:27-84`;
`T/ui/asset/AssetTransferStateTest.kt` (its `listModel()` at `:116` and the held fixture, `:181-200`).
**`<base>`** = master at dispatch plus this plan's commit. **Rows:** 1–14. **Rulings:** R93-2, R93-3, R93-6, R93-8,
R93-13, R93-14 (all DECIDED 2026-10-01).
**Interfaces produced:** `AssetsViewModel(…, excludeHeld: Boolean = false)` and `constructor(graph, excludeHeld =
false)`; `internal fun AssetPicker(…)` exactly as C3's fragment; `internal fun EmptyList(…, onNewAsset: (() ->
Unit)?, …)`. **Consumed:** nothing new.
**Greps:** §7's `excludeHeld`, `AssetPicker`, `EmptyList` and private-count lines; `AssetsScreen.kt`'s diff
touches only `EmptyList`'s modifier, its `onNewAsset` type and the button's null guard (read it).
**Untouched:** `A/share/**`, every other `A/ui/**` file, `C/**`, `tools`, `docs`, every shipped test file.
**Must NOT:** filter held rows after the controls; build `byId` from the reduced rows; drop ARCHIVED, retired or
components in the switch; add a `private` top-level helper to `AssetViewModels.kt` (N-1); give `AssetPicker` a view
model, a selection or a word of its own; edit the tab's `EmptyList` call. **Counted RED (5):** rows 6, 7, 12, 13, 14.
**Caps:** 7 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 15 lines in
`AssetViewModels.kt`, 6 in `AssetsScreen.kt`, 80 in `AssetPicker.kt`; about 260 test lines. **Estimate:** 45 min.

## 10. B2 — the intake's two steps, Back, Change, and the test moves (C4–C9; app JVM, three grown Compose cases)

**Read:** audit §0.1, §0.6, §0.8, §1, §4, §6; `A/share/ShareIntakeViewModel.kt:53-164`, `:223-320`, `:455-527`;
`A/share/ShareIntakeScreen.kt:32-208`; `A/share/ShareIntakeActivity.kt:56-116`;
`T/share/ShareIntakeViewModelTest.kt:170-240`, `:490-600`, `:775-815`, `:925-951`;
`AT/share/ShareIntakeScreenTest.kt:1-120`, `:160-230`, `:260-273`; `AT/share/ShareBoundaryTest.kt:94-109` (read
only: the `TYPE` coverage row 27 receives); B1's report. **`<base>`** = B1's accepted tip.
**Rows:** 15–24, 26, 27. **Rulings:** R93-4, R93-5 ("Change"; the name alone), R93-7, R93-9's relocation, R93-10,
R93-11, R93-12.
**Interfaces consumed:** C2's switch, C3's `AssetPicker`. **Produced:** `choose(assetId, name)`, `changeAsset()`,
`picking`, `backChangesAsset`, `IntakeStrings.CHANGE`, `ShareIntakeScreen`'s C9 parameters.
**Connected:** none run (rows 22, 23 and 27 are edited and compiled; they run at the merged gate, the picker's first
device composition). Row 27 grows `theTypeControlIsDrawnOnBytesAndNeverOnALink` (`:165-172`): picker first on a
byte share, no "TYPE"; the `storeReady = false` flip (C-2: the no-folder sentence and "Close", no "Cancel", no
Save); then chosen, "TYPE" and the seven labels. **Edits beyond the code:** the three KDocs of C9 (N-6) and `assets`'
(C4). **Greps:** `'onNodeWithText\("TYPE"\)\.assertIsDisplayed'` in `ShareIntakeScreenTest.kt` → ≥ 1 inside that
case (read it); §7's C-1 structural check (read it); §7's
`ShareIntakeViewModel.kt`, `ShareIntakeActivity.kt` and `ShareIntakeScreen.kt` lines; the `"share-intake"` builder
(`ShareIntakeActivity.kt:63-76`) unchanged in the diff (read it); `IntakeForm`'s Name-to-Save body unchanged in the
diff but for the ATTACH TO block (read it).
**Pin list:** the B2 rows of §3's moving-pins table.
**Untouched:** `A/share/SharedItem.kt`, `A/ui/**`, `C/**`, `tools`, `docs`, `AT/share/ShareBoundaryTest.kt`.
**Must NOT:** look a choice up in `assets`; drop or reshape `assets`; filter, sort or search anything in `A/share`;
nest `AssetPicker` (or any lazy list) in the form's scroll column, or draw the title twice (C5); restore "That is not
a link." on Change; let Back or Change act while saving; change the form's order, padding or any field; label Change
anything but "Change"; add any other string; edit `ShareBoundaryTest` (B3's). **Counted RED (5):** rows 15, 16, 17,
18, 19. **Caps:** 7 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 110
production lines (+130 / −30), 28 mechanical call-site edits, about 185 test lines. **Estimate:** 55 min; past the
hour, §4's stop-and-split clause applies (B2′ takes Change and Back, never the step).

## 11. B3 — the spec, the R4 wording and the boundary assertion (C11; docs, one device case edited)

**Read:** audit §0.7, §1 ("Spec lines the plan amends"); `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`
at SPEC:40-47, :64-81, :92-94, :441-446, :489-494, :533-570; `docs/release-proofs.md:36-52`;
`docs/superpowers/planning-policy.md:61-94`; `AT/share/ShareBoundaryTest.kt:25-50`, `:84-168`; B2's report.
**`<base>`** = B2's accepted tip (row 27 must already hold the relocated `TYPE`; stop if it does not). **Rows:** 25.
**Rulings:** R93-9 and G5 (DECIDED; ratified text written verbatim).
**Greps:** `'reaches the byte form'` in `docs/release-proofs.md` → 0 and `'reaches the byte-share form'` in
`planning-policy.md` → 0; `'onNodeWithText\(TYPE\)'` in `ShareBoundaryTest.kt` → 0;
`'@Test fun '` there → 3 (unchanged); `'ReachesTheByteForm'` → 0 and `'ReachesTheIntake'` → 1 there;
`'const val TYPE = '` there → 0; `'"Change"'` in SPEC → 1, on the Amendment (#93) line beside :566 (0 at
`78c23a88`); `'Choose asset'` in SPEC → base + ≥ 1 (§7's paragraph now names it and the two steps; read it — this
replaces rev 1.1's vacuous `chip` check, N-4); SPEC:492 gains the held clause (read it).
**Untouched:** every `src/main` file, `tools`, `docs/api`, `docs/versioning.md`.
**Must NOT:** add a fourth boundary case or make case 2 choose, type or press anything; move any assertion but `:107`
or edit beyond `:56`, `:94-109`, `:156`; re-add a byte-form check to the boundary case (its `TYPE` lives in row 27);
edit any other R-layer; re-word anything the owner has not ratified. **Counted RED:** none (docs and a device
assertion, compiled only). **Caps:** no mutation runs; **1 h target, 2 h hard stop**; fix round 45 min. **Size:**
about 45 doc lines, 6 test lines. **Estimate:** 25 min.

## 12. Errata after the merge (controller, 2026-10-01; merged bca5939a)

Rev 1.2 is the ratified spec; the code and the documents are as built (the share-intake spec, `docs/release-proofs.md` R4, `planning-policy.md` layer 4). Where they differ, this section records the difference — collected by the whole-branch review from the three task reviews, the reports and the ledger. Nothing above is rewritten.

1. **E-1, the `excludeHeld` grep.** §7's R6 line "`'excludeHeld'` anywhere else under `app/src/main` → 0"
   contradicts its own "`AssetsViewModel\(graph, excludeHeld = true\)` in `ShareIntakeActivity.kt` → 1". The tree
   has 1, at `ShareIntakeActivity.kt:106`, the C9 builder.
   - Correct wording: "`excludeHeld` outside `AssetViewModels.kt` → 1 (`ShareIntakeActivity.kt`), 0 in any other
     file".
2. **E-2, `ReachesTheIntake`.** §11's "`'ReachesTheIntake'` → 1" in `ShareBoundaryTest.kt` is really **2**. Case 1
   was already `anExternalTextShareFromAnotherUidReachesTheIntake`.
3. **E-3, "reaches the intake".** It is **2** in `release-proofs.md` and **2** in `planning-policy.md` (1 each at
   base: R4 item 1 and layer 4's text clause). Any "→ 1" reading is wrong. The two "byte form" greps → 0 as specified.
4. **E-4, the `chip` count.** "chip" in the SPEC goes 4 → **5**: §7's new sentence names the retired "chip cloud"
   historically. Rev 1.2's replacement check, `'Choose asset'` base + ≥ 1, holds (1 → 2).
5. **E-5, the `CHOOSE_ASSET` constant.** C11 says the dead `TYPE` const "is removed". B3 *replaced* it with
   `const val CHOOSE_ASSET = "Choose asset"` (`ShareBoundaryTest.kt:156`) rather than a literal, which is the file's
   idiom. `'const val TYPE = '` → 0 holds.
6. **E-6, `cancel()`'s KDoc.** It became false with R93-4 but was not in C9/N-6's list of KDocs to amend. The
   controller fixed it in `ce92b16a`, and it is now true. It is one 197-character line (NOTE-3).
7. **E-7, `EmptyList`'s KDoc** (`AssetsScreen.kt:268-273`). It still promises "adding an asset", and it is not in
   N-6's list. It is left on purpose by B1's three-line fence (NOTE-4).
8. **E-8, `ShareBoundaryTest.kt:105`.** C11 says `:103-106` "stay". The comment at `:105` became false and was not
   amended (MINOR-1).
9. **E-9, `ShareBoundaryTest.kt:121`.** The KDoc "never a byte form with an empty Received line" is stale. It sits
   outside C11's fence (`:56`, `:94-109`, `:156`) (NOTE-1).
10. **E-10, C10's I-8 citation.** C10 says the hosted model's read path is `ReadPathsWriteNothingTest.kt:506`,
    "re-run". That case builds the tab-shaped model without `transfers`, `excludeHeld` or `refresh()`. The hosted
    instance's no-write property holds by construction, not by that re-run (NOTE-5).
11. **E-11, C7's header.** C7 lists four header elements: the title, the Received block, ATTACH TO and "Choose
    asset". By the controller's ruling (after B1's NOTE-2) they ship as **three** unkeyed items, with ATTACH TO and
    "Choose asset" sharing one. That keeps the item count fixed for a visit. Intended; recorded.

**Not errata, checked:**
- C9 names the resume refresh, and it is in the code verbatim.
- The #93 SPEC amendment sits at `:574`, directly after the #67 amendment. The "beside :566" line numbers shifted by
  B3's own earlier edits.
- §10's "All 50 RATIFIED" header is unchanged, as N-3 rules.
- `AssetPicker`'s signature matches C3's fragment exactly.

**Errata count: 11.**

---

- **Reviews and rounds.** Three briefs, one task review each (no fix rounds; controller-inspection fixes: B2's `cancel()` KDoc, the two `ShareBoundaryTest` comments at the branch review); one whole-branch review (MERGE WITH FIXES: 0 BLOCKER, 0 MAJOR, 1 MINOR, 8 NOTE) with B3's task review folded in. Controller rulings: the tab's resume `refresh()` copied into the intake activity (B1 review concern 4); the picker's header a fixed item count, never keyed by an asset id. Owner-visible, as ratified: the list scrolls to the top after Change; a no-folder byte share's form says Close yet Back returns to the picker. The gate is `.superpowers/sdd/2026-10-01-issue-93/gate/` (55 device classes; the picker step's first device composition and the three grown Compose cases; the MCP and loader pytests), run once on bca5939a.
