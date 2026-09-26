# Phase 2B-2 — Physical asset model: design

Approved in conversation on 2026-09-15 after the Phase 2B-1 merge (`8b8b721`). Authorities above
it: D4 §4 (asset table), §13 (lifecycle), D5 §6 (seasonal activation), D12 §5/§16, the 2A and
2B-1 specs whose model this extends. Deviations from D4 are in §11.

## 1. Goal

"Can the app accurately model this equipment's physical identity, structure, location, lifecycle
and seasonal context?" After 2B-2 an asset carries its full metadata, can be part of another
asset, can be retired, can declare a season, and the app's navigation no longer pretends that
reading a tag is something you open a tab for.

## 2. Scope

**In 2B-2.** Room v4 (asset columns + parent FK); full asset fields and a grouped editor; the
identity plate and a DETAILS section that render them; retirement as data; parent/child
composition with COMPONENTS and "Part of"; season windows with exactly one effect (OUT OF SEASON
presentation); a category suggestion catalog with creation-time template hints; the Scan tab
retired from bottom navigation with the scan screen kept as a Settings utility; backup format 4
with formats 1–3 importing and a parent-before-child restore order.

**Not in 2B-2.** Schedules, reminders or any consumer of the season window beyond presentation
(Phase 3); attachments; supplies; a canonical equipment-type key (D4 §4 decision); roll-up of
children's ledgers or readings into the parent; nested tree browsing in the Assets list; reset
from template; the deferred 2B-1 minors unless a task already touches their code.

## 3. Global constraints

All of the 2A and 2B-1 spec §3 constraints, plus: category is free text and template selection is
creation-time assistance, never authority; hierarchy validation has one owner
(`AssetTree`) used by the use case, the picker and the backup codec (as 2B-1 did with
`derivedProblems`); season semantics are exact (§6) because Phase 3 inherits them; identities
stay at adapter boundaries.

## 4. Model changes (`:core.model.Asset`)

```kotlin
enum class AssetStatus { ACTIVE, ARCHIVED }        // RETIRED removed: retirement is data (§7)
data class Asset(
    …existing…,
    val manufacturer: String = "", val model: String = "", val serialNumber: String = "",
    val purchaseOn: String? = null, val inServiceOn: String? = null,     // ISO dates
    val purchasePriceMinor: Long? = null, val currency: String? = null,  // [A-Z]{3}
    val vendor: String = "", val location: String = "",
    val warrantyExpiresOn: String? = null, val warrantyNotes: String = "",
    val retiredOn: String? = null,                                       // non-null = retired
    val parentAssetId: AssetId? = null,
    val seasonStartMmdd: String? = null, val seasonEndMmdd: String? = null,   // "MM-DD"
)
val Asset.isRetired get() = retiredOn != null
```

**Purchase price ↔ minor units.** `purchasePriceMinor` is an integer count of the currency's
minor unit. Conversion has one owner, `core.model.Money`: `fractionDigits(code)` =
`java.util.Currency.getInstance(code).defaultFractionDigits` (a code that does not resolve, or
that reports a negative digit count, is `BadCurrency` whenever a price is present);
`parse(text, code): Long?` accepts `"123.45"`, `"123"`, `"1,234.5"` (grouping stripped, `.`
decimal), refuses more fractional digits than the currency has, and scales by `10^digits`;
`format(minor, code)` renders with exactly that many digits and the ISO code (`123.45 USD`,
`5000 JPY`). A price without a currency is a validation problem (`CurrencyRequired`); a currency
without a price is allowed.

`AssetStatus.RETIRED` is removed. No app version ever wrote it (there was no code path), so a
backup carrying it is treated as any unknown enum name: `BackupCorrupt`.

## 5. Hierarchy (`core.model.AssetTree`, pure)

- `fun wouldCycle(assets: Collection<Asset>, assetId: AssetId, newParentId: AssetId?): Boolean` —
  walks up from `newParentId` through existing `parentAssetId` links; true if it reaches
  `assetId` (self included) or if the chain is longer than the asset count (a pre-existing cycle
  in the input, which restore validation must reject).
- `fun descendants(assets, assetId): Set<AssetId>`; `fun children(assets, assetId)`; `fun
  parentsFirst(assets): List<Asset>` — a topological order (Kahn's algorithm) with roots first and
  a stable tie-break by id; throws if a cycle exists.
- Semantics (pinned by the owner): one parent, any depth, no cycles, reparenting allowed, a child
  is a full independent asset and stays independently scannable and taggable; parent deletion is
  refused while children exist; archiving a parent does not archive descendants; a child may exist
  without a tag; a parent's readings and service record contain only its own data.
- Use cases: `UpdateAsset` takes the new fields including `parentAssetId` and refuses with
  `AssetCycle(assetId, parentId)` when `wouldCycle`, and `NoSuchAsset` when the parent is unknown;
  `DeleteAsset` (new) refuses with `AssetHasChildren(ids)` — otherwise deletes with the schema
  cascades after the existing typed confirmation flow; `ArchiveAsset` unchanged (no cascade).
- Picker: the editor's "Part of" list = all assets minus self minus `descendants(self)`, archived
  included but marked.

## 6. Season windows (`core.model.Season`, pure)

- Both null = year-round. Otherwise both required (`SeasonValidation.BothOrNeither`), each
  `"MM-DD"` with a real month-day (`02-29` valid; `SeasonValidation.BadDate` otherwise).
- Inclusive boundaries. `start <= end` (string compare works for zero-padded MM-DD) is an
  ordinary window: `start <= today <= end`. `start > end` wraps the year: `today >= start ||
  today <= end`. `start == end` is a one-day season.
- **Feb 29 rule:** in a non-leap year a boundary of `02-29` behaves as `02-28`; `inSeason` takes
  the year from `today`, so the same asset never has an undefined season.
- `fun inSeason(startMmdd: String?, endMmdd: String?, today: LocalDate): Boolean` is the single
  owner; Phase 3's `inSeason(asset, T)` (D5 §6) will call it.
- The one effect in 2B-2: an asset out of season shows the D12 OUT OF SEASON status line (season
  family, `calendar_month` glyph) on its screen under the plate and as a badge on its Assets row.
  Nothing else reads the window.

## 7. Retirement

`retiredOn != null` is the canonical retired state; there is no lifecycle enum for it. **Retire**
is an action in the asset overflow: a dialog asks for the date (default today, backdating allowed)
and commits `RetireAsset(id, on)` on its own. Then, as a separate follow-on offer, a second dialog
asks "Log what happened?" with **Log replacement** / **Log note** / **Not now** — choosing an
event opens the entry route with `kind = REPLACEMENT` or `NOTE`; cancelling that entry never
undoes the retirement. **Unretire** clears `retiredOn`. A retired asset renders a RETIRED badge
(paused family) on its plate and its Assets row, sorts after active ones with archived, and is
excluded from the dashboard's CURRENT list like an archived one. Tags on a retired asset still
resolve (the asset screen shows the badge).

## 8. Category suggestions (`core.journal.CategorySuggestions`, compiled)

`Suggestion(label, suggestedTemplateKey?)`: Generator → `power_equipment`; Lawn mower →
`power_equipment`; Snowblower → `power_equipment`; UPS → `ups`; Battery → null; Inverter / charger
→ null; Solar charge controller → null; RO system → `ro_water`; Hot tub → `hot_tub`; HVAC → null;
Pump → null; Other → `generic`. The category field is free text with these as a dropdown of
suggestions (typing anything else is equally valid). **Hint rule:** on a new asset, picking a
suggestion pre-selects its template only while the user has not explicitly chosen a template
(`templateTouched == false` in the form state); once they have, category edits never change the
template. Editing an existing asset never shows or changes the template. Nothing reads the
category string after creation. No equipment-type key.
*Superseded in part by #74 (2026-09-26, `docs/superpowers/plans/2026-09-26-issue-74-durable-categories.md`):
a saved category is promoted to a durable catalog row and offered beside these suggestions, the
stored string is the catalog's spelling, and the hint matches by normalised key.*

## 9. Screens

**Asset editor** (`AssetEditScreen`, grown): sections IDENTITY (Name, Category with suggestions,
Manufacturer, Model, Serial number, Description), PLACEMENT (Location, Part of — picker, "None"
default; Season — "Year-round" switch, else two month-day pickers), PURCHASE (Purchase date,
In service date, Price + Currency [A-Z]{3} defaulted once from the device locale where Android
resolves one, Vendor), WARRANTY (Expires on, Notes), NOTES, and on a new asset only, TEMPLATE
(as today, with the hint rule). Dates via a date picker writing ISO strings. Problems under fields
(`AssetValidation`: name required; currency resolvable through `java.util.Currency` when a price is present, else the three-letter shape, and a price without a currency refused; price ≥ 0 and within the currency's fraction digits; dates parse; season both-or-neither
and valid; cycle → snackbar naming the parent).

**Asset detail**: plate cells become MODEL (manufacturer + model) / SERIAL / LOCATION / PURCHASED /
IN SERVICE / NFC TAG (six cells, 2×3; the category stays the eyebrow and is not repeated as a
cell); badges RETIRED, ARCHIVED, OUT OF SEASON as applicable;
"Part of <parent>" line under the plate, tap → parent; DETAILS section listing only set fields
(purchase date, price, vendor, warranty expiry with "expired" wording when past, warranty notes);
**COMPONENTS** section (always; "No components" when empty — "+ Add component" is how the first
child gets made, so it cannot be behind having one) (children: name, category, "N readings out of
range" from the child's own `LatestReadings`, tap → child; "+ Add component" creates a new asset
with Part of preset); overflow gains Retire / Unretire and Delete (refused with the children
named).

**Assets list**: flat; each row gains a subtitle "Part of <parent>" when applicable and the
RETIRED / OUT OF SEASON badges; sort active first, then retired, then archived, by name.

**Navigation**: `TopLevelRoutes = Dashboard, Assets`; the bottom bar has two items; `Route.Scan`
stays as a pushed destination reached from Settings → **Read / inspect tag** (the existing scan
screen, unchanged); the dashboard's "Scan a tag" empty-state action opens the same route. The
write flow from an asset is unchanged. Ambient dispatch is unchanged.

## 10. Data

**Room v4.** `asset` gains the §4 columns (all nullable or TEXT NOT NULL DEFAULT ''), `retired_on`,
`parent_asset_id` with FK → `asset(id)` **RESTRICT** and an index, `season_start_mmdd`,
`season_end_mmdd`. Because of the FK the migration recreates `asset` (create `_new_asset` from
`4.json`, copy the v3 columns mapping `status = 'RETIRED'` → `'ARCHIVED'` defensively, drop,
rename, recreate its indexes — as 2B-1 did for definitions; FK checks are off during `migrate`,
and `nfc_tag`/`external_link`/journal tables keep referencing `asset` by name). `MIGRATION_3_4`,
`4.json`, `Migration3To4Test` and a chained `Migration1To4Test`.

**Backup format 4.** `AssetDto` gains the fields with defaults; `FORMAT_VERSION = 4`; formats 1–3
decode. Validation: `parentAssetId` resolves in the file, `AssetTree.parentsFirst` succeeds (no
cycle), currency and price shape via `Money`, season rules, dates parse. **Restore order:** import inserts assets in
`parentsFirst` order regardless of the file's list order; a test imports a deliberately shuffled
file (children listed before parents) and asserts success and identical ids.

**Destructive full-replace deletion (single strategy).** With a RESTRICT self-FK, an unordered
or repeated `DELETE … WHERE parent_asset_id IS NOT NULL` can meet a child while its grandchild
still references it. Every full wipe of assets — the replace import, the debug Wipe, the test
`clearInstall` — deletes rows **children-first** by walking `AssetTree.parentsFirst(all).asReversed()`
and deleting each id individually inside the one transaction. The Room `AssetRepository.deleteAll`
implements exactly that (the adapter reuses the core authority; no ad hoc SQL), and
`ImportBackupReplace` relies on it. Proof: a three-level tree (root → child → grandchild) survives
export → wipe → import, and `deleteAll` on that tree succeeds.

## 11. Deviations recorded

- D4 §4 `status ACTIVE | ARCHIVED | RETIRED` → `RETIRED` removed; retirement is `retired_on`.
- D4 §13 "Retire asset: same as archive plus a suggested event" → retirement is independent of
  archive; the event is an optional follow-on that cannot undo it.
- D4 §4 "UI shows one level" → COMPONENTS shows direct children; "Part of" shows the direct
  parent; deeper levels are reached by tapping.
- D12 §16 bottom bar Dashboard · Assets · Scan → Dashboard · Assets (already recorded there).

## 12. Proof

**JVM `:core`.** `AssetTreeTest` (cycle via self, direct, transitive; pre-existing cycle
detected; `parentsFirst` roots first with shuffled input; descendants), `SeasonTest` (ordinary,
wrapping, one-day, boundaries inclusive, Feb 29 in leap and non-leap years, both-or-neither,
bad dates), `MoneyTest` (parse/format round trip for a 2-digit and a 0-digit currency; too many
fraction digits refused; unresolvable code refused when a price is present), `AssetUseCasesTest` additions (update with parent; cycle refused; unknown parent;
delete refused with children; retire/unretire; validation problems), `CategorySuggestionsTest`
(catalog, hints), `BackupCodecTest` (format 3 decodes; format 4 round trip; parent unknown;
cycle; shuffled import order), `BackupUseCasesTest` (shuffled file imports).
**JVM `:app`.** `Migration3To4Test`, `Migration1To4Test`, `AssetDaoTest` (RESTRICT on parent),
`RestoreProofTest` (a three-level tree survives export → wipe → import), `RoomRepositoriesTest` (`deleteAll` on root → child → grandchild succeeds children-first), `AssetViewModelsTest` (form sections, hint rule incl.
"explicit template not overridden", picker excludes descendants, components with out-of-range
counts, season badge from an injected today), `NavigationSmokeTest` updated (two tabs).
**Instrumented (automated).** `AssetModelDeviceProofTest`: parent with two children → COMPONENTS
and "Part of"; reparent one child under the other; delete parent refused naming children; archive
parent leaves children active; season window set → OUT OF SEASON with an out-of-window date
(inject `today` through the graph's clock in the test); category "RO system" pre-selects RO water
and an explicit template survives a category change; retire → badge, follow-on event cancelled →
still retired; Settings → Read / inspect tag opens the scan screen; bottom bar has two items; a
real 2B-1 install upgrades v3→v4 with rows intact. No manual rows.
**Exit criteria.** (1) the tree round-trips through backup with ids exact and a shuffled file
imports; (2) season state is correct on every boundary case in tests and shows on device; (3)
category hints never override an explicit template choice (test + device); (4) retirement is
independent of the optional event (test + device); (5) the Scan tab is gone and ambient dispatch
plus the utility route still reach the scan screen; (6) v1→v4 chain green and a real 2B-1
install upgrades on device; (7) the taxonomy grep stays clean.
