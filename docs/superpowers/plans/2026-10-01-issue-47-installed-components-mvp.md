# #47 — the Installed Components MVP: nested identity, current fitted state, install/remove/replacement history, direct SupplyItem linkage and aggregate composition: plan and briefs (rev 1, 2026-10-01)

> **Rev 1 folds in the owner's correction and rulings of 2026-10-01, recorded on #47** (the issue body on GitHub is
> updated): **aggregate installed composition** — an installed component may be made of one or more #15 SupplyItems
> with quantities (a battery pack = `4 × Battery X`) without those becoming child rows (C4, C9, C12, C16–C19, C20,
> C23, C26); the pointer is **`replacesId` on the successor**; **no `eventId` at all**; every owner ruling R47-1…R47-10
> and R47-12 is **DECIDED** (§6). §5's strings await ratification as **one block**.

> **HARD SCOPE (owner, binding across every brief):** "#47 MVP = canonical nested InstalledComponent identity +
> current fitted state + install/remove/replacement history + optional direct SupplyItem linkage + aggregate
> SupplyItem composition. Nothing more." An assembly is an installed component that contains installed components or
> is composed of SupplyItems — there is **no `InstalledAssembly`** and **no history table**. **Out:** stock, quantity
> on hand, reorder, procurement (#95); schedule material requirements, pack-vs-rebuild **planning**, batch replacement,
> stock-aware orchestration (#96 — #47 **represents** both compositions, the aggregate `4 × X` and the instance-level
> four child rows, and #96 plans which to do); resources on an installed component (#69 — only the owner identity a
> later `accepts` rule plugs into); ranged guidance (#88); NFC on an installed component (never); #86's whole-Asset
> succession (separate; the successor gets no installed components, R47-9); the health `PART` binding the 1.4 spec
> promised "for #47 later" (**not in #47**, R47-12); `schedule_supply_requirement` (#96, R47-10); a journal link (no
> `eventId`, R47-7); a stock decrement or automatic event from a composition (never).

> **Status: PLANNING ONLY, owner rulings DECIDED.** **Dispatch precondition:** §5 RATIFIED as one block. **Release
> line:** no version bump and no release — `versionName` / `versionCode` stay **1.6.0 / 19**; the vehicle is the
> owner's (the post-#98 fresh 1.0.0 on the train #47 → #69 → #16 → #98). #47 lands **Room schema 19 / backup format
> 19** (R47-5) on master, validated on the emulator only; master builds never go on a phone.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-01-issue-47/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-10-01-issue-47/audit.md`, every citation read on `1476e0d8`.
> The audit predates the composition ruling: **its counts are recomputed here** (§4's table), never copied. This
> planner re-read on the same base every site it cites beyond the audit. **Fourteen dispatches on one branch
> `issue-47`:** B1a, B1b, B2a, B2b, B2c, B3a, B3b, then **B4 ∥ (B6a → B6b → B6c)**, then B5, B7, B8 — each `<base>`
> the previous accepted tip; **B1a's `<base>` is master at dispatch** (today `1476e0d8`: 1.6.0 / code 19, Room schema
> 18 / backup format 18, MCP 84 tools, gitlink `7e0377a`) plus this plan's commit. One task review each, at most one
> bounded fix round each, one whole-branch review, the merge, one merged-tip gate.

**Goal:** one canonical **installed component** — a physical, serviceable part, assembly or position fitted inside an
Asset or inside another installed component — stored as **one row per fitted instance**: its Asset, an optional parent
row of the same Asset, a label, an optional **direct** SupplyItem ("this unit is an X"), an optional ordered
**composition** of SupplyItem entries with quantity and unit ("this unit is made of 4 × X"), an optional serial or lot,
an optional install date, a removal date once it leaves, the predecessor it replaced, a sibling order and notes.
Current state is the rows with no removal date; history is the closed rows, each keeping its own composition. Install,
remove, replace and edit are use cases, each one `uow.write`; a replace closes one row and inserts one, and nothing
else moves. Stored in Room schema 19, carried by backup format 19 through export, replace, merge and the Transfer Pack,
reachable over `/v1` and the MCP, and drawn by one section on asset detail. NFC, the journal, the scheduler, #86's
Replace and health are unchanged.

**Inputs:** issue #47 with the owner's binding 2026-09-29 clarification, the #69 amendment and the 2026-10-01
correction (`.superpowers/sdd/2026-10-01-issue-47/issue-47.md` is the pre-correction snapshot; the GitHub body is the
record); the audit; the #15 plan rev 1.2 with its §20 errata (`docs/superpowers/plans/2026-10-01-issue-15-supply-item-mvp.md`);
`docs/design/14-asset-model.md` (D14); `docs/api/v1.md`; `docs/release-proofs.md`; `docs/superpowers/planning-policy.md`.
Paths: `C/` = `core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` =
`core/src/test/kotlin/com/loosecannon/servicetag/core/`, `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`,
`T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`, `AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`,
`M/` = `tools/servicetag-mcp/`, `S/` = `tools/servicetag-schedules/`. Every `file:line` was read on `1476e0d8`.

## Global constraints

- **Reuse, never re-implement.** The persistence template is #15's, layer by layer (audit §2): the row is
  `asset_supply`'s shape (`A/data/room/entities/SupplyEntities.kt:63-90`) and **the composition is
  `supply_specification`'s** — an ordered child list with durable ids, replaced with its parent's write, nested in the
  parent's DTO and counted in the manifest beside it (`SupplyItem.specifications`, `C/model/SupplyItem.kt:20-49`;
  `BackupCodec.kt:285-288`, `:327`). The migration copies `MIGRATION_17_18` (`A/data/room/Migrations.kt:771-808`); the
  codec gate `BackupCodec.kt:470-482`; the merge section `MergePlanner.kt:535-626` (its child-id arm for
  specifications); the pack `TransferGraph.kt:62-63`, `:232-237`, `:314`; the return `ApplyBackupMergePlan.kt:313-316`;
  the guard `HeldWriteGuard.kt:148`, `:480-481`. The date rule copies the loan's (one internal shape function the use
  cases call with today and the content check without, `C/usecase/LoanCommands.kt:82-128`). The one-write replace
  copies #86's re-read inside the write (`C/usecase/ReplaceAsset.kt:212-226`). The phone reuses
  `SupplyItemPickerSheet` (`A/ui/supplies/SupplyItemPicker.kt:56-60`), `listRowsOf`
  (`A/ui/supplies/SupplyListViewModel.kt:53`), `SupplyLinkLine` (`A/ui/supplies/SupplyLinkLine.kt:33-39`) and
  `DateField` (`A/ui/asset/AssetEditScreen.kt:952-957`) as they are. Every write is one use-case call; no handler,
  view model or tool re-checks what a use case checks.
- **One schema step and one format step, forward-only, two new tables, one new list.** Room 18 → 19 is **two `CREATE
  TABLE`** (`installed_component`, `installed_component_composition`) and their indices — no `ALTER`, no column on an
  existing table, no backfill. Format 18 → 19 adds **one list** (`installedComponents`, each row carrying its
  `composition`) and **two manifest count keys**; an older archive decodes with none, and an older app refuses a
  format-19 archive as newer. No existing row's content changes, so **no older-archive merge exception** exists (audit
  §0.4). `LAST_LEGACY_FORMAT` stays 7. `18.json` is never edited.
- **The twin-pin rule, up front (#15 §20).** A shipped assertion moves only where §3's moving-pins table names it for
  the brief. A **twin** — an assertion outside the table pinning the same fact for the same reason (a count, a format
  literal, a table set, a tool count, a key position) — moves under the same rule and is listed in the report;
  anything else stops the brief. Every brief confirms its pin set with "Briefs — common"'s greps **before** editing.
  Expect 10–20 twins (#15 found about twenty, E-5, E-6).
- **The 2.6 tombstones are never touched.** `git diff <base> -- . ':!app/schemas' | grep -cE
  '^[-+].*(external_link|externalLinks)'` → 0.
- **API version stays 1, additive.** Every refusal is a stable `code`, plus `field` where one body key is at fault,
  in the shipped envelope; 422 = change this body, 409 = change another row first, 404 = no such row. Every new
  mapping is an arm of an exhaustive `when`, never an `else`. Unknown keys stay 400. **The API returns codes, not
  sentences.**
- **No inference.** Installing a component creates no applicability row, no event, no material line, no SupplyItem
  and no stock change; a composition entry decrements nothing and schedules nothing; applicability or a material line
  creates no component; nothing links or composes by matching names. A link or an entry exists only because a person
  (phone, API, MCP) chose it, or a row carrying it was copied verbatim (archive, merge, pack) — or, on a replace, the
  documented copy of the predecessor's direct link and composition when the caller names none (R47-17).
- **The fence (C30).** No column, field, key, route, tool, string or test about stock, quantity on hand, reorder,
  lead time or procurement (#95); schedule material requirements, kits, batch replacement or orchestration (#96); an
  attachment, reference, photo or document owned by a component (#69 — `AttachmentOwner` untouched); a range,
  interval or condition (#88); an NFC tag, binding, scan route or "write tag" action on a component; an event link;
  a carry-forward in #86's Replace; a health binding. A composition's quantity is **how much of X this unit is made
  of**, never an amount kept, used or due.
- **No personal data.** The owner's private Stage A/B data is never quoted, copied, counted into a fixture or loaded
  by a test. Fixtures are fictional ("Example UPS", "Example Battery Tray", "Example 12 V Battery", "Example RO
  System", manufacturer "Example Power Co."; a URL, if ever needed, under `example.invalid` — #47 needs none); no real
  name, host, serial or e-mail address in code, tests, docs or commits; home paths written `~`.
- **Strings.** Every user-visible string is ratified before it ships (§5, one block). The plan proposes the new ones
  (P47-1…) and the relabel set (P47-R1…R11) and names every reused one with its one home; the `message` texts are the
  G-list.
- **Tests.** JVM first (core, then app over the production router and the Room-backed `FakeGraph`); Compose
  instrumented tests only for what a composable draws or wires (**one new class**, §3); **no new device-boundary
  class** — #47 crosses no OS boundary. No test touches the network. Every counted RED is a real mutation run with
  `--no-build-cache --rerun-tasks`, its failing assertion quoted, reverted before the commit. **No
  rerun-until-green.** Device rows only for platform-only facts.
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump** (1.6.0 / 19). Commits: one
  casual lowercase subject line; no body, no trailers, no AI attribution. `tools/` never names a screen-driving tool.
  **Implementers never write ledgers.** No dependency is added. **Every `AppGraph` and `FakeGraph` edit lands in
  B1b–B3b** (C-1's lesson): B4, B5, B6a–c, B7 and B8 edit neither.
- **Schema 19 on master.** Master debug builds carry schema 19 under version name 1.6.0: emulator only, never a
  phone. The schema-19 release gate is written by B8 into `docs/release-proofs.md` (C29), run by the release that
  carries it.
- **Time boxes:** every brief 1 h target, 2 h hard stop; past 1 h with under half the rows green, stop and report.
  Fix rounds 45 min, findings' files only. **Mutation caps** per brief. **Review budget:** one task review per brief,
  at most one bounded fix round, a scoped re-review only for a substantive correctness finding, mechanical fixes
  (< ~50 lines) by controller inspection, one whole-branch review.

## 1. Scope

**HARD SCOPE (owner, 2026-10-01, binding):** "#47 MVP = canonical nested InstalledComponent identity + current fitted
state + install/remove/replacement history + optional direct SupplyItem linkage + aggregate SupplyItem composition.
Nothing more."

**In scope — the acceptance criteria, each on its contracts** (AC1–AC13 as the 2026-09-29 snapshot numbers them;
**AC-C** is the 2026-10-01 composition requirement):

| AC | the issue's words (abridged) | contracts |
|---|---|---|
| AC1 | an Asset contains serviceable fitted Components without turning them into Assets or NFC targets | C4 (a type of its own), C30.4 (no NFC diff) |
| AC2 | a Component nests under another to represent an assembly/position structure | C4 `parentId`, C6 self-FK, C10 graph, C16 install inside, row 37 |
| AC3 | a Component references a #15 SupplyItem without duplicating the catalog | C4 (`supplyId` and entries name the id only), C9 (keys pinned), C25 (identity read from the catalog) |
| AC4 | several positions may reference the same SupplyItem | C6 (non-unique `supply_id` indices), row 37 |
| **AC-C** | an installed component may be made of one or more SupplyItems with quantities, without child rows; several entries, same or different SupplyItems; the composition stays with its row; a replacement starts with its own; archived may stay, never enter | C4 `composition`, C5 `compositionProblems`, C6, C9–C12, C16–C19, C20, C23, C26, rows 4, 13, 21, 33–37 |
| AC5 | current state changes without erasing install/remove facts | C4 (no delete), C17, C18 |
| AC6 | replacing one position leaves unrelated Components unchanged | C18 (one write, whole-table snapshot compare, row 34) |
| AC7 | optional instance serial/lot metadata, not warehouse inventory | C4 `serialOrLot` (free text), C30 |
| AC8 | current state and history survive backup/restore/merge | C9–C14, rows 17, 30, 37 |
| AC9 | #69 can later attach instance resources to the Component owner identity | C4 (immutable id and asset; never deleted one by one), C20 (the route noun), §8 |
| AC10 | the multi-stage treatment fixture holds a fitted subcomponent only where instance history matters | row 38 |
| AC11 | ordinary recurring consumables stay SupplyItems with no Component row | row 38 |
| AC12 | manuals/specs stay on the SupplyItem; Component resources are instance-specific | C30 (no resource on a component), §8 |
| AC13 | ranged/conditional guidance is not fabricated here (#88) | C4 (no interval, range or condition field), C30 |

**Out of scope — #96, verbatim ("Explicitly deferred to #96"):** schedule→SupplyItem material requirement rows;
required/optional material quantities for maintenance; whole-pack-vs-rebuild service planning; batch replacement of
several positions; richer kit/assembly service workflows; stock/readiness projection; a single completion transaction
orchestrating several Component mutations plus stock usage; procurement lead-time overrides; advanced
schedule/material/on-hand dashboards. **Out — #95:** stock decrement, reorder, procurement. **Out — #69:**
"installation-specific knowledge … is Component-owned" — #69's to build; #47 adds no owner column and no resource.
**Out — #86:** "whole-Asset succession remains separate". **Out — #88**, **NFC**, **health `PART`**, **`eventId`**.

**Also out** (follow-up candidates): a "where fitted / where composed" list on the SupplyItem detail (the #15 fence at
`A/ui/supplies/SupplyDetailScreen.kt:44-45` holds); a phone reorder control (R47-11); a parent picker and moving a
component (R47-16: a move is remove plus install); deleting a component or an entry's history; a removal-date
correction (R47-15); per-unit history inside a composition (that is the instance-level form: child rows); copying a
removed pack's child positions into its replacement (#96); the schedules loader and the bundle tool; a
`command-shapes.json` entry (the PATCH is an overlay, R91-12's precedent); renaming the child-Asset API and MCP nouns
(until #98, R47-1); a version bump or release.

**Recorded limits (stated, not fixed):**
1. **No journal link (R47-7).** A replacement's journal fact is an ordinary REPLACEMENT event logged as today; the
   component and the event share the SupplyItem and the date, and nothing ties them.
2. **#86's Replace carries no installed components (R47-9).** They stay with the predecessor as its history.
3. **Removing or replacing a component with current children closes its subtree on the same date in the same write
   (R47-6).** A replacement starts with no children; with the instance-level form, a whole-pack replacement is one
   Replace of the pack and one install per new position. A composition needs no such step: it is one row's list.
4. **The naming residue until #98 (R47-1).** The phone reads "Installed components" and "Child assets"; the API and
   MCP keep `/v1/assets/{id}/components`, the `components` keys and `create_component` for child Assets, documented
   as such, beside the new `installed-components` nouns.
5. **A row removed or edited after an export re-plans `CONFLICT`** against that export (by id, every field and every
   composition entry compared, no UPDATE — the loan's accepted behaviour). An unedited export re-plans `IDENTICAL`.
6. **A component edited on two phones under one id is a `CONFLICT`** refusing the whole archive or Transfer Pack,
   a return pack included (#15 limit 4's rule); there is no difference view.
7. **A removal date is set once (R47-15).** Edit corrects the name, the direct link, the composition, the serial or
   lot, the install date and notes, on a current or a removed row; never a removal date, the Asset, the parent or
   `replacesId`.
8. **A composition entry has no history of its own**: editing a row's composition replaces its list (the row's
   `updatedAt` moves); per-unit fitted history is the instance-level form (child rows).
9. **A format-19 archive or pack is refused by every schema-18 build** as a newer format (forward-only).
10. **The MCP refuses the five component tools for a phone below schema 19 locally** (C24).
11. **What the emulator cannot observe** (§7): the schema 18 → 19 in-place upgrade of a signed build (the release's
    gate, C29), the phones (untouched), an on-device export (`/v1` has no export route).

## 2. Contracts

### Common (C1–C3)

- **C1, the words and the fence (audit §0.2, §1.3; R47-1 DECIDED).** Domain `InstalledComponent` /
  `InstalledComponentId` / `CompositionEntry`; tables `installed_component`, `installed_component_composition`; archive
  list `installedComponents` (each with a nested `composition`); manifest count keys `installedComponents` and
  `compositionEntries`; wire keys `parentId`, `supplyId`, `composition`, `quantity`, `unit`, `serialOrLot`,
  `installedOn`, `removedOn`, `replacesId`, `sortOrder`; routes `/v1/installed-components` and
  `/v1/assets/{id}/installed-components`; `MergeTable.INSTALLED_COMPONENTS`; MCP `list_installed_components`,
  `install_component`, `update_installed_component`, `remove_installed_component`, `replace_installed_component`
  (each docstring: "not `create_component`, which makes a child Asset"); phone words "Installed components" and, in
  its sentences, "component"; "Composition" for the entry list (§5). **Never a bare `component` / `components` on a
  new machine surface** (a child Asset in five shipped layers, audit §0.2). The eleven child-Asset phone strings become
  "Child asset(s)" (C28). Never "part", "assembly", "kit" or "position" as a type or key name.
- **C2, the codes.** New codes; every `message` awaits ratification with §5's G-list:

| code | status | field | when |
|---|---|---|---|
| `NO_SUCH_INSTALLED_COMPONENT` | 404 | — / `parentId` | the path id names no row (`NoSuchInstalledComponent`); with `field` `parentId`, the body's parent names none (`ParentMissing`) |
| `INSTALLED_COMPONENT_NAME_REQUIRED` | 422 | `name` | `NameRequired`: blank after trimming |
| `INSTALLED_COMPONENT_DATE_INVALID` | 422 | the key | `BadDate(field)`: `installedOn`, `removedOn` or `replacedOn` not ISO `YYYY-MM-DD` |
| `INSTALLED_COMPONENT_DATE_AFTER_TODAY` | 422 | the key | `AfterToday(field)`: later than the phone's today |
| `INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED` | 422 | `removedOn` / `replacedOn` / `installedOn` | `RemovedBeforeInstalled(field)`: a closing date before this row's or a current descendant's install date (C17); on an edit, an install date after the row's removal date |
| `INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET` | 422 | `parentId` | `ParentOnAnotherAsset` |
| `INSTALLED_COMPONENT_PARENT_REMOVED` | 409 | `parentId` | `ParentRemoved`: installing inside a removed row |
| `INSTALLED_COMPONENT_REMOVED` | 409 | — | `AlreadyRemoved`: removing or replacing a removed row |
| `COMPOSITION_QUANTITY_INVALID` | 422 | `composition` | `QuantityInvalid(index)`: an entry's quantity not a finite number above zero |
| `INSTALLED_COMPONENT_INVALID` | 422 | — | fallback for a validation naming no problem: unreachable, documented (`SUPPLY_ITEM_INVALID`'s precedent, `A/api/ApiJson.kt:780`) |

  Reused unchanged: `no_such_asset` (404, `OwnerMissing`); `NO_SUCH_SUPPLY_ITEM` (404, `field` `supplyId` for the
  direct link, `composition` for an entry — `SupplyItemMissing` / `EntrySupplyItemMissing(index)`);
  `SUPPLY_ITEM_ARCHIVED` (409, the same two fields — `SupplyItemArchived` / `EntrySupplyItemArchived(index)`,
  `A/api/ApiJson.kt:814`); the guard's 409 `asset_transferred_out`; `400 bad_request` for an unknown key (a PATCH
  naming `assetId`, `parentId`, `removedOn` or `replacesId` is the decoder's 400) or a malformed body; the MCP's local
  `APP_SCHEMA_TOO_OLD` (C24). An edit that changes nothing answers **200 with the stored row** and writes nothing
  (`Unchanged`, never a code — E-13's precedent).
- **C3, the wire shapes.** Additive, API version 1; every answer reuses the archive's row DTO (C9):

| surface | shape |
|---|---|
| `GET /v1/assets/{id}/installed-components` | the **27th** asset sub-resource: `{installedComponents: [InstalledComponentDto], supplyItems: [SupplyItemDto]}` — every row of the asset, current and removed, by id, each with its `composition`, and each SupplyItem the rows and entries name once, by name casefolded then id (`A/api/SupplyDtos.kt:38-39`'s shape); a held asset reads as any other |
| `POST /v1/installed-components` | `{assetId, parentId?, name, supplyId?, composition?: [{id?, supplyId, quantity, unit?}], serialOrLot?, installedOn?, notes?, sortOrder?}`; absent text `""`, absent date unknown, absent `composition` none, absent `sortOrder` appended (C16) → 201 `{installedComponent}` |
| `GET /v1/installed-components/{id}` | `{installedComponent}` |
| `PATCH /v1/installed-components/{id}` | an **overlay**: `{name?, supplyId?, composition?, serialOrLot?, installedOn?, notes?, sortOrder?}`; **absent or `null` = unchanged**; **a given `""` clears** `supplyId`, `installedOn`, `serialOrLot`, `notes`; a given `composition` is **the whole ordered list** (an entry sent with its `id` keeps it when this row owns it), **`[]` empties it**; `name` never blank → 200 `{installedComponent}` (no-op: 200, nothing written) |
| `POST /v1/installed-components/{id}/remove` | `{removedOn}` (required) → 200 `{installedComponent, closed: [InstalledComponentDto]}` — every current descendant the write closed (R47-6), by id |
| `POST /v1/installed-components/{id}/replace` | `{replacedOn, name?, supplyId?, composition?, serialOrLot?, notes?}`; `replacedOn` required; absent or `null` `name`, `supplyId` or `composition` = **the predecessor's** (the composition copied with fresh entry ids, R47-17); `""` `supplyId` = none, `[]` composition = none; absent `serialOrLot` / `notes` = `""` → 201 `{installedComponent: <the new row>, replaced: <the closed row>, closed: [...]}` |
| no `DELETE` | nothing deletes a component; any other verb is the shipped 405, any other sub-path the shipped 404 |
| `GET /v1/status` | `schemaVersion` 19, `backupFormatVersion` 19; `counts` gains `installedComponents` and `compositionEntries` |
| `data.json` | `installedComponents[]` appended last; each row every key, nulls written explicitly, `composition` in `(sortOrder, id)` order |

### B1a — the domain, the tree and the shape rules (C4–C5)

- **C4, the domain and the port (audit §2.1, §2.2, §4; R47-2, R47-3, R47-8, R47-11, R47-17).** In
  `C/model/InstalledComponent.kt` (new); `InstalledComponentId` beside `SupplyId` (`C/model/Ids.kt:36-37`):

  ```kotlin
  @JvmInline
  value class InstalledComponentId(val value: String)

  data class InstalledComponent(               // one fitted instance; an aggregate root with its composition
      val id: InstalledComponentId,
      val assetId: AssetId,                    // immutable after insert
      val parentId: InstalledComponentId?,     // same asset; immutable after insert
      val name: String,                        // the label: "Battery tray", "Position 3", "Alternator"
      val supplyId: SupplyId?,                 // direct identity: this unit IS an X (R47-3)
      val composition: List<CompositionEntry>, // aggregate: this unit is MADE OF these; (sortOrder, id) order
      val serialOrLot: String,                 // "" = none, the Journal.kt convention
      val installedOn: String?,                // ISO YYYY-MM-DD; null = not recorded (R47-8)
      val removedOn: String?,                  // ISO YYYY-MM-DD; null = current
      val replacesId: InstalledComponentId?,   // the predecessor; set by a replace at insert, immutable (R47-2)
      val sortOrder: Int,                      // among siblings (R47-11)
      val notes: String,
      val createdAt: Long, val updatedAt: Long,
  ) { val isCurrent: Boolean get() = removedOn == null }

  data class CompositionEntry(val id: String, val supplyId: SupplyId, val quantity: Double,
                              val unit: String, val sortOrder: Int)   // a child row with a durable id
  ```

  No constructor defaults. **Direct link and composition are independent** (a pack may name its pack SKU and be
  `4 × X`; either, both or neither); entries may name the same or different SupplyItems, and one SupplyItem twice.
  No price, stock, interval, tag, attachment, health or event field (C30). Port in `C/ports/Repositories.kt` after
  `AssetSupplyRepository` (`:377-391`):

  ```kotlin
  interface InstalledComponentRepository {
      suspend fun get(id: InstalledComponentId): InstalledComponent?
      suspend fun forAsset(assetId: AssetId): List<InstalledComponent>   // current and removed, by id
      suspend fun all(): List<InstalledComponent>                       // by id
      suspend fun insert(row: InstalledComponent)                       // with its composition
      suspend fun update(row: InstalledComponent)                       // the row whole; its composition replaced
      fun observeForAsset(assetId: AssetId): Flow<List<InstalledComponent>>
  }
  ```

  **No `delete` and no `deleteAll`:** a row leaves only by its Asset's CASCADE (`DeleteAsset`, the replace wipe,
  `ReturnScope`'s removal), and an entry only with its row or by its row's `update` — the E-3 style break.
- **C5, the tree and the shape rules (audit §3, §4; R47-14).** **`C/model/InstalledComponentTree.kt`** (new, typed;
  `AssetTree` untouched): `parentsFirst(rows)` (Kahn, roots first, ties by id, a row whose parent is outside the
  collection a root, throws on a cycle — `AssetTree.kt:51-75`'s algorithm); `descendants(rows, id)`; the sibling
  order `(sortOrder, name casefolded, id)`; `current(rows)` — the current rows as a depth-annotated pre-order list;
  `history(rows, id)` — the instances at `id`'s position, newest first, following `replacesId` back, bounded by the
  row count; `successorOf(rows, id)` — the row whose `replacesId` is `id`; `removedUnreplaced(rows)` — removed rows
  with no successor, by `(removedOn descending, name casefolded, id)`.
  **The shape rules,** in `C/usecase/InstalledComponentCommands.kt` (new), internal, `loanProblems`' contract
  (`LoanCommands.kt:82-90`): `installedComponentProblems(name, installedOn, removedOn, today: LocalDate? = null)` —
  `NameRequired`, `BadDate(field)`, `AfterToday(field)` only with a today, `RemovedBeforeInstalled("removedOn")`;
  and `compositionProblems(entries)` — `QuantityInvalid(index)` for a quantity not finite or not above zero. Every
  problem collected. The use cases pass their `Today`; the content check (C10) passes none.

### B1b — Room schema 19 (C6–C8)

- **C6, the two tables (audit §2.3; H1; R47-3).** `A/data/room/entities/InstalledComponentEntities.kt` (new), the
  `AssetSupplyEntity` / `SupplySpecificationEntity` shapes (`SupplyEntities.kt:40-90`):
  - `installed_component(id TEXT PK, asset_id TEXT NOT NULL → asset(id) ON DELETE CASCADE, parent_id TEXT →
    installed_component(id) ON DELETE CASCADE, name TEXT NOT NULL, supply_id TEXT → supply_item(id) ON DELETE
    RESTRICT, serial_or_lot TEXT NOT NULL, installed_on TEXT, removed_on TEXT, replaces_id TEXT, sort_order INTEGER
    NOT NULL, notes TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`; indices on `asset_id`,
    `parent_id`, `supply_id` (non-unique, AC4) and **`UNIQUE(replaces_id)`** (one successor per predecessor; SQLite
    admits any number of NULLs). `replaces_id` is **soft** — no FK (the R79-4 shape,
    `A/data/room/entities/ServiceCaseEntities.kt:13-14`).
  - `installed_component_composition(id TEXT PK, component_id TEXT NOT NULL → installed_component(id) ON DELETE
    CASCADE, supply_id TEXT NOT NULL → supply_item(id) ON DELETE RESTRICT, quantity REAL NOT NULL, unit TEXT NOT
    NULL, sort_order INTEGER NOT NULL)`; indices on `component_id` and `supply_id` (non-unique).
  - **Every self- and child FK is CASCADE, never RESTRICT (H1):** SQLite checks RESTRICT the moment a parent row is
    deleted, so a RESTRICT `parent_id` could refuse a parent mid-cascade while its child remains; the paths are
    `DeleteAsset`, `ReturnScope`'s removal (`ApplyBackupMergePlan.kt:187`) and the replace wipe
    (`ImportBackupReplace.kt:191`). Row 7 decides it on Room. The two `supply_id` RESTRICTs never fire: SupplyItems are
    archive-only and the replace wipe deletes assets before the catalog (`:191`, `:199`). No SQL `CHECK`.
  - DAO `A/data/room/dao/InstalledComponentDao.kt`, the `SupplyItemDao` aggregate shape (`SupplyDaos.kt`, the
    child list replaced inside the upsert's transaction) for `insert` / `update`, and `asset_supply`'s
    (`SupplyDaos.kt:79-101`) for the reads; repository and mapper beside the supply ones; `AppDatabase` registers both
    entities and the DAO (`A/data/room/AppDatabase.kt:94-98`, `:125-126`); `AppGraph` wires `val installedComponents:
    InstalledComponentRepository` **unguarded** (B2c wraps it, C14) beside `:364-365`.
- **C7, `MIGRATION_18_19` and `19.json`.** After `MIGRATION_17_18` (`Migrations.kt:774`), its KDoc in that one's
  shape: two `CREATE TABLE` and six `CREATE [UNIQUE] INDEX` statements **copied verbatim from the exported `19.json`**
  — no `ALTER`, `UPDATE`, `INSERT` or `DEFAULT`. Registered in both chains (`A/di/AppGraph.kt:268`,
  `T/data/room/MigrationTestSupport.kt:68`). `AppDatabase` `version = 19` (`:98`); `AppGraph.SCHEMA_VERSION = 19`
  (`:1063`); `18.json` unchanged.
- **C8, the chain pins and the constraint proof.** `T/data/room/Migration18To19Test` (new, on `Migration17To18Test`'s
  shape); `V19_TABLES = setOf("installed_component", "installed_component_composition")` beside `V18_TABLES`
  (`MigrationTestSupport.kt:225`); the six whole-chain table-set pins (`Migration10To11Test` … `Migration14To15Test`,
  `ReferenceMigrationTest`) gain `V19_TABLES`. `T/data/room/InstalledComponentDaoConstraintTest` (new, on
  `SupplyDaoConstraintTest`'s shape) proves H1 on a **three-deep** tree carrying composition entries at every depth,
  through the Asset delete **and** `assets.deleteAll()`, plus both RESTRICTs, the unique `replaces_id`, the parent FK
  and the composition list replaced by `update` (row 7).

### B2a — backup format 19 (C9–C11)

- **C9, the DTOs (audit §2.4).** In `C/backup/BackupFormat.kt` after `AssetSupplyDto` (`:655-663`), every column in
  column order, **no defaults**: `CompositionEntryDto(id, supplyId, quantity: Double, unit, sortOrder)` and
  `InstalledComponentDto(id, assetId, parentId: String?, name, supplyId: String?, composition:
  List<CompositionEntryDto>, serialOrLot, installedOn: String?, removedOn: String?, replacesId: String?, sortOrder,
  notes, createdAt, updatedAt)` — the composition **nested** as `SupplyItemDto.specifications` is. `BackupData`
  (`:666-726`) appends `installedComponents: List<InstalledComponentDto> = emptyList()` **last**.
- **C10, the codec.** In `C/backup/BackupCodec.kt`:
  1. `FORMAT_VERSION = 19` (`:166`); `internal const val FIRST_INSTALLED_COMPONENT_FORMAT = 19` beside
     `FIRST_SUPPLY_FORMAT` (`:210`); the KDoc gains a "Format 19 adds one list, and no upgrade" paragraph.
  2. **The ≤ 18 gate** after #15's (`:470-482`): an archive below 19 carrying a non-empty `installedComponents` is
     `BackupCorrupt` naming the list.
  3. **Sort** (`:280-288`): rows by id, each `composition` by `(sortOrder, id)` (the specifications' rule). **Counts**
     (`:320-328`): `"installedComponents"` and `"compositionEntries"` (the sum of the lists, as
     `"supplySpecifications"` is) → **31** manifest keys (E-4's lesson: count keys).
  4. **Graph** (after `:922-948`): row ids unique, and **entry ids unique across every row** (the child-id pattern);
     `assetId` in `assets`; a non-null `parentId` names a row in the file of the **same asset**; every non-null
     `supplyId` and every entry's `supplyId` names a SupplyItem in the file (archived is valid); a non-null
     `replacesId` names a row in the file of the same asset, not itself, and **that row is removed**; no two rows hold
     one `replacesId`; **a current row's parent is current**; no parent cycle (`InstalledComponentTree.parentsFirst`
     throwing → `BackupCorrupt("installedComponents: cycle")`, `:563-569`'s shape); following `replacesId` never
     returns to a row.
  5. **Content** (`C/backup/BackupContentCheck.kt:140-142`, a `checkInstalledComponents` beside `checkSupplies`):
     `installedComponentProblems(name, installedOn, removedOn)` with no today, and `compositionProblems(entries)`,
     through the shipped `refuse` helper naming the list, the row and the entry.
- **C11, export and replace import.** `readSnapshot` (`C/usecase/ExportBackupSet.kt:169-205`) reads
  `installedComponents.all()`; `BackupRepositories` (`:136-162`) and `ExportBackupSet`'s constructor (`:94-105`) gain
  the port. `ImportBackupReplace` gains the port: **the wipe adds nothing** — `assets.deleteAll()` (`:191`) CASCADEs
  the rows and their entries before the catalog wipe (`:199`), whose comment (`:194-198`) gains one sentence; **the
  write**, straight after the applicability rows (`:218`): `InstalledComponentTree.parentsFirst(rows).forEach {
  installedComponents.insert(it) }`. **The pack class line lands here** (E-7: `TransferTableClassificationTest` pins
  every `BackupData` list): `"installedComponents" to TransferTableClass.ASSET_OWNED` (`TransferGraph.kt:62-63`).
  **Constructor plumbing (C-1):** B2a owns the `AppGraph` lines, `FakeGraph`, `CT/testing/BackupInstall.kt` and every
  test site `git grep -nE 'ExportBackupSet\(|ImportBackupReplace\(|BackupRepositories\('` names — **10, 9 and 5 on
  `1476e0d8`** — arguments only. **Split clause:** past a 1 h dispatch estimate, C11 and row 17 go to **B2a2**.

### B2b — the merge (C12)

- **C12, the table and the rules (audit §2.5, §3).** In `C/merge/`:
  - **Table.** `MergeTable` appends `INSTALLED_COMPONENTS` after `ASSET_SUPPLIES` (`MergePlan.kt:80-84`); the KDoc
    gains a "#47 appended …" paragraph. **The composition has no table of its own**: it is compared with its row, as
    specifications are with their SupplyItem.
  - **Position.** A new section **after applicability (`MergePlanner.kt:572-626`) and before profiles (`:627`)**:
    the assets (`:389-427`) and SupplyItems (`:535-571`) are decided by then; nothing planned later names a component.
  - **Parents first.** Decided in `InstalledComponentTree.parentsFirst(…)` order — the asset loop's shape
    (`:405-427`); the file is acyclic (C10), so it cannot throw here.
  - **Arms, in order** (by id, every field and the ordered composition compared, **no UPDATE**):
    `IDENTICAL` when `dto.ordered() == local.toDto().ordered()`; `CONFLICT CONTENT_DIFFERS` when a local row differs
    (a composition-only difference included); `CONFLICT CHILD_ROW_ID_TAKEN` when an entry id is held by another
    component here or claimed earlier in this pass (the specification arm, `:535-571`); `CONFLICT
    OWNER_NOT_AVAILABLE` naming the asset when `!assetAvailable(assetId)`, the SupplyItem when the direct `supplyId`
    **or any entry's** is not available, the parent when a non-null `parentId` is neither local nor accepted earlier
    in this pass; **`CONFLICT INSTALLED_COMPONENT_REPLACEMENT_TAKEN`** (new, appended last to `MergeReason`, KDoc in
    `SUCCESSION_TAKEN`'s shape, `MergePlan.kt:337-342`; detail = the holder) when a non-null `replacesId` is held by
    another local row or one accepted earlier — what the unique index would refuse at apply; else `INSERT`.
  - **Unreachable, so no arm** (the codec holds each per file; a local parent that is `IDENTICAL` carries the file's
    asset and removal date): a parent on another asset, a current row under a removed parent, a cycle. **No
    older-archive exception** (audit §0.4): an archive below 19 names no component, so every local component is not
    decided and every older row compares as before (row 23).
  - **Snapshot, writes, report (C-1).** `MergeSnapshot` (`MergePlan.kt:461-489`) and `MergeWrites` (`:421-427`) gain
    a defaulted `installedComponents` list; `mergeSnapshotOf` reads the port, its callers pass it
    (`ApplyBackupMergePlan.kt:168-173`, `BuildBackupMergePlan.kt:106`); `MergeReport` (`:503-534`, `:612`) gains
    `installedComponents: MergeTally` last; `A/api/ApiDtos.kt`'s report rows gain the one key at their end.
  - **Held (M2).** The writes join the M2 list (`MergePlanner.kt:1324-1340`) through `TransferOwnership.of(…)`.
  - **Apply.** `ApplyBackupMergePlan`: `fresh.writes.installedComponents.forEach { installedComponents.insert(it) }`
    **straight after the applicability rows (`:203`)** — after assets and SupplyItems, parents-first.
  - **Constructor plumbing (C-1):** the test sites `git grep -nE 'ApplyBackupMergePlan\(|BuildBackupMergePlan\('`
    names — **10 and 8 on `1476e0d8`** — arguments only.

### B2c — the Transfer Pack and the held guard (C13–C14)

- **C13, the pack (audit §0.7, §2.6; R47-4).** `TransferGraph.select` (`:232-264`): `installedComponents =
  carry("installedComponents", data.installedComponents) { it.assetId in selected }` — every row of a carried asset,
  current and removed, each with its composition: **history travels whole**; `supplyIdsInUse` (`:235-237`) gains
  every carried row's direct `supplyId` **and every entry's** ("by naming row", `:232-233`). **`retain`**
  (`:306-316`): `filterNot { it.assetId in heldIds }`. No `entangledRefs` change (a component names only its asset,
  rows of that asset and SupplyItems, never dropped). `TransferOwnership.of(component: InstalledComponent) =
  listOf(OwnerRef.OfAsset(component.assetId))` beside `:113`. **The return** (`ReturnScope.of`,
  `ApplyBackupMergePlan.kt:298-317`): `installedComponents = full.installedComponents.filterNot { it.assetId in
  returning }` beside `:316` — the pack's rows plan `INSERT`, the returning assets' delete (`:187`) cascades the old
  rows and entries; a component removed, replaced or recomposed on the borrowing phone lands instead of refusing the
  return (row 30; #15's C-4 lesson).
- **C14, the guard.** `HeldWriteGuard.installedComponents(port)` beside `:148`, a `GuardedInstalledComponents` beside
  `:480-481`, checking the row written **and** the stored row an update replaces (its composition rides the row). The
  count words "seventeen" (`HeldWriteGuard.kt:69`, `:101`, `:257`; `A/di/AppGraph.kt:294`, `:361`) say **eighteen**.
  No `HeldWriteGuard` constructor changes.

### B3a / B3b — the use cases (C15–C19)

- **C15, commands, problems and results (audit §4; B3a).** In `C/usecase/InstalledComponentCommands.kt`:
  - `CompositionInput(id: String?, supplyId: SupplyId, quantity: String, unit: String)` — `quantity` as the person
    typed it, parsed by the use case (`ConsumableInput`'s convention, `C/usecase/EventCommands.kt:34`, `:250`);
  - `InstallComponentCommand(assetId, parentId?, name, supplyId?, composition: List<CompositionInput>, serialOrLot,
    installedOn?, notes, sortOrder: Int?)`;
  - `ReplaceComponentCommand(replacedOn, name, supplyId?, composition: List<CompositionInput>, serialOrLot, notes)` —
    the caller fills `name`, `supplyId` and `composition` from the predecessor when its person gave none (C3,
    R47-17); entry ids sent are ignored (every successor entry is minted fresh);
  - `UpdateInstalledComponentCommand(name, supplyId?, composition, serialOrLot, installedOn?, notes, sortOrder: Int)` —
    the whole editable set; **no `assetId`, `parentId`, `removedOn` or `replacesId`** (immutable by construction);
  - `sealed interface InstalledComponentProblem`: `OwnerMissing`, `ParentMissing`, `ParentOnAnotherAsset`,
    `ParentRemoved`, `SupplyItemMissing`, `SupplyItemArchived`, `EntrySupplyItemMissing(index)`,
    `EntrySupplyItemArchived(index)`, `QuantityInvalid(index)`, `NoSuchInstalledComponent`, `AlreadyRemoved`,
    `NameRequired`, `BadDate(field)`, `AfterToday(field)`, `RemovedBeforeInstalled(field)`, `Unchanged` — each KDoc
    naming its C2 code (`AssetSupplyProblem`'s shape, `AssetSupplyCommands.kt:28-50`);
  - `sealed interface InstalledComponentResult { Ok(row, replaced: InstalledComponent?, closed: List<…>) ;
    Refused(problems: List<InstalledComponentProblem>) }`.

  **Two kinds of check, in order.** Command-only problems (C5's rules, with today) are collected **before any
  transaction**; a refusal opens none. State problems are checked **inside the one `uow.write`, before its first
  write** (`ReplaceAsset.run`'s shape) — a state refusal returns having written nothing. **Entry ids** follow
  `SaveProfile`'s rule (`C/usecase/SaveProfile.kt:88`, `ownConsumableIds`): an entry `id` is kept only when the stored row owns it,
  otherwise minted; `sortOrder` is the list position. **The archived rule (R47-3):** on an edit, a direct `supplyId` naming
  an archived SupplyItem is accepted **only when the stored direct link is that SupplyItem**, and an entry naming one
  **only when the stored composition already names it** — so an existing link or composition may keep it; install and
  replace never accept one. A held asset's write throws `AssetTransferredOut` at the guarded port, after every check.
- **C16, `InstallComponent` (B3a).** State steps, one refusal each, in order: `OwnerMissing`; with a `parentId`,
  `ParentMissing`, `ParentOnAnotherAsset`, `ParentRemoved`; with a `supplyId`, `SupplyItemMissing`,
  `SupplyItemArchived`; then every entry's `EntrySupplyItemMissing` / `EntrySupplyItemArchived` (collected). Stores the
  trimmed text, the dates as given (`installedOn` may be null), `removedOn = null`, `replacesId = null`, the
  composition with minted ids, and `sortOrder` = the command's or **one more than the greatest among this parent's
  current children (or the asset's current top-level rows), 0 when none** (R47-11). One insert.
- **C17, `RemoveInstalledComponent(id, removedOn)` (B3a).** Shape: `BadDate`, `AfterToday`. State:
  `NoSuchInstalledComponent`, `AlreadyRemoved`, then `RemovedBeforeInstalled("removedOn")` when the date is before
  this row's `installedOn` **or any current descendant's**. One `uow.write`: the row with `removedOn` and `updatedAt
  = now`, and **every current descendant** closed on the same date and stamp (R47-6). Each closed row **keeps its
  composition** untouched. Nothing else is written; nothing is deleted (AC5).
- **C18, `ReplaceInstalledComponent(id, cmd)` — AC6 (B3b).** Shape: `NameRequired`, `BadDate("replacedOn")`,
  `AfterToday("replacedOn")`, `QuantityInvalid`. State: `NoSuchInstalledComponent`, `AlreadyRemoved`, the successor's
  direct and entry SupplyItems as C16 (**an archived one is refused even when the predecessor names it**: the
  successor's links are new), `RemovedBeforeInstalled("replacedOn")` as C17. One `uow.write`: **insert the successor**
  — a new id, the predecessor's `assetId`, `parentId` and `sortOrder`, the command's name, link, composition (fresh
  entry ids), serial or lot and notes, `installedOn = replacedOn`, `replacesId = predecessor.id`, current; **close the
  predecessor** — `removedOn = replacedOn`, `updatedAt = now`, **its composition unchanged**; close every current
  descendant (R47-6: the successor starts with no children). **Every other row of every table is byte-equal before
  and after** (row 34). No event, line, applicability row or stock is written.
- **C19, `UpdateInstalledComponent(id, cmd)` (B3b).** Shape: C5's rules over the command's name and `installedOn`
  with the stored `removedOn`, and the composition's quantities, with today (an install date after the removal date
  is `RemovedBeforeInstalled("installedOn")`). State: `NoSuchInstalledComponent`; the direct link and entries under
  C15's archived rule. Current or removed rows alike (corrections, R47-15). Equal after trimming, the composition
  compared with its kept ids → `Unchanged`, nothing written. Otherwise one update (the composition replaced whole),
  `updatedAt = now`.
- **Wiring.** The four use cases in `A/di/AppGraph.kt` beside #15's, each with the guarded port and what it needs
  (`AssetRepository`, `SupplyItemRepository`, `UnitOfWork`, `IdGenerator`, `Clock`, `Today`); `FakeGraph` mirrors.
  **B3a wires `InstallComponent` and `RemoveInstalledComponent`; B3b the other two — the plan's last `AppGraph` /
  `FakeGraph` edit.**

### B4 — the API (C20–C22)

- **C20, the routes (audit §8; C3).** New `A/api/InstalledComponentDtos.kt` and `InstalledComponentHandlers.kt`, the
  `SupplyHandlers` collaborator shape (`A/api/SupplyHandlers.kt:48-61`, built through `constructor(graph)`, reached as
  `handlers.installedComponents.*`), added to `ApiHandlers`' primary constructor beside `supplies`
  (`A/api/ApiHandlers.kt:175`, `:193`) — **its test construction sites move, arguments only** (12 at #15's merge, E-6;
  B4 counts them with `git grep -nE 'ApiHandlers\('` first). Routed in `A/api/ApiRouter.kt`: the sub-resource beside
  `:245`; `POST /v1/installed-components`; `GET|PATCH …/{id}`; `POST …/{id}/remove` and `…/replace` beside the supply
  rows (`:267-291`). **PATCH is an overlay assembled in the handler** from the stored row
  (`SupplyHandlers.update`, `:91-109`): each given key replaces, absent or `null` keeps, **`""` clears** `supplyId`,
  `installedOn`, `serialOrLot`, `notes`; a given `composition` is the whole list, absent sends the stored entries back
  with their ids, `[]` empties; `name: ""` is `NameRequired`. Every clearable field clears by `""`, so **no raw-key
  tri-state reader** (`A/api/ScheduleForms.kt:96-118` unused). Replace's absent `name` / `supplyId` / `composition` are
  filled from the stored predecessor in the handler. `Unchanged` → 200 with the stored row.
- **C21, the mappers.** `installedComponentRefusal(problems)` in `A/api/ApiJson.kt` after #15's block (`:772-827`):
  one exhaustive `when` over the first problem, `problems` naming every problem; `OwnerMissing` → `no_such_asset`;
  the SupplyItem arms → `NO_SUCH_SUPPLY_ITEM` / `SUPPLY_ITEM_ARCHIVED` with `field` `supplyId` or `composition`;
  `Unchanged` → the shipped 500 `internal` (never emitted, `:828`); `AssetTransferredOut` → the shipped 409.
- **C22, status, history and the wire document.** `GET /v1/status` `counts` gains `"installedComponents"` and
  `"compositionEntries"` beside `ApiHandlers.kt:227-228`. The router KDoc (`ApiRouter.kt:98`) reads **"Seventy-seven
  path shapes over ninety-five method-and-path rows"** (six rows over five shapes) with a "#47 added …" paragraph
  after `:166-172`. `docs/api/v1.md`: "Installed components (#47)" after "Asset supplies (#15)" (`:1563`) — C3's shapes,
  the composition (a quantity is what the unit is made of, never stock), the overlay's clearing rule, replace and
  remove, the subtree rule, and that `/v1/assets/{id}/components` and the `components` keys are **child Assets**; a
  "#47 codes" table after "The #15 codes" (`:2000`); the two status keys; the import range **1–18 → 1–19** (`:209`,
  `:2110`); the tally count **22 → 23** (`:582`) and a **"Table 23 (format 19, #47)"** paragraph after `:2301`. No
  `command-shapes.json` entry. **Split clause:** past 1 h, C22's `v1.md` part and row 45 go to **B4b** in the lane.

### B5 — the MCP (C23–C24)

- **C23, the tools.** In `M/src/servicetag_mcp/server.py`, appended to `TOOL_NAMES` (`:78`):
  `list_installed_components(asset_id)`; `install_component(asset_id, name, parent_id=None, supply_id=None,
  composition=None, serial_or_lot=None, installed_on=None, notes=None, sort_order=None)`;
  `update_installed_component(installed_component_id, …)` (overlay: only given arguments are sent; `""` clears the
  four clearable fields, `composition` replaces the whole list and its docstring says to pass each kept entry's `id`,
  `[]` empties it — no `clear_fields`); `remove_installed_component(installed_component_id, removed_on)`;
  `replace_installed_component(installed_component_id, replaced_on, name=None, supply_id=None, composition=None,
  serial_or_lot=None, notes=None)` — **five tools, 84 → 89**; `composition` is `list[dict[str, Any]]` of `{id?,
  supplyId, quantity, unit?}`. No delete tool. Docstrings say "not `create_component`", name the subtree rule, and say a
  composition quantity is never stock; no #95/#96 word.
- **C24, the gate, documents and pins.** `_MIN_INSTALLED_COMPONENT_SCHEMA_VERSION = 19` after `:225`;
  `_require_installed_component_schema(tool)` over `_require_tool_schema` (`:281`), beside `:331`; **all five** call
  it before any request. `import_merge`'s docstring (`:1248`, `:1292`): "1–18" → "1–19", "twenty-two" →
  "twenty-three", the list gains `installedComponents`. `M/README.md`: the tools, the gate, `:397`, `:416`. The tool
  count pins (`\b84\b`: 20 mentions in 5 test files on `1476e0d8`) → 89; the merge pins (`test_tools.py:971`, `:975`,
  `:984`, `TWENTY_TWO_TALLIES`) → 23. `S/` unchanged and green (`S/src/servicetag_schedules/phone.py:274` reads the
  child-Asset key).

### B6a / B6b / B6c — the phone section (C25–C27)

- **C25, the section and its state (B6a; R47-16).** New `A/ui/installed/InstalledComponentsSection.kt`,
  `InstalledComponentsSectionViewModel.kt` and `InstalledComponentStrings.kt` (the one home of every P47 string), on
  `AssetSuppliesSection`'s shape (`A/ui/supplies/AssetSuppliesSection.kt:57-75`): takes `assetId`, `graph`,
  `snackbars`, `onOpenSupply`, `readOnly`; owns its view model; a stateless `InstalledComponentsList` draws. **State**
  (JVM-proven): observes `graph.installedComponents.observeForAsset(assetId)` and `graph.supplyItems.observeAll()`;
  draws `InstalledComponentTree.current(rows)` **indented 16 dp per depth**, each the name and a quiet line joining
  with " · " what is there — the direct SupplyItem's name and part number (read from the catalog, never copied; the
  "Archived" badge when archived), the composition summarised as P47-21 per entry (`"4 × Example 12 V Battery"`, at
  most two then "+{n}", P47-22), the serial or lot, "Installed {day}" (P47-15); empty: P47-2. Below, when any exist,
  the toggle **"Removed ({n})"** (P47-18) expanding `removedUnreplaced(rows)` with "Removed {day}" (P47-17). Picker
  rows: `listRowsOf(items.filter { it.archivedAt == null })` (R47-3).
- **C26, the sheets (B6b, B6c).** `ModalBottomSheet`s (`AssetSuppliesSection.kt:182`, `:196-198`) in
  `A/ui/installed/InstalledComponentSheets.kt`:
  - **The row sheet (B6b):** the name; the direct SupplyItem (a tap opens it via `onOpenSupply`); **"Composition"**
    (P47-23), one line per entry (P47-21; a tap opens that SupplyItem); the serial or lot (P47-6); the install date
    (P47-7, or P47-16); the removal date (P47-8); **"History"** (P47-14): `history(rows, id)` newest first — each
    instance's name, its install line, and "Replaced by {name} on {day}" (`ReplaceStrings.replacedBy`,
    `A/ui/replace/ReplaceStrings.kt:106`) or "Removed {day}", the oldest with "Replaces {name}" where it applies
    (`:109`); then, for a **current** row with writes offered, **"Install inside"** (P47-4), **"Replace"** (P47-9),
    **"Remove"** and **"Edit"** (reused); a removed row offers **"Edit"** only.
  - **The install / edit / replace sheet (B6b without composition, B6c adds it):** titles P47-3 / P47-13 / P47-10;
    "Name" (prefilled on replace); a quiet "Inside {name}" (P47-5) when installing inside; the direct link through
    `SupplyLinkLine` — "Link supply" opens `SupplyItemPickerSheet`, "Remove link" clears (reused P15-21/22/23;
    prefilled on replace); **(B6c)** "Composition" (P47-23): one row per entry — the SupplyItem's name with the
    "Archived" badge, "Qty" and "Unit" fields (reused, `A/ui/setup/ProfileEditScreen.kt:409`,
    `:420`), a close glyph labelled P47-24 — and an "Add supply" row button
    (reused P15-13) opening the picker; a pick appends an entry with quantity empty and the unit filled from the
    item's preferred unit **only if** blank (#15 C34's rule), prefilled on replace with the predecessor's entries;
    "Serial or lot" (P47-6, empty on replace); "Installed on" (P47-7) through `DateField` — on replace, the
    replacement date, prefilled today; "Notes". Save ("Save", or "Replace" on replace) enabled while the trimmed name
    is non-blank and nothing is saving. A replace of a row with current children draws P47-12 above the button.
  - **The remove sheet (B6b):** title P47-11, "Removed on" (P47-8) through `DateField`, prefilled today; P47-12 when
    the row has current children; "Remove" / "Cancel". No typed confirmation (nothing is erased).
  - **Refusals** under the field the problem names: `RemovedBeforeInstalled` → P47-20; `AfterToday` → the shipped
    `DATE_NOT_LATER_THAN_TODAY` (`A/ui/condition/ChangeConditionViewModel.kt:35`); `QuantityInvalid(i)` → P47-25 under
    the rows, entry `i` marked; `SupplyItemMissing` / `SupplyItemArchived` / the entry twins → P15-20 (reused);
    `NoSuchInstalledComponent` / `AlreadyRemoved` / `ParentMissing` / `ParentRemoved` / `ParentOnAnotherAsset` /
    `OwnerMissing` / `BadDate` → P47-19; `Unchanged` closes as saved; `NameRequired` unreachable (Save disabled).
    Cancel and dismiss write nothing.
- **C27, the placement and the read-only rule (B6a).** `A/ui/asset/AssetDetailScreen.kt`: the call after
  `AssetSuppliesSection(…)` (`:447-453`), **before** `ComponentsSection(…)` (`:454-458`), with `readOnly =
  !current.offersWrites` and the **existing** `onOpenSupply` — **no new `AssetDetailScreen` parameter, no
  `ServiceTagRoot` change**. Header P47-1, its add glyph labelled P47-3 when writes are offered (installs at the top
  level; the header rule, `AssetSuppliesSection.kt:102-113`). A held asset draws rows, toggle, facts and history, and
  no glyph or action.

### B7 — the relabel (C28; R47-1 DECIDED)

- **C28.** The eleven child-Asset phone strings move to "Child asset(s)" at their one home each (§5's R-list):
  `A/ui/asset/AssetDetailScreen.kt:729`, `:999`, `:1001`, `:1020`; `A/ui/asset/AssetsScreen.kt:450`, `:459`, `:462`,
  `:465`; `A/ui/transfer/TransferStrings.kt:27`, `:48`; `A/ui/dashboard/DashboardScreen.kt:162`. The chip constant's
  picker and share-intake uses (`A/ui/asset/AssetPicker.kt:33`, `A/share/ShareIntakeScreen.kt:60`) move with it.
  **Untouched:** every identifier (`ComponentRow`, `componentsOf`, `onToggleComponents`, `isComponent`,
  `ComponentCondition`), every API/MCP noun, the API message `"this asset still has components"`
  (`A/api/ApiJson.kt:366`, the child-Asset API vocabulary until #98), S27's format
  (`A/ui/condition/ConditionWords.kt:86-90`).

### B8 and cross-cutting (C29–C30)

- **C29, the documents (R47-5).** B8 writes:
  - **`docs/release-proofs.md`: a schema-19 paragraph after the schema-18 one (`:131`)**, in its shape: the first
    signed release carrying Room 19 / format 19 — also the first carrying 18, so **the schema-18 paragraph's checks
    are its checks too** — proves the direct in-place upgrade from each release a phone runs when it is cut (named
    then); seeds nothing #47-shaped on the older side; takes a pre-upgrade export; verifies `schemaVersion` /
    `backupFormatVersion` 19, `counts` gaining `installedComponents: 0` and `compositionEntries: 0`, a post-upgrade
    export carrying `"installedComponents": []`; then installs a fictional tray with four child positions naming one
    SupplyItem **and** a pack composed of `4 ×` that SupplyItem (both forms), through the routes, the MCP and the
    phone's section; replaces one position and the pack, removes one; re-plans the pre-upgrade export **applicable
    with zero INSERT and no `CONFLICT`**; proves the format-19 round trip export → import → re-plan with no edit
    between, on app-made archives (replace restore reads every row, entry, pointer and date back; a merge into an
    install without them inserts them, tallying `installedComponents`). It names the emulator's blind spots (limit
    11), makes no Transfer Pack, and names no release number.
  - **`docs/design/14-asset-model.md`:** `:15-18` (Component built at schema 19 as "installed component"; the phone's
    "Installed components"; child Assets read "Child assets"), `:45` (the table row), and a "What #47 built" paragraph
    in §4 (`:81-104`) — one row per fitted instance; current = no removal date; a replace closes one and inserts one;
    **the two compositions: instance-level (child rows, each with its history) and aggregate (`4 × X` on one row, no
    per-unit history)**, and that choosing between them for a service is #96's; a removed container closes its
    subtree; no journal link; #86 carries none; health `PART` not bound. `:30-32`'s sentence is **kept verbatim**; the
    §3 example table (`:62`) gains the aggregate form on the UPS pack row.
  - **`docs/design/04-domain-data-model.md`:** a two-line note after `:199-204` (installed components hold fitted
    structure without Asset identity; child Assets remain the escalation path).
  - **Untouched:** `docs/versioning.md`, `README.md`, `docs/api/command-shapes.json`, the 1.4 spec and archaeology.
- **C30, the invariants every brief keeps.**
  1. **No inference:** row 39.
  2. **The fence tripwire (word-anchored, tip vs base, #15's C-6 rule):**
     `P95='\b(stock(s|ed|ing)?|reorder(s|ed|ing)?|low[- ]?stock|lead[- ]?times?|on[-_ ]?hand|procure(ment|d)?)\b'` and
     `P96='\b(schedule_supply_requirement|material[_ ]?requirements?|batch[_ ]?replace(ment)?|kits?)\b'`:
     `git grep -ciE "$P" -- . ':!docs/superpowers'` summed at the tip ≤ the same sum at `<base>`, each pattern; list
     order says "move" or "resequence", never "reorder".
  3. **The word tripwire:** no new phone string contains "part", "assembly" or "kit"; after B7, `git grep -hoE
     '"[^"]*"' -- 'app/src/main/**/*.kt' | grep -iE '\bcomponents?\b'` lists only §5's P47 strings and the API message
     at `ApiJson.kt:366`.
  4. **Untouched, `git diff <base> --` → empty:** `A/nfc/`, `C/model/TagBinding.kt`,
     `A/data/room/entities/NfcTagEntity.kt` (AC1); `C/model/Attachment.kt`, `A/data/room/entities/AttachmentEntity.kt`
     (#69); `C/usecase/ReplaceAsset.kt`, `C/usecase/ReplaceSetup.kt` (#86); `C/model/Health.kt`, `A/ui/health/`
     (R47-12); `C/model/AssetTree.kt` (R47-14); `C/model/SupplyItem.kt`, `A/data/room/entities/SupplyEntities.kt`,
     `A/ui/supplies/` (the #15 fences stay true — `SupplyItem.kt:7-8`, `SupplyEntities.kt:14-15`,
     `SupplyDetailScreen.kt:44-45` — no SupplyItem type gains a fitted field; #47's rows name SupplyItems from their own
     table); `C/reminders/`, `A/reminders/`, `C/journal/SeedTemplates.kt`, `C/usecase/CompleteSchedule.kt`, `S/`,
     `tools/servicetag-bundle/`, `docs/api/command-shapes.json`, `app/schemas/**/18.json`.
  5. **The tombstones** → 0.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED (the mutation that must fail it). App classes in `T/` unless named;
core in `CT/`; MCP in `M/tests/`. "Pin" = a shipped assertion that moves or is re-run, with no RED. **JVM** unless
marked **Compose** (instrumented, first run at the merged-tip gate). Case names are the implementer's to refine; what
each proves is not. Fixtures are fictional (Global constraints).

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C5 parents first | `CT/model/InstalledComponentTreeTest` (new) · `aShuffledThreeDeepTreeComesParentsFirst`; `aParentOutsideTheListIsARoot`; `aCycleThrows`; ties by id | a row whose parent is outside the list counted with indegree 1 (it never comes out) |
| 2 | C5 current view and sibling order | `currentDropsRemovedRowsAndAnnotatesDepth`; `siblingsBySortOrderThenNameThenId` ("Position 10" after "Position 2" by `sortOrder`) | siblings ordered by name only |
| 3 | C5 history | `historyFollowsReplacesIdBackNewestFirst`; `successorOfFindsTheRowNamingIt`; `removedUnreplacedListsOnlyRowsWithNoSuccessor`; `aReplacesIdCycleTerminates` | the walk unbounded (the cycle case hangs → the test's timeout) |
| 4 | C5 shape rules | `CT/usecase/InstalledComponentShapeTest` (new) · blank name; bad dates; `afterTodayOnlyWithAToday`; `removedBeforeInstalled`; `aZeroNegativeOrNonFiniteQuantityIsRefusedByIndex`; every problem collected | `RemovedBeforeInstalled` dropped |
| 5 | C7 the migration adds two tables only | `T/data/room/Migration18To19Test` (new) · `bothTablesExistEmptyWithTheirIndicesAndForeignKeys`; `everyV18TableIsUnchanged`; `theMigratedSchemaEqualsAFreshVersion19` | the migration's `parent_id` FK `RESTRICT` while the entity says CASCADE (the schema compare fails) |
| 6 | C8 the whole chain | the six table-set pins gain `V19_TABLES` | none: pins |
| 7 | C6/C8 H1 and the constraints | `T/data/room/InstalledComponentDaoConstraintTest` (new) · `deletingAnAssetCascadesAThreeDeepTreeAndEveryEntry`; `theReplaceWipeTakesTheTree` (`assets.deleteAll()`); `aSupplyItemNamedDirectlyOrByAnEntryCannotBeDeleted` (raw SQL); `replacesIdIsUniqueAndNullsRepeat`; `anUnknownParentOrAssetIsRefused` | **the `parent_id` FK made RESTRICT** — H1 decided on Room: the cascade fails mid-tree |
| 8 | C6 the aggregate port | same class · `insertWritesTheEntriesInOrder`; `updateReplacesTheCompositionWhole`; `forAssetReturnsCurrentAndRemovedByIdWithEntries`; `observeForAssetEmitsOnAnEntryChange` | `update` leaves stale entries |
| 9 | C7 schema pins | `VersionAgreementTest.kt:85`, `:155`; `MaintenanceRoutesTest.kt:1548` → 19 | none: pins |
| 10 | C9/C10 round trip | `CT/backup/BackupFormat19Test` (new) · `everyRowEntryPointerAndDateRoundTrips` (nulls written explicitly) | `toDto` drops `replacesId` |
| 11 | C9 the fence, structurally | `theDtosCarryExactlyTheseKeys` (both DTOs' descriptor names pinned in order) | a `quantityOnHand` key added to `CompositionEntryDto` |
| 12 | C10 the ≤ 18 gate | `aFormat18ArchiveDecodesWithNone`; `aFormat18ArchiveCarryingRowsIsCorrupt` | the gate removed |
| 13 | C10 the graph | unknown asset; unknown parent; **parent on another asset**; unknown direct or **entry** SupplyItem; an entry id on two rows; `replacesId` unknown, on another asset, itself, naming a current row, or held twice; a current row under a removed parent; a parent cycle; a `replacesId` cycle; an archived SupplyItem is valid | the parent's same-asset check removed |
| 14 | C10 the content | blank name; bad date; removed before installed; `aZeroQuantityEntryIsCorrupt`; `aFutureDateIsAcceptedOnRestore` (no today) | the content check passed a today |
| 15 | C10 sort and counts | `rowsByIdEntriesBySortOrderThenId`; `theManifestCountsRowsAndEntries` (31 keys) | the entry sort dropped |
| 16 | C10 newer refused | `aFormat20ArchiveIsRefusedAsNewer` | none: pin |
| 17 | C11 export and replace | `ExportBackupSetTest` (+1) · `anExportCarriesRowsAndEntries`; `ImportBackupReplaceTest` (+2) · `aShuffledTreeRestoresParentsFirstByteEqual`; `replacingAnInstallHoldingATreeSucceeds` | the restore writes in file order (a child before its parent: the FK fails) |
| 18 | C9/C10 format pins | §3's B2a pins | none: pins |
| 19 | C12 by id | `CT/merge/MergePlannerInstalledComponentTest` (new) · `theSameRowIsIdentical`; `aRowRemovedHereAfterTheExportIsAConflict`; `aCompositionOnlyDifferenceIsAConflict`; `aNewRowInserts` | the comparison drops `composition` |
| 20 | C12 parents first | `aShuffledNewTreeInsertsParentsFirst`; `aChildOfAnAcceptedParentInserts`; `anOrphanIsOwnerNotAvailable` | decided in file order (the child meets an undecided parent) |
| 21 | C12 owners and child ids | `anAbsentAssetIsOwnerNotAvailable`; `anAbsentEntrySupplyItemIsOwnerNotAvailable`; `aSupplyItemThisPlanInsertsResolves`; `anEntryIdHeldByAnotherRowIsChildRowIdTaken`; `…claimedEarlierInThisPass…` | the entry `supplyId`s left out of the owner check |
| 22 | C12 replacement taken | `aReplacesIdHeldHereIsReplacementTaken`; `…heldEarlierInThisPass…` | the arm removed (the apply's unique index throws) |
| 23 | C12 no older-archive exception needed | `aFormat18ExportAgainstAnInstallWithComponentsIsApplicableAndIdentical` | a decision emitted for local rows the archive does not name |
| 24 | C12 the write order on Room | `T/api/ApiRouterTest` (+1) · `aMergeInsertingATreeWithEntriesAndItsSupplyItemsCommits` (the Room-backed `FakeGraph`) | components applied before the SupplyItems (the RESTRICT FK fails) |
| 25 | C12 held (M2) | `anInsertForAHeldAssetIsAssetTransferredOut` | the M2 line omitted |
| 26 | C12 tally and report | the tally and report-key pins (B2b) and one new assertion naming the table | none: pins |
| 27 | C11 classification | `TransferTableClassificationTest:25` · the new list classified (B2a, E-7) | none: pin |
| 28 | C13 carry | `CT/transfer/TransferGraphTest` (+2) · `aPackCarriesAnAssetsWholeHistoryWithEntries`; `aSupplyItemNamedOnlyByAnEntryTravels` | `supplyIdsInUse` built from direct links only |
| 29 | C13 retain | `retainDropsAHeldAssetsRows` | `retain` keeps them |
| 30 | C13 the return | `ImportTransferPackTest` (+1) · `aReturningPackWithARowReplacedAndAPackRecomposedOnTheBorrowingPhoneLands` | the `installedComponents` filter removed from `ReturnScope.of` (the return refuses `CONFLICT`) |
| 31 | C14 the guard | `HeldWriteGuardTest` (+2) · `installingOnAHeldAssetThrows`; `updatingAHeldAssetsRowThrows` | the wrapper skips its check |
| 32 | C16 install | `CT/usecase/InstalledComponentUseCasesTest` (new) · `installStoresTrimmedTextAndAppendsSortOrder`; `anUnknownDateIsAccepted` (R47-8); `ownerParentAndSupplyStepsRefuseInOrder` (each problem); `aRefusalOpensNoTransaction`; `anArchivedDirectSupplyItemIsRefused` | the archived check removed |
| 33 | C15/C16 the composition on install | `entriesAreStoredInOrderWithMintedIds`; `oneSupplyItemTwiceIsTwoEntries`; `anArchivedEntryIsRefusedByIndex`; `aBadQuantityIsCollectedByIndex` | an archived entry accepted |
| 34 | C18 replace — AC6 | `replaceClosesOneAndInsertsOneInOneWrite` (**whole-table snapshot compare: every other row and entry byte-equal**); `theSuccessorTakesParentAndSortOrderAndNamesItsPredecessor`; `thePredecessorKeepsItsComposition`; `anAbsentCompositionIsCopiedWithFreshIds` (handler-filled command, R47-17); `anArchivedSupplyItemIsRefusedForTheSuccessorEvenIfThePredecessorNamesIt`; `replacingARemovedRowIsAlreadyRemoved`; `replacingAPackClosesItsSubtreeAndTheSuccessorStartsEmpty` | the successor's entries reuse the predecessor's ids (the snapshot compare fails: the predecessor's entries move) |
| 35 | C17 remove | `removeWritesRemovedOnAndUpdatedAtOnce`; `removingTwiceIsAlreadyRemoved`; `beforeAnInstallDateIsRefused`; `theCurrentSubtreeClosesOnTheSameDateInTheSameWrite` (R47-6); `closedRowsKeepTheirComposition`; `nothingIsDeleted` | the subtree left current |
| 36 | C19 update | `editableFieldsMoveUpdatedAt`; `anEditChangingNothingIsUnchangedAndWritesNothing`; `aRemovedRowMayBeCorrected`; `anInstallDateAfterTheRemovalDateIsRefused`; `aKeptArchivedLinkOrEntryIsAccepted`; `aNewArchivedLinkOrEntryIsRefused`; `theCompositionIsReplacedWholeKeepingOwnedIds` | `Unchanged` compares without the composition |
| 37 | AC2/AC4/AC-C/AC8 the UPS shapes | `CT/usecase/InstalledComponentFixtureTest` (new) · **instance-level**: a tray with four positions naming one SupplyItem, position 2 replaced, the other three byte-equal; **aggregate**: a pack composed of `4 ×` that SupplyItem plus its pack SKU as the direct link, replaced whole; both exported, replace-imported and re-planned `IDENTICAL` | the merge apply skips `installedComponents` |
| 38 | AC10/AC11 the RO fixture | same class, on the RO water template (`C/journal/SeedTemplates.kt:133-137`) with `LinkageInstall` (`CT/usecase/SupplyItemFixtureTest.kt:39`) · a membrane and a lamp as installed components only where instance history matters; the sanitizer and the cartridge as SupplyItems with no row | none of its own: row 37's mutation fails it (recorded) |
| 39 | no inference | `installingCreatesNoApplicabilityEventLineOrStockAndApplicabilityCreatesNoComponent` | `InstallComponent` adds an `AssetSupply` for its link |
| 40 | C20 install and read | `T/api/InstalledComponentRoutesTest` (new) · `aPostIs201AndReadsBack`; `theSubResourceListsCurrentAndRemovedAndEachSupplyItemOnce` (direct and entry); `getById` | the sub-resource drops removed rows |
| 41 | C20 the PATCH overlay | `onlyNotesLeavesEverythingElse`; `aNullKeyIsUnchanged`; `anEmptyStringClearsTheLinkTheDateTheSerialAndTheNotes`; `aBlankNameIs422`; `anAbsentCompositionKeepsEntriesAndIds`; `aGivenCompositionReplacesIt`; `anEmptyCompositionEmptiesIt`; `assetParentRemovedOnAndReplacesIdKeysAre400`; `aNoOpIs200AndWritesNothing` | PATCH as a full replace (an absent key becomes `""`) |
| 42 | C20 remove and replace | `removeIs200WithTheClosedSubtree`; `removeTwiceIs409`; `replaceIs201WithBothRows`; `anAbsentNameLinkAndCompositionAreThePredecessors`; `anEmptyCompositionReplacesWithNone` | the handler fills the composition from nothing |
| 43 | C2/C21 every code | `everyProblemHasItsCodeStatusAndField` (the mapper called directly, one case per sealed member); `everyNewCodeIsReachableOverTheWire` | `AlreadyRemoved` mapped to 422 |
| 44 | C22 status | the status test (+1) · `statusCountsRowsAndEntries` | `compositionEntries` omitted |
| 45 | C22 the document agrees | `everyNewCodeIsInV1md` (each as `^\| (404\|409\|422) \| `CODE` \|`) | a code missing from `v1.md` |
| 46 | C20 held | `aHeldAssetIs409AssetTransferredOut` (install, PATCH, remove, replace) | none: row 31's mutation fails it (recorded) |
| 47 | C23 the tools | `M/tests/test_installed_component_tools.py` (new) · each tool's method, path and body; `update_sends_only_given_keys`; `update_sends_an_empty_string_to_clear`; `composition_is_sent_whole_and_an_empty_list_empties` ; no delete tool | `update_installed_component` sends every key |
| 48 | C24 the gate | `…_refuses_schema_18_with_nothing_sent` for all five | the gate removed from one tool |
| 49 | C24 pins | 84 → 89; 22 → 23 tallies; "1–19" | none: pins |
| 50 | C25 the section state (B6a) | `T/ui/installed/InstalledComponentsSectionViewModelTest` (new) · `currentRowsAreIndentedByDepthInSiblingOrder`; `theQuietLineReadsLinkCompositionSerialAndDate`; `removedUnreplacedSitBehindTheToggle`; `thePickerOffersUnarchivedOnly`; `readOnlyOffersNothing` | the picker includes archived items |
| 51 | C26 the write state (B6b) | same class (+5) · `installPassesTheCommand`; `installInsideCarriesTheParent`; `replacePrefillsNameAndLink`; `removeWithChildrenDrawsP47_12`; `eachProblemDrawsItsSentenceUnderItsField` | replace prefill drops the link |
| 52 | C26 the composition state (B6c) | same class (+4) · `aPickAppendsAnEntryAndFillsOnlyABlankUnit`; `replacePrefillsTheEntries`; `removingAnEntryRemovesOnlyIt`; `aBadQuantityMarksItsEntryWithP47_25` | a pick overwrites a typed unit |
| 53 | C25–C27 drawn | **Compose** `AT/ui/installed/InstalledComponentsSectionTest` (new, ~14 cases) · rows, indentation and quiet lines; empty line; the add glyph; read-only draws no glyph or action; the toggle; the row sheet's facts, composition and history; the install sheet's Save enabling and Cancel writing nothing; the composition rows' add and remove; the remove sheet with P47-12 | none in-brief: device cases |
| 54 | C28 the relabel | the JVM pins (`AssetViewModelsTest`, `TransferSelectionViewModelTest`, `MaintenanceSheetViewModelTest`) and the device pins (§3's B7 rows) move to the R-list's words | none: pins (the words are ratified, the tests follow) |
| 55 | C29 the documents | `ReleaseProofPolicyTest` unchanged and green; B8's anchored greps (§7) | none: tripwire |
| 56 | C30 the fence | the tripwire, word and untouched greps (§7) at every brief | none: greps |

**Moving pins — each named shipped assertion, the brief that may touch it, and why** (the audit's §2.7 inventory,
re-sorted by brief and recomputed for two manifest keys; each brief confirms by grep and lists twins).

| pin | brief | moves, because |
|---|---|---|
| `T/VersionAgreementTest.kt:85`, `:155`; `T/api/MaintenanceRoutesTest.kt:1548` | B1b | the schema is 19 |
| `Migration10To11Test` … `Migration14To15Test`, `ReferenceMigrationTest` (the six table sets); `MigrationTestSupport.kt:68` | B1b | the chain gains two tables and one step |
| the format literals: `VersionAgreementTest.kt:86`, `:156`; `MaintenanceRoutesTest.kt:1196`, `:1549`, `:1596`; `CT/backup/BackupCodecTest.kt:1067`; `BackupFormat6Test:360`, `8Test:281`, `9Test:70`, `13Test:54`, `14Test:48`, `15Test:48`, `17Test:99`, `18Test:74` (`17Test:223` and `18Test:350` are the triples' third lines, next row); `ExportBackupSetTest.kt:48`; `Format7ImportIdentityTest.kt:247`; `AT/backup/Format7RestoreContractTest.kt:164` | B2a | the format is 19 (25 `assertEquals(18,` hits on `1476e0d8`; B1b and B2a split them by reading each line) |
| `BackupFormat8Test:277`, `:280`, `:281`; `17Test:218`, `:222`, `:223`; `18Test:345`, `:349`, `:350` | B2a | the "one format past this build" archive becomes 20 |
| the manifest `counts.size` 29 → **31**: `BackupFormat6Test:406`, `7Test:296`, `8Test:310`, `13Test:68`, `14Test:65`, `15Test:63`, `18Test:334` | B2a | two count keys |
| from-the-end manifest key positions: `BackupFormat12Test:70`, `13Test:58`, `14Test:52`, `15Test:52-53`, `9Test:102` | B2a | two keys appended (each position moves by 2; B2a reads each) |
| the `BackupData` list counts: `BackupFormat6Test:289`, `7Test:222`; `StageABundleConformanceTest:90-98`, `:215` (`FORMAT_18_TABLES` → `FORMAT_19_TABLES`) | B2a | one list (22 → 23) |
| `TransferTableClassificationTest.kt:25` | B2a | the new list must be classified (E-7) |
| merge tables 22 → 23: `CT/merge/MergePlannerTransferTest.kt:383`; the enum mirrors `MergePlannerMaintenanceTest:274-277`, `MergePlannerReferenceTest:379-383`, `MergePlannerSeasonHealthTest:152`; `MaintenanceRoutesTest:1323`, `:1376` (report key positions, `dropLast` +1 each, E-6) | B2b | one table and one report key |
| `HeldWriteGuardTest` (its port list) | B2c | one more wrapped port |
| `T/api/CommandShapesGoldenTest.kt:137-139`, `:174-175`, `:187`; `T/api/ReferenceRoutesTest.kt:736`, `:741`, `:744-756`, `:760-767` (range, tables, sub-resources 26 → 27, `counts.keys`); `T/api/SupplyRoutesTest.kt:634-635`; the `ApiHandlers(` test sites (arguments) | B4 | range 1–19, 23 tables, 27 sub-resources, two status keys, "19 since #47" |
| `M/tests/test_argument_guard.py` (12 mentions, e.g. `:51`, `:203-204`, `:257-258`), `test_tools.py` (`EXPECTED_TOOLS`, `:971`, `:975`, `:984`), `test_reference_tools.py`, `test_maintenance_tools.py`, `test_supply_tools.py` | B5 | 89 tools; 23 tallies; 1–19 |
| `T/ui/asset/AssetViewModelsTest.kt:154`, `:604`, `:1035`; `T/ui/transfer/TransferSelectionViewModelTest` (one); `T/ui/maintenance/MaintenanceSheetViewModelTest.kt:338` (B7 reads it: a block name may stay) | B7 | the relabel |
| `AT/ui/asset/AssetsFiltersTest.kt:155`, `:157`, `:159`, `:315`; `AssetsSearchTest.kt:75`; `AssetTransferDetailTest.kt:94`, `:111`; `AT/ui/AssetModelDeviceProofTest.kt:79`, `:125`, `:131`, `:156`, `:172`, `:190`, `:230`, `:283`; `AT/share/ShareIntakeScreenTest.kt:135`; `AT/ui/transfer/TransferFlowScreenTest` (one); `AT/ui/dashboard/DashboardAttentionTest` (one); **`AT/ui/maintenance/GroupScreensTest.kt:162`** (`"COMPONENTS"`, the upper-cased header — outside the audit's list) | B7 | the relabel (device classes: 8) |

**Device rows.** No device-boundary class: #47 crosses no Android or OS boundary (planning policy,
`docs/superpowers/planning-policy.md:61-91`). Every rule — migration, codec, merge, pack, guard, use cases, routes,
MCP, and each view model's state (rows 50–52) — is JVM-proven first. What remains is Compose drawing an
already-proven state: **one new class** (`InstalledComponentsSectionTest`, ~14 cases; #15's two classes overran their
estimates by 2 and 4, E-18, so budget up to ~18) and the relabel's edits to **eight** shipped device classes (no new
case). **Gate growth:** 57 → **58** device classes, ~300 → ~314–318 tests; at #15's merged-tip record (12.37 min) about
3 s per case → **~13.1–13.3 min**, under the 14- and 15-minute reporting lines; #90's trigger is not reached. **Known
cost:** the new section between Supplies and the child-Asset section shifts the asset-detail layout under the shipped
`AssetDetail*Test` classes, first seen at the merged-tip gate (one fix round, accepted as #15's N-12); the relabel's
device classes also first run there.

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `C/model/{Ids,InstalledComponent (new),InstalledComponentTree (new)}.kt`; `C/ports/Repositories.kt`; `C/usecase/InstalledComponentCommands.kt` (new: the problem type and the two shape functions only); `CT/model/InstalledComponentTreeTest.kt`, `CT/usecase/InstalledComponentShapeTest.kt` (new) | `app/**`, `C/backup`, `C/merge`, `C/transfer`, every shipped use case, `docs`, `tools` |
| B1b | `A/data/room/entities/InstalledComponentEntities.kt` (new); `A/data/room/dao/InstalledComponentDao.kt` (new); `A/data/room/{InstalledComponentRepositories (new),InstalledComponentMappers (new),Migrations,AppDatabase}.kt`; `A/di/AppGraph.kt`; `T/testing/FakeGraph.kt` (the port); `app/schemas/…AppDatabase/19.json` (generated); `T/data/room/{Migration18To19Test (new),InstalledComponentDaoConstraintTest (new),MigrationTestSupport}.kt` and B1b's pins | `C/**` main, `A/api`, `A/ui`, `18.json`, `docs`, `tools` |
| B2a | `C/backup/{BackupFormat,BackupCodec,BackupContentCheck}.kt`; `C/usecase/{ExportBackupSet,ImportBackupReplace}.kt`; `C/transfer/TransferGraph.kt` (the `CLASSES` line only); constructor arguments only (C-1): `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt`, `CT/testing/BackupInstall.kt` and the 24 test sites; `CT/backup/BackupFormat19Test.kt` (new); `ExportBackupSetTest`, `ImportBackupReplaceTest`; B2a's pins | `C/merge`, `C/transfer` (bar one line), other use cases, `A/**` main bar wiring, `docs`, `tools` |
| B2b | `C/merge/{MergePlan,MergePlanner}.kt`; `C/usecase/{ApplyBackupMergePlan,BuildBackupMergePlan}.kt` (the writes; the snapshot call); constructor arguments only: `AppGraph`, `FakeGraph` and the 18 test sites; `A/api/ApiDtos.kt` (the report rows only); `CT/merge/MergePlannerInstalledComponentTest.kt` (new); `T/api/ApiRouterTest.kt` (row 24 only); B2b's pins | `C/backup`, `C/transfer`, other use cases, `A/ui`, `A/data`, `docs`, `tools` |
| B2c | `C/transfer/{TransferGraph,TransferOwnership,HeldWriteGuard}.kt`; `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of` only); `A/di/AppGraph.kt` (the guard's wrap and the count words); `T/testing/FakeGraph.kt` (the guarded port); `CT/transfer/{TransferGraphTest,ImportTransferPackTest,HeldWriteGuardTest}.kt` | `C/backup`, `C/merge`, other use cases, `A/api`, `A/ui`, `docs`, `tools` |
| B3a | `C/usecase/{InstalledComponentCommands,InstallComponent (new),RemoveInstalledComponent (new)}.kt`; `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (wiring); `CT/usecase/InstalledComponentUseCasesTest.kt` (new) | `C/backup`, `C/merge`, `C/transfer`, every shipped use case, `A/api`, `A/ui`, `docs`, `tools` |
| B3b | `C/usecase/{ReplaceInstalledComponent (new),UpdateInstalledComponent (new)}.kt`; `AppGraph`, `FakeGraph` (wiring — **the last edit of either**); `CT/usecase/{InstalledComponentUseCasesTest,InstalledComponentFixtureTest (new)}.kt` | as B3a |
| B4 | `A/api/{InstalledComponentDtos (new),InstalledComponentHandlers (new),ApiRouter,ApiHandlers,ApiJson}.kt`; the `ApiHandlers(` test sites, arguments only; `docs/api/v1.md`; `T/api/{InstalledComponentRoutesTest (new),CommandShapesGoldenTest,ReferenceRoutesTest,SupplyRoutesTest}.kt` | `C/**`, `A/ui/**` main, `A/data`, `A/di`, `FakeGraph`, `tools`, `command-shapes.json` |
| B5 | `M/src/servicetag_mcp/server.py`; `M/README.md`; `M/tests/{test_installed_component_tools (new),test_tools,test_argument_guard,test_reference_tools,test_maintenance_tools,test_supply_tools}.py` | `app/**`, `core/**`, `S/**`, `command_shapes.py`, `docs` |
| B6a | `A/ui/installed/{InstalledComponentsSection,InstalledComponentsSectionViewModel,InstalledComponentStrings}.kt` (new); `A/ui/asset/AssetDetailScreen.kt` (the one call); `T/ui/installed/InstalledComponentsSectionViewModelTest.kt` (new); `AT/ui/installed/InstalledComponentsSectionTest.kt` (new: the list cases) | `C/**`, `A/api`, `A/data`, `A/di`, `A/nav`, `A/ui/supplies`, `docs`, `tools` |
| B6b | `A/ui/installed/*` (the sheets; the view model's writes); the two test classes | as B6a, and `AssetDetailScreen.kt` |
| B6c | `A/ui/installed/*` (the composition editor and display); the two test classes | as B6b |
| B7 | `A/ui/asset/{AssetDetailScreen,AssetsScreen}.kt`, `A/ui/transfer/TransferStrings.kt`, `A/ui/dashboard/DashboardScreen.kt` (the eleven strings only); the §3 B7 pins | `C/**`, `A/api`, `tools`, every identifier, `docs` |
| B8 | `docs/release-proofs.md`; `docs/design/{14-asset-model,04-domain-data-model}.md` | any `.kt`, `.py`, `tools`, `docs/api`, `docs/versioning.md`, `README.md` |

**B4 ∥ (B6a → B6b → B6c) — the one parallel pair.** B4's files are `A/api/**`, `docs/api/v1.md`, `T/api/**` and the
`ApiHandlers(` sites (all in `T/api`, `T/VersionAgreementTest.kt`, `T/ui/api/` and `T/testing/MaintenanceFixtures.kt`
— B4 lists them first and stops if one is in `T/ui/installed/`); the UI lane's are `A/ui/installed/**`, one line of
`AssetDetailScreen.kt`, `T/ui/installed/**`, `AT/ui/installed/**` — **no file in common**, and neither edits
`AppGraph` or `FakeGraph`. Under the two-lane rule the UI lane may run in a second worktree off B3b's tip; the
controller lands it before B5, whose `<base>` holds both.

**Order:** B1a → B1b → B2a → B2b → B2c → B3a → B3b → { B4 ∥ (B6a → B6b → B6c) } → B5 → B7 → B8. B1b needs the
domain; B2a the tree and the shape rules; B2b `FIRST_INSTALLED_COMPONENT_FORMAT` and the DTOs; B2c the snapshot list;
B3a the guarded port; B4 and B6 the use cases; B5 B4's routes; B7 follows B6 (both touch `AssetDetailScreen.kt`); B8
describes everything.

**The composition's representation, costed (R47-17).**

| | **A. nested child table (chosen)** — `supply_specification`'s pattern | B. a separate archive relation |
|---|---|---|
| Room | `installed_component_composition`, CASCADE from its row | the same table |
| archive | nested `composition` on each row; one list; count key `compositionEntries` | a second `BackupData` list `installedComponentCompositions`, its own sort, gate and graph checks |
| merge | compared **with its row** (one `MergeTable`; the specifications' child-id arm) | a 24th `MergeTable`, its own section, reasons, tally and report key, its own M2 line |
| pack / return / guard | rides its row (no class, no filter, no port) | a pack class, a `retain` filter, a `ReturnScope` filter, a 19th guarded port |
| API / MCP | nested in the row's DTO; a `composition` key on install, replace and PATCH; no route, no tool | entry routes (`POST`/`PATCH`/`DELETE`) and 2–3 more tools |
| UI | edited on the component's sheet | the same, over more calls |
| history semantics | an entry belongs to one instance row and **stays with it when it closes** by construction; a replacement's entries are minted fresh | the same, enforced by two writers |
| pins | manifest +2 keys; nothing else beyond the one list | +1 list, +1 table, +1 class, +1 tally — roughly +25 pins |
| per-entry merge granularity | none: a composition edit is a row edit (`CONFLICT` as a whole) | per entry |

A is chosen: the composition is part of what one fitted instance **is**, it never outlives or leaves its row, and it
needs no identity of its own beyond the durable id an archive round trip needs. B buys per-entry merge granularity no
AC asks for at about twice the surface. The cost of A is one stated limit (8) and that a two-phone composition edit
conflicts as a whole (limit 6).

**Recomputed counts** (the audit's figures predate the composition ruling; recounted on `1476e0d8`).

| fact | the audit | now |
|---|---|---|
| Room tables added | 1 | **2** |
| `BackupData` lists | 22 → 23 | 22 → 23 |
| manifest count keys | 29 → 30 | **29 → 31** (`installedComponents`, `compositionEntries`) |
| `MergeTable` | 22 → 23 | 22 → 23 (the composition has none) |
| `MergeReason` | +1 | +1 (`INSTALLED_COMPONENT_REPLACEMENT_TAKEN`; `CHILD_ROW_ID_TAKEN` reused) |
| pack classes / guarded ports | +1 / 17 → 18 | +1 / 17 → 18 |
| asset sub-resources; router | 26 → 27; 72/89 → 77/95 | the same (no entry route) |
| new API codes | ~5 | **10** (+ `NO_SUCH_SUPPLY_ITEM` / `SUPPLY_ITEM_ARCHIVED` reused with `field` `composition`) |
| `/v1/status` count keys | +1 | **+2** |
| MCP tools | 84 → ~89 | 84 → 89 (`composition` is an argument) |
| moving pins | ~90 in ~45 files | ~95 in ~47 files (§3), plus the relabel's 11 test classes and 10–20 twins |
| construction sites | 42 (+ `ApiHandlers` ~12) | 10 + 9 + 5 (B2a), 10 + 8 (B2b), ~12 (B4) |
| new phone strings | ~22–26, + 11 relabel | **25**, + 11 relabel |
| device classes | 57 → 58, ~+12 cases, ~13.0 min | 57 → 58, **~+14–18** cases, **~13.1–13.3 min** |
| dispatches | 10–11 | **14** (B1, B3 and B6 split by plan) |

**Gate budget** at the merged tip (estimates; B1a records the base's exact counts): core +~110 (tree and shape 18,
format 24, merge 18, pack and guard 8, use cases 42); app +~70 (Room 12, routes 30, view models 16, relabel 0 new);
MCP +~30; loader unchanged; device classes **58**. Timed against #15's record, reporting only, no rerun-until-green.

## 5. Strings

**Awaiting ratification as one block** (P47-1…25, P47-R1…R11, the reused list, G1–G3). Each new string is declared
once as a `const val` (or a one-line function for a format) in `A/ui/installed/InstalledComponentStrings.kt` and
imported, never copied (the `ROLE_HEADER` rule); the R-list stays at its existing homes.

| id | proposed wording | where (contract) |
|---|---|---|
| P47-1 | "Installed components" | the section header (C27) |
| P47-2 | "No installed components" | the section, empty (C25; the "No supplies" shape) |
| P47-3 | "Install component" | the add glyph's accessibility label and the install sheet's title (C26, C27) |
| P47-4 | "Install inside" | a current row's sheet action (C26) |
| P47-5 | "Inside %s" — `%s` the parent's name | the install sheet's quiet line (C26) |
| P47-6 | "Serial or lot" | the sheet field and the row fact (C26) — the shipped "Serial number" (`SERIAL_NUMBER_FIELD`, `A/ui/asset/AssetEditScreen.kt:236`) is the alternative if the owner prefers it |
| P47-7 | "Installed on" | the date field and the fact; on replace, the replacement date (C26) |
| P47-8 | "Removed on" | the remove sheet's date field and the fact (C26) |
| P47-9 | "Replace" | a current row's sheet action and the replace sheet's button (C26; the word ships inline at `A/ui/backup/BackupScreen.kt:302` for a replace restore) |
| P47-10 | "Replace %s" | the replace sheet's title (C26) |
| P47-11 | "Remove %s" | the remove sheet's title (C26) |
| P47-12 | "Everything installed inside it is removed on the same date." | the remove and replace sheets, when the row has current children (C26; R47-6) |
| P47-13 | "Edit component" | the edit sheet's title (C26) |
| P47-14 | "History" | the row sheet's section (C26) |
| P47-15 | "Installed %s" — `%s` the day, `ReplaceStrings.day` | the quiet line and history (C25, C26) |
| P47-16 | "Install date not recorded" | the fact and history, `installedOn` null (C26; R47-8) |
| P47-17 | "Removed %s" | the removed rows and history (C25, C26) |
| P47-18 | "Removed (%d)" | the toggle under the tree (C25) |
| P47-19 | "This component was removed or changed. Nothing was saved." | any sheet, a gone or closed row or parent (C26) |
| P47-20 | "The removal date cannot be before the install date." | under the date field (C26; `LoanWords.kt:110`'s shape) |
| P47-21 | "%1$s × %2$s" — the quantity with its unit, then the SupplyItem's name ("4 × Example 12 V Battery", "2 L × Example Coolant") | the composition's lines and the quiet line (C25, C26) |
| P47-22 | "+%d more" | the quiet line past two entries (C25) |
| P47-23 | "Composition" | the row sheet's and the editor's section (C26) |
| P47-24 | "Remove from composition" | an entry row's close glyph, accessibility label (C26) |
| P47-25 | "Each supply needs a quantity above zero." | under the composition rows after a refused save (C26; P15-12's shape) |

**The relabel (R47-1, DECIDED) — the eleven child-Asset strings, each at its home (C28):**

| id | home | now | becomes |
|---|---|---|---|
| P47-R1 | `AssetDetailScreen.kt:999` | "Components" | "Child assets" |
| P47-R2 | `:1001` | "No components" | "No child assets" |
| P47-R3 | `:1020` | "+ Add component" | "+ Add child asset" |
| P47-R4 | `:729` | "Components first" | "Child assets first" |
| P47-R5 | `AssetsScreen.kt:450` | "Components" (P73-3, the chip) | "Child assets" |
| P47-R6 | `:459` | "Matching assets are components. Turn on Components to see them." | "Matching assets are child assets. Turn on Child assets to see them." |
| P47-R7 | `:462` | "Matching assets are hidden. Turn on Components and Archived to see them." | "Matching assets are hidden. Turn on Child assets and Archived to see them." |
| P47-R8 | `:465` | "Only components here. Turn on Components to see them." | "Only child assets here. Turn on Child assets to see them." |
| P47-R9 | `TransferStrings.kt:27` | "Components go with the asset they belong to." | "Child assets go with the asset they belong to." |
| P47-R10 | `TransferStrings.kt:48` | "$child is a component of $parent. Select $parent too." | "$child is a child asset of $parent. Select $parent too." |
| P47-R11 | `DashboardScreen.kt:162` | "Components are listed on the asset they belong to." | "Child assets are listed on the asset they belong to." |

**Reused verbatim from their one home:** "Name" (`NAME_FIELD`, `A/ui/asset/AssetEditScreen.kt:227`); "Notes"
(`NOTES_LABEL`, `A/ui/attachments/AttachmentEditSheet.kt:41`); "Qty", "Unit" (`A/ui/setup/ProfileEditScreen.kt:409`,
`:420`); "Add supply" (`ADD_SUPPLY`, `A/ui/supplies/SupplyStrings.kt:54`); "Choose a supply" and its empty sentence
(`:60`, `:63`, inside the picker); "Link supply", "Linked to %s", "Remove link" (`:72`, `:78`, `:81`, inside
`SupplyLinkLine`); "That supply item is no longer available." (`SUPPLY_ITEM_GONE`, `:12`); "Archived"
(`A/ui/maintenance/GroupListScreen.kt:96`); "Edit" (`A/ui/asset/AssetDetailScreen.kt:658`); "Remove"
(`A/ui/references/ReferencesSection.kt:255`); "Save", "Cancel" (`A/ui/maintenance/GroupEditScreen.kt:77`, `:70`);
"The date cannot be later than today." (`DATE_NOT_LATER_THAN_TODAY`, `A/ui/condition/ChangeConditionViewModel.kt:35`);
"Replaced by %s on %s", "Replaces %s" and the day format (`ReplaceStrings.replacedBy`, `.replaces`, `.day`,
`A/ui/replace/ReplaceStrings.kt:106`, `:109`, `:115`).

**G-list (developer-facing, for the same ratification):**
- **G1** — each C2 code's `message`: "no such installed component"; "an installed component needs a name"; "a date
  must be YYYY-MM-DD"; "a date cannot be later than today"; "the removal date cannot be before the install date";
  "the parent belongs to another asset"; "the parent has been removed"; "this installed component has already been
  removed"; "every composition quantity must be a number above zero"; "the installed component was refused" (the
  fallback); and the reused codes' shipped messages.
- **G2** — the codec's `BackupCorrupt` messages (C10), in the shipped templates.
- **G3** — the MCP's `APP_SCHEMA_TOO_OLD` feature name "installed components".

**Not drawn anywhere (decided by the contracts):** a name-required sentence (Save disabled); a delete confirmation
(nothing deletes a component); a reorder control (R47-11); a typed confirmation on remove (nothing is erased).

## 6. Owner rulings (owner, 2026-10-01 — recorded on #47; every owner ruling DECIDED)

| ruling | whose | the ruling | where it lands |
|---|---|---|---|
| **R47-1** (Q1) | owner — **DECIDED** | **Names:** `InstalledComponent` / `installed_component` / `/v1/installed-components` / `*_installed_component(s)` (and `install_component`); the phone "Installed components"; the eleven child-Asset phone strings → "Child asset(s)"; the child-Asset API/MCP contract (`/v1/assets/{id}/components`, the `components` keys, `create_component`, the loader's read) untouched until #98. | C1, C28, §5, B7 |
| **R47-2** (Q2) | owner — **DECIDED** | **One row per fitted instance** (parent?, `supplyId?` FK RESTRICT, `installedOn?`, `removedOn?`, **`replacesId?` on the successor**, `sortOrder`); closed rows are the history; no history table; no `InstalledAssembly`. (The planner brief's `replacedById` on the closed row is superseded.) | C4, C6, C18 |
| **R47-3** (Q3) | owner — **DECIDED** | **`supply_id` FK RESTRICT** (direct and composition alike); an archived SupplyItem may stay on an existing link or composition and never enter a new one; no rule ties fitted state to applicability. | C6, C15–C19 |
| **R47-C** | owner — **DECIDED** (the correction) | **Aggregate composition:** an installed component may be made of several SupplyItem entries with quantity and unit, same or different SupplyItems; the composition belongs to its instance row and stays with it when it closes; a replacement starts with its own; no stock decrement, reorder, schedule requirement, automatic event or per-unit history (that is the child-row form). | C4, C5, C9–C26 |
| **R47-4** (Q4) | owner — **DECIDED** | **History travels whole** in a Transfer Pack (`ASSET_OWNED`). | C13 |
| **R47-5** (Q5) | owner — **DECIDED** | **A normal bump to schema 19 / format 19**; schema 18 is not re-opened; version stays 1.6.0 / 19. The release-proof form — a separate schema-19 paragraph naming the schema-18 paragraph's checks as its own — is the controller's (C29). | C7, C10, C29 |
| **R47-6** (Q6) | owner — **DECIDED** | **A parent's removal or replacement closes its whole current subtree** on the same date in one transaction; the replacement starts empty. | C17, C18 |
| **R47-7** (Q7) | owner — **DECIDED** | **No `eventId` field at all**; never an automatic event. | C4, limit 1 |
| **R47-8** (Q8) | owner — **DECIDED** | **`installedOn` may be unknown;** `removedOn` required on an explicit remove or replace. | C4, C16–C18 |
| **R47-9** (Q9) | owner — **DECIDED** | **#86's successor gets no installed components.** | C30.4, limit 2 |
| **R47-10** (Q10) | owner — **DECIDED** | `schedule_supply_requirement` is **#96's**; #47 adds nothing for it. | §1 |
| **R47-11** | controller default | **`sortOrder`:** install appends (one more than the greatest current sibling, 0 when none), replace inherits, the API and MCP may set it, the phone has no reorder control; drawn `(sortOrder, name casefolded, id)`. Cost if wrong: a phone "move" control is one later brief. | C4, C5, C16 |
| **R47-12** | owner — **DECIDED** | **The health `PART` binding, NFC and resources (#69) are not in #47.** | C30.4 |
| **R47-13** | controller default | **#47 represents both compositions** — instance-level (child rows) and aggregate (a composition) — and #96 plans which a service uses. | §1, C29 |
| **R47-14** | controller default | **The tree helper is a new typed `InstalledComponentTree`**; `AssetTree` untouched. The alternative (a generic helper `AssetTree` delegates to) touches shipped restore, merge and wipe code. | C5 |
| **R47-15** | controller default | **Edit's reach:** name, direct link, composition, serial or lot, install date, notes (and `sortOrder` over the API), on current and removed rows; never the asset, parent, removal date or `replacesId`. | C19, limit 7 |
| **R47-16** | controller default | **UI shape:** no parent picker ("Install inside" from a row's sheet); removed rows with no successor behind one toggle; the SupplyItem detail gains nothing (the #15 fence). | C25–C27 |
| **R47-17** | controller default | **The composition is a nested child table** (§4's costing); on a replace, an absent `composition` (and `name`, `supplyId`) is the predecessor's, copied with fresh ids, and the phone prefills it. If the owner reads "starts with its own composition" as "starts empty", the default flips to `[]` in one handler line and one prefill. | C3, C15, C18, C20, C26 |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; B5 `uv run --frozen pytest` in `M/` and in `S/` (unchanged). No device run in
  any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **58 classes**, zero
  skips; row 53 and the relabel's eight classes run here first — a red is one post-merge fix round), R3 (the three
  Python suites), R5 (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps.
  `ReleaseProofPolicyTest` unchanged and green. Timed; the 14- and 15-minute lines are reporting only.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'^    const val FORMAT_VERSION = 19$'` → 1; `'^    internal const val FIRST_INSTALLED_COMPONENT_FORMAT = 19$'` → 1;
    `LegacyArchive.kt` diff → empty.
  - `'^    version = 19,$'` in `AppDatabase.kt` → 1; `'^        const val SCHEMA_VERSION = 19$'` → 1;
    `'MIGRATION_18_19'` in `AppGraph.kt` and `MigrationTestSupport.kt` → each equal to `'MIGRATION_17_18'`'s count
    there; `'ALTER TABLE'` inside `MIGRATION_18_19` → 0 (read it); `19.json` present, `18.json` diff → empty.
  - `'^value class InstalledComponentId\('` → 1; `'^data class InstalledComponent\('` → 1; `'^data class
    CompositionEntry\('` → 1; `'fun delete'` inside the `InstalledComponentRepository` block, the DAO and the
    repository → 0 (E-1's lesson: counted per type block, anchored `fun delete(All)?\(`).
  - `MergeTable`'s last line ends `ASSET_SUPPLIES, INSTALLED_COMPONENTS,`; `'INSTALLED_COMPONENT_REPLACEMENT_TAKEN'` in
    `MergePlan.kt` → 1 declaration; the installed-component section's first line between the applicability and the
    profiles sections (read it).
  - `'"installedComponents" to TransferTableClass.ASSET_OWNED'` → 1; `'seventeen'` in `HeldWriteGuard.kt` and
    `AppGraph.kt` → 0; `'installedComponents ='` inside `ReturnScope.of` → 1.
  - Each C2 code in `A/api` → its arm(s) only; each as `'^\| (404|409|422) \| `CODE` \|'` in `docs/api/v1.md` → 1;
    `'else ->'` inside the new `when`s → 0; `'1–18'` in `v1.md` → 0.
  - `'^@mcp\.tool\('` in `server.py` → 89; `'^_MIN_INSTALLED_COMPONENT_SCHEMA_VERSION = 19$'` → 1; `'format 1–19'`
    → 1 and `'format 1–18'` → 0; `'format \*\*1–19\*\*'` in `M/README.md` → 1.
  - C30's tripwires, word and untouched greps; the tombstone check → 0; gitlink `7e0377a`; `versionName` /
    `versionCode` unchanged; `git diff <base> -- tools/servicetag-schedules tools/servicetag-bundle` → empty.
- **The schema-19 upgrade note.** The schema 18 → 19 in-place upgrade of a signed build is **not** proven by this
  plan's gate: it is the carrying release's gate, written by B8 (C29) beside the schema-18 one — and since no release
  carries 18 yet, that first release proves both paragraphs.
- **The emulator and the phones.** The merged-tip gate runs a debug build at schema 19 on the emulator only. Not
  proven by #47: the phones (untouched; no install), an on-device export, a Transfer Pack between two phones (JVM-proven
  in rows 28–30), the owner's real equipment (never in a test).

## 8. Relationships

- **#15 — SupplyItems.** Reused unchanged: the stable archive-only `SupplyId` (both links name it), the picker, the
  link line and the catalog reads; the pack's "by naming row" rule takes the component's direct link and entries
  (C13). Applicability stays configuration ("this UPS takes X"); a composition says what one fitted unit is made of.
  The #15 fences stay true (C30.4).
- **#69 — resources.** Plugs in later as `AttachmentOwner.OfInstalledComponent(InstalledComponentId)`
  (`C/model/Attachment.kt:17-20`, `accepts` at `:29`), a nullable owner column on `attachment` (the 12-step recreate is
  #69's), the codec's owner check and `TransferOwnership.of(attachment)`; the row sheet has room below "History". #47
  guarantees an immutable id and asset, no single-row delete, and the route noun (#92 plan `:787`).
- **#95 — inventory.** A composition quantity is never stock; C30's tripwire guards it.
- **#96 — requirements and orchestration.** Schedules naming SupplyItems or positions, choosing pack-vs-rebuild,
  batch replacement and one completion composing several component mutations are #96's; #47's use cases each write
  inside one `uow.write`, so #96 can compose them later without a #47 column.
- **#86 — succession.** Untouched; R47-9. **#88** — no range or condition. **#98** — the child-Asset API/MCP nouns'
  rename, if any. **#76** — the roadmap of record. **#90** — the gate-time trigger is not reached (§3). **#62** — no
  black-box UI driving; row 53 is tier-2 Compose semantics.
- **The 1.4 spec** promised a health `PART` binding "for #47 later"
  (`docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md:1244`); the narrowed #47 does
  not build it (R47-12) — a later issue may bind a `PART` subject to an `InstalledComponentId`.

## Briefs — common to every brief (fourteen dispatches)

Read §1–§8, the audit, issue #47 (the GitHub body with the 2026-10-01 correction) and every earlier report on this
branch. **Dispatch precondition:** §5 RATIFIED (every owner ruling is DECIDED, §6). **Constructor plumbing (C-1):** a
brief whose contract adds a constructor parameter owns its `AppGraph` line, its `FakeGraph` line and every test
construction site `git grep` names — arguments only; no `AppGraph` or `FakeGraph` edit after B3b.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (over `app/src/main` and `core/src/main` unless
named); the untouched diff against the brief's "never touches"; C30's tripwire, word and untouched greps; the
tombstone check → 0; `git ls-tree HEAD libs/nfc-tag-core` → `7e0377a…`; `versionName` / `versionCode` equal
`<base>`'s; `git diff <base> -- app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml tools/*/uv.lock
app/src/main/AndroidManifest.xml` → empty; `git status` clean. B5 adds `uv run --frozen pytest` in `M/` and `S/`.

**Pin rule and the twin rule.** A shipped assertion moves only where §3's table names it for this brief, or as its
twin (Global constraints). Confirm the set first with `git grep -nE 'FORMAT_VERSION|formatVersion\)|schemaVersion\)|
SCHEMA_VERSION|1–18|18 since|[Tt]wenty|seventeen|counts\.size|dropLast'` and `git grep -nE 'TOOL_NAMES|\b84\b'` over
`app/src/test app/src/androidTest core/src/test tools/servicetag-mcp/tests`; report every hit the table does not name
and how the twin rule treats it before editing.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each grep
with its count; the test counts before and after; every twin moved; the wall time against the box; what is done,
proven and not proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin and twin
rules; a JVM or build failure the brief did not cause (reported, not retried); network access from a test; a phone
string not in §5; a schema or format change, or a table, outside B1b/B2a; a cited line that reads differently on
`<base>`; an undecided owner question; the cap reached, the 1-hour target passed with under half the rows green, or the
2-hour hard stop.

**Must NOT, always:** step outside the **HARD SCOPE** (owner, binding): "#47 MVP = canonical nested InstalledComponent
identity + current fitted state + install/remove/replacement history + optional direct SupplyItem linkage + aggregate
SupplyItem composition. Nothing more." — so never add stock, quantity on hand, reorder, lead time or procurement
(#95); a schedule material requirement, kit, batch replacement, pack-vs-rebuild planning or orchestration (#96); an
attachment, reference, photo or document on a component (#69); a range, interval or condition (#88); an NFC tag,
binding or scan route on a component; an `eventId` or any automatic event; a carry-forward in #86's Replace; a health
binding; an `InstalledAssembly` or a history table. Also never: put a bare `component(s)` on a new machine surface;
delete a component or write a delete port; decrement anything from a composition; link or compose by matching names;
delete a shipped assertion; commit outside the brief's files; add a device-boundary class or run a device; bump a
version; write a repository from a handler or a view model; re-check in the API or UI a rule the use case owns; touch
the 2.6 tombstones, `18.json`, `C/reminders`, `A/reminders`, `S/`, `tools/servicetag-bundle`, `tools/emulator/*`, a
harness helper or the gate script; add a dependency; write a ledger; quote the owner's private data or write a real
name, host, serial or e-mail address (fixtures: `example.invalid`).

## 9. B1a — the domain, the tree and the shape rules (C4–C5; core JVM)

**Read:** audit §2.1, §2.2, §3, §4; `C/model/{Ids,SupplyItem,AssetTree,AssetLoan}.kt`; `C/ports/Repositories.kt:350-391`;
`C/usecase/{LoanCommands,AssetSupplyCommands}.kt`; `CT/model/AssetTreeTest.kt`. `<base>` = master at dispatch plus
this plan's commit. **Rows:** 1–4. **Rulings:** R47-2, R47-3, R47-8, R47-11, R47-14, R47-17. **Interfaces produced:**
`InstalledComponentId`, `InstalledComponent`, `CompositionEntry`, `InstalledComponentRepository`,
`InstalledComponentTree`, `InstalledComponentProblem`, `installedComponentProblems`, `compositionProblems` — consumed by
every later brief. **Greps:** `'^value class InstalledComponentId\('` → 1; `'^data class InstalledComponent\('` → 1;
`'^data class CompositionEntry\('` → 1, no `=` default in either (read them); `'fun delete'` in the port block → 0;
`git diff <base> -- C/model/AssetTree.kt` → empty. **Untouched:** `app/**`, `C/backup`, `C/merge`, `C/transfer`, every
shipped use case. **Must NOT:** add a field beyond C4's; give a field a default; add `eventId`, a quantity-on-hand, or a
delete. **Counted RED (4):** rows 1, 2, 3, 4. **Caps:** 6 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about
170 production, 220 test lines. **Estimate:** 40 min.

## 10. B1b — Room schema 19 (C6–C8; app JVM)

**Read:** audit §0.5, §2.3, §3 (H1); `A/data/room/entities/SupplyEntities.kt`; `A/data/room/dao/SupplyDaos.kt`;
`A/data/room/{Migrations,AppDatabase,SupplyRepositories,SupplyMappers}.kt` (`Migrations.kt:760-808`);
`A/di/AppGraph.kt:255-270`, `:355-366`, `:1063`; `T/data/room/{Migration17To18Test,MigrationTestSupport,
SupplyDaoConstraintTest}.kt`; `T/testing/FakeGraph.kt`. `<base>` = B1a's accepted tip. **Rows:** 5–9. **Rulings:**
R47-2, R47-3, R47-5. **Interfaces produced:** the two tables at schema 19, the Room repository wired raw in `AppGraph`
and `FakeGraph` — consumed by B2a–B3b. **Greps:** `'^    version = 19,$'` → 1; `'^        const val SCHEMA_VERSION =
19$'` → 1; `'MIGRATION_18_19'` counts equal `'MIGRATION_17_18'`'s in each file; inside `MIGRATION_18_19` no `ALTER`,
`UPDATE`, `INSERT`, `DEFAULT` (read it); `'ON DELETE RESTRICT'` in it → exactly the two `supply_id` FKs; `19.json`
added, `18.json` unchanged; `'FORMAT_VERSION = 18'` in `BackupCodec.kt` → 1 (the format is B2a's). **Pin list:** §3's
B1b rows. **Untouched:** `C/**` main, `A/api`, `A/ui`, `docs`, `tools`. **Must NOT:** make any self- or child FK
RESTRICT; add an FK to `replaces_id`; add a column to an existing table; wrap the port (B2c's). **Counted RED (3):**
rows 5, 7 (the H1 mutation — its quoted failure is the H1 evidence), 8. **Caps:** 5 runs; 1 h / 2 h; fix round 3 runs,
45 min. **Size:** about 230 production, 280 test lines, plus `19.json` and ~10 pin sites. **Estimate:** 55 min.

## 11. B2a — backup format 19 (C9–C11; core JVM) — and its split clause

**Read:** audit §2.4, §2.7; `C/backup/BackupFormat.kt:620-726`; `C/backup/BackupCodec.kt:100-215`, `:270-330`,
`:440-485`, `:540-575`, `:900-950`; `C/backup/BackupContentCheck.kt:80-175`, `:215-230`; `C/usecase/ExportBackupSet.kt:85-210`;
`C/usecase/ImportBackupReplace.kt:100-225`; `C/transfer/TransferGraph.kt:36-64`; `CT/backup/BackupFormat18Test.kt`;
the construction sites (§4). `<base>` = B1b's accepted tip. **Rows:** 10–18, 27. **Rulings:** R47-3, R47-5, R47-17.
**Interfaces produced:** both DTOs, `BackupData.installedComponents`, `FIRST_INSTALLED_COMPONENT_FORMAT`, the
`BackupRepositories` port — consumed by B2b, B2c, B4. **Greps:** `'^    const val FORMAT_VERSION = 19$'` → 1;
`'^    internal const val FIRST_INSTALLED_COMPONENT_FORMAT = 19$'` → 1; `'"compositionEntries" to'` → 1;
`'"installedComponents" to TransferTableClass.ASSET_OWNED'` → 1; `LegacyArchive.kt` diff → empty. **Pin list:** §3's
B2a rows (the format literals, the triples, `counts.size` 29 → 31, the key positions, the list counts, the
classification). **Untouched:** `C/merge`, every use case but the two named, `C/transfer` bar the one line, `A/**` main
bar wiring, `docs`, `tools`. **Must NOT:** give a DTO field a default (only the `BackupData` list); accept a row below
format 19; skip an entry's SupplyItem resolution. **Counted RED (6):** rows 10, 12, 13, 14, 15, 17. **Caps:** 8 runs;
1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 200 production, 380 test lines, plus 24 construction sites and ~45
pin assertions. **Estimate:** 60–70 min — **split clause taken by this estimate unless the controller's dispatch
estimate is under 1 h:** **B2a2** = C11 (export, replace import, their 24 construction sites, row 17) off B2a's tip.

## 12. B2b — the merge (C12; core JVM, one app row)

**Read:** audit §2.5, §3; `C/merge/MergePlan.kt:55-95`, `:150-170`, `:330-370`, `:415-540`, `:600-615`;
`C/merge/MergePlanner.kt:380-430`, `:530-630`, `:1240-1345`, `:1440-1480`; `C/usecase/{ApplyBackupMergePlan,
BuildBackupMergePlan}.kt` (the constructors, `:160-215`, `:95-110`); `CT/merge/{MergePlannerSupplyTest,
MergePlannerSuccessionTest}.kt`; `T/api/ApiRouterTest.kt` (its import-merge case). `<base>` = B2a's (or B2a2's) tip.
**Rows:** 19–26. **Rulings:** R47-2, R47-3. **Interfaces produced:** `MergeTable.INSTALLED_COMPONENTS`, the reason, the
snapshot and writes lists, the tally — consumed by B2c, B4, B5. **Greps:** `MergeTable`'s last line ends
`ASSET_SUPPLIES, INSTALLED_COMPONENTS,`; `'INSTALLED_COMPONENT_REPLACEMENT_TAKEN'` → 1 declaration; the section's first
line between `--- applicability` and `--- profiles` (read it); `'^enum class MergeHint'`'s line unchanged.
**Pin list:** §3's B2b rows. **Untouched:** `C/backup`, `C/transfer`, every use case but the two, `A/ui`, `A/data`,
`docs`, `tools`; `ApiDtos.kt` beyond the report rows. **Must NOT:** add an UPDATE verdict or any write to an existing
row; add an older-archive exception; give the composition a `MergeTable` member; move a shipped `MergeTable` or
`MergeReason` member. **Counted RED (6):** rows 19, 20, 21, 22, 23, 24. **Caps:** 8 runs; 1 h / 2 h; fix round 3
runs, 45 min. **Size:** about 150 production, 340 test lines, plus 18 construction sites. **Estimate:** 50 min.

## 13. B2c — the Transfer Pack and the held guard (C13–C14; core JVM)

**Read:** audit §0.7, §2.6; `C/transfer/TransferGraph.kt:150-320`; `C/transfer/TransferOwnership.kt:70-120`;
`C/transfer/HeldWriteGuard.kt:55-150`, `:250-260`, `:470-490`; `A/di/AppGraph.kt:288-302`, `:355-366`;
`C/usecase/ApplyBackupMergePlan.kt:160-190`, `:255-360`; `CT/transfer/{TransferGraphTest,ImportTransferPackTest,
HeldWriteGuardTest}.kt`. `<base>` = B2b's tip. **Rows:** 28–31. **Rulings:** R47-4. **Interfaces produced:** the
guarded `InstalledComponentRepository` — consumed by B3a. **Greps:** `'seventeen'` in `HeldWriteGuard.kt` and
`AppGraph.kt` → 0; `'installedComponents ='` inside `ReturnScope.of` → 1; `'fun of\(component: InstalledComponent\)'`
→ 1. **Untouched:** `C/backup`, `C/merge`, `C/usecase` bar `ReturnScope.of`, `A/api`, `A/ui`, `docs`, `tools`.
**Must NOT:** drop removed rows from a pack; filter `supplyItems` in `retain` or `ReturnScope`; add an
`entangledRefs` rule. **Counted RED (4):** rows 28, 29, 30, 31. **Caps:** 6 runs; 1 h / 2 h; fix round 2 runs, 45 min.
**Size:** about 60 production, 220 test lines. **Estimate:** 40 min.

## 14. B3a / B3b — the use cases (C15–C19; core JVM) — split by plan

**B3a — commands, install and remove.** **Read:** audit §4, §5; `C/usecase/{AssetSupplyCommands,AddAssetSupply,
SaveSupplyItem,SaveProfile,LoanCommands,EventCommands}.kt` (the entry-id rule `SaveProfile.kt:88`, the quantity
parse `EventCommands.kt:250`); `C/usecase/ReplaceAsset.kt:195-230`; `CT/usecase/{AddReferenceTest,
SupplyItemUseCasesTest}.kt`. `<base>` = B2c's tip. **Rows:** 32, 33, 35. **Rulings:** R47-3, R47-6, R47-8, R47-11,
R47-17. **Interfaces produced:** the commands, results, `InstallComponent`, `RemoveInstalledComponent`, wired —
consumed by B3b, B4, B6. **Greps:** `'data class InstallComponentCommand\('` → 1, no `=` default (read it);
`'class DeleteInstalledComponent|fun deleteInstalledComponent'` → 0. **Must NOT:** write an event, line,
applicability row or SupplyItem; accept an archived SupplyItem on install; open a transaction for a command-only
refusal. **Counted RED (3):** rows 32, 33, 35. **Caps:** 5 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about
190 production, 280 test lines. **Estimate:** 50 min.

**B3b — replace, update and the fixtures.** `<base>` = B3a's tip. **Read:** B3a's report;
`C/journal/SeedTemplates.kt:100-140`; `CT/usecase/SupplyItemFixtureTest.kt:30-130`. **Rows:** 34, 36–39. **Rulings:**
R47-2, R47-3, R47-6, R47-15, R47-17. **Interfaces produced:** `ReplaceInstalledComponent`,
`UpdateInstalledComponent`, wired — **the last `AppGraph` / `FakeGraph` edit** — consumed by B4, B6. **Greps:**
`'data class UpdateInstalledComponentCommand\('` carries no `assetId`, `parentId`, `removedOn` or `replacesId` (read
it); `git diff <base> -- C/journal/SeedTemplates.kt C/usecase/ReplaceAsset.kt` → empty. **Must NOT:** reuse a
predecessor's entry ids; move a predecessor's composition; touch any row outside the predecessor, its current subtree
and the successor; edit a removal date. **Counted RED (4):** rows 34, 36, 37, 39. **Caps:** 6 runs; 1 h / 2 h; fix
round 3 runs, 45 min. **Size:** about 170 production, 380 test lines (the fixtures ~150). **Estimate:** 55 min.

## 15. B4 — the API and the wire document (C20–C22; app JVM, docs) — and its split clause

**Read:** audit §8; `A/api/{ApiRouter,ApiHandlers,ApiJson,SupplyDtos,SupplyHandlers}.kt` at the cited lines;
`docs/api/v1.md` (`:200-215`, `:575-590`, `:1515-1610`, `:2000-2050`, `:2105-2115`, `:2295-2310`);
`T/api/{SupplyRoutesTest,ReferenceRoutesTest,CommandShapesGoldenTest}.kt`. `<base>` = B3b's tip (B4's lane); count the
`ApiHandlers(` sites first. **Rows:** 40–46. **Rulings:** R47-1, R47-17. **Interfaces produced:** the six rows and the
codes — consumed by B5. **Greps:** each C2 code in `A/api` → its arm; `'else ->'` in the new `when`s → 0;
`'"installedComponents" to'` and `'"compositionEntries" to'` in `ApiHandlers.kt` → 1 each; `'Seventy-seven path
shapes over ninety-five'` → 1; each C2 row in `v1.md` → 1; `'1–18'` in `v1.md` → 0; `git diff <base> --
docs/api/command-shapes.json` → empty. **Pin list:** §3's B4 rows. **Untouched:** `C/**`, `A/ui/**` main, `A/data`,
`A/di`, `FakeGraph`, `tools`. **Must NOT:** add a delete route or a tri-state reader; rename a child-Asset noun; draw a
sentence from a `message`. **Split clause:** past 1 h, C22's `v1.md` part and row 45 go to **B4b** in the same lane.
**Counted RED (5):** rows 40, 41, 42, 43, 44. **Caps:** 7 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about
300 production, 420 test, 180 document lines, plus ~12 construction sites. **Estimate:** 60 min — at the target, so the
split is the controller's call at dispatch.

## 16. B5 — the MCP (C23–C24; pytest)

**Read:** `M/src/servicetag_mcp/server.py:70-90`, `:180-340`, `:1240-1300`, and the supply tools; `M/README.md`
(`:390-420`); `M/tests/{test_supply_tools,test_argument_guard,test_tools}.py`. `<base>` = the tip holding B4 and B6c.
**Rows:** 47–49. **Rulings:** R47-1, R47-17. **Greps:** `'^@mcp\.tool\('` → 89; `'^_MIN_INSTALLED_COMPONENT_SCHEMA_VERSION
= 19$'` → 1; `'_require_installed_component_schema\('` → the definition and five calls; `'format 1–19'` → 1, `'format
1–18'` → 0; `git diff <base> -- tools/servicetag-schedules tools/servicetag-mcp/src/servicetag_mcp/command_shapes.py`
→ empty. **Pin list:** §3's B5 rows. **Untouched:** `app/**`, `core/**`, `S/**`, `docs`. **Must NOT:** touch
`create_component`; add a delete tool or a `clear_fields` argument; send a key the caller did not give. **Counted RED
(2):** rows 47, 48. **Caps:** 4 pytest mutation runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 220
production, 300 test lines. **Estimate:** 50 min.

## 17. B6a / B6b / B6c — the phone section (C25–C27; app JVM, Compose) — split by plan

**Read (all three):** audit §7; `A/ui/supplies/{AssetSuppliesSection,AssetSuppliesSectionViewModel,SupplyItemPicker,
SupplyLinkLine,SupplyListViewModel,SupplyStrings}.kt`; `A/ui/asset/AssetDetailScreen.kt:430-460`; `A/ui/asset/
AssetEditScreen.kt:940-990` (`DateField`); `A/ui/replace/ReplaceStrings.kt:100-125`; `AT/ui/supplies/SupplySurfacesTest.kt`.
**Rulings:** R47-1, R47-3, R47-6, R47-15, R47-16, R47-17; §5 ratified. **Shared greps:** each P47 constant declared
once in `InstalledComponentStrings.kt`; string literals only: `git grep -hoE '"[^"]*"' -- A/ui/installed | grep -iE
'\bparts?\b|assembly|\bkits?\b'` → 0; no `ViewModel` import in the stateless list or sheet composables; `'archivedAt ==
null'` in the view model → 1. **Untouched:** `C/**`, `A/api`, `A/data`, `A/di`, `A/nav`, `A/ui/supplies`, `docs`,
`tools`. **Must NOT:** add a parent picker, a reorder control or a typed confirmation; offer an archived item in a
picker; overwrite a typed unit on a pick; call a repository from a view model; draw an API `message`.

- **B6a — the section and its read state** (C25, C27; rows 50 and 53's list cases). `<base>` = B3b's tip (the UI
  lane). The one call in `AssetDetailScreen.kt` before `ComponentsSection(` → 1. **Counted RED (1):** row 50. **Size:**
  about 230 production, 200 test lines. **Estimate:** 45 min.
- **B6b — the sheets without composition** (C26: row sheet, install/edit/replace, remove; rows 51 and 53's sheet
  cases). `<base>` = B6a's tip. **Counted RED (1):** row 51. **Size:** about 300 production, 230 test lines.
  **Estimate:** 55 min.
- **B6c — the composition editor and display** (C26's composition parts; rows 52 and 53's composition cases).
  `<base>` = B6b's tip. **Counted RED (1):** row 52. **Size:** about 160 production, 160 test lines. **Estimate:**
  40 min.

**Caps:** 4 runs per part; 1 h / 2 h each; fix round 3 runs, 45 min. The Compose cases (row 53) first run at the
merged-tip gate; each part compiles them (`:app:compileDebugAndroidTestKotlin`).

## 18. B7 — the relabel (C28; app JVM, Compose sources)

**Read:** audit §1.1, §9; the eleven homes (C28); the §3 B7 pins, each read. `<base>` = the tip holding B5. **Rows:**
54. **Rulings:** R47-1; §5's R-list ratified. **Greps:** after the edit, `git grep -nE '"[^"]*\b[Cc]omponents?\b[^"]*"'
-- app/src/main` lists only `A/ui/installed/` strings, `ApiJson.kt:366` and comments or KDoc (read each); each R-string
→ 1 at its home; every identifier named in C28 unchanged (`git diff <base> -U0 | grep -cE '^[-+].*(ComponentRow|
componentsOf|onToggleComponents|isComponent|ComponentCondition)'` → 0). **Pin list:** §3's B7 rows. **Untouched:** `C/**`,
`A/api`, `tools`, `docs`, `A/ui/installed`. **Must NOT:** rename an identifier, an API key, a route or a tool; change a
sentence's meaning beyond the word. **Counted RED:** none (ratified words; the tests follow). **Caps:** 1 h / 2 h.
**Size:** about 15 production, ~30 test lines in ~11 classes. **Estimate:** 40 min.

## 19. B8 — the documents and the release-proofs paragraph (C29; docs)

**Read:** `docs/release-proofs.md:100-135`; `docs/design/14-asset-model.md`; `docs/design/04-domain-data-model.md:190-210`;
every brief report on this branch. `<base>` = B7's tip. **Rulings:** R47-5, R47-6, R47-7, R47-9, R47-12, R47-13.
**Greps:** the schema-19 paragraph present once after the schema-18 one (`'^\*\*The first signed release carrying Room
schema 19'` → 1); D14's `:30-32` sentence unchanged (`git diff <base> -U0 -- docs/design/14-asset-model.md` shows no
`-` line for it); no #95/#96 word added (C30.2); `git diff <base> -- docs/versioning.md README.md` → empty.
**Untouched:** every source and test file, `docs/api`, `tools`. **Must NOT:** quote the owner's private data; promise a
release or name a version; claim a journal link, a stock effect or a health binding. **Counted RED:** none
(documents). **Caps:** 1 h / 2 h. **Size:** about 110 document lines. **Estimate:** 35 min.
