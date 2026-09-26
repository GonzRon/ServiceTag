# #74 — durable Asset categories: plan and briefs (rev 2, reviewed 2026-09-26; re-review fixes N1–N8 applied; RATIFIED 2026-09-26)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md`. Three briefs (§9), one lane, in order
> B1 → B2 → B3; one task review per brief, one batched fix round each, one whole-branch review and
> one integrated gate at the end. Planned from issue #74's body under the owner's GO of 2026-09-26.
> Rev 2 folds in the independent brief review (verdict REJECT on rev 1: canonicalising a spelling
> outside an owner save broke merge idempotency; the backfill trigger raced; promotions made by the
> merge apply were invisible to the plan; the API mirrors, `StoreIsEmpty`, the export and forty-odd
> constructor sites were missing; B1 could not end green). **Owner ruling 2026-09-26: all nineteen §6 strings
> RATIFIED verbatim; R74-3 (the canonical stored spelling, with the merge/migration protections) and R74-10
> (built-ins first in compiled order, then the owner's categories alphabetically) RATIFIED; R74-1, R74-2,
> R74-4–R74-9, R74-11–R74-13 stand; the re-review corrections N1–N8 are part of the contract — in particular
> `LAST_LEGACY_FORMAT` stays 7 and format-8 1.4 data decodes unchanged. GO: B1 dispatched.**

**Goal:** a category the owner types into an Asset and saves becomes a durable, reusable category —
offered in every Asset's Category picker, kept when the last Asset using it is gone, carried by
backup, restore, merge and device migration, and manageable (rename, delete when unused, never a
silent orphaning) — while the compiled built-in categories keep their template hints and cannot be
renamed or deleted. Category stays the **one** classification dimension: #73's `Type` filter
consumes exactly this catalog; no equipment-type taxonomy appears beside it.

**Spec:** issue #74 (AC 1–10, the "one classification dimension" rule, the durability rule, the
successful-save-only rule); issue #73 §Type (the consumer: named choices from the durable catalog;
"any category currently used by an Asset must remain filterable"); D4
(`docs/design/04-domain-data-model.md` §3 "category is free text and the user's classification;
`template_key` is provenance only"; the 2026-09-15 equipment-type decision — no equipment-type key);
the 2B-2 spec §8 (`docs/superpowers/specs/2026-09-15-phase-2b2-asset-model-design.md`: the compiled
suggestion list, the creation-time hint rule); `docs/versioning.md` (schema and format bumps are
MINOR; a change that stops an older archive restoring is MAJOR); the merge contract of
`MergePlan.kt` (no `UPDATE` verdict; a row already here either matches or conflicts, or is skipped
when a second identity already holds it; the only normalisation is the child-list order — this plan
adds a second, §4 C13); `Migrations.kt`'s 7→8 precedent (a Kotlin row-by-row step through a pure
core function, `copySchedulesThroughTheLegacyMapping` via `LegacySeasonMapping`; "no `updated_at`
anywhere is written").

## Global constraints

- **One dimension.** `asset.category` remains the stored classification (free text, trimmed as
  today); the catalog is a small table of the owner's own categories beside the compiled built-ins.
  No `equipment_type`, no foreign key from `asset` to the catalog, no second concept.
- **Durability, not derivation.** The picker and #73's menu never derive their choices from
  `SELECT DISTINCT asset.category`; they read the catalog (built-ins ∪ rows). A row outlives the
  last Asset that used it. The one retroactive step (the migration's backfill, C10) applies the
  promotion rule once to saves made before the catalog existed and never runs again.
- **Promotion only by a successful save.** Typing, cancelling, a refused save, a template pick — none
  writes the catalog. Every successful Asset create or edit does, in the same transaction, after
  every refusal and after the unchanged-save early return.
- **Merge idempotency.** Re-planning an archive that was just applied, and planning any pre-upgrade
  export against its own phone after the upgrade, is all IDENTICAL. No backfill, merge or replace
  rewrite ever moves an asset's `updatedAt`.
- **Built-ins are compiled and protected.** `CategorySuggestions.all` stays the built-in list with its
  template hints; built-ins are never rows, so they cannot be renamed or deleted by the owner, and a
  custom category never acquires a hint. An archive row whose key is a built-in's is dropped, never
  refused (C11, C13).
- **Strings.** Every user-visible sentence is in §6, verbatim, ratified before dispatch.
- Room schema **9**, backup format **9**; no app version bump in #74 (R74-11). Tests: JVM first
  (`:core`, `:app`); Compose instrumented tests on `emulator-5554` only, one class per invocation; no
  UI-driving harness. Hygiene (no e-mail addresses, `/home/<user>` paths, serials, private equipment
  names, locations, household nouns). Gitlink `libs/nfc-tag-core` stays `7e0377a`. Commits: one
  casual subject, no body, no trailers. The 2.6 tombstone rule (`external_link` / `externalLinks`
  never bumped, re-purposed or dropped). Each brief ends with `:core:test` and `:app:testDebugUnitTest`
  green.

## 1. Audit (controller, read-only, done 2026-09-26; corrected by the review)

**The save path.** The editor (`ui/asset/AssetViewModels.kt`, `AssetEditViewModel.commit()`) builds
one `AssetSettingsCommand` and calls `SaveAssetSettings.run(id, cmd, templateKey)` (core), which
validates, lays the command over the row (`AssetCommand.trimmed()` trims `category`; nothing else
touches it), **returns early when nothing changed** (`next.copy(updatedAt = current.updatedAt) ==
current`), raises its 409s after `next` is built, then `assets.upsert(next)` inside one `uow.write`.
The API's `POST /v1/assets` and `PATCH /v1/assets/{id}` go through `CreateAsset.run` (builds and
returns the asset **outside** its write) and `UpdateAsset.run` (always upserts). The MCP and the
loader reach the API; the bundle tool is private data. Other asset upserts — `RetireAsset`,
`ArchiveAsset`, `SetSeasonMode`, `SetMaintenanceBreak`, `SetHealthPolicy`, `ApplyTemplate` — never
change `category`. The merge (`ApplyBackupMergePlan`, writing `MergeWrites` field by field, then
`rebuildAll()`) and the replace import (`ImportBackupReplace`) upsert assets from an archive;
`ExportBackupSet` builds `BackupData` from every repository.

**The catalog today.** `core/journal/CategorySuggestions.kt`: `CategorySuggestion(label,
suggestedTemplateKey?)`, twelve built-ins (Generator, Lawn mower, Snowblower, UPS, Battery,
Inverter / charger, Solar charge controller, RO system, Hot tub, HVAC, Pump, Other), and
`templateFor(category)` = exact label match, case-insensitive. Two consumers: the editor's
`CategoryField` (an `ExposedDropdownMenuBox` filtering `CategorySuggestions.all` by prefix,
case-insensitive; picking sets the field text to the label) and `onCategory()` (the hint rule: on a
new asset with `templateTouched == false`, the template follows `templateFor(value)`).

**Where the string is read.** The assets list (`AssetsScreen.kt:173`), the detail plate's eyebrow and
icon (`AssetDetailScreen.kt:698, 709`; `categoryIcon()` is a keyword match, default `Info`), children
rows, the scan sheet's picker detail, the due read model, and the **dashboard's category filter**
(`DashboardViewModel.kt:312` — `inService.map { it.category }.distinct().sorted()`;
`DashboardFilters.kt` "All categories" / "Category"). The stored spelling is user-visible in five
places and compared by exact string in one — the reason the stored spelling is canonical (R74-3).

**Persistence layers.** Room schema 8 (22 tables; `AppDatabase.version = 8`; `AppGraph.SCHEMA_VERSION
= 8` pinned by `VersionAgreementTest`; `MIGRATION_7_8` and `Migration7To8Test` + `MigrationTestSupport`
show the JVM proof: seed v7, migrate, open through Room, compare with a fresh v8). Backup format 8
(`BackupCodec.FORMAT_VERSION = 8`, encode sorts each list by id, `uniqueIds` graph check;
`BackupData` lists with `= emptyList()` defaults; `LegacyArchive` strips the format-8 lists from a ≤7
tree; `BackupContentCheck` refuses malformed rows per table; `BackupFormat8Test` per format;
`Format7RestoreContractTest` (connected) pins the re-export format and 11 merge decisions).
Merge: `MergePlanner.mergePlanOf(backup, snapshot)` per `MergeTable` with INSERT / IDENTICAL /
CONFLICT / SKIPPED, no UPDATE; assets are compared **field for field** as DTOs (`dto ==
local.toDto()`); a "second identity" already held locally is SKIPPED with a reason; `MergeReport`
tallies one `MergeTally` per table and the API mirrors it (`MergeReportResponse`; `/v1/status.counts`
one key per table — both pinned by tests); `MergeTable.entries` and its ordinals are pinned by three
planner tests (appending is the precedent). `StoreIsEmpty` answers #40's dialog from **five kinds,
and exactly five** — a category row would be a sixth kind of record a restore deletes.
`CrossConceptWriteTest` records which tables each use case writes.

**Tests that pin today's words.** `CategorySuggestionsTest`, `AssetViewModelsTest` (the hint rule),
`ApiRouterTest` and the MCP pytest (`category: "Water"` round-trips — first promotion keeps the typed
spelling, so these stay true).

**Precedents for the management UI.** Settings → "Utilities" rows (`SettingsScreen.kt:237`), the
setup screen's per-row overflow menu with "Delete" and its confirm dialogs ("Delete this reading?" /
"Delete this action?") and its refusal dialog ("Cannot delete this reading" + OK), the editor's
"problems under fields" rule (`supportingText` + `isError`), the screen snackbar for a refused action.

## 2. The model (core; brief B1)

- **C1, the entity.** `AssetCategory(key: String, display: String, createdAt: Long, updatedAt: Long)`
  in `core/model`. `key` is the identity (§3); `display` is the owner-facing spelling. Built-ins are
  **not** rows.
- **C2, the key.** `CategoryKey.of(text)`: Unicode NFC normalisation (`java.text.Normalizer`), trim,
  collapse every run of `Char.isWhitespace()` characters to one space, `lowercase(Locale.ROOT)` — no
  further case folding (`ß` and the final sigma are not folded; stated, not accidental).
  `CategoryKey.display(text)`: NFC, trim and collapse only (the spelling kept). Blank → no key (never
  promoted). A built-in's key is `CategoryKey.of(label)`. **The rule is persisted** (a Room primary
  key and an archive field): changing it later is a migration plus a format bump.
- **C3, the catalog read model.** `CategoryCatalog` (core): `choices(custom: List<AssetCategory>):
  List<CategoryChoice>` where `CategoryChoice(display, key, builtIn: Boolean, templateKey: String?)`
  = the built-ins in compiled order (with their hints) followed by the rows ordered by
  `String.CASE_INSENSITIVE_ORDER` on `display`, then by key (R74-10), **deduplicated by key with the
  built-in winning** (a row under a built-in's key can exist after the built-in list grows; C11);
  `resolve(text, custom)`: the choice whose key equals `CategoryKey.of(text)`, or null.
  `templateFor(text)` becomes a key match (`CategoryKey.of(text) == CategoryKey.of(label)`), so
  `hot  tub` and `HOT TUB` carry the Hot tub hint as `ro SYSTEM` does today; built-ins only (AC 6–7).
- **C4, the port.** `CategoryRepository { upsert(row); get(key); all(); delete(key); deleteAll();
  observeAll(): Flow<List<AssetCategory>> }`.
- **C5, promotion, in two halves.** `PromoteCategory(categories)`:
  `suspend fun resolve(typed: String, now: Long): Promotion` — read inside the caller's transaction, `now` the command's own so a new row and the asset share one timestamp — returns
  `Promotion(spelling: String, newRow: AssetCategory?)`: blank → `("", null)`; a built-in key → `(label,
  null)`; an existing row's key → `(row.display, null)`; otherwise `(display, AssetCategory(key,
  display, now, now))`. `suspend fun write(p: Promotion)` upserts `newRow` when present. The three
  commands call `resolve` to build `next` (so `next.category` is the canonical spelling and the
  unchanged-save comparison sees it), and call `write` **beside `assets.upsert`, after the early
  return and after every refusal** — an unchanged save writes nothing, a 409 adds no row.
  `CreateAsset` builds and returns the canonical row **inside** its write. The stored string is
  therefore always a catalog spelling (R74-3); a refused save never reaches `write`; a cancelled
  editor never calls either.
- **C6, rename.** `RenameCategory(categories, assets, uow, clock).run(key, newText)`: the new key and
  display from C2; blank → `CategoryValidation`; the new key a built-in's → `CategoryIsBuiltIn`; the
  new key another row's → `CategoryExists(existingDisplay)`; unchanged (`CategoryKey.display(newText)
  == row.display`) → no-op, nothing written. Otherwise, in one `uow.write`: same key → the row's
  `display` is updated in place (`updatedAt` = now); different key → a new row is inserted with the
  old row's `createdAt` and `updatedAt` = now, then the old row is deleted; **in both cases every
  asset whose `CategoryKey.of(category) == key` is upserted with the new display and its `updatedAt`
  moves** (an owner action changed the asset) — archived and retired assets included.
- **C7, delete.** `DeleteCategory(categories, assets, uow).run(key)`: in one `uow.write`, count the
  assets whose key matches (archived and retired included); `> 0` → `CategoryInUse(display, count)`,
  nothing written; else the row is deleted. Nothing is ever reclassified by a delete.
- **C8, usage.** `CategoryUsage.count(assets, key)` over **every** asset, archived and retired
  included — the same number the screen shows (P74-6) and C7 refuses with (P74-17), so the count
  matches even though the Assets list hides archived assets by default.
- **C9, the pure backfill function.** `CategoryBackfill.plan(rows: List<AssetRow>): Backfill` in core
  (`AssetRow(id, category, createdAt)`; `Backfill(newRows: List<AssetCategory>, rewrites:
  Map<AssetId, String>)`): for every non-blank category, the key; built-in keys → the asset is rewritten
  to the label; other keys → one row per key with `display` = the spelling of the asset with the
  smallest `createdAt` (ties by id) and `createdAt = updatedAt = that minimum`, and every asset of that
  key rewritten to that display. Pure, deterministic, unit-tested; the migration (C10), the replace (C12) and the planner (C13) all choose a new key's display by this one rule, so three paths cannot pick three spellings.

## 3. Identity, normalisation, display

| typed | key | first display | later effect |
|---|---|---|---|
| `Appliance` | `appliance` | `Appliance` | — |
| ` appliance ` | `appliance` | — | reuses the row; the asset stores `Appliance` |
| `APPLIANCE` | `appliance` | — | same |
| `Water  heater` (two spaces) | `water heater` | `Water heater` | one space kept |
| `hot tub` | `hot tub` | (built-in) | the asset stores `Hot tub`, no row, the hint applies |
| `Éclairage` typed precomposed or decomposed | `éclairage` | `Éclairage` (NFC) | one row |
| `` / `   ` | none | — | nothing written, the asset stores `` |

The first successful save fixes the spelling; a later variant reuses the row and the asset takes the
row's spelling. To change the spelling the owner renames (C6). The dashboard filter, #73's Type menu
and the list therefore compare exact strings safely, and the merge planner compares by key (C13).

## 4. Persistence, backup, merge, migration (B1 persistence; B2 the rest)

- **C10, Room schema 9 with the backfill (B1).** Table `asset_category(key TEXT NOT NULL PRIMARY KEY,
  display TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`; entity
  `AssetCategoryEntity`, DAO, `RoomCategoryRepository`, mappers; `AppDatabase.version = 9`,
  `AppGraph.SCHEMA_VERSION = 9`, `9.json` exported. `MIGRATION_8_9`: `CREATE TABLE`, then **the
  backfill in Kotlin, row by row**, on the 7→8 precedent: `SELECT id, category, created_at FROM asset`
  through `connection.prepare`, `CategoryBackfill.plan(rows)` (C9, core), `INSERT` each new row,
  `UPDATE asset SET category = ? WHERE id = ?` for each rewrite — **`updated_at` untouched**, no SQLite `lower()`, atomic with the schema step, before any DAO read, once; the step reads the whole `SELECT` into a list and closes the statement before the first `INSERT` / `UPDATE` (it updates the table it reads; the 7→8 step wrote to a different table while stepping). `MIGRATION_8_9` is registered in **both** `AppGraph`'s `addMigrations(...)` and `MigrationTestSupport.openMigrated` (the first omission crashes the upgraded phone on open, the second turns every migration test red). `Migration8To9Test`: every
  v8 row survives field for field except `category` where the plan rewrites it; the migrated file
  equals a fresh v9; a seeded variant set (`appliance` / `Appliance` / ` APPLIANCE`, `hot tub`, a
  blank) yields exactly the planned rows and rewrites, with `updated_at` unchanged.
- **C11, backup format 9 (B2).** `AssetCategoryDto(key, display, createdAt, updatedAt)`;
  `BackupData.assetCategories: List<AssetCategoryDto> = emptyList()` (older archives decode);
  `ExportBackupSet` writes `categories.all()`; the codec sorts the list by key, `uniqueIds` covers it,
  the manifest counts it; `BackupCodec.FORMAT_VERSION = 9`; `LegacyArchive` is **untouched** (`LAST_LEGACY_FORMAT` stays 7: raising it would send every format-8 archive through `upgrade`, which strips the three 1.4 lists and resets season, break, health and policy — every 1.4.x export the owner holds is format 8; a ≤8 archive written by any shipped app never carries `assetCategories`, and the codec's strict decode refuses a hand-built one as it refuses any unknown key today);
  `BackupContentCheck` refuses **malformed rows only**: blank display, `key != CategoryKey.of(display)`,
  a duplicate key. A row whose key is a built-in's is **not** refused (a built-in added by a later
  release must never make an older archive unrestorable — MAJOR by `versioning.md`); the planner and
  the replace drop it (C12, C13). `BackupFormat9Test`: round trip; a format-8 archive decodes with no
  categories; a format-9 archive is refused by `supportedFormat = 8` with `BackupNewerFormat`; the DTO field set; an export→replace round trip carries the rows (AC 4); a format-8 archive with a MANUAL asset, a break, health rows and the three 1.4 lists decodes unchanged under format 9.
- **C12, replace import (B2).** `ImportBackupReplace`: `categories.deleteAll()` with the other tables;
  insert the archive's categories **first**, dropping any built-in-keyed row; after the assets,
  **always** promote every restored asset through C5's rule in the same transaction, a new key's display chosen by C9's rule over the restored assets (idempotent: a
  format-9 archive whose rows are complete adds nothing; a ≤8 archive or one whose assets name absent
  categories gets them), the assets' spellings canonicalised **without moving `updatedAt`**.
- **C13, merge (B2).** `MergeTable.CATEGORIES` appended **last** in the enum (ordinals pinned by three
  planner tests; the write order is `MergeWrites`' field order, where `categories` comes first);
  `MergeSnapshot.categories`, `MergeWrites.categories`, `mergeSnapshotOf`, `BuildBackupMergePlan`,
  `ApplyBackupMergePlan` gain the repository. Rules per incoming category row: a built-in's key →
  SKIPPED `CATEGORY_IS_BUILT_IN`; the key held locally with the same `display` → IDENTICAL (**on key
  and display only**; `createdAt` / `updatedAt` are never compared, reason NONE); the key held locally
  with another `display` → SKIPPED `CATEGORY_KEY_HELD` (the local spelling wins; a spelling is never
  worth refusing an archive — the reference's D-18 C precedent); a key claimed by an earlier row of
  the same archive → SKIPPED `CATEGORY_KEY_HELD` (reachable only from a hand-built `Backup`, since the
  codec refuses duplicate keys — noted like the planner's other "not total" arms); else INSERT.
  **Promotions are planned, not applied after:** for every accepted asset the planner resolves the
  category key; a key that is not a built-in's, not held locally and not an accepted archive row
  gets a synthesised INSERT decision (table CATEGORIES, id = key, display chosen by C9's rule over the accepted assets — an existing local row or an accepted archive row still wins) and a `MergeWrites.categories` row; `MergeDecision`'s KDoc gains a line for synthesised rows; and every accepted asset
  is written with the **canonical spelling** (the local row's, the accepted row's, or the built-in
  label) — `MergeWrites.assets` carries it, so the report, the tallies and the fingerprint all see
  it and the apply writes `MergeWrites` and nothing else. **The asset comparison gains a second
  normalisation** beside the child-list order: `category` is put through `CategoryKey.of` on both
  sides before `dto == local.toDto()`, so an archive's `appliance` against a local `Appliance` is
  IDENTICAL. The planner's KDoc ("never treats a name as identity") is amended for this table. `categories` sits after `healthSubjects` in `MergeReport` and `MergeReportResponse` (enum order), and the wire test's "in write order" wording is corrected. The
  accepted costs of key-as-identity (R74-2): an archive made before a new-key rename re-inserts the
  old key as an unused row; a deleted category resurrects from an archive that holds it. Both pinned.
- **C14, wiring (B1).** `AppGraph` and `FakeGraph` gain `categories`, `promoteCategory`,
  `renameCategory`, `deleteCategory`; `CreateAsset`, `UpdateAsset`, `SaveAssetSettings` gain the
  `PromoteCategory` collaborator (their constructors change at every construction site — the
  implementer greps them; the review names them: about eight for `CreateAsset`, six for
  `SaveAssetSettings`, four for `AssetEditViewModel`). B2 adds `ImportBackupReplace`, `ExportBackupSet`,
  `BuildBackupMergePlan`, `ApplyBackupMergePlan` and `StoreIsEmpty` collaborators (about nine, eleven,
  six, five and one sites).
- **C15, the API mirrors and docs (B2).** Additive: a `categories` tally in `MergeReport` and
  `MergeReportResponse`; an `assetCategories` key in `/v1/status.counts` (one key per table; the map is built in `ApiHandlers.kt`'s `counts`, which gains the repository — its construction sites, about ten, are re-anchored);
  `docs/api/v1.md`: the status key, the tally, and one note under the asset command — `category` is
  stored in its catalog spelling, so a response may differ from the request by case or spacing (a
  `POST` of `water` after `Water` answers `Water`). No categories route (R74-8). The MCP pytest is
  unchanged (its status docstring lists table names as prose, not a pinned set — the implementer
  confirms). `StoreIsEmpty` gains the sixth kind: a category row alone makes the store non-empty (its
  KDoc's "five kinds, and exactly five" becomes six, with the reason: a row that exists without any
  asset by design). Supersession notes: D4 §3 (`04-domain-data-model.md` at the "nothing branches on
  the chosen string" sentence), the 2B-2 spec §8 ("nothing reads the category string after
  creation") and the `CategorySuggestions` KDoc each gain one line pointing at this plan.

## 5. The UI (brief B3)

- **C16, the picker.** `AssetEditViewModel` gains a `CategoryRepository` constructor parameter and
  exposes `categoryChoices: StateFlow<List<CategoryChoice>>` from `categories.observeAll()` through
  `CategoryCatalog.choices` (not a field of `AssetEditState`); `CategoryField` offers those, filtered
  by `display.startsWith(typed.trim(), ignoreCase = true)` as today; a row reads exactly like a
  built-in; picking one sets the text to its display. The hint rule is unchanged in shape (C3's
  `templateFor`).
- **C17, Settings → Categories.** A `Route.Categories` screen from a new Utilities row (P74-1, icon `ServiceTagIcons.Label`, a new outlined "label" glyph added in that file's own pattern): title P74-2; section P74-3 listing the owner's categories by display with
  a usage line (P74-6a/b/c, C8's count), each with an overflow menu P74-13 / P74-14; section P74-4
  listing the built-ins as quiet rows with no menu and P74-7 under the header; P74-5 when there are
  no rows. Rename opens a dialog (P74-8, field P74-9 pre-filled, `Rename` held while the text is
  blank or unchanged by C6's rule, `Cancel`); a refusal (`CategoryExists` → P74-11 with the existing
  row's display, `CategoryIsBuiltIn` → P74-12) is drawn as the field's `supportingText` with
  `isError`, the dialog staying (the app's problems-under-fields rule; a snackbar would sit under the
  dialog's scrim). Delete: unused → the confirm dialog (P74-15, P74-16, `Delete` / `Cancel`); in use →
  no dialog, the screen's snackbar P74-17a/b, nothing written (the row already shows its usage, so no
  refusal dialog is needed).
- **C18, everything else unchanged.** The assets list, the plate, `categoryIcon`, the dashboard filter
  (still the in-service categories of the dashboard's own rows, R74-9), the scan sheet.

## 6. Strings — for ratification (`<x>` = a category's display, `<n>` = a count)

| id | where | text |
|---|---|---|
| P74-1 | Settings → Utilities row | `Categories` |
| P74-2 | the screen's title | `Categories` |
| P74-3 | section header | `Your categories` |
| P74-4 | section header | `Built-in` |
| P74-5 | empty state under P74-3 | `No categories of your own yet. Save an asset with a new category to add one.` |
| P74-6a | usage line | `Used by <n> assets` |
| P74-6b | usage line, one | `Used by 1 asset` |
| P74-6c | usage line, none | `Not used` |
| P74-7 | under P74-4 | `Built-in categories are always offered and cannot be renamed or deleted.` |
| P74-8 | rename dialog title | `Rename category` |
| P74-9 | rename field label | `Name` |
| P74-10 | rename confirm button | `Rename` |
| P74-11 | under the rename field, name taken (`<x>` = the existing row's display) | `A category named <x> already exists.` |
| P74-12 | under the rename field, built-in | `<x> is a built-in category.` |
| P74-13 | overflow menu | `Rename` |
| P74-14 | overflow menu | `Delete` (the existing word) |
| P74-15 | delete dialog title | `Delete this category?` |
| P74-16 | delete dialog body | `<x> is not used by any asset.` |
| P74-17a | snackbar, in use | `<x> is used by <n> assets. Change their category first.` |
| P74-17b | snackbar, in use by one | `<x> is used by 1 asset. Change its category first.` |
| — | unchanged | `Category` (the field), `Cancel`, `Delete`, `All categories` |

Nineteen strings; P74-14 reuses the existing word; P74-10 and P74-13 are the same word in two places.
No sentence anywhere else changes. No "Add" affordance: promotion by a save is the one way in, and
P74-5 says so; no snackbar follows a successful rename (the row changes in place).

## 7. Rulings (controller, 2026-09-26; the owner reads these before dispatch and may override any)

- **R74-1, built-ins stay compiled; the table holds the owner's categories only.** Protection from
  rename and delete is by construction (AC 6, "not accidentally deletable"); the picker and the Type
  menu are the union, deduplicated by key with the built-in winning. Rejected: seeding built-ins as
  rows with a flag (a second copy of compiled data, and a way for a merge to carry built-ins).
- **R74-2, identity = the normalised key, as the primary key.** Deterministic across devices, so two
  phones that both type `Appliance` produce the same row identity (AC 10) with no uuid to reconcile
  and no second-identity arm in the planner. Accepted costs, pinned by test: a new-key rename is
  delete + insert, so an archive made before the rename re-inserts the old key as an unused row; a
  deleted category resurrects from an archive that holds it (a uuid would resurrect it too).
- **R74-3, the stored spelling is the catalog's.** On every successful save the asset's category is
  canonicalised to the built-in label or the row's display, and rename rewrites every matching asset
  (a spelling-only rename included). Without it the list would show `Appliance` and `appliance` side
  by side and the Type filter could not match by string. First promotion keeps the typed spelling, so
  today's data and the API/MCP tests are unchanged; the migration, the merge and the replace canonicalise too
  but never move `updatedAt`, and the planner compares by key, so every re-plan stays IDENTICAL. **RATIFIED
  2026-09-26** — one visible `Appliance`, never three different-looking classifications.
- **R74-4, the backfill lives in the migration, in Kotlin, once.** `MIGRATION_8_9` creates the table
  and applies the promotion rule retroactively to the saves made before the catalog existed, through
  the pure core function C9 (the 7→8 precedent): atomic with the schema step, before any DAO read, no
  race with an editor or API save, the app's own key rule (never SQLite's ASCII `lower()`), and never
  again. This is not a live derivation: rows added by it outlive their assets like any other. Rev 1's
  start-up "table empty" trigger was wrong twice — it raced the first save and it was a standing
  derive-from-assets rule.
- **R74-5, rename rewrites the assets in one transaction** — spelling-only renames included — and
  refuses collisions with a built-in or another row.
- **R74-6, an in-use delete is refused with the count**, never reconciled by reassigning — a bulk
  reclassification is a later feature; the owner edits the assets (or renames the category).
- **R74-7, merge never conflicts on a spelling, and plans its promotions.** A key held locally is
  IDENTICAL or SKIPPED (`CATEGORY_KEY_HELD`, local wins); a built-in's key is SKIPPED
  (`CATEGORY_IS_BUILT_IN`); the categories an accepted asset needs are INSERT decisions in the plan;
  accepted assets are written in the canonical spelling; the asset comparison normalises `category`
  by key (AC 10, and the owner's re-plan-IDENTICAL proofs).
- **R74-8, no categories route.** The API's asset create/update promote through core; the additive
  mirrors (a tally, a status count, one doc note) are the contract's own one-key-per-table rule. A
  `GET /v1/categories` and an MCP `list_categories` can follow when a consumer needs them.
- **R74-9, the dashboard's category filter is untouched** (it filters the dashboard's own rows; an
  unused category there is noise). #73 consumes the catalog on the Assets list.
- **R74-10, picker order: built-ins in compiled order, then the owner's categories alphabetically**
  (`String.CASE_INSENSITIVE_ORDER` on display, then key). **RATIFIED 2026-09-26** — built-ins carry semantics
  (the template hints) that custom categories deliberately do not; prefix filtering reaches either group.
- **R74-11, schema 9 / format 9 now, no app version bump.** The next feature-bearing release carries
  Phase 1A, #83, #78, #68, #70 and #74 and records the bumps in its `docs/versioning.md` row (a MINOR
  by the versioning rule; no older archive stops restoring).
- **R74-12, the hint and the icon.** Template hints stay on built-ins only, now matched by key (so a
  whitespace or case variant of a built-in still carries its hint); the keyword icon stays
  keyword-based (a custom category gets the default icon unless a keyword matches).
- **R74-13, the owner's bundle after the upgrade.** The planner compares `category` by key, so a bundle asset spelled `hot tub` re-plans IDENTICAL against a local `Hot tub`; no category diff appears. Only a genuinely new key shows up, as a category INSERT decision in the plan.

## 8. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the key rule | `CategoryKeyTest` · the §3 table; NFC (precomposed == decomposed); NBSP and tabs collapse; the Turkish case under `Locale.setDefault(Locale("tr"))` restored in `finally` (`INFO` must give `info`, never `ınfo`) | `toLowerCase()` with the default locale; drop NFC; regex `\s` |
| promotion (AC 1–3, 7) | `PromoteCategoryTest` · blank resolves to blank with no row; a built-in resolves to its label with no row; a new text resolves to a new row; a variant resolves to the stored display with no row; `write` upserts only a new row; `templateFor` of a custom text is null and of `hot  tub` is the hint | insert built-ins; return the typed text |
| successful save only (AC 2) | `AssetViewModelsTest` · `cancellingAfterTypingACategoryAddsNothing` and `aRefusedSaveAddsNoCategory` (B1, repository-only); `aSavedCategoryIsOfferedNextTime` (B3: `categoryChoices` gains it after the save) | promote in `onCategory` |
| the commands promote, and only when they write | `CreateAssetTest` / `UpdateAssetTest` / `SaveAssetSettingsTest` · each stores the canonical spelling and writes the row; a 422 and a 409 add no row; an unchanged `SaveAssetSettings` save writes nothing (no row, no upsert); `CreateAsset` returns the canonical row | promote before the early return; skip one command |
| which tables each writes | `CrossConceptWriteTest` · the three commands write `asset_category` only with a new category; rename, delete, replace and merge write it; nothing else does | — |
| rename (AC 9) | `RenameCategoryTest` · spelling-only rewrites the matching assets and updates the row in place; new key inserts (old `createdAt`), rewrites every matching asset (`updatedAt` moves), deletes the old row; archived and retired assets included; unchanged is a no-op; built-in and existing-row collisions refused; blank refused; all-or-nothing on a failing upsert (`FakeUnitOfWork` rollback) | leave one asset; skip archived assets |
| delete (AC 5, 9) | `DeleteCategoryTest` · unused deleted; in use refused with the count (archived included), nothing written; deleting the last asset using a category leaves the row | reassign on delete; count live assets only |
| the catalog (AC 6, 8) | `CategoryCatalogTest` · built-ins first with hints, rows after by `CASE_INSENSITIVE_ORDER` then key, no hints; a row under a built-in's key is dropped; `resolve` by key | sort built-ins; keep the duplicate |
| the backfill plan | `CategoryBackfillTest` · variants of one key → one row spelled as the oldest asset (ties by id) and every asset rewritten; built-ins → rewritten to the label, no row; blanks ignored; no assets → nothing | pick the newest spelling |
| schema 9 (R74-4) | `Migration8To9Test` · every v8 row survives field for field except the planned category rewrites; migrated == fresh; the seeded variant set yields exactly the planned rows and rewrites; `updated_at` unchanged everywhere | skip the backfill; bump `updated_at` |
| `SCHEMA_VERSION` | `VersionAgreementTest` re-anchored to 9 | — |
| format 9 (AC 4) | `BackupFormat9Test` (C11), `BackupContentCheckTest` (blank display, key ≠ `of(display)`, duplicate key refused; a built-in key accepted), `ExportBackupSetTest` (the rows are exported), `LegacyArchiveUpgradeTest` unchanged and green (`LAST_LEGACY_FORMAT` still 7) | accept duplicates; refuse built-in keys; raise `LAST_LEGACY_FORMAT` |
| replace (AC 4) | `ImportBackupReplaceTest` · a format-9 archive restores its rows; a ≤8 archive's assets promote their categories; a format-9 archive naming an absent category gets it; a built-in-keyed row is dropped; restored assets' `updatedAt` unchanged | skip the promotion |
| merge (AC 10) | `MergePlannerCategoryTest` · INSERT; IDENTICAL on key + display with different timestamps; same key other spelling → SKIPPED `CATEGORY_KEY_HELD`, local kept; a built-in's key → SKIPPED `CATEGORY_IS_BUILT_IN`; a legacy archive's inserted asset synthesises an INSERT decision and the tally, report and fingerprint change; an accepted asset is written in the local spelling when the key is held; **apply then re-plan the same archive → all IDENTICAL**; **a pre-upgrade archive with `appliance` against a canonical local `Appliance` → IDENTICAL**; a rename-then-old-archive re-inserts the old key; a deleted category resurrects | CONFLICT on spelling; compare timestamps; promote after the writes |
| the API mirrors | `MaintenanceRoutesTest` / `ReferenceRoutesTest` re-anchored: the tally and the status count present; `ApiRouterTest` · `POST "water"` after `"Water"` answers `"Water"` | drop the key |
| #40's dialog | `StoreIsEmptyTest` · a category row alone → not empty | drop the sixth clause |
| the screen (AC 9) | `CategoriesScreenTest` (Compose, emulator) · rows with usage and built-ins without menus; rename held while blank or unchanged; a refused rename shows P74-11 under the field and keeps the dialog; unused delete asks P74-15/16 then removes the row; in-use delete shows P74-17a and removes nothing; empty state P74-5 | remove the built-in section's guard |
| the picker (AC 1) | `AssetEditorCategoryPickerTest` (Compose, emulator) · after saving an asset with a new category, a second editor types its prefix and the dropdown offers it (the menu clips item thirteen unfiltered) | filter rows out |
| regressions | `CategorySuggestionsTest`, `ApiRouterTest`, the MCP pytest (unchanged, 324), `Format7RestoreContractTest` (connected; re-anchored: re-export is format 9, and the restore's decisions count grows by the promoted categories), `AssetDetailConditionHealthSeasonTest`, `SeasonReconciliationNavigationTest` | — |

## 9. The briefs (one lane, B1 → B2 → B3; each ends with `:core:test :app:testDebugUnitTest` green)

### B1 — core model, use cases, Room schema 9, the migration, the wiring

**Files.** Create `core/model/AssetCategory.kt`, `core/journal/CategoryKey.kt`, `core/journal/CategoryCatalog.kt`,
`core/journal/CategoryBackfill.kt`, `core/usecase/CategoryCommands.kt` (`CategoryValidation`,
`CategoryIsBuiltIn`, `CategoryExists`, `CategoryInUse`), `core/usecase/PromoteCategory.kt`, `RenameCategory.kt`,
`DeleteCategory.kt`, `CategoryUsage.kt`; `app/…/data/room/entities/AssetCategoryEntity.kt`, its DAO,
`RoomCategoryRepository` (+ mappers), `app/schemas/…/9.json`; tests `CategoryKeyTest`, `PromoteCategoryTest`,
`RenameCategoryTest`, `DeleteCategoryTest`, `CategoryCatalogTest`, `CategoryBackfillTest`,
`Migration8To9Test`, `InMemoryCategoryRepository` (Rollbackable + Witnessed) in `InMemoryRepositories.kt`; `MigrationTestSupport.openMigrated` registers `MIGRATION_8_9`.
Modify `core/ports/Repositories.kt`, `CategorySuggestions.kt` (`templateFor` by key; the KDoc supersession
line), `CreateAsset.kt`, `UpdateAsset.kt`, `SaveAssetSettings.kt` (C5), `AppDatabase.kt` (entity, version 9,
DAO), `Migrations.kt` (`MIGRATION_8_9` with C10's Kotlin step), `AppGraph.kt` (`SCHEMA_VERSION = 9`, C14),
`FakeGraph.kt` (C14), every construction site of the three commands, `VersionAgreementTest` (schema 9), `MaintenanceRoutesTest` (`status.schemaVersion` 9),
`CreateAssetTest` / `UpdateAssetTest` / `SaveAssetSettingsTest` / `AssetViewModelsTest` / `CrossConceptWriteTest`
additions. **Contracts.** C1–C10, C14, §3. **Gate.** `:core:test :app:testDebugUnitTest` zero failures/skips;
the schema export diff shows exactly one new table; `git grep -nF 'MIGRATION_7_8, MIGRATION_8_9' -- app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt app/src/test/kotlin/com/loosecannon/servicetag/data/room/MigrationTestSupport.kt` → 2; `git grep -n 'lower(' -- app/src/main/kotlin/com/loosecannon/servicetag/data/room/Migrations.kt` adds no line; the tombstone grep unchanged; `git grep -n 'SELECT DISTINCT' -- core app` adds no line.
**Must NOT.** Add a foreign key, a uuid id, a flag for built-ins, a route, a string, a backup or merge change
(B2's), a UI (B3's); move `updated_at` in the migration; promote before the early return or a refusal.

### B2 — backup format 9, export, replace, merge, the API mirrors, StoreIsEmpty, docs

**Files.** Modify `core/backup/BackupFormat.kt`, `BackupCodec.kt` (FORMAT_VERSION 9, sort, `uniqueIds`,
counts), `BackupContentCheck.kt`, `core/merge/MergePlan.kt` (table appended last, two reasons, snapshot, writes, report tally), `MergePlanner.kt`
(C13, the KDoc), `core/usecase/ExportBackupSet.kt`, `BuildBackupMergePlan.kt`, `ApplyBackupMergePlan.kt`,
`ImportBackupReplace.kt`, `StoreIsEmpty.kt`, `app/…/api/ApiDtos.kt` (the tally mirror), `app/…/api/ApiHandlers.kt` (the status count; the repository and its construction sites),
`docs/api/v1.md` (C15), `docs/design/04-domain-data-model.md` and the 2B-2 spec §8 (one supersession line
each), `AppGraph.kt` / `FakeGraph.kt` (the five collaborators) and their construction sites; tests
`BackupFormat9Test`, `BackupContentCheckTest`, `ExportBackupSetTest`, `ImportBackupReplaceTest`, `MergePlannerCategoryTest`, `StoreIsEmptyTest`, the re-anchors in `MergePlannerReferenceTest`, `MergePlannerSeasonHealthTest` and `MergePlannerMaintenanceTest` (the `MergeTable.entries` pins), `VersionAgreementTest` (format 9), `MaintenanceRoutesTest` (format 9; the tallies 14 → 15), `ReferenceRoutesTest`, `BackupFormat6Test`, `Format7RestoreContractTest` (connected). **Contracts.** C11–C13, C15. **Gate.** `:core:test :app:testDebugUnitTest`
zero failures/skips; connected on `emulator-5554`: `Format7RestoreContractTest`; MCP `uv run --frozen pytest`
unchanged (324); the tombstone grep unchanged. **Must NOT.** Refuse a built-in-keyed archive row; compare
category timestamps; write anything in the apply that is not in `MergeWrites`; move `updatedAt` on a merge
or replace rewrite; add a route.

### B3 — the picker, the Categories screen, the strings

**Files.** Modify `ui/asset/AssetViewModels.kt` (C16), `ui/asset/AssetEditScreen.kt` (`CategoryField` over
`CategoryChoice`s), `ui/nav/Route.kt` (`Categories`), `ui/nav/ServiceTagRoot.kt`, `ui/settings/SettingsScreen.kt`
(P74-1); create `ui/settings/CategoriesScreen.kt` + `CategoriesViewModel.kt` (the P74 `const val`s beside them).
Tests: `AssetViewModelsTest` additions, `CategoriesViewModelTest`, `CategoriesScreenTest`,
`AssetEditorCategoryPickerTest`. **Contracts.** C16–C18, §6 verbatim. **Gate.** `:app:testDebugUnitTest` zero
failures/skips; connected on `emulator-5554`, one class per invocation: `CategoriesScreenTest`,
`AssetEditorCategoryPickerTest`, `AssetDetailConditionHealthSeasonTest`, `SeasonReconciliationNavigationTest`;
anchored `git grep -nF` counts over `app/src/main`, each literal on one line: P74-3, P74-5, P74-7, P74-8,
P74-11's fixed part `already exists.`, P74-12's ` is a built-in category.`, P74-15, P74-16's ` is not used by
any asset.`, P74-17a's `Change their category first.`, P74-17b's `Change its category first.` → exactly 1 each across `app/src/main`, in the file that declares the const; `"Categories"` → 2 (the Settings row and the title); `"Built-in"` → 1; the words
`Name`, `Rename`, `Delete`, `Cancel` are not counted (shared words). **Must NOT.** Derive choices from assets;
add a string outside §6; touch the dashboard filter; let the picker write anything; show a refusal in a
snackbar under the rename dialog.

### Whole-branch review and integrated gate (controller)

One whole-branch review (most capable model) over B1–B3; then from scratch:
`:core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` zero failures/skips; the connected
classes: `Format7RestoreContractTest`, `CategoriesScreenTest`, `AssetEditorCategoryPickerTest`,
`AssetDetailConditionHealthSeasonTest`, `SeasonReconciliationNavigationTest`, `ComponentsSmokeTest`; MCP
`uv run --frozen pytest` unchanged (324 / 56 tools); `docs/versioning.md` untouched (R74-11); gitlink
`7e0377a`; hygiene; merge `--no-ff`.

## 10. What this plan does not do

No API or MCP category routes (R74-8); no bulk reclassification; no change to the dashboard filter
(R74-9), the plate icon, the template hints' set or the built-in list; no version bump; #73's Type
filter itself (it consumes C3 and C4 when it lands).

## 11. Errata and decisions from the build (2026-09-26; the contract stands)

- **R74-14 (B2, from B2's review N5).** `BackupContentCheck` also refuses a category row whose `display` is not
  in `CategoryKey.display` form (untrimmed, a run of whitespace, or not NFC) — landed with format 9 itself,
  because a restore check tightened later would refuse archives an earlier build accepted.
- **A fifth refusal, `NoSuchCategory(key)`** (B1): a rename or delete aimed at a key with no row; no user-visible
  words — the screen closes the dialog and the list refreshes. **`CategoryIsBuiltIn` carries the built-in's
  label**, so P74-12 reads "Hot tub is a built-in category." rather than echoing the typed text.
- **The template lookup runs before any write** in `CreateAsset` and `SaveAssetSettings`'s create path (B1), so
  "the category row is written after every refusal" is literally true; pinned by `aRefusedCreateAddsNoRow`.
- **The codec refuses a format-8-or-older archive that carries a category row** (B2): no shipped writer can
  produce one; the `BackupViewModelTest.asFormatFour` helper strips the list and recomputes `dataSha256`.
- **`LegacyArchive` is untouched and `LAST_LEGACY_FORMAT` stays 7** (the plan's re-review N1); a format-8
  archive with a MANUAL asset, a break, health rows and the three 1.4 lists decodes unchanged under format 9.
- **One chooser for a new key's display** (B1's review N6): `CategoryBackfill.plan(rows, existing)`; the
  migration, the replace and the planner all use it, existing local and accepted archive rows winning.
- **Re-plan proofs read "applicable, zero INSERT", not "every verdict IDENTICAL"** (B2's review N4): a category
  row a built-in or a differently spelled local row holds re-plans SKIPPED (`CATEGORY_KEY_HELD` /
  `CATEGORY_IS_BUILT_IN`) on every re-plan; `docs/api/v1.md` Table 15 says so. The owner's release proofs and
  the dev→prod merge should use that reading.
- **The MCP figure in §9 is stale:** the suite is 338 tests (56 tools) since #83's tests, not 324; `tools/` is
  untouched by #74.
- **`clearInstall()` wipes `asset_category`** (B3's review B-1): connected suites that saved an asset with a
  category left catalog rows that `StoreIsEmpty` now counts; `EmptyStoreRestorePromptTest` and
  `SettingsBackupEntryTest` join the integrated gate.
- **Open, for the owner (not blocking the merge):** (a) two sentences for unexpected storage failures on the
  Categories screen, in the setup screen's form — P74-18 `Could not rename that category.` and P74-19
  `Could not delete that category.` — implemented only if ratified, else "silent on storage failure" stands
  (today a failed rename leaves the dialog open, a failed delete closes silently); (b) the ratified key rule
  does not strip zero-width or other Unicode format characters (a pasted one makes a second, identical-looking
  category) — one line before format 9 ships, a migration plus a format bump after; (c) the MCP's
  `import_merge` and `status` docstrings still say "format 1–8" / "fourteen tables" — a docstring-only
  follow-up outside #74 by R74-8.
- **Deferred tidy:** `SentenceSectionHeader` and `ConfirmDialog` move to `ui/components` later (B3's review N5).
- **Out-of-list files the task reviews accepted:** `ReferenceMigrationTest` (the new table subtracted as the v8
  tables are), the two version tests renamed and re-anchored (`theSchemaIsNineAndTheFormatIsNine`,
  `statusReportsNineAndNineAndFourNewCounts` or as the branch names them), `SettingsBackupEntryTest` (the
  required `onCategories`), `AppSmokeTest.clearInstall()` (wipes `asset_category`); a test-only `BackupInstall`
  harness in `core/src/test`; `CategoryRoundTripTest` (two JVM end-to-end cases from the whole-branch review).
- **The format-5 preserved-set restore** (`PreservedSetRestoreTest`) passes at the merge tip with the set staged
  on the emulator; its assumption fails, not the code, when the staged file is missing (an emulator restart
  loses it — re-push `noteNFC-backups/pre-2.7-20260918/`'s export to `/data/local/tmp/servicetag-proof-data.zip`).
- **Deferred to #73 (whole-branch review N7/N8):** `CategoryCatalog.resolve` has no caller yet; the dashboard's
  own category filter keeps a renamed category's old name until the dashboard is changed.
