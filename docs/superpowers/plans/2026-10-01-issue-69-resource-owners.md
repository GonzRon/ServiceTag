# #69 — resources owned by SupplyItems and installed components, and Share intake to all three owners: plan and briefs (rev 1, 2026-10-01)

> **Status: PLANNING ONLY — at the owner gate.** Four owner questions are open (§6: R69-0 the scope sentence, R69-2
> the vendor URL, R69-3 Share's first step, R69-4 the Asset detail) and §5's strings await ratification as **one
> block**. Every other R69 ruling is a controller default the owner may overrule. **Dispatch precondition:** R69-0,
> R69-2, R69-3 and R69-4 ruled, §5 ratified. **Release line:** no version bump and no release — `versionName` /
> `versionCode` stay **1.6.0 / 19**; the vehicle is the owner's (the post-#98 fresh 1.0.0 on the train #47 → #69 →
> #16 → #98). #69 lands **Room schema 20 / backup format 20** (R69-5) on master, validated on the emulator only; master
> builds never go on a phone.

> **Scope, PROPOSED for the owner's ratification (pending R69-0), binding on every brief once ruled:** *#69 = one
> resource system — the shipped attachments and references, the AttachmentStore, the Share intake and the #85
> materialization — with three explicit owners (Asset, SupplyItem, InstalledComponent), ownership following what a
> resource is about, visibility by navigation without duplicated rows or bytes, Share intake to all three owners under
> the #43 rules, and the same backup / merge / Transfer Pack travel for every owner. Nothing more.* **Out:** a
> purchase-order / acquisition-lot system (a later purchasing feature); stock (#95); schedule material requirements
> (#96); any change to #47's fitted-state semantics or #15's identity beyond what an owner needs; NFC; a second
> resource identity for child Assets (AC14); a second downloader, store or provenance representation (#85 reused); the
> shipped child-Asset API/MCP nouns (#98).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-01-issue-69/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-10-01-issue-69/audit.md`, every citation read on `ad49b788`.
> This planner re-read on the same base every site it cites beyond the audit, and **corrects the audit in five
> places** (§4, "Audit corrections"). **Fifteen dispatches on one branch `issue-69`:** B1a, B1b, B2a, B2a2, B2b, B3a,
> B3b, B3c, then **B4 ∥ (B6a → B6b → B6c → B7)**, then B5, B8 — each `<base>` the previous accepted tip; **B1a's `<base>`
> is master at dispatch** (today `ad49b788`: 1.6.0 / code 19, Room schema 19 / backup format 19, MCP 89 tools, gitlink
> `7e0377a`) plus this plan's commit. One task review each, at most one bounded fix round each, one whole-branch review,
> the merge, one merged-tip gate.

**Goal:** the shipped resource machinery — an `attachment` row with its bytes in the AttachmentStore, an
`asset_reference` row with no bytes, the role, the source provenance and the materialization that turns one into the
other — gains **two more owners**: a #15 **SupplyItem** (what a product *is*: its manual, data sheet, package photo,
vendor page, receipt as purchase evidence for the product) and a #47 **installed component** (what this fitted
instance *is*: its installation photo, label or serial photo, wiring photo, setup record). Each resource has exactly one
owner, fixed for life. A SupplyItem's resources are stored once and **reached** from every component, asset and Share
flow that names it; nothing is copied for visibility. Stored in Room schema 20, carried by backup format 20 through
export, replace, merge and the Transfer Pack, reachable over `/v1` and the MCP, drawn on the SupplyItem detail and on a
new installed-component screen, and targeted by Android Share. NFC, the journal, the scheduler, #47's fitted state and
#15's identity are unchanged.

**Inputs:** issue #69 with the owner's FINAL clarification of 2026-09-29 (`.superpowers/sdd/2026-10-01-issue-69/issue-69.md`,
the last comment); the audit; the #47 plan rev 1.1 with its §20 errata
(`docs/superpowers/plans/2026-10-01-issue-47-installed-components-mvp.md`) as the shape and the freshest precedent;
`docs/design/04-domain-data-model.md` (D4), `docs/design/14-asset-model.md` (D14); `docs/api/v1.md`;
`docs/release-proofs.md`; `docs/superpowers/planning-policy.md`. Paths: `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`,
`A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`,
`AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`, `M/` = `tools/servicetag-mcp/`, `S/` =
`tools/servicetag-schedules/`. Every `file:line` was read on `ad49b788`.

## Global constraints

- **One resource system, reused, never forked.** The shipped owner shape is extended, not paralleled: `attachment`
  already has two nullable CASCADE owner FKs with exactly-one enforced in code (`A/data/room/entities/AttachmentEntity.kt:13-20`,
  `:31-52`; `A/data/room/Mappers.kt:165-169`; `C/backup/BackupFormat.kt:1149-1154`) and a sealed owner
  (`C/model/Attachment.kt:17-20`). `asset_reference` takes the same shape by one table rebuild (`MIGRATION_7_8`'s
  precedent, `A/data/room/Migrations.kt:424-436`). The store (`A/attachments/SafTreeAttachmentStore.kt`), the sweep
  (`C/usecase/AttachmentSweep.kt`), the artifacts (`C/usecase/RestoreArtifacts.kt:70`), `FetchDocument`, the source
  provenance and the id-keyed edit/remove use cases are reused unchanged. **No second store, downloader, provenance
  field, link system, kind vocabulary or owner table.**
- **Exactly one owner, fixed for life.** An attachment names exactly one of {asset, event, SupplyItem, installed
  component}; a reference exactly one of {asset, SupplyItem, installed component}. A move is a delete plus an add (I-6
  unchanged: `C/model/AssetReference.kt:17-19`; `UpdateAttachmentCommand` has no owner). There is **no SQL `CHECK`**
  (Room's hash cannot see one, `AttachmentEntity.kt:16-20`): the Room write and the backup read enforce it, as shipped.
- **One schema step and one format step, forward-only, columns only.** Room 19 → 20 is two `ALTER TABLE … ADD COLUMN …
  REFERENCES … ON DELETE CASCADE` on `attachment` (the `:329` precedent) and one rebuild of `asset_reference`; no new
  table. Format 19 → 20 adds **two keys** to each of two existing DTOs and nothing else: **no new list, no new
  `MergeTable`, no new manifest count key, no new sub-resource on an asset** (audit fact 4). `19.json` is never edited;
  `LAST_LEGACY_FORMAT` stays 7.
- **The compile-atomic owner extension (the reason the brief split differs from the audit's §13).** Adding a member to
  a sealed owner makes every exhaustive `when` over it a compile error in the same commit, in both modules
  (`C/model/Attachment.kt:111-114`; `C/usecase/AddAttachment.kt:122-125`; `C/backup/BackupCodec.kt:1130-1138`;
  `C/usecase/ApplyBackupMergePlan.kt:296-299`; `C/transfer/TransferOwnership.kt:93-96`, `:130-139`;
  `C/transfer/HeldWriteGuard.kt:237-250`; `A/data/room/RoomRepositories.kt:108-124`). So: the **substrate** goes first
  and additively (B1a Room, B1b the format envelope); each owner type is then introduced by one **seam** brief (B2a
  attachments, B2b references) that writes every arm the compiler forces, each to its contract here, with its rows;
  the **semantics the compiler does not force** (merge availability, the pack, the return's references, the delete's
  bytes, the restore order) follow in B3a–B3c. Every brief leaves `:core:test` and `:app:testDebugUnitTest` green.
- **The twin-pin rule, up front (#15 §20, #47 E-4).** A shipped assertion moves only where §3's moving-pins table names
  it for the brief. A **twin** — an assertion outside the table pinning the same fact for the same reason (a format
  literal, a key order, a strip list, a route count, a tool docstring) — moves under the same rule and is listed in the
  report; anything else stops the brief. Every brief confirms its pin set with "Briefs — common"'s greps **before**
  editing. Expect 10–20 twins.
- **The 2.6 tombstones are never touched.** `git diff <base> -- . ':!app/schemas' | grep -cE
  '^[-+].*(external_link|externalLinks)'` → 0.
- **API version stays 1, additive.** Every refusal is a stable `code` (plus `field` where one body key is at fault) in
  the shipped envelope; 422 = change this body, 409 = change another row first, 404 = no such row, 400 = the decoder's
  refusal. Every new mapping is an arm of an exhaustive `when`, never an `else` (the router's verb `else -> notAllowed`
  stays, #47 E-18). **The API returns codes, not sentences.** Every row read on an asset's sub-resources reads exactly
  as today: an asset-owned row still carries a non-null `assetId`.
- **No inference.** Nothing assigns an owner by name, kind, MIME type, role or URI; a receipt is never "therefore" a
  SupplyItem's (the issue's receipt semantics); a component's resources are never copied to its SupplyItem or the
  reverse; installing, replacing or removing a component, or archiving a SupplyItem, creates, moves or deletes no
  resource. A resource has an owner only because a person (phone, Share, API, MCP) chose it, or a row carrying it was
  copied verbatim (archive, merge, pack).
- **The fence (C33).** No column, field, key, route, tool, string or test about a purchase, an order, an acquisition
  lot or a transaction; stock, quantity on hand, reorder or procurement (#95); schedule material requirements or kits
  (#96); a `vendor_url` (or any URL) column on `supply_item` (R69-2); an NFC tag, binding, scan route or "write tag"
  action on a SupplyItem or a component; a new `AttachmentKind` or `ReferenceKind` value (R69-6); a resource owner for
  a child Asset other than `OfAsset` (AC14); a change to `installed_component` or `supply_item`'s own columns. **C-3
  wording rule (#47):** no KDoc, docstring, document or test name added by a brief carries a #95/#96 or purchasing word,
  even to deny it — say what the surface does.
- **No personal data.** The owner's private data is never quoted, copied, counted into a fixture or loaded by a test.
  Fixtures are fictional ("Example Generator", "Example Alternator", "Example UPS", "Example Battery Tray", "Example 12 V
  Battery", manufacturer "Example Power Co."), every URL under `https://example.invalid/…`; no real name, host, serial
  or e-mail address in code, tests, docs or commits; home paths written `~`.
- **Strings.** Every user-visible string is ratified before it ships (§5, one block). The plan proposes the new ones
  (P69-1…) and names every reused one with its one home; developer-facing `message` texts are the G-list.
- **Tests.** JVM first (core over the core doubles through `BackupInstall`, then app over the production router and the
  Room-backed `FakeGraph`); Compose instrumented tests only for what a composable draws or wires (**one new class**,
  §3); **no new device-boundary class** — #69 crosses no new OS boundary (the Share grant is target-independent and
  already proven by `AT/share/ShareBoundaryTest.kt`). No test touches the network. Every counted RED is a real mutation
  run with `--no-build-cache --rerun-tasks`, its failing assertion quoted, reverted before the commit. **No
  rerun-until-green.** Device rows only for platform-only facts.
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump** (1.6.0 / 19). Commits: one
  casual lowercase subject line; no body, no trailers, no AI attribution. `tools/` never names a screen-driving tool.
  **Implementers never write ledgers.** No dependency is added. **Every `AppGraph` and `FakeGraph` edit lands in
  B1a–B3b** (#47 C-1): B3c, B4, B5, B6a–c, B7 and B8 edit neither.
- **Schema 20 on master.** Master debug builds carry schema 20 under version name 1.6.0: emulator only, never a phone.
  The schema-20 release gate is written by B8 into `docs/release-proofs.md` (C31), run by the release that carries it.
- **Time boxes:** every brief 1 h target, 2 h hard stop; past 1 h with under half the rows green, stop and report.
  Fix rounds 45 min, findings' files only. **Mutation caps** per brief. **Review budget:** one task review per brief,
  at most one bounded fix round, a scoped re-review only for a substantive correctness finding, mechanical fixes
  (< ~50 lines) by controller inspection, one whole-branch review.

## 1. Scope

**Scope (PROPOSED, pending R69-0):** the sentence in the header, verbatim.

**In scope — the acceptance criteria, each on its contracts** (AC1–AC14 as the issue numbers them; **E1–E7** are the
issue's "cross-cutting full-fidelity equipment acceptance" items):

| AC | the issue's words (abridged) | contracts |
|---|---|---|
| AC1 | a SupplyItem owns a product photo, PDF data sheet, receipt and vendor/product URL | C4 `OfSupplyItem` (both owners), C5 (`AddAttachment`), C13 (`AddReference`), C26, row 39 |
| AC2 | a non-Asset installed component owns installation photos, documents and web references | C4 `OfInstalledComponent`, C5, C13, C27, row 39 |
| AC3 | a component referencing a SupplyItem surfaces both resource sets without duplication | C27 (two ownerships, open-only groups; R69-8), rows 51–52 |
| AC4 | two components referencing one SupplyItem see the same manual, not copies | C27 (read by owner), I6, row 39 |
| AC5 | Share targets a SupplyItem or a component for a URL, image or document under the #43 rules | C29–C30, rows 53–54 |
| AC6 | shared URLs are durable references, reopened later | C29 (the link arm → `AddReference` with the owner), row 53 |
| AC7 | shared documents/images use the AttachmentStore | C29 (the bytes arm → `AddAttachment` with the owner), row 53 |
| AC8 | cancelling Share creates nothing | C29 (`save` the only writer, unchanged), row 53 |
| AC9 | backup/restore/merge preserve both owner types and their identities | C9–C15, rows 7–11, 29–37, 39 |
| AC10 | Asset surfaces reach their components' and SupplyItems' resources without copying | C27, C28 (navigation only; R69-4), row 52 |
| AC11 | a preferred SupplyItem vendor URL coexists with other durable references | C19, §4's vendor table (R69-2) |
| AC12 | component-specific material is not stored as SupplyItem knowledge | I2 (owner fixed), C30 (the person picks the owner), C33 (no inference), row 39 |
| AC13 | #85 materialization works identically for SupplyItem- and component-owned web references | C17, C21, rows 26, 44 |
| AC14 | a child Asset keeps ordinary Asset-owned resources | C4 (no child case), C33.4 (grep), row 40 |
| E1–E7 | the generic complex-equipment scenario: several SupplyItems' product resources; a component's own; reachability both ways; Share to either; #85 for both; backup/restore/merge preserving Asset → component → SupplyItem → resource | row 39 (JVM fixture), rows 51–54 (drawn), §7 (the schema-20 release paragraph exercises it on the emulator) |

**Out of scope** (the proposed sentence's list, and): a "where used" list of resources on the Asset detail (R69-4); a
vendor-URL column or a "preferred" marker (R69-2, unless the owner rules option D); moving a resource between owners
(a delete plus an add); a new attachment kind for specifications or diagrams (R69-6); resources on a maintenance
group, a schedule, a profile or a case; a purchase or lot row that *names* resources (a later feature, D4 note,
§8); renaming `AssetReference` / `asset_reference` / `assetReferences` (the name stays; §1 limit 1); a phone delete or
merge of duplicate resources across owners; the schedules loader and the bundle tool; a `command-shapes.json` entry.

**Recorded limits (stated, not fixed):**
1. **The name residue.** The table `asset_reference`, the archive list `assetReferences`, the domain
   `AssetReference` and the route noun `/v1/references` keep their names while their rows may belong to a SupplyItem
   or a component. Renaming them is a schema, format and wire break with no behaviour behind it; #98 may.
2. **A SupplyItem's resources are never held (H5).** A SupplyItem is global (`C/transfer/TransferOwnership.kt:68-78`):
   its resources travel in every pack whose rows name it, bytes included, and are never read-only. **Editing one while
   a pack naming its item is out makes that pack's return a `CONFLICT`** refusing the whole return, as an edited
   SupplyItem already does (`C/merge/MergePlanner.kt:572`; `applicable = conflicts.isEmpty()`).
3. **Pack size.** A large manual on a SupplyItem rides every pack that names the item (R69-7's cost).
4. **The vendor link is a reference row like any other (R69-2 as recommended).** "Preferred" is the owner's naming
   ("Vendor — …"), not a marker; several web references coexist (AC11).
5. **A removed component keeps its resources on its closed row**, and a replacement starts with none (no carry —
   R47-17b's spirit; the person adds or shares to the successor).
6. **An archived SupplyItem keeps its resources and may still gain them from its detail;** Share never offers it
   (R69-10).
7. **A format-20 archive or pack is refused by every schema-19 build** as a newer format (forward-only).
8. **The MCP refuses a SupplyItem or component owner key for a phone below schema 20 locally** (C24); an asset owner
   key works as today on any schema the tool already accepted.
9. **What the emulator cannot observe** (§7): the schema 19 → 20 in-place upgrade of a signed build (the release's
   gate, C31), the phones, an on-device export, a Transfer Pack between two phones, a real Share from another app to a
   non-Asset owner (the boundary is owner-independent; JVM and Compose prove the owner step).

## 2. Contracts

### Common (C1–C3)

- **C1, the words.** Domain `AttachmentOwner.OfSupplyItem(supplyId: SupplyId)` and
  `AttachmentOwner.OfInstalledComponent(componentId: InstalledComponentId)`; `ReferenceOwner` (new sealed:
  `OfAsset(assetId)`, `OfSupplyItem(supplyId)`, `OfInstalledComponent(componentId)`); `OwnerRef.OfInstalledComponent`.
  Columns `supply_item_id`, `installed_component_id` (both tables). Archive and wire keys `supplyItemId`,
  `installedComponentId` (beside the shipped `assetId` / `eventId`). Locator directories **`supply-items/<id>`** and
  **`installed-components/<id>`** (H6: permanent — never `components/`, never a word #98 might rename). Routes
  `/v1/supply-items/{id}/{references|attachments}` and `/v1/installed-components/{id}/{references|attachments}`. MCP
  arguments `supply_item_id`, `installed_component_id`. **Never a bare `component(s)`** on a new machine surface (a
  child Asset in five shipped layers until #98).
- **C2, the codes.** **No new error code.** Reused unchanged, with their shipped messages: `no_such_asset` (404);
  `NO_SUCH_SUPPLY_ITEM` (404); `NO_SUCH_INSTALLED_COMPONENT` (404); the shipped reference duplicate and refusal codes
  (`ReferenceProblem` → the shipped mapper); the shipped attachment codes; the guard's 409 `asset_transferred_out`;
  `400 bad_request` for a body naming **no owner or more than one** on `POST /v1/references` (G1's sentence, the #47
  E-19/E-20 precedent of a ratified developer-facing 400) and for any unknown key. A refusal naming a SupplyItem or
  component the path names: 404 with no `field`; one the body names: 404 with `field` `supplyItemId` or
  `installedComponentId`.
- **C3, the wire shapes.** Additive, API version 1; every row reuses the archive's DTO (`A/api/ReferenceDtos.kt:4-8`,
  `:26-29`; `A/api/AttachmentDtos.kt:33-37`), so the owner keys reach every response with no handler change:

| surface | shape |
|---|---|
| `GET /v1/assets/{id}/references`, `…/attachments` | **unchanged**: the asset's own rows only, each with `assetId` set and `supplyItemId` / `installedComponentId` `null` |
| `GET /v1/supply-items/{id}/references` | `{references: [AssetReferenceDto]}` — that SupplyItem's own rows, by `displayName` then `id` (the shipped order); archived item included |
| `GET /v1/supply-items/{id}/attachments` | the shipped asset attachments response shape, that SupplyItem's own rows |
| `POST /v1/supply-items/{id}/attachments` | the shipped upload (stream, metadata header, `operationKey`) with the owner from the path → 201; the row's locator under `supply-items/<id>/` |
| `GET` / `POST /v1/installed-components/{id}/attachments`, `GET …/references` | the same three, for a component (current or removed); a held asset's component reads as any other and refuses writes 409 |
| `POST /v1/references` | `{assetId?, supplyItemId?, installedComponentId?, uri, displayName, description?, role?}` — **exactly one owner key, non-null** (none, two or an explicit `null` as the only one → 400 G1) → 201 as shipped |
| `PATCH /v1/references/{id}`, `GET`/`PATCH /v1/attachments/{id}` | **unchanged** (id-keyed; an owner key in a PATCH body stays the decoder's 400 — the owner never changes) |
| `POST /v1/references/{id}/materialize` | **unchanged route and body**; the saved attachment lands on the reference's owner (C17); the held check follows the owner (C21) |
| `GET /v1/status` | `schemaVersion` 20, `backupFormatVersion` 20; `counts` **unchanged** (the attachment and reference totals already count every row) |
| `data.json` | `attachments[]` and `assetReferences[]` rows gain `supplyItemId` and `installedComponentId`, appended last, nulls written explicitly; `assetReferences[].assetId` may be `null` |

### B1a — Room schema 20 (C6–C8)

- **C6, `attachment` (audit §2(a); R69-1).** Two columns appended: `supply_item_id TEXT REFERENCES supply_item(id) ON
  UPDATE NO ACTION ON DELETE CASCADE` and `installed_component_id TEXT REFERENCES installed_component(id) ON UPDATE NO
  ACTION ON DELETE CASCADE`, each by `ALTER TABLE … ADD COLUMN` (default NULL — SQLite admits a REFERENCES column only
  so; the precedent `Migrations.kt:329`), plus `Index("supply_item_id")` and `Index("installed_component_id")` under
  Room's generated names. The entity (`AttachmentEntity.kt:31-74`) gains the two FKs, the two indices and the two
  fields **appended after `source_name`** (column order = ALTER order). `requireExactlyOneOwner` (`Mappers.kt:165-169`)
  counts **four** columns. **Fallback, pre-authorised:** if `Migration19To20Test`'s migrated-equals-fresh compare
  rejects the ALTER-added FK (audit concern 1), B1a rebuilds `attachment` the 12-step way (as C7) instead — same
  columns, same index names — and records it in the report; it is not a stop.
- **C7, `asset_reference` (fact 2; R69-1).** Rebuilt: `CREATE TABLE _new_asset_reference` with Room's exact v20 DDL
  (from `20.json`), `INSERT … SELECT` copying every v19 column with `NULL` for the two new ones, `DROP`, `RENAME`, then
  the four indices recreated under Room's names: `UNIQUE(asset_id, uri)` and `Index(asset_id)` (kept, `AssetReferenceEntity.kt:42-45`),
  **`UNIQUE(supply_item_id, uri)`** and **`UNIQUE(installed_component_id, uri)`** (one per owner: SQLite treats NULLs as
  distinct, so a single `UNIQUE(owner…, uri)` cannot hold, I3). `asset_id` becomes nullable; the two new columns are
  FK CASCADE to their owner tables. Safe because no table points at `asset_reference` (provenance keeps no
  `reference_id`, `C/model/Attachment.kt:52-55`). The entity's KDoc (`AssetReferenceEntity.kt:15-22`) is restated
  (three owners; I-6; I-7 per owner). A reference twin of `requireExactlyOneOwner` (three columns) runs on every Room
  write of a reference.
- **C8, the step, the DAOs and the pins.** `MIGRATION_19_20` in `A/data/room/Migrations.kt` (after `MIGRATION_18_19`,
  `:827`); `version = 20` (`A/data/room/AppDatabase.kt:103`); `SCHEMA_VERSION = 20` (`A/di/AppGraph.kt:1097`); both
  migration lists in `AppGraph` and `T/data/room/MigrationTestSupport.kt:69`; `app/schemas/…/20.json` generated.
  **No new table, so `V19_TABLES` and the whole-chain table sets do not move.** The DAOs gain owner-keyed reads beside
  the shipped ones (`dao/AttachmentDao.kt:26-42`; `dao/AssetReferenceDao.kt:19-49`): rows and observed rows by
  `supply_item_id` and by `installed_component_id`, and `findByUri` per owner column, each ordered as its asset twin.
  **B1a is app-only and additive:** the domain is untouched, so the mappers still build only asset/event owners; the
  reference entity's nullable `assetId` maps through `requireNotNull` (interim, replaced by B2b — row 27).

### B1b — the format-20 envelope (C9–C10)

- **C9, the DTOs (fact 3).** `AttachmentDto` (`C/backup/BackupFormat.kt:395-416`) gains `supplyItemId: String? = null`
  and `installedComponentId: String? = null` **appended after `sourceName`**; `AssetReferenceDto` (`:428-439`) gains
  the same two **appended after `role`**, and its `assetId` becomes `String?` **with no default** (the key is always
  written; a missing key stays a decode failure). The KDocs name format 20 as the keys' first format, in the shipped
  `role`/`source` paragraph shape. Defaults keep a format ≤ 19 archive decoding.
- **C10, the gate and exactly-one.** `FORMAT_VERSION = 20` (`BackupCodec.kt:181`); `internal const val
  FIRST_RESOURCE_OWNER_FORMAT = 20` beside `:231`, KDoc "the first format that can carry a SupplyItem or installed
  component owner on an attachment or a reference (#69): an archive below it that carries one was built by hand".
  **The field gate**, in the shape of `:420-452`, after the reference-role gate: a format < 20 archive with a non-null
  `supplyItemId` or `installedComponentId` on any attachment or reference is `BackupCorrupt` naming the list and the
  row (G2). Explicit nulls are accepted. **Exactly-one in `toDomain`:** `AttachmentDto.toDomain` (`:1149-1154`)
  counts every owner key; `AssetReferenceDto.toDomain` (`:1340-1348`) refuses a row with no owner key or more than
  one (G2: "reference {id} must name exactly one owner, an asset, a supply item or an installed component"). **At B1b's
  tip the domain holds only the shipped owners**, so a row whose one owner key is a new one is refused by that
  exactly-one rule (B2a and B2b widen it — rows 14 and 23); nothing writes such a row before then. The ~28 format pins
  move here (§3). No graph check, merge, pack or use case changes in B1b.

### B2a — the attachment owners, the seam (C4, C5, C11)

- **C4, the domain (audit §2; R69-1, R69-6; H6).** In `C/model/Attachment.kt`:

  ```kotlin
  sealed interface AttachmentOwner {
      data class OfAsset(val assetId: AssetId) : AttachmentOwner
      data class OfEvent(val eventId: EventId) : AttachmentOwner
      data class OfSupplyItem(val supplyId: SupplyId) : AttachmentOwner                       // #69
      data class OfInstalledComponent(val componentId: InstalledComponentId) : AttachmentOwner // #69
  }
  // R67-11 widened (R69-6): a role on anything but an entry's file.
  fun AttachmentOwner.accepts(role: DocumentRole?): Boolean = role == null || this !is AttachmentOwner.OfEvent
  ```

  `AttachmentLocator.dirFor` (`:111-114`) gains `"supply-items/${supplyId.value}"` and
  `"installed-components/${componentId.value}"` — **the one home** of the directory; `matchesShape` follows unchanged.
  The KDocs of `accepts` (`:22-28`) and `DocumentRole` (`C/model/DocumentRole.kt:9-11`) are restated to name the
  widened rule, values unchanged. No new `AttachmentKind`. A child Asset's files stay `OfAsset` (AC14).
- **C5, the forced arms — each to its contract, nothing more.**
  - `AddAttachment.ownerExists` (`:122-125`): a SupplyItem **archived or not** (R69-10), a component **current or
    removed**; constructor `+ supplyItems: SupplyItemRepository, installedComponents: InstalledComponentRepository`
    (C-1: B2a owns the `AppGraph` line, the `FakeGraph` line and every construction site `git grep` names — arguments
    only). The `require(owner.accepts(cmd.role))` message (`:54`) is restated owner-neutrally ("a document role
    belongs on an asset's, a supply item's or an installed component's file, not an entry's").
  - `BackupFormat`: `Attachment.toDto` (`:1119-1122`) writes the owner's key and nulls the others;
    `AttachmentDto.toDomain` builds the four owners from exactly one key (C10's rule, widened); its role refusal
    (`:1156-1158`) now reads only for an entry's file (G2 unchanged in shape).
  - The codec graph (`BackupCodec.kt:1130-1138`, inside `validateGraph`, which already holds `supplyIds` (`:958`) and
    `componentIds` (`:997`)): a SupplyItem owner must be in `supplyItems` (archived included), a component owner in
    `installedComponents` (current or removed) — G2 messages in the two shipped templates ("…points at supply item X,
    which is not in supplyItems"; "…points at installed component X, which is not in installedComponents"). The
    locator check (`:1146`) follows `dirFor`.
  - `ReturnScope.of` (`ApplyBackupMergePlan.kt:295-300`): `OfSupplyItem -> false` (global, never removed);
    `OfInstalledComponent -> componentId in returningComponents`, where `returningComponents` = the ids of
    `full.installedComponents` whose `assetId in returning`. Because `locators = attachments.map { it.storageLocator }`
    (`:365`), the component-owned bytes of a returning asset are swept after the apply (H2), and the reduced snapshot
    (`:314`) no longer keeps rows the asset delete's two-level CASCADE takes (the C-4 lesson).
  - `TransferOwnership.of(attachment)` (`:93-96`): `OfSupplyItem -> emptyList()` (as `of(applicability)`'s SupplyItem,
    `:113-114`); `OfInstalledComponent -> listOf(OwnerRef.OfInstalledComponent(componentId))`. `OwnerRef` gains
    `OfInstalledComponent(id: InstalledComponentId)` ("a component's resource: the component's asset owns it");
    `OwnerLookup` gains `fun installedComponent(id: InstalledComponentId): InstalledComponent?`; `resolve` (`:127-142`)
    adds `lookup.installedComponent(ref.id)?.let { owners += it.assetId }`. **Every implementer** gains the method
    with no default (a default `null` would fail open): `HeldWriteGuard.Lookup` (`:227-260`; its `fetch` reads the
    **unwrapped** component port, a new constructor parameter — 8 construction sites), the planner's object
    (`C/merge/MergePlanner.kt:1379-1387`, from `snapshot.installedComponents + installedComponentWrites`),
    **`A/ui/maintenance/ScheduleDetailViewModel.kt:548-556`'s `InHand`** (`null`; it resolves schedules only) and
    `CT/transfer/TransferOwnershipTest.kt:30`'s double — the audit named two (correction 1).
  - Room: `Mappers.kt:171-177` and `:202-205` map four owners; `RoomRepositories.kt:108-124` branches `forOwner` /
    `observeForOwner` onto B1a's DAO reads.
  The guarded ports (`HeldWriteGuard.kt:409-420`) are unchanged: they already ask `TransferOwnership.of`.
- **C11, the core doubles (#47 C-2).** The core attachment and reference doubles cascade from nothing today
  (`CT/testing/InMemoryRepositories.kt:153-159`'s KDoc), so a test of "the asset delete takes its components'
  resources" would pass vacuously. B2a adds, in `CT/testing/`: the component double (`InstalledComponentFixtures.kt`)
  reports the ids its `cascadeFromAsset` removes to registered listeners, and `BackupInstall` registers the attachment
  double's `cascadeFromInstalledComponents(ids)` (removes rows owned by those ids — **only** those: asset-owned rows
  keep today's behaviour, so no shipped test moves). B2b registers the reference double's twin. No owner-existence
  check in either double (Room proves the FKs, rows 4–5).

### B2b — the reference owner, the seam (C12, C13, C17)

- **C12, the domain and the port (fact 2; R69-1; H3).** In `C/model/AssetReference.kt`:

  ```kotlin
  sealed interface ReferenceOwner {
      data class OfAsset(val assetId: AssetId) : ReferenceOwner
      data class OfSupplyItem(val supplyId: SupplyId) : ReferenceOwner
      data class OfInstalledComponent(val componentId: InstalledComponentId) : ReferenceOwner
  }
  /** The one map from a reference's owner to the owner its saved document gets (#85 reused, C17). Total. */
  fun ReferenceOwner.asAttachmentOwner(): AttachmentOwner = when (this) { /* the three same-named members */ }
  ```

  `AssetReference.assetId: AssetId` becomes **`owner: ReferenceOwner`** — **no convenience `assetId` property** (the
  compile errors are the inventory: 17 construction sites, every `.assetId` read). I-6/I-7 restated: the owner is
  fixed; `(owner, uri)` is unique. The port (`C/ports/Repositories.kt:334-345`) replaces `forAsset`, `findByUri` and
  `observeForAsset` by **`forOwner(owner)`, `findByUri(owner, uri)`, `observeForOwner(owner)`** (same orders) — the
  E-3-style break; no asset-keyed twin stays. The reference double (`InMemoryRepositories.kt:677-720`) keys
  `findByUri` by the owner value, throws `IllegalStateException` on a second `(owner, uri)` (the three unique indices,
  I3), and registers its component cascade (C11).
- **C13, the forced arms.** `AddReference.run(owner: ReferenceOwner, cmd)` (`C/usecase/AddReference.kt:47`): the
  owner step (`:69-71`) resolves each owner (an archived SupplyItem and a removed component accepted, R69-10) through
  `+ supplyItems, installedComponents` constructor parameters; the duplicate step (`:72`) is `findByUri(owner, uri)`;
  the step order and every refusal are unchanged (I-2). `MaterializeReference` (C17). `BackupFormat`:
  `AssetReference.toDto` (`:1320-1331`) writes one owner key; `AssetReferenceDto.toDomain` builds the three owners
  (C10 widened). The codec's reference owner check (`BackupCodec.kt:808-816`) **moves after `componentIds` (`:997`)**
  — it runs before the SupplyItem and component id sets exist today (correction 2) — and checks each owner kind,
  shipped asset message unchanged. The merge's second identity (`MergePlanner.kt:310-311`, `:364-365`, `:1061`)
  becomes the pair **`(owner, uri)`** with the owner value-typed (H3: `(null, uri)` and an id string shared by an asset
  and a SupplyItem must never collide); the reference availability arm (`:1087-1088`) resolves each owner —
  `assetAvailable`, `supplyItemAvailable` (`:585`), a component "here or accepted" (`:653`, `:661`) — reusing
  `OWNER_NOT_AVAILABLE` with the owner's id. `ReturnScope.of` (`:315`) drops a reference owned by a returning asset or
  one of its components. `TransferOwnership.of(reference)` (`:106`) maps as C5. App: `ReferenceMappers`,
  `ReferenceRepositories` (`RoomReferenceRepository`, `:16`), `ReferenceHandlers.kt:83` (`ReferenceOwner.OfAsset`, the
  shipped create — C20 widens it), `AttachmentHandlers.kt:280-289` (passes `reference.owner` to `prepare`; the held
  check keeps its asset arm only, and C21 in B4 adds the component arm with row 44 — a test-only gap between B2b and
  B4), and the callers' arguments: `ReferencesSectionViewModel.kt:114`, `:225` (`OfAsset(assetId)`),
  `ShareIntakeViewModel.kt:367-376`, the materialize view model — **arguments only**, no behaviour.
- **C17, materialize keyed by the reference's owner (fact 9; H4; AC13).** The five asset-bound sites of
  `C/usecase/MaterializeReference.kt` take the owner, and the downloader stays one: `prepare(owner: ReferenceOwner,
  referenceId, onProgress)` (`:67-74`) answers `NoSuchReference` unless `reference.owner == owner`; eligibility, the
  store, the network permission and the one `FetchDocument` call (`:79-101`) are unchanged; the duplicate check (`:108`)
  reads `attachments.forOwner(owner.asAttachmentOwner())` — **the owner's own files only** (H4: the same bytes on the
  asset never refuse a SupplyItem's save, and the reverse); `Prepared.Ready.assetId` (`:182-183`) becomes `owner:
  ReferenceOwner`; `commit` (`:147`) calls `addAttachment.run(ready.owner.asAttachmentOwner(), …)` with the snapshot's
  role (R69-6 makes it acceptable on every owner a reference can have) and the shipped source. KDocs (`:61-66`,
  `:119-124`, `:175-181`) restated owner-neutrally ("on the reference's owner").

### B3a — the merge and the restore order (C14)

- **C14 (fact 5; H1; no older-archive exception, fact 3).**
  - **Attachments' availability** (`MergePlanner.kt:998-1003`): the owner string and `ownerAvailable` become four arms
    — `assetAvailable`, the events' "here or accepted", `supplyItemAvailable(id)`, the components' "here or accepted"
    (the component pass at `:648` precedes the attachment pass at `:969`); `OWNER_NOT_AVAILABLE` names the id. No new
    `MergeReason`. (`dto.assetId ?: dto.eventId!!` at `:998` throws on a new owner between B2a and B3a — interim,
    test-only; row 29 flips it.)
  - **M2** asks `TransferOwnership.of` (C5) for both tables: a component-owned insert on a held asset is
    `ASSET_TRANSFERRED_OUT`; a SupplyItem-owned one never is.
  - **No older-archive exception:** an owner column is write-once, so a format ≤ 19 archive's row equals the local one
    with both new keys null; the shipped comparisons (`:986-991`, `:1053-1058`) stay. Row 31 proves a format-19 export
    re-plans `IDENTICAL` against an install that has since gained SupplyItem- and component-owned resources.
  - **The apply order is already right** (`ApplyBackupMergePlan.kt:205`, `:210` before `:219-220`); row 33 pins it on
    Room.
  - **The replace restore's order moves (H1):** `ImportBackupReplace.kt:218-220` writes references straight after the
    assets, before the SupplyItems (`:223`) and the components (`:227-228`); the reference line moves **after `:228`**
    (the attachments are already last, `:257-258`), and its comment (`:218-219`) is restated ("after every owner:
    assets, SupplyItems and installed components"). The wipe needs no change: attachments (`:171`) and references
    (`:180`) are cleared before SupplyItems (`:205`).

### B3b — the pack, the return and the delete (C15–C16a)

- **C15, the Transfer Pack (fact 7; R69-7; H2, H5).** `TransferGraph.select` (`C/transfer/TransferGraph.kt:255`,
  `:259`): an attachment travels when its asset is selected, its event is carried, **its component is carried**
  (`installedComponents` of the selected assets, `:241`) **or its SupplyItem is in use** (`supplyIdsInUse`, `:242-246`,
  computed before the pack); a reference likewise (no event arm). The pack's artifacts follow the carried attachment
  rows unchanged (bytes included). `retain` (`:305`, `:309`): drops rows owned by a held asset's components (computed
  locally from `data.installedComponents`, so `DroppedRows` and `entangledRefs` are unchanged and nothing entangles);
  **SupplyItem-owned rows are never dropped** (globals stay whole, `:321-322`). The `CLASSES` lines for `attachments`
  and `assetReferences` (`:46`, `:50`) stay `ASSET_OWNED`; the map's KDoc (`:34-38`) adds them to "two lists hold rows
  of two classes, and the row decides" (a SupplyItem's row travels as its item does, `GLOBAL_IN_USE`). The
  return's references and attachments are C5/C13's.
- **C16a, the delete (H2; R69-11).** `DeleteAsset.run` (`C/usecase/DeleteAsset.kt:65-74`) reads, inside the same
  write and before `assets.delete`, the locators of the attachments owned by **every installed component of the
  asset** (current and removed) beside its own and its events'; the two-level CASCADE (asset → `installed_component` →
  `attachment` / `asset_reference`) takes the rows; `storage.sweepBytes` takes the bytes after the commit. Constructor
  `+ installedComponents: InstalledComponentRepository` (C-1, B3b's plumbing — the last `AppGraph`/`FakeGraph` edit).
  **The byte lifecycle per owner (R69-11):**

| event | rows | bytes |
|---|---|---|
| a SupplyItem archived | kept (nothing deletes a SupplyItem) | kept |
| a component removed or replaced | kept on the closed row (nothing deletes a component) | kept |
| an asset deleted | its components' rows go by CASCADE | swept by `DeleteAsset` (C16a) |
| a return lands (`ReturnScope`) | the returning assets' components' rows go by CASCADE; the pack's plan as inserts | swept by `locators` (C5) |
| the replace restore | wiped with every row (`:171`, `:180`) | the shipped restore's artifact handling, unchanged |
| a resource removed by the person | the one row | the shipped `DeleteAttachment` sweep |

### B3c — the equipment scenario (C16b)

- **C16b (E1–E7, AC3, AC4, AC12).** One JVM fixture class proves the issue's generic complex-equipment scenario over
  the core doubles and the shipped export / replace-import / `BuildBackupMergePlan` path (#47 row 37's shape): an
  "Example Generator" with an "Example Alternator" component whose direct link names a SupplyItem; an "Example UPS"
  with an "Example Battery Tray" and positions naming one battery SupplyItem, and a pack composed of `4 ×` it; product
  resources on the SupplyItems (a manual file with role `USER_MANUAL`, a data-sheet file of kind `DOCUMENT` with no role,
  a package photo, a vendor web reference, a receipt file with role `PURCHASE_INVOICE_OR_RECEIPT`); installation, label and wiring photos and a setup reference on the components. No production code.

### B4 — the API (C19–C22)

- **C19, the routes.** In `A/api/ApiRouter.kt`, beside the shipped SupplyItem (`:278-290`) and component
  (`:306-318`) shapes: `rest.size == 3 && rest[0] == "supply-items" && rest[2] == "references"` (GET),
  `… "attachments"` (GET, POST), and the same three under `installed-components`. **+4 path shapes, +6 method rows**:
  the router KDoc (`:98`) "Seventy-seven path shapes over ninety-five method-and-path rows" becomes **"Eighty-one …
  over one hundred and one …"**. A wrong verb is the shipped 405; the asset sub-resources (27) are unchanged.
  Handlers: `ReferenceHandlers.listForOwner(owner)` and `AttachmentHandlers.listForOwner` / `upload(owner, …)`
  generalise the shipped asset handlers (`AttachmentHandlers.kt:153-176`), never copy them; the 404s are C2's.
- **C20, the create.** `CreateReferenceRequest` (`A/api/ReferenceDtos.kt:44-51`) takes `assetId: String? = null`,
  `supplyItemId: String? = null`, `installedComponentId: String? = null`; the handler maps exactly one non-null key to
  a `ReferenceOwner` (none or several → 400 G1, before any read); the rest is `AddReference`'s. `ignoreUnknownKeys =
  false` stays. **The vendor link is one of these rows** (R69-2 as recommended): no `vendor`/`preferred` key.
  **`OwnerMissing` maps by the owner the handler passed** — today it is always `no_such_asset`
  (`A/api/ApiJson.kt:429`, `:726`; `A/api/AttachmentHandlers.kt:347-349`): an asset keeps `no_such_asset`, a SupplyItem
  answers `NO_SUCH_SUPPLY_ITEM` and a component `NO_SUCH_INSTALLED_COMPONENT` (each with the body key as `field` on the
  create, no `field` on a path). The mapper takes the owner kind as an argument — an exhaustive `when`, no `else`; the
  handler never re-reads the owner.
- **C21, the held checks and the upload id (H9).** Materialize (`AttachmentHandlers.kt:280-283`) and upload (`:176`)
  ask the owner's asset: an asset itself; a component's `assetId`; a SupplyItem none (never held). A component on a
  held asset → 409 `asset_transferred_out` naming that asset. **The upload's derived id:** `attachmentOperationId`
  (`A/api/AttachmentOperationIds.kt:18`) stays **byte-identical for an asset** (v2 prefix, text and the four shipped
  vectors in `docs/api/attachment-operation-ids.json` unchanged). The two new owners derive with a **new prefix**
  `servicetag:attachment-upload:v3` and the text `prefix \n installationId \n ownerKind \n ownerId \n operationKey`,
  `ownerKind` ∈ {`supply-item`, `installed-component`} (domain separation: an id string shared across owner kinds can
  never derive the same row id). The golden file gains a **new top-level key** (`ownerVectors`, fictional ids, at
  least two per kind) so its shipped readers are untouched; the MCP twin (C23) reads both.
- **C22, the document.** `docs/api/v1.md`: the owner keys on both row shapes ("exactly one is set"; `assetId` may be
  `null` on a reference read through a SupplyItem or component route); the six new rows; the create's owner rule and
  its 400; the held rule; the v3 derivation; the import range "format **1–20**" (`:209` and its twin) and the status
  lines "20 since #69" (`:215`, `:218`); "What has no endpoint" names moving a resource between owners.

### B5 — the MCP (C23–C24)

- **C23, five tools widened, none added (89 stays).** `list_references` (`M/src/servicetag_mcp/server.py:2103`),
  `add_reference` (`:2123`), `list_attachments` (`:3107`), `add_attachment` (`:3180`) and `materialize_reference`
  (`:3336`) each take **exactly one** of `asset_id`, `supply_item_id`, `installed_component_id` (keyword, default
  `None`); none or several → a local `ToolError` before any call. `materialize_reference` keeps its owner argument
  because its outcome recovery lists the owner's references and attachments (`:3380`, `:3396`) and `/v1` has no
  `GET /v1/references/{id}` (correction 3) — it reads the owner's routes (C19). `add_attachment` derives the v3 id for
  the new owners (`_attachment_operation_id`'s twin, `:3040-3048`, v2 untouched). `update_reference`,
  `get_attachment`, `update_attachment` are unchanged. Each docstring names the three owners and the import range
  1–20; no docstring names the child-Asset `create_component` here (C-3 sense: say what the tool does).
- **C24, the gate.** `_MIN_RESOURCE_OWNER_SCHEMA_VERSION = 20` beside `:241`: a SupplyItem or component owner key on
  a phone below schema 20 is refused locally with the shipped `APP_SCHEMA_TOO_OLD` shape (G3), nothing sent; an
  `asset_id` call keeps exactly today's gates.

### B6a / B6b / B6c — the phone (C25–C28)

- **C25, the sections keyed by owner (fact 11; H4).** `AttachmentsSection(graph, owner, …)` is reused **as is**
  (`A/ui/attachments/DocumentsSection.kt:145-160`; its Role control follows `accepts`,
  `AttachmentsSectionViewModel.kt:183` — R69-6 turns it on for the two new owners with no UI edit; key documents are
  already per-owner, `:92`). `ReferencesSection(assetId, …)` (`A/ui/references/ReferencesSection.kt:72-83`) and its
  view model (`ReferencesSectionViewModel.kt:79-120`) take a **`ReferenceOwner`**; the model key becomes
  owner-specific (e.g. `"references-" + dirFor(owner.asAttachmentOwner())`); the "saved" indicator reads the
  **owner's own** files' source URIs (`:115-119`, H4). `MaterializeSheet` / `MaterializeViewModel`
  (`MaterializeSheet.kt:70-79`; `MaterializeViewModel.kt:77-85`, `:108`) take the owner. The four owner-worded
  sentences pick the owner's twin (C28). **No visible change on an Asset** (the asset call sites pass
  `ReferenceOwner.OfAsset`; `AssetDetailScreen.kt:488`).
- **C26, the SupplyItem detail (R69-10).** `SupplyDetailScreen` (`A/ui/supplies/SupplyDetailScreen.kt:52-60`) gains,
  **below "Used by"** — the slot its KDoc reserves (`:44-45`, restated) — `AttachmentsSection(owner =
  OfSupplyItem)` then `ReferencesSection(owner = ReferenceOwner.OfSupplyItem)`, and a `SnackbarHost` (it has none).
  Writable for an archived item too; never read-only (a SupplyItem is never held). `onOpenSettings` and the link
  launcher are passed as `AssetDetailScreen` passes them. Nothing else on the screen moves.
- **C27, the installed-component screen (H7; R69-8, R69-9).** A component lives only inside a bottom sheet
  (`A/ui/installed/InstalledComponentSheets.kt:71-90`) and the sections open their own sheets (#47 E-30: one sheet
  at a time), so the smallest honest surface is **a route and a screen**: `Route.InstalledComponentDetail(val id:
  String)` beside `Route.SupplyDetail` (`A/ui/nav/Route.kt:168`), its `entry` in `A/ui/nav/ServiceTagRoot.kt` beside
  `:580-589`. Reached from the row sheet by a **"Documents" action (P69-1)** offered on **every** row — current,
  removed, and on a held asset (where it opens read-only) — through a new `onOpenDocuments(id)` from
  `InstalledComponentsSection` (`InstalledComponentsSection.kt:58-64`) via `AssetDetailScreen` to the root. The
  screen: a top bar with the component's name and the shipped "Back"; then **two ownerships, in order**:
  1. **its own** — the heading P69-2, then `AttachmentsSection(owner = OfInstalledComponent)` and
     `ReferencesSection(owner = ReferenceOwner.OfInstalledComponent)`, writable unless the component's asset is held
     (the shipped `readOnly` flags, `DocumentsSection.kt:150-154`; `ReferencesSection.kt:78-79`);
  2. **each referenced SupplyItem's** — one group per distinct SupplyItem named by the row's direct link and its
     composition entries (R69-8), ordered direct link first, then entries in composition order, deduplicated: the
     heading P69-3 "From {name}" (a tap opens `SupplyDetail`, writing nothing — the "Used by" rows' shape), the quiet
     line P69-4, then the same two sections for `OfSupplyItem` with **`readOnly = true`** (open-only; edits on the
     SupplyItem's own detail). An archived SupplyItem's group is drawn (its resources are still its product's).
  A component whose asset is deleted while the screen is open goes back (no sentence). The view model reads the row
  through `InstalledComponentRepository.get` and the catalog's names through the shipped list rows; it writes nothing
  itself (the sections own their writes).
- **C28, the owner-worded sentences.** Four shipped sentences name "asset" and are reached from every owner; each
  keeps its asset wording and gains **a SupplyItem twin and a component twin** (P69-11…16), chosen by the owner kind
  in one small function per home (no shipped pin moves): `IntakeStrings.DUPLICATE_URI`
  (`A/share/ShareIntakeViewModel.kt:82`), the in-app duplicate (`ReferencesSectionViewModel.kt:283`), the remove
  confirmation (`A/ui/references/ReferenceSheets.kt:139`) and P85-17 (`A/ui/references/MaterializeStrings.kt:35`)
  (correction 5: the audit named one).

### B7 — Share intake (C29–C30)

- **C29, the target (fact 12; H8).** `AssetChoice` (`A/share/ShareIntakeViewModel.kt:110`) becomes a sealed
  **`ShareTarget`** — `Asset(id, name)`, `Supply(id, name)`, `Component(assetId, componentId, label)` — held in
  `chosen` (`:128`). The three save arms map it once: the link arm (`:367-376`) to `AddReference.run(ReferenceOwner…)`,
  the bytes arm (`:418-436`) to `AddAttachment.run(AttachmentOwner…)`, **the note arm (`:475-480`) Asset only** (a
  prose share never reaches a SupplyItem or component target — the step does not offer one). `save` stays the only
  writer (`:338`); Cancel writes nothing (`:334`); the security rules, the managed copy and the size checks run before
  any target exists and are untouched. `savedTo` (`:96`) takes the target's display text (an asset's or SupplyItem's
  name; a component's P69-10). The duplicate sentence is C28's twin for the target. Every save still meets the guard
  (`A/di/AppGraph.kt:316`, `:324`).
- **C30, the steps (R69-3 as recommended).** For a **link or bytes** share: a first step **"Attach to" (P69-5)** with
  three choices in this order — **Asset (P69-6), Supply (P69-7), Installed component (P69-8)** — then the owner's
  picker: Asset → the shipped `AssetPicker` step (#93, unchanged: `ShareIntakeScreen.kt:152-169`, the activity's
  `AssetsViewModel(graph, excludeHeld = true)`, `ShareIntakeActivity.kt:106`); Supply → the shipped `SupplyItemPicker`
  (`A/ui/supplies/SupplyItemPicker.kt:27`, a plain `LazyColumn`, so it fits a step) handed **unarchived** SupplyItems
  by the intake view model (its KDoc's rule, `:22-24`); Installed component → the `AssetPicker` step, then that
  asset's **current** components as an indented tree under the title P69-9 (the #47 section's current-tree derivation
  reused, never re-implemented), empty → the shipped P47-2 "No installed components". Held assets are excluded at the
  asset step, so their components are never offered. A **prose** share skips "Attach to" and opens the Asset picker as
  today. "Change" (`:90`) returns to "Attach to" for link and bytes, to the asset picker for prose. The dead ends are
  unchanged (`NO_ASSETS` when the install has no asset; a SupplyItem with no asset is still reachable — the "Attach to"
  step is drawn whenever an asset **or** an unarchived SupplyItem exists).

### B8 and cross-cutting (C31–C33)

- **C31, the documents.** `docs/release-proofs.md`: a **schema-20 paragraph** beside the schema-19 one (`:133`),
  naming the schema-18 and -19 paragraphs' checks as its own (the first release carrying 20 also carries 18 and 19),
  seeding nothing new on the older side, then making SupplyItem- and component-owned files and links through the
  phone (SupplyItem detail, the component screen, Share), the API and the MCP, materializing one of each, and proving
  the pre-upgrade export re-plans with zero INSERT and the format-20 round trip (export → import → re-plan) `IDENTICAL`.
  `docs/design/04-domain-data-model.md` §9's amendment (`:440`): "`vendor_url` is #69's" becomes "#69 keeps a
  SupplyItem's vendor and product links as ordinary references the SupplyItem owns; there is no `vendor_url` column"
  (or option D's wording if ruled). `docs/design/14-asset-model.md`: a resources paragraph (three owners; ownership
  follows what a resource is about; visibility by navigation; the child-Asset rule). `docs/versioning.md` untouched.
- **C32, the owner model in D14** is the one prose statement of I1–I6 below; code KDocs point at it, never restate it.
- **C33, the fence, as greps** (every brief): (1) the tripwire `git diff <base> -U0 -- app/src/main core/src/main
  tools/servicetag-mcp/src | grep -ciE '^[+].*\b(purchase|acquisition|lot_id|order_id|on_hand|reorder|stock)\b'` → 0;
  (2) `vendor_url|vendorUrl|preferred_reference` added → 0 (unless R69-2 = D); (3) no new `AttachmentKind` /
  `ReferenceKind` value (`git diff` of `C/model/Attachment.kt:9` and `AssetReference.kt:15` empty); (4) no new
  `TagTarget` member and no child-Asset owner case (`git diff -- C/model/TagBinding.kt` empty; `git grep -nE
  'parentAssetId' -- C/model/Attachment.kt C/model/AssetReference.kt` → 0); (5) the directory literal `"components/`
  in `C/` → 0; (6) the child-Asset contract did not move (#47 N-16 with E-35's allowance):
  `git diff <base> -U0 -- app/src/main tools/servicetag-mcp/src | grep -cE '^[-].*("components"|create_component|createComponent)'` → 0.

**The invariants (C32's statement; each has rows):** **I1** exactly one owner per attachment (of four) and per
reference (of three); **I2** the owner is immutable; **I3** a reference is unique per `(owner, uri)`; **I4** a locator
is `dirFor(owner)/<id>.<ext>`; **I5** a component-owned row counts as its component's asset's for transfer, the guard
and M2, and a SupplyItem-owned row is no asset's; **I6** no row or byte is copied for visibility.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED. App classes in `T/` unless named; core in `CT/`; MCP in `M/tests/`.
"Pin" = a shipped assertion that moves or is re-run, with no RED. **JVM** unless marked **Compose** (instrumented,
first run at the merged-tip gate). Case names are the implementer's to refine; what each proves is not. Fixtures are
fictional; **row 22's and row 24's fixtures deliberately give an asset and a SupplyItem the same id string** so an
untyped owner key collides observably.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C6 `attachment` columns | `T/data/room/Migration19To20Test` (new) · `attachmentGainsTwoNullableCascadeOwnersAndTheirIndices`; `everyV19AttachmentRowIsUnchanged` (ids, locators, roles, sources) | the ALTER's FK written `RESTRICT` while the entity says CASCADE (the schema compare fails) |
| 2 | C7 the rebuild | same · `everyV19ReferenceRowSurvivesTheRebuildByteForByte` (ids, URIs, roles, timestamps); `theFourReferenceIndicesCarryRoomsNames`; `theMigratedSchemaEqualsAFreshVersion20` | the copy omits `document_role` (the row compare fails) |
| 3 | C8 the chain | `MigrationTestSupport` chain gains `MIGRATION_19_20`; the whole-chain table sets **unchanged** | none: pin |
| 4 | C6/C7 I3 and the cascade | `T/data/room/ResourceOwnerDaoConstraintTest` (new) · `oneUriOncePerOwnerAndAcrossOwnersFreely`; `deletingAnAssetTakesItsComponentsFilesAndLinks` (two-level); `aSupplyItemWipeTakesItsResources`; `exactlyOneOwnerIsRequiredOnBothEntities` (0 and 2 refused) | `UNIQUE(supply_item_id, uri)` declared on `supply_item_id` alone (a second link on one SupplyItem is refused) |
| 5 | C8 DAO reads | same · `rowsAndObservedRowsBySupplyItemAndByComponentInTheShippedOrder` | the component read filters `supply_item_id` |
| 6 | C8 schema pins | `VersionAgreementTest.kt:86`, `:157`; `MaintenanceRoutesTest.kt:1551` → 20 | none: pins |
| 7 | C9 round trip of the envelope | `CT/backup/BackupFormat20Test` (new) · `assetAndEventRowsWriteBothNewKeysAsExplicitNulls`; `theDtosCarryExactlyTheseKeysInOrder` (both descriptors pinned) | `supplyItemId` given no default (a format-19 archive no longer decodes) |
| 8 | C10 the gate | same · `aFormat19ArchiveWithANewOwnerKeyIsCorrupt` (each key × each list); `explicitNullsInAFormat19ArchiveDecode` | the gate removed |
| 9 | C10 exactly-one | same · `aReferenceWithNoOwnerKeyIsCorrupt`; `twoOwnerKeysAreCorrupt` (both lists) | the reference check removed (`AssetId(null)` throws an NPE, not `BackupCorrupt`) |
| 10 | C10 newer refused | same · `aFormat21ArchiveIsRefusedAsNewer` | none: pin |
| 11 | C10 format pins | §3's B1b pins | none: pins |
| 12 | C4 directories (H6) | `CT/usecase/AttachmentRulesTest` (+3) · `dirForGivesSupplyItemsAndInstalledComponents`; `matchesShapeIsPerOwner`; `noOwnerDirectoryIsComponentsSlash` | `dirFor` writes `components/` |
| 13 | C4 R69-6 | same (+2) · `aRoleIsAcceptedOnAnAssetASupplyItemAndAComponent`; `aRoleOnAnEntrysFileIsRefused` | `accepts` left `is OfAsset` |
| 14 | C5 the codec, attachments | `BackupFormat20Test` (+5) · `aSupplyItemsAndAComponentsFilesRoundTrip`; `anArchivedSupplyItemOrRemovedComponentIsAValidOwner`; `anOwnerNotInTheFileIsCorrupt` (each kind); `aLocatorUnderAnotherOwnersDirectoryIsCorrupt`; `aRoleOnASupplyItemsFileDecodes` | the component arm checks `supplyIds` |
| 15 | C5 Room, attachments | `T/data/room/AttachmentDaoTest` (+2) · `fourOwnersRoundTripThroughTheRepository`; `observeForOwnerEmitsPerOwner` | `toEntity` drops `supply_item_id` |
| 16 | C5 AddAttachment | `CT/usecase/AttachmentUseCasesTest` (+5) · `addsToASupplyItemArchivedIncluded`; `addsToAComponentRemovedIncluded`; `bytesLandUnderTheOwnersDirectory`; `anUnknownSupplyItemOrComponentIsOwnerMissing`; `aRoleOnAnEntryStillThrows` | `ownerExists` answers true for an unknown SupplyItem |
| 17 | C5 I5 resolution | `CT/transfer/TransferOwnershipTest` (+3) · `aSupplyItemsResourceHasNoOwner`; `aComponentsResourceResolvesToItsAsset`; `anUnknownComponentResolvesToNothing` | the `resolve` arm adds nothing (fails open) |
| 18 | C5 the guard | `HeldWriteGuardTest` (+3) · `aComponentsFileOnAHeldAssetThrows` (upsert and delete); `aSupplyItemsFilePassesWhileEveryAssetNamingItIsHeld` | `Lookup.fetch` skips `OfInstalledComponent` |
| 19 | C5 the return, attachments (H2) | `CT/usecase/ReturnScopeTest` or `ImportTransferPackTest` (+2) · `aReturningAssetsComponentFilesLeaveTheSnapshotAndTheirLocatorsAreSwept`; `aSupplyItemsFileStays` | the arm answers `false` for a component (the snapshot keeps the row, the locator is not listed) |
| 20 | C11 the doubles | `CT/testing/ResourceOwnerDoubleTest` (new) · `anAssetDeleteThroughBackupInstallTakesItsComponentsFilesAndLinks`; `assetOwnedRowsDoNotCascadeInTheDouble` (unchanged behaviour); `aSecondOwnerUriPairThrows` | the registration line removed |
| 21 | C12 the owner map | `CT/model/ReferenceOwnerTest` (new) · `asAttachmentOwnerIsTotalAndSameNamed`; `updateReferenceNeverChangesTheOwner` | `OfSupplyItem` mapped to `OfAsset` |
| 22 | C13 AddReference (H3) | `CT/usecase/AddReferenceTest` (+5) · `addsToEachOwner`; `theSameUriOnAnAssetAndASupplyItemSharingAnIdStringIsTwoRows`; `aDuplicateIsPerOwner`; `anArchivedSupplyItemAndARemovedComponentAccept`; `theStepOrderIsUnchanged` (owner-missing still after the name) | `findByUri` keyed by the id string alone |
| 23 | C13 the codec, references | `BackupFormat20Test` (+3) · `aSupplyItemsAndAComponentsLinksRoundTrip`; `anOwnerNotInTheFileIsCorrupt`; `theOwnerCheckSeesSupplyItemsAndComponents` (the moved block) | the moved check run before `supplyIds` exists (a valid file refused) |
| 24 | C13 the merge pair (H3) | `CT/merge/MergePlannerReferenceTest` (+3) · `aSupplyItemsLinkDoesNotMatchAnAssetsLinkSharingIdAndUri` (INSERT, not `REFERENCE_HELD_BY_A_LOCAL_ROW`); `theSecondIdentityIsPerOwnerInTheArchive`; `anAbsentSupplyItemOrComponentIsOwnerNotAvailable` | the pair keyed `(ownerIdString, uri)` |
| 25 | C13 the return, references | `ImportTransferPackTest` (+1) · `aReturningAssetsComponentLinkLeavesTheSnapshotAndTheReturnLands` | the `ReturnScope` reference filter checks the asset only (the borrowed-phone link edit refuses `CONFLICT`) |
| 26 | C17 materialize (AC13, H4) | `CT/usecase/MaterializeReferenceTest` (+5) · `prepareOnASupplyItemsLinkAndCommitLandsOnTheSupplyItem`; `…onAComponentsLink…`; `theDuplicateCheckIsTheOwnersOwnFiles` (the same bytes on the asset do not refuse a SupplyItem's save); `theRoleAndSourceTravel`; `aReferenceOfAnotherOwnerIsNoSuchReference` | `commit` writes `OfAsset` / the duplicate check reads another owner |
| 27 | C13 Room, references | `T/data/room/ReferenceDaoConstraintTest` (+2) · `threeOwnersRoundTrip`; `theMapperRefusesNoOwner` | `toEntity` writes `asset_id` for every owner |
| 28 | C13 pins and arguments | the 17 `AssetReference(` sites take `owner = ReferenceOwner.OfAsset(…)`; `ReferencesSectionViewModelTest`, `MaterializeViewModelTest` green unchanged in assertions | none: pins |
| 29 | C14 attachment availability | `CT/merge/MergePlannerTest` (+4) · `aSupplyItemOrComponentOwnerHereOrAcceptedIsAvailable`; `anAbsentOneIsOwnerNotAvailableNamingIt`; `aComponentThisPlanRefusesRefusesItsFiles` | `dto.assetId ?: dto.eventId!!` kept (the NPE) |
| 30 | C14 M2 | `MergePlannerTransferTest` (+2) · `aComponentsFileInsertedForAHeldAssetIsTransferredOut`; `aSupplyItemsFileIsNot` | the planner's `installedComponent` lookup answers `null` |
| 31 | C14 no older-archive exception | `MergePlannerTest` (+1) · `aFormat19ExportAgainstAnInstallWithNewOwnersResourcesIsApplicableAndIdentical` (no decision for rows the archive does not name) | a `sameAttachment` arm comparing without the new keys |
| 32 | C14 H1 restore order | `T/backup/ResourceOwnersRestoreTest` (new, Room-backed `FakeGraph`) · `aShuffledArchiveWithSupplyItemAndComponentLinksRestoresByteEqual` | the reference line moved back before the SupplyItems (SQLite's FK fails the restore) |
| 33 | C14 apply order on Room | `ApiRouterTest` (+1) · `aMergeInsertingSupplyItemsComponentsAndTheirResourcesCommits` | references applied before components (the FK fails) |
| 34 | C14 tallies | no new table: the report and tally pins **unchanged** and re-run | none: pins |
| 35 | C15 select | `CT/transfer/TransferGraphTest` (+3) · `aPackCarriesItsComponentsFilesAndLinks`; `aPackCarriesTheResourcesOfEverySupplyItemInUseWithBytes`; `anUnusedSupplyItemsResourcesStayHome` | the supply arm dropped from `select` |
| 36 | C15 retain | `TransferGraphRetainTest` (+2) · `aHeldAssetsComponentsResourcesDropAndTheKeptArchiveDecodes`; `aSupplyItemsResourcesAreKeptWhenOnlyHeldRowsNameIt` | `retain` keeps component-owned rows (the kept archive is corrupt: owner not in file) |
| 37 | C15 H5 | `ImportTransferPackTest` (+1) · `aSupplyItemsFileEditedHereWhileThePackIsOutRefusesTheReturnAsConflict` (the limit, pinned) | none: a limit's pin |
| 38 | C16a delete (H2) | `CT/usecase/RetireDeleteAssetTest` (+2) · `deletingAnAssetSweepsItsComponentsBytesCurrentAndRemoved`; `aSupplyItemsBytesAreNotSwept` | the component locators not read inside the write |
| 39 | C16b the scenario (E1–E7, AC3, AC4, AC12) | `CT/usecase/ResourceOwnersFixtureTest` (new) · both components naming the battery SupplyItem read **the same** rows by `forOwner(OfSupplyItem)` (no copy: row and byte counts equal before and after); no component's resource appears under a SupplyItem and the reverse; export → replace import into an empty install reads every owner back; the export re-planned against the non-empty install `IDENTICAL`; a merge into an install without them inserts all, owners before resources | the replace import drops `installedComponentId` (the re-plan reads `INSERT`) |
| 40 | AC14 | same · `aChildAssetsFileIsAnAssetOwnedRow` | none of its own: C33.4's grep |
| 41 | C19 routes | `T/api/ResourceOwnerRoutesTest` (new) · each GET lists only that owner's rows in the shipped order; `aMissingSupplyItemOrComponentIs404`; `aHeldAssetsComponentReadsAsAnyOther`; `theAssetSubResourcesAreUnchanged` | the SupplyItem route reads the asset's rows |
| 42 | C20 the create | same · `exactlyOneOwnerKey` (none, two, a lone `null` → 400; each owner → 201); `anUnknownOwnerIs404WithItsCodeAndField` (each kind's own code, C20); `aDuplicateIsPerOwner`; `aPatchNamingAnOwnerKeyIs400` | two keys accepted, the first winning |
| 43 | C21 upload (H9) | same · `uploadToASupplyItemAndAComponent` (201, locator directory); `aHeldAssetsComponentIs409`; `theAssetDerivationIsByteIdenticalToTheShippedVectors`; `theNewOwnersMatchTheV3Vectors` | the v3 text omits `ownerKind` (the vectors fail) |
| 44 | C21 materialize route (AC13) | `MaterializeRoutesTest` (+3) · `aSupplyItemsLinkSavesOnTheSupplyItem`; `aComponentsLinkSavesOnTheComponent`; `aHeldAssetsComponentLinkIs409` | the held check reads `(owner as OfAsset)` (a component's link on a held asset proceeds) |
| 45 | C22 the document | `everyNewRouteIsInV1md`; the import range and status pins (§3) | a route missing from `v1.md` |
| 46 | C23 the tools | `M/tests/test_reference_tools.py`, `test_attachment_tools.py` (+~12) · each widened tool's method, path and body per owner; none/several owners refused locally, nothing sent; `materialize_reference` recovers through the owner's routes; `add_attachment`'s v3 id equals the golden `ownerVectors` | `add_reference` sends `assetId` for a SupplyItem |
| 47 | C24 the gate | `…_refuses_a_new_owner_below_schema_20_with_nothing_sent` (all five); `an_asset_call_keeps_todays_gates` | the gate removed from one tool |
| 48 | C23 pins | 89 tools unchanged; "1–20"; the argument guard | none: pins |
| 49 | C25 sections by owner (H4) | `T/ui/references/ReferencesSectionViewModelTest` (+4) · `stateIsTheOwnersRows`; `theSavedMarkReadsTheOwnersOwnFiles`; `addPassesTheOwner`; `theDuplicateSentenceIsTheOwnersTwin`; `MaterializeViewModelTest` (+2) · `preparePassesTheOwner`; `alreadyHaveIsTheOwnersTwin`; `AttachmentsSectionViewModelTest` (+2) · `rolesOfferedOnASupplyItemAndAComponent`; `notOnAnEntry` | the saved mark reads `OfAsset` |
| 50 | C26 SupplyItem detail | `SupplyDetailViewModelTest` (+1) · `anArchivedItemIsWritable`; **Compose** `AT/ui/supplies/SupplySurfacesTest` (+3) · both sections drawn below "Used by"; add actions present with a folder; an archived item's sections draw | none in-brief: device cases |
| 51 | C27 the component screen | `T/ui/installed/InstalledComponentDetailViewModelTest` (new) · `ownSectionsAreKeyedByTheComponent`; `oneGroupPerDistinctSupplyItemDirectFirstThenEntries`; `anArchivedSupplyItemsGroupIsDrawn`; `aHeldAssetMakesOwnSectionsReadOnly`; `aRemovedComponentIsWritable`; `aDeletedAssetGoesBack` | a SupplyItem named by the link and an entry drawn twice |
| 52 | C27 drawn | **Compose** `AT/ui/installed/InstalledComponentDetailTest` (new, ~10 cases) · the title; P69-2 then both own sections; each "From {name}" group with P69-4 and no add or edit action; a group tap opens the SupplyItem; held → no add action; empty sections' shipped lines; **Compose** `InstalledComponentsSectionTest` (+2) · the row sheet offers P69-1 on a current, a removed and a held row; the tap reports the id | none in-brief: device cases |
| 53 | C29–C30 Share state | `T/share/ShareIntakeViewModelTest` (+~12) · link and bytes open "Attach to", prose does not; each owner's save calls the one use case with that owner; the Supply picker gets unarchived items only; the component step lists **current** rows of the chosen asset as a tree; a held asset is never offered; Cancel writes nothing on each path; "Change" returns to the right step; `savedTo` per target; the duplicate twin per target | the component step lists removed rows |
| 54 | C30 drawn | **Compose** `AT/share/ShareIntakeScreenTest` (+5) · the three choices in order; the Supply picker step; the component tree step with P69-9 and P47-2 when empty; the chosen component line P69-10 with "Change"; a prose share opens the asset picker directly | none in-brief: device cases |
| 55 | the boundary unchanged | `AT/share/ShareBoundaryTest` (3), `ShareResolutionContractTest` (4), `SharedItemLiftTest` (7): untouched, re-run at the gate | none: re-run |
| 56 | C31 the documents | `ReleaseProofPolicyTest` unchanged and green; B8's anchored greps (§7) | none: tripwire |
| 57 | C33 the fence | the six greps at every brief | none: greps |

**Moving pins — each named shipped assertion, the brief that may touch it, and why** (the audit's §3 inventory,
re-read on `ad49b788`; each brief confirms by grep and lists twins).

| pin | brief | moves, because |
|---|---|---|
| `T/VersionAgreementTest.kt:86`, `:157`; `T/api/MaintenanceRoutesTest.kt:1551`; `T/data/room/MigrationTestSupport.kt:69` (the chain) | B1a | the schema is 20 |
| the format literals: `VersionAgreementTest.kt:87`, `:158`; `MaintenanceRoutesTest.kt:1197`, `:1552`, `:1599`; `CT/backup/BackupCodecTest.kt:1071`; `BackupFormat6Test:363`, `8Test:281`, `9Test:70`, `13Test:54`, `14Test:48`, `15Test:48`, `17Test:99`, `:223`, `18Test:74`, `:350`, `19Test:111`, `:384`; `CT/usecase/ExportBackupSetTest.kt:51`; `Format7ImportIdentityTest.kt:248`; `AT/backup/Format7RestoreContractTest.kt:164` | B1b | the format is 20. The 26 `assertEquals(19,` hits on `ad49b788` are **24 pins** (3 schema in B1a, 21 format here) **plus 2 that never move**: `VersionAgreementTest.kt:61` (the versionCode) and `MaterializeStringsTest.kt:100` (a MIME label) |
| the one-past archives and their `refusal.found` lines: `BackupFormat8Test:277`, `:280`; `17Test:218`, `:222`; `18Test:345`, `:349`; `19Test:379`, `:383` | B1b | "one format past this build" becomes 21 |
| the strip lists `BackupFormat9Test:116`, `:120`; `BackupCodecTest.kt:1201` (the `sourceKeys` idiom); the reference key orders in `BackupFormat7Test` and `BackupFormat17Test` | B1b | two keys appended to each DTO |
| **unchanged, re-run:** `counts.size` 31 (`BackupFormat6Test:411`, `7Test:299`, `8Test:310`, `13Test:68`, `14Test:65`, `15Test:63`, `18Test:334`, `19Test:368`); `MergeTable` 23 (`6Test:292`, `7Test:225`, `MergePlannerTransferTest.kt:383`); the key positions; the manifest-count maps; `V19_TABLES`; `Migration9To10Test:69`, `Migration15To16Test:82` (column counts at v10 and v16) | — | columns only: no list, table, key or count moves (a hit here stops the brief) |
| the 17 `AssetReference(` construction sites (9 in `CT/`, 4 in `T/`, 4 in main) | B2b | the field is `owner` (arguments only) |
| `T/api/CommandShapesGoldenTest.kt:137-139`, `:176-180`; `T/api/ReferenceRoutesTest.kt:748-760`; `InstalledComponentRoutesTest.kt:746-750`; `SupplyRoutesTest.kt:634`; `ApiRouter.kt:98`'s KDoc | B4 | range 1–20, "20 since #69", 81 shapes / 101 rows |
| `M/tests/test_tools.py:979-994`; `test_argument_guard.py`; the "89" docstrings (`test_reference_tools.py:35`, `test_installed_component_tools.py:45`, `test_maintenance_tools.py:45`, `test_supply_tools.py:45`); `server.py:1265` | B5 | range 1–20; the widened signatures (the count stays 89) |
| `AT/share/ShareIntakeScreenTest.kt` (13; notably `:166`, `:308`, `:368`, where the picker is now the second step); `T/share/ShareIntakeViewModelTest.kt` (47) | B7 | "Attach to" precedes the asset picker for link and bytes |
| `AT/ui/references/ReferencesSectionTest` (12) | B6a | the composable's parameter is an owner (arguments only) |

**Device rows.** No device-boundary class (planning policy's hard rule): the Share grant from another UID is
target-independent and already proven by `ShareBoundaryTest`; SAF directory creation under a new directory name is
`SafTreeAttachmentStore`'s generic segment walk (`:20`, `:37-40`, `:109-113`), already proven by
`SafTreeAttachmentStoreContractTest`. What remains is Compose drawing an already-proven state: **one new class**
(`InstalledComponentDetailTest`, ~10 cases; budget up to ~14, #47's E-33 overran by 4) and cases added to
`SupplySurfacesTest` (+3), `InstalledComponentsSectionTest` (+2) and `ShareIntakeScreenTest` (+5).
**Gate growth:** 58 → **59** device classes, 318 → ~338–342 tests. At #47's clean per-class rate (~12.4 min / 57
classes; ~2.4 s per case) about **+0.8–1.0 min → ~13.3–13.7 min**: under the 14- and 15-minute reporting lines, but
the closest yet; #90's trigger is not reached. **Known cost:** the SupplyItem detail grows below "Used by", and the
installed-component row sheet gains one action, under the shipped `SupplySurfacesTest` and
`InstalledComponentsSectionTest` — first seen at the merged-tip gate (one fix round, accepted as #15's N-12).

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `A/data/room/entities/{AttachmentEntity,AssetReferenceEntity}.kt`; `A/data/room/dao/{AttachmentDao,AssetReferenceDao}.kt`; `A/data/room/{Migrations,AppDatabase,Mappers,ReferenceMappers}.kt` (the exactly-one twins and the interim `requireNotNull` only); `A/di/AppGraph.kt` (`SCHEMA_VERSION`, the migration list); `T/testing/FakeGraph.kt` (the migration list if it holds one); `app/schemas/…/20.json` (generated); `T/data/room/{Migration19To20Test (new),ResourceOwnerDaoConstraintTest (new),MigrationTestSupport}.kt`; B1a's pins | `C/**`, `A/api`, `A/ui`, `A/share`, `19.json`, `docs`, `tools` |
| B1b | `C/backup/{BackupFormat,BackupCodec}.kt` (the DTO keys, the constant, the gate, exactly-one); `CT/backup/BackupFormat20Test.kt` (new); B1b's pins in `CT/`, `T/`, `AT/backup/` | `C/model`, `C/merge`, `C/transfer`, `C/usecase`, `A/**` main, `docs`, `tools` |
| B2a | `C/model/{Attachment,DocumentRole}.kt`; `C/usecase/AddAttachment.kt`; `C/backup/{BackupFormat,BackupCodec}.kt` (the attachment arms); `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of`'s attachment arm only); `C/transfer/{TransferOwnership,HeldWriteGuard}.kt`; `C/merge/MergePlanner.kt` (the lookup object only); `A/ui/maintenance/ScheduleDetailViewModel.kt` (`InHand`, one method); `A/data/room/{Mappers,RoomRepositories}.kt`; `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the two constructors); the construction sites (arguments only); `CT/testing/{InMemoryRepositories,InstalledComponentFixtures,BackupInstall}.kt` (C11); the classes of rows 12–20; `CT/testing/ResourceOwnerDoubleTest.kt` (new) | `C/model/AssetReference.kt`, the reference arms, `C/merge` beyond the lookup, `A/api` bar arguments, `A/ui` bar `InHand`, `A/share`, `docs`, `tools` |
| B2a2 | `CT/transfer/{TransferOwnershipTest,HeldWriteGuardTest}.kt`; the return's test class (`CT/transfer/ImportTransferPackTest.kt` or `CT/usecase/ReturnScopeTest.kt`, new) — rows 17–19 only | every main file |
| B2b | `C/model/AssetReference.kt`; `C/ports/Repositories.kt` (the reference port); `C/usecase/{AddReference,MaterializeReference}.kt`; `C/backup/{BackupFormat,BackupCodec}.kt` (the reference arms; the moved block); `C/merge/MergePlanner.kt` (the reference pair and availability); `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of`'s reference filter only); `C/transfer/TransferOwnership.kt` (`of(reference)`); `A/data/room/{ReferenceMappers,ReferenceRepositories}.kt`; `A/api/{ReferenceHandlers,AttachmentHandlers}.kt` (arguments and C21's minimal held read); `A/ui/references/{ReferencesSectionViewModel,MaterializeViewModel}.kt`, `A/share/ShareIntakeViewModel.kt` (arguments only); `AppGraph`, `FakeGraph` (constructors); `CT/testing/InMemoryRepositories.kt`, `BackupInstall.kt`; the 17 sites; the classes of rows 21–28 | `C/model/Attachment.kt`, `C/transfer/TransferGraph.kt`, `A/ui` composables, `A/share/ShareIntakeScreen.kt`, `docs`, `tools` |
| B3a | `C/merge/MergePlanner.kt` (the attachment availability); `C/usecase/ImportBackupReplace.kt` (one line moved, its comment); `CT/merge/{MergePlannerTest,MergePlannerTransferTest}.kt`; `T/backup/ResourceOwnersRestoreTest.kt` (new); `T/api/ApiRouterTest.kt` (row 33 only) | `C/backup`, `C/transfer`, `C/model`, other use cases, `A/**` main, `AppGraph`, `FakeGraph`, `docs`, `tools` |
| B3b | `C/transfer/TransferGraph.kt` (`select`, `retain`, the `CLASSES` KDoc); `C/usecase/DeleteAsset.kt`; `AppGraph`, `FakeGraph` (`DeleteAsset`'s constructor — **the last edit of either**); the construction sites; `CT/transfer/{TransferGraphTest,TransferGraphRetainTest,ImportTransferPackTest}.kt`, `CT/usecase/RetireDeleteAssetTest.kt` | `C/backup`, `C/merge`, `C/model`, `A/api`, `A/ui`, `docs`, `tools` |
| B3c | `CT/usecase/ResourceOwnersFixtureTest.kt` (new) only | every main file |
| B4 | `A/api/{ApiRouter,ApiJson,ReferenceHandlers,AttachmentHandlers,ReferenceDtos,AttachmentOperationIds}.kt`; `docs/api/v1.md`; `docs/api/attachment-operation-ids.json` (the new key only); `T/api/{ResourceOwnerRoutesTest (new),MaterializeRoutesTest,AttachmentRoutesTest,CommandShapesGoldenTest,ReferenceRoutesTest,InstalledComponentRoutesTest,SupplyRoutesTest}.kt` | `C/**`, `A/ui/**`, `A/share`, `A/data`, `A/di`, `FakeGraph`, `tools`, `command-shapes.json` |
| B5 | `M/src/servicetag_mcp/server.py`; `M/README.md`; `M/tests/{test_reference_tools,test_attachment_tools,test_tools,test_argument_guard,test_installed_component_tools,test_maintenance_tools,test_supply_tools}.py` | `app/**`, `core/**`, `S/**`, `docs` |
| B6a | `A/ui/references/{ReferencesSection,ReferencesSectionViewModel,MaterializeSheet,MaterializeViewModel,MaterializeStrings,ReferenceSheets}.kt`; `A/ui/asset/AssetDetailScreen.kt` (the one call); `T/ui/references/*`; `AT/ui/references/ReferencesSectionTest.kt` (arguments) | `C/**`, `A/api`, `A/data`, `A/di`, `A/share`, `A/ui/supplies`, `A/ui/installed`, `docs`, `tools` |
| B6b | `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`; `T/ui/supplies/SupplyDetailViewModelTest.kt`; `AT/ui/supplies/SupplySurfacesTest.kt` | as B6a, and `A/ui/references` |
| B6c | `A/ui/installed/{InstalledComponentDetailScreen (new),InstalledComponentDetailViewModel (new),InstalledComponentSheets,InstalledComponentsSection,InstalledComponentStrings}.kt`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `A/ui/asset/AssetDetailScreen.kt` (one parameter); `T/ui/installed/InstalledComponentDetailViewModelTest.kt` (new); `AT/ui/installed/{InstalledComponentDetailTest (new),InstalledComponentsSectionTest}.kt` | as B6b, and `A/ui/supplies` |
| B7 | `A/share/{ShareIntakeScreen,ShareIntakeViewModel,ShareIntakeActivity}.kt`; `T/share/ShareIntakeViewModelTest.kt`; `AT/share/ShareIntakeScreenTest.kt` | `C/**`, `A/api`, `A/ui/**` main (reuses `SupplyItemPicker` and the #47 tree as they are), the three boundary classes, the manifest, `docs`, `tools` |
| B8 | `docs/release-proofs.md`; `docs/design/{04-domain-data-model,14-asset-model}.md` | any `.kt`, `.py`, `tools`, `docs/api`, `docs/versioning.md`, `README.md` |

**B4 ∥ (B6a → B6b → B6c → B7) — the one parallel pair.** B4's files are `A/api/**`, `docs/api/**` and `T/api/**`; the
UI lane's are `A/ui/references/**`, `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`, `A/ui/installed/**`,
`A/ui/nav/{Route,ServiceTagRoot}.kt`, two lines of `AssetDetailScreen.kt`, `A/share/**` and their tests — **no file in
common**, and neither edits `AppGraph` or `FakeGraph`. Under the two-lane rule the UI lane may run in a second worktree
off B3c's tip; the controller lands it before B5, whose `<base>` holds both.

**Order:** B1a → B1b (→ B1b2) → B2a → B2a2 → B2b (→ B2b2) → B3a → B3b → B3c → { B4 (→ B4b) ∥ (B6a → B6b → B6c (→ B6c2)
→ B7 (→ B7b)) } → B5 → B8 — **fifteen dispatches** with B2a2 taken by default; more only by a split clause. B1b needs nothing of B1a's (core only) but follows it so the schema and format move one at a time; B2a
needs both (its Room arms read B1a's DAO, its DTO arms B1b's keys); B2b needs B2a's `OwnerRef` and doubles; B3a needs
B2a's lookup; B3b B2b's reference owner; B3c everything core; B4 and B6 the use cases; B7 B6a's owner twins; B5 B4's
routes; B8 describes everything.

**The owner shape, costed (R69-1; the audit's §2).**

| | **(a) nullable FK columns (chosen)** | (b) a polymorphic `(owner_type, owner_id)` pair | (c) per-owner tables |
|---|---|---|---|
| Room | 2 `ALTER`s on `attachment` (already this shape); 1 rebuild of `asset_reference` | both tables rebuilt (or a second representation beside `asset_id`/`event_id`) | 4 new tables |
| referential safety | the schema's CASCADE: a component's rows go with its asset at every removal path | none: `DeleteAsset`, the replace wipe and the return delete each delete by code | the schema's, ×4 |
| archive / merge | 2 keys per DTO; no list, table or count | 2 keys per DTO; the codec the only referential check | +4 lists, +4 `MergeTable`s, +4 count keys, 4 planner passes |
| use cases, UI, #85 | one path each, the owner a parameter | the same | forked per owner (the issue forbids, `issue-69.md` "Do not create separate file stores, link systems …") |
| a later fifth owner | one more column and member | one more type value | one more table set |

**The vendor URL, costed (R69-2; the audit's §4).**

| option | cost | against the PATCH overlay and the MCP's conventions |
|---|---|---|
| A. `supply_item.vendor_url` column | `ALTER`; DTO key + gate; a C12-style older-archive exception (an existing item gains a value); `LinkLaunchPolicy` validation copied | a new overlay key on `PATCH /v1/supply-items/{id}` (`""` clears) and a `clear_fields` entry on the MCP's update tool; **a second link representation** — not launched as a reference, not materializable, not a Share target. Fails "no … link systems" |
| B. a new `DocumentRole` (vendor page) | one member, its `when`/label sites, +1 Role chip (`ShareIntakeScreenTest:262` moves); uniqueness of "preferred" in the use case, codec and merge | no new key; a role already travels on the reference PATCH (`role: null` clears) |
| **C. reference rows, no marker (recommended)** | none beyond §2 | none: `POST /v1/references` with `supplyItemId`, `PATCH` as shipped, `add_reference(supply_item_id=…)` |
| D. `supply_item.preferred_reference_id`, a soft pointer to one of its own references | `ALTER`; DTO key + gate; a C12-style exception; a codec check (it names a reference of this item); a clear when that reference is removed (in `RemoveReference`, the replace wipe and the merge) | a new overlay key (`""` clears) on the SupplyItem PATCH, a `clear_fields` entry, and one more brief (**B2d**, ~50 min) after B2b |

**Audit corrections (re-read on `ad49b788`).** (1) `OwnerLookup` has **four** implementers, not two: add
`A/ui/maintenance/ScheduleDetailViewModel.kt:548-556` and `CT/transfer/TransferOwnershipTest.kt:30` (C5). (2) The
codec's reference owner check (`BackupCodec.kt:808-816`) runs **before** the SupplyItem and component id sets exist
(`:958`, `:997`) and must move (C13). (3) `/v1` has no `GET /v1/references/{id}` (`ApiRouter.kt:375-376`: PATCH only),
so `materialize_reference` keeps an owner argument (C23). (4) The sealed extension is compile-atomic, so the audit's
layer split (B1a domain, B1b Room, …) cannot each pass the gate; §4's order replaces it. (5) Four owner-worded
sentences, not one (C28).

**Recomputed counts.**

| fact | now |
|---|---|
| Room tables added | 0 (2 columns on `attachment`, 2 on `asset_reference`, 1 rebuild) |
| `BackupData` lists / manifest count keys / `MergeTable` / `MergeReason` | unchanged: 23 / 31 / 23 / +0 |
| pack classes / guarded ports | unchanged (two lists join "the row decides"); the guard gains one constructor reader |
| asset sub-resources; router | 27 unchanged; 77/95 → **81/101** |
| new API codes | **0** (one ratified 400 sentence, G1) |
| MCP tools | **89 unchanged** (5 widened) |
| moving pins | 24 literal + 4 one-past pairs + strip/key-order twins + the 17 construction sites + B4's and B5's ranges, plus 10–20 twins |
| construction sites (C-1) | `AddAttachment`, `HeldWriteGuard` (8) in B2a; `AddReference` in B2b; `DeleteAsset` in B3b — each `git grep`-counted by its brief |
| new phone strings | **16** (P69-1…16), 0 relabels |
| device classes | 58 → **59**, ~+20–24 cases, **~13.3–13.7 min** |
| dispatches | **15** (B2a2 by default; B1b2, B2b2, B4b, B6c2, B7b by clause; +1 B2d if R69-2 = D) |

**Gate budget** at the merged tip (estimates; B1a records the base's exact counts): core +~95 (format 15, domain and
seams 30, merge 10, pack, return and delete 12, scenario 6, use cases 22); app +~75 (Room 12, routes 25, view models
24, Share 14); MCP +~20; loader unchanged; device classes **59**. Timed against #47's per-class rate, reporting only,
no rerun-until-green.

## 5. Strings

**PROPOSED — to be RATIFIED by the owner as one block.** Each new string is declared once as a `const val` (or a
one-line function for a format) at the home named, and imported, never copied.

| id | proposed wording | home · where (contract) |
|---|---|---|
| P69-1 | "Documents" | `InstalledComponentStrings.kt` · the row sheet's action opening the component screen, on every row (C27) |
| P69-2 | "This component" | same · the heading over the component's own Documents and References (C27) |
| P69-3 | "From %s" — `%s` the SupplyItem's name | same · each open-only group's heading; a tap opens the SupplyItem (C27) |
| P69-4 | "Added and changed on the supply." | same · the quiet line under each group's heading (C27) |
| P69-5 | "Attach to" | `IntakeStrings` · the Share step's title (C30) |
| P69-6 | "Asset" | same · the first choice (C30) |
| P69-7 | "Supply" | same · the second choice (C30) |
| P69-8 | "Installed component" | same · the third choice (C30) |
| P69-9 | "Choose an installed component" | same · the component step's title (C30; "Choose a supply"'s shape) |
| P69-10 | "%1$s · %2$s" — the asset's name, then the component's | same · the chosen component's line on the form and in "Saved to %s" (C29) |
| P69-11 | "That link is already on this supply" | `A/ui/references/ReferencesSectionViewModel.kt` beside `:283` (declared by B6a; `IntakeStrings` imports it in B7) (C28) |
| P69-12 | "That link is already on this component" | as P69-11 (C28) |
| P69-13 | "The link is removed from this supply. Nothing in the other app is changed." | `ReferenceSheets.kt` (C28) |
| P69-14 | "The link is removed from this component. Nothing in the other app is changed." | as P69-13 (C28) |
| P69-15 | "This supply already has this file: %s." | `MaterializeStrings.kt` (C28; P85-17's shape) |
| P69-16 | "This component already has this file: %s." | as P69-15 (C28) |

**Reused verbatim from their one home:** "Documents", "No documents yet", "Add file", "Take photo"
(`A/ui/attachments/DocumentsSection.kt:93`, `:100`, `:108-109`); "References", "No references yet", "Add link", "Open"
(`A/ui/references/ReferencesSection.kt:171`, `:174`, `:197`, `:245`); the Name / Kind / Notes / Role labels and the
role values (`A/ui/attachments/AttachmentEditSheet.kt:39-43`; `AttachmentsSectionViewModel.kt:67-75`); every #85
string (`MaterializeStrings.kt:14-37`, P85-17 kept for an asset); "Choose a supply" and its empty sentence
(`A/ui/supplies/SupplyStrings.kt:60`, `:63`, inside `SupplyItemPicker`); "No installed components" (P47-2, inside the
component step); "Change", "Saved to %s" (`ShareIntakeViewModel.kt:90`, `:96`) and the rest of `IntakeStrings`
(`:76-87`); "Back" (the shipped top bars' content description, e.g. `A/ui/asset/AssetDetailScreen.kt:288`); the
asset-worded originals of P69-11…16 for an asset.

**The classification table (R69-6) — no new kind, no new role:**

| the issue's classification | shipped kind / role |
|---|---|
| PRODUCT_PHOTO / LABEL_PHOTO | `PHOTO` / `LABEL_PHOTO` (`C/model/Attachment.kt:9`) |
| INSTALLATION_PHOTO | `PHOTO` on a component owner |
| RECEIPT / INVOICE | `RECEIPT`; role `PURCHASE_INVOICE_OR_RECEIPT` on the owner the receipt is about |
| MANUAL / INSTRUCTIONS | `MANUAL`; roles `USER_MANUAL` / `SERVICE_MANUAL` |
| SPECIFICATION / DATA_SHEET | `DOCUMENT` (a gap, recorded; a new kind is a format and MCP-list change — R69-6) |
| WIRING / DIAGRAM | `DOCUMENT` or `PHOTO` (the same gap) |
| WARRANTY | `WARRANTY` |
| PRODUCT / VENDOR / SUPPORT WEB REFERENCE | `WEB_URL`, the subject in the description (`C/model/AssetReference.kt:4-8`) |
| OTHER | `OTHER` |

**G-list (developer-facing, ratified with the block):**
- **G1** — the create's 400: "a reference names exactly one owner: assetId, supplyItemId or installedComponentId".
- **G2** — the codec's `BackupCorrupt` messages (C10, C5, C13) in the shipped templates: the field gate ("…a format N
  archive cannot carry a supply item or installed component owner (attachment X)"), exactly-one, and the two "points
  at … which is not in …" lines; the owner-neutral `require` in `AddAttachment` (C5).
- **G3** — the MCP's `APP_SCHEMA_TOO_OLD` feature name "supply item and installed component resources".

**Not drawn anywhere (decided by the contracts):** a "move to another owner" action (I2); an aggregated resource list
on the Asset detail (R69-4); a "preferred" badge (R69-2); a held-asset dead end in Share (held assets are excluded).

## 6. Owner rulings

| ruling | whose | the question · the recommendation | where it lands |
|---|---|---|---|
| **R69-0** | **owner — OPEN** | **The scope sentence** (header): ratify as written, or amend. | every brief's must-nots |
| R69-1 | controller default | **The owner shape (a):** nullable owner FK columns, each CASCADE, exactly one set, enforced on the Room write and the backup read; the sealed `AttachmentOwner` gains two members; `asset_reference` rebuilt with one unique `(owner, uri)` index per owner. (b) loses referential safety exactly where #47 made rows leave only by CASCADE; (c) forks the use cases, views and #85 (§4's table). | C4–C13 |
| **R69-2** | **owner — OPEN** | **The vendor URL (AC11 against #15's "no URL"):** recommend **C — reference rows owned by the SupplyItem, no marker**; **D** (a soft `preferred_reference_id`) only if one link must be marked preferred (+1 brief B2d, a C12-style exception, a PATCH key and a `clear_fields` entry); **never A** (a second link system). §4's table costs all four. | C20, C31, §4 |
| **R69-3** | **owner — OPEN** | **Share's first step:** recommend a three-way **"Attach to: Asset / Supply / Installed component"**, Asset first, then the owner's own picker (#93's asset list; the shipped `SupplyItemPicker`; the chosen asset's current component tree); a prose share offers Asset only. The alternative — two entries (an asset picker that also lists each asset's components, plus Supply) — grows #93's picker and its tests. | C29–C30, P69-5…10 |
| **R69-4** | **owner — OPEN** | **The Asset detail:** recommend it **reaches** its components' and SupplyItems' resources by navigation only (a component row → its screen; a Supplies row → the SupplyItem detail) and never lists them (AC12: nothing reads as Asset-owned; the busiest screen does not grow). | C27, §1 out |
| R69-5 | controller default | **Schema 20 / format 20, a normal bump.** Never amend the unreleased 19 in place: emulator databases and format-19 exports exist, and Room refuses a hash mismatch with no migration. ~40 mechanical sites (§3). | C6–C10 |
| R69-6 | controller default | **Document roles on SupplyItem- and component-owned files too** (R67-11 widened to "anything but an entry's file"; `DocumentRole.kt:9-11` anticipates it); **no new `AttachmentKind`** — SPECIFICATION and DIAGRAM stay `DOCUMENT` (§5's table). Load-bearing for AC13: #85 carries the reference's role into its saved file. | C4, C5, C17, C25 |
| R69-7 | controller default | **The Transfer Pack:** a SupplyItem's resources, bytes included, travel with it as `GLOBAL_IN_USE` and are never held; a component's travel with its asset. Costs stated: pack size (limit 3); an edit while a pack is out refuses the return (limit 2, H5). | C15, C5, C13 |
| R69-8 | controller default | **The component screen shows** its own resources **and** the resources of every SupplyItem its direct link and its composition entries name, one open-only group per distinct SupplyItem, as two ownerships. | C27 |
| R69-9 | controller default | **The smallest honest component surface** is a route and a screen reached from the row sheet's "Documents" action on every row (a held asset's read-only); no section on the row sheet (sheets would stack, #47 E-30). No product choice remains beyond §5's words. | C27 |
| R69-10 | controller default | **Archived and removed owners:** an archived SupplyItem and a removed component keep their resources and may gain more from their screens and over the API/MCP; Share offers unarchived SupplyItems and current components only. | C5, C13, C26, C30 |
| R69-11 | controller default | **Bytes follow each owner's life** (C16a's table): kept with an archived SupplyItem and a removed component; swept with a deleted asset's components and a returning asset's. | C5, C16a |
| R69-12 | controller default | **API/MCP shape:** owner keys on the create (exactly one), +4 shapes / +6 rows, five MCP tools widened (89 stays, six new tools rejected), the v3 upload derivation for the new owners with v2 byte-identical for assets. | C19–C24 |
| R69-13 | controller default | **Owner-worded sentences:** per-owner twins for the four asset-worded sentences, the asset wording unchanged (no shipped pin moves), rather than one owner-neutral rewrite. | C28, P69-11…16 |
| R69-14 | controller default | **The brief split** (§4's order) replaces the audit's §13: substrate, two seams, then semantics — the sealed extension is compile-atomic. | §4, briefs |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; B5 `uv run --frozen pytest` in `M/` and in `S/` (unchanged). No device run in
  any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **59 classes**, zero
  skips; rows 50, 52 and 54 run here first — a red is one post-merge fix round), R3 (the three Python suites), R5
  (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps. `ReleaseProofPolicyTest`
  unchanged and green. **Record the environment** (#47 §20: which emulator, how started, whether one run): the 14/15
  min lines are judged only on a clean single run, reporting only.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'^    const val FORMAT_VERSION = 20$'` → 1; `'^    internal const val FIRST_RESOURCE_OWNER_FORMAT = 20$'` → 1;
    `LegacyArchive.kt` diff → empty.
  - `'^    version = 20,$'` in `AppDatabase.kt` → 1; `'^        const val SCHEMA_VERSION = 20$'` → 1; `'MIGRATION_19_20'`
    in `AppGraph.kt` and `MigrationTestSupport.kt` → each equal to `'MIGRATION_18_19'`'s count there; `20.json`
    present, `19.json` diff → empty; `'CREATE TABLE'` inside `MIGRATION_19_20` → 1 (the rebuild; read it).
  - `'data class OfSupplyItem\('` → 2 (attachment and reference owners); `'data class OfInstalledComponent\('` → 3
    (the two owners and `OwnerRef`); `'"supply-items/'` and `'"installed-components/'` in `Attachment.kt` → 1 each;
    `'"components/'` in `core/src/main` → 0.
  - `'val assetId: AssetId'` inside `data class AssetReference(` → 0 (read it); `'fun forAsset\('` and `'fun
    observeForAsset\('` inside the `ReferenceRepository` block → 0.
  - `'fun installedComponent\(id: InstalledComponentId\)'` → 4 (the interface and three main implementers: guard,
    planner, `InHand`).
  - `'MergeTable'`'s last line unchanged (`ASSET_SUPPLIES, INSTALLED_COMPONENTS,`); `MergeReason` diff → empty.
  - `'"attachments" to TransferTableClass.ASSET_OWNED'` → 1 and `'"assetReferences" to TransferTableClass.ASSET_OWNED'`
    → 1 (unchanged).
  - `'servicetag:attachment-upload:v2'` in `AttachmentOperationIds.kt` → 1 and `'…:v3'` → 1; the golden file's
    `vectors` array diff → empty.
  - `'Eighty-one path shapes over one hundred and one'` in `ApiRouter.kt` → 1; `'1–19'` in `v1.md` → 0; `'else ->'`
    inside the new mapping `when`s → 0.
  - `'^@mcp\.tool\('` in `server.py` → 89; `'^_MIN_RESOURCE_OWNER_SCHEMA_VERSION = 20$'` → 1; `'format 1–20'` → 1 and
    `'format 1–19'` → 0; `'format \*\*1–20\*\*'` in `M/README.md` → 1.
  - C33's six greps; the tombstone check → 0; gitlink `7e0377a`; `versionName` / `versionCode` unchanged;
    `git diff <base> -- tools/servicetag-schedules tools/servicetag-bundle` → empty; the three Share boundary classes'
    diff → empty.
- **The schema-20 upgrade note.** The schema 19 → 20 in-place upgrade of a signed build is **not** proven by this
  plan's gate: it is the carrying release's gate, written by B8 (C31) beside the schema-18 and -19 paragraphs — since
  no release carries 18 or 19 yet, that first release proves all three.
- **The emulator and the phones.** The merged-tip gate runs a debug build at schema 20 on the emulator only. Not
  proven by #69: the phones (untouched; no install), an on-device export, a Transfer Pack between two phones
  (JVM-proven, rows 35–37), a real Share from another app to a SupplyItem or a component (the boundary is
  owner-independent; rows 53–55), the owner's real equipment (never in a test).

## 8. Relationships

- **#15 — SupplyItems.** Reused unchanged: the stable `SupplyId`, the picker, the catalog reads, "archived, never
  deleted". #15's "no URL" fence holds under R69-2 C: the SupplyItem row gains nothing; its links are references it
  owns. D4's "`vendor_url` is #69's" is restated (C31).
- **#47 — installed components.** Plugs in exactly as #47 §8 promised: `AttachmentOwner.OfInstalledComponent`, the
  codec's owner check, `TransferOwnership.of(attachment)`; #47 guarantees an immutable id and asset and no single-row
  delete, which is why "a removed component keeps its resources" costs nothing. The row sheet gains one action (C27);
  #47's fitted-state semantics are untouched.
- **#85 — materialization.** One fetch, one provenance, one `AddAttachment`; the owner is derived from the reference
  (`asAttachmentOwner`, C12, C17).
- **#43 / #93 — Share.** The rules and the managed copy are untouched; the target generalises (C29–C30).
- **#67 / #91 — roles.** Widened to the new owners (R69-6); key documents stay per-owner by construction.
- **A later purchasing feature** names resources without re-owning them (a link table touching no owner column); if a
  purchase must **own** receipts, shape (a) takes a fifth column and member. #69 encodes no "receipt ⇒ SupplyItem".
- **#95, #96** — fenced (C33). **#98** — the child-Asset nouns and the `asset_reference` naming residue (limit 1).
  **#76** — the roadmap of record. **#90** — the gate-time trigger is not reached (§3). **#62** — no black-box UI
  driving; rows 50, 52 and 54 are tier-2 Compose semantics.

## Briefs — common to every brief (fifteen dispatches)

Read §1–§8, the audit, issue #69 (`.superpowers/sdd/2026-10-01-issue-69/issue-69.md`, the FINAL clarification) and
every earlier report on this branch. **Dispatch precondition:** R69-0, R69-2, R69-3, R69-4 ruled and §5 ratified. **The
core doubles (C11):** every core brief from B2a builds its fixtures through `BackupInstall`, never a bare list, so a
missing cascade fails for the right reason. **Fenced words (C-3):** no KDoc, docstring, document or test name added by
a brief carries a purchasing, #95 or #96 word, even to deny it. **Constructor plumbing (C-1):** a brief whose contract
adds a constructor parameter owns its `AppGraph` line, its `FakeGraph` line and every construction site `git grep`
names — arguments only; no `AppGraph` or `FakeGraph` edit after B3b. **Forced arms (R69-14):** a seam brief writes
exactly the arms its contract names; a compile error it meets **outside** its "touches" list stops it.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (over `app/src/main` and `core/src/main` unless
named); the untouched diff against the brief's "never touches"; C33's greps; the tombstone check → 0; `git ls-tree
HEAD libs/nfc-tag-core` → `7e0377a…`; `versionName` / `versionCode` equal `<base>`'s; `git diff <base> --
app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml tools/*/uv.lock app/src/main/AndroidManifest.xml`
→ empty; `git status` clean. B5 adds `uv run --frozen pytest` in `M/` and `S/`.

**Pin rule and the twin rule.** A shipped assertion moves only where §3's table names it for this brief, or as its
twin. Confirm the set first with `git grep -nE 'FORMAT_VERSION|formatVersion\)|schemaVersion\)|SCHEMA_VERSION|1–19|19
since|counts\.size|dropLast|assetId = |findByUri|observeForAsset'` and `git grep -nE 'TOOL_NAMES|\b89\b'` over
`app/src/test app/src/androidTest core/src/test tools/servicetag-mcp/tests`; report every hit the table does not name
and how the twin rule treats it before editing.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each grep
with its count; the test counts before and after; every twin moved; the wall time against the box; what is done,
proven and not proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin and twin
rules; a JVM or build failure the brief did not cause (reported, not retried); network access from a test; a phone
string not in §5; a schema or format change outside B1a/B1b; a cited line that reads differently on `<base>`; an
undecided owner question; the cap reached, the 1-hour target passed with under half the rows green, or the 2-hour hard
stop.

**Must NOT, always:** step outside the scope (pending R69-0, binding once ruled): "#69 = one resource system — … with
three explicit owners (Asset, SupplyItem, InstalledComponent) … Nothing more." — so never add a purchase, order, lot
or transaction row, field or word; stock, quantity on hand or reorder (#95); a schedule material requirement or kit
(#96); a `vendor_url` column (R69-2) or a "preferred" marker unless D is ruled; an NFC tag, binding or scan route on a
SupplyItem or component; a new `AttachmentKind`, `ReferenceKind` or `DocumentRole` value; a child-Asset owner case; a
second store, downloader, provenance field or link system; a change to `installed_component` or `supply_item` columns
or to #47's fitted-state rules. Also never: copy a row or a byte for visibility; infer an owner from a name, kind, role
or URI; move a resource between owners; give `OwnerLookup` a default method; rename `AssetReference`,
`asset_reference` or `assetReferences`; use `components/` as a directory; put a bare `component(s)` on a new machine
surface; delete a shipped assertion; commit outside the brief's files; add a device-boundary class or run a device;
bump a version; write a repository from a handler or a view model; re-check in the API or UI a rule the use case owns;
touch the 2.6 tombstones, `19.json`, `S/`, `tools/servicetag-bundle`, `tools/emulator/*`, a harness helper or the gate
script; add a dependency; write a ledger; quote the owner's private data or write a real name, host, serial or e-mail
address (fixtures: `example.invalid`).

## 9. B1a — Room schema 20 (C6–C8; app JVM)

**Read:** audit §0.1–0.2, §2(a), §3; `A/data/room/entities/{AttachmentEntity,AssetReferenceEntity}.kt`;
`A/data/room/dao/{AttachmentDao,AssetReferenceDao}.kt`; `A/data/room/Migrations.kt:326-332` (the ALTER precedent),
`:424-440` (the rebuild precedent), `:827-904` (`MIGRATION_18_19`); `A/data/room/Mappers.kt:160-210`;
`T/data/room/{Migration18To19Test,ReferenceDaoConstraintTest,MigrationTestSupport}.kt`. **Rows:** 1–6. **Rulings:**
R69-1, R69-5. **Interfaces produced:** the two entities' v20 shapes, the owner-keyed DAO reads, `MIGRATION_19_20`,
`20.json`. **Greps:** §7's schema lines; `'CREATE TABLE'` inside `MIGRATION_19_20` → 1; `git diff <base> -- core` →
empty. **Untouched:** `C/**`, `A/api`, `A/ui`, `A/share`, `19.json`. **Must NOT:** change a domain type; add a `CHECK`;
add a single-column index on `asset_reference`'s new owner columns (the unique pairs' left prefixes serve; if Room's
compiler demands one, stop and report). **Counted RED (4):** rows 1, 2, 4, 5. **Caps:** 7 runs; 1 h / 2 h; fix round 3
runs, 45 min. **Size:** about 120 production lines plus `20.json`, 260 test lines. **Estimate:** 55 min.

## 10. B1b — the format-20 envelope (C9–C10; core JVM)

**Read:** audit §0.3–0.4, §3; `C/backup/BackupFormat.kt:385-439`, `:1119-1170`, `:1320-1350`;
`C/backup/BackupCodec.kt:176-236`, `:418-456`; `CT/backup/BackupFormat19Test.kt` (the shape to copy). **Rows:** 7–11.
**Rulings:** R69-5. **Interfaces produced:** the DTO keys, `FIRST_RESOURCE_OWNER_FORMAT`, the gate, exactly-one.
**Greps:** §7's format lines; `git diff <base> -- C/model C/merge C/transfer C/usecase app/src/main` → empty.
**Untouched:** as §4. **Must NOT:** map a new owner key to a domain owner (B2a/B2b do); add a list, table or count
key; give `AssetReferenceDto.assetId` a default. **Counted RED (3):** rows 7, 8, 9. **Caps:** 6 runs; 1 h / 2 h.
**Size:** about 50 production lines; ~28 pin edits; 220 test lines. **Estimate:** 55 min. **Split clause:** past a
1 h dispatch estimate, the pins (row 11) go to **B1b2** off B1b's tip with `FORMAT_VERSION` — never apart from it.

## 11. B2a — the attachment owners, the seam (C4, C5, C11; core and app JVM)

**Read:** audit §0.1, §0.6–0.8, §0.10, §1.5, §2; this plan's C4, C5, C11 and correction 1; `C/model/Attachment.kt`;
`C/usecase/AddAttachment.kt`; `C/backup/BackupCodec.kt:585-600`, `:950-1000`, `:1120-1160`;
`C/usecase/ApplyBackupMergePlan.kt:270-369`; `C/transfer/{TransferOwnership,HeldWriteGuard}.kt`;
`C/merge/MergePlanner.kt:1365-1400`; `A/ui/maintenance/ScheduleDetailViewModel.kt:540-556`;
`A/data/room/{Mappers,RoomRepositories}.kt`; `CT/testing/{InMemoryRepositories,InstalledComponentFixtures,BackupInstall}.kt`.
**Rows:** 12–20. **Rulings:** R69-1, R69-6, R69-10, R69-11. **Interfaces produced:** the two `AttachmentOwner`
members, widened `accepts`, `dirFor`'s two directories, `OwnerRef.OfInstalledComponent`,
`OwnerLookup.installedComponent`, the guard's new reader, the doubles' component cascade. **Greps:** `'"supply-items/'`,
`'"installed-components/'` → 1 each; `'fun installedComponent\(id: InstalledComponentId\)'` → 4; `HeldWriteGuard(`
construction sites all updated (count before = after). **Untouched:** as §4. **Must NOT:** touch `AssetReference`; add
the merge's attachment availability (B3a) or the pack's select/retain (B3b); add `OwnerLookup` a default; let the
double cascade asset-owned rows. **Split by plan, taken by default:** the arms cannot move (the compiler forces
every one into B2a's commit), so the split is of **rows**: B2a writes C4, C5 and C11 whole with rows 12–16 and 20 (the
domain, the codec, Room, `AddAttachment`, the doubles); **B2a2**, off B2a's tip and touching only
`CT/transfer/{TransferOwnershipTest,HeldWriteGuardTest}.kt` and the return's test class (`ImportTransferPackTest` or a
new `CT/usecase/ReturnScopeTest.kt`), adds rows 17–19 with their counted REDs by mutation of the arms B2a wrote; its
gate is the common one. **B2a — Counted RED (6):** rows 12, 13, 14, 15, 16, 20 (row 20 by mutation when not natural).
**Caps:** 9 runs; 1 h / 2 h; fix round 4 runs, 45 min. **Size:** about 140 production lines, 240 test lines, ~30
construction-site arguments. **Estimate:** 60 min. **B2a2 — Counted RED (3):** rows 17, 18, 19. **Caps:** 5 runs;
1 h / 2 h. **Size:** about 120 test lines. **Estimate:** 35 min.

## 12. B2b — the reference owner, the seam (C12, C13, C17; core and app JVM)

**Read:** audit §0.2, §0.9, §1.2, H3, H4; this plan's C12, C13, C17 and corrections 2–3; `C/model/AssetReference.kt`;
`C/ports/Repositories.kt:323-345`; `C/usecase/{AddReference,MaterializeReference}.kt`;
`C/backup/BackupCodec.kt:800-820`, `:950-1000`; `C/merge/MergePlanner.kt:300-370`, `:580-590`, `:648-664`,
`:1036-1095`; `A/data/room/{ReferenceMappers,ReferenceRepositories}.kt`; `A/api/ReferenceHandlers.kt:60-90`;
`A/api/AttachmentHandlers.kt:265-290`. **Rows:** 21–28 (row 26 is C17's: `prepare(owner: ReferenceOwner, …)`,
`takeIf { it.owner == owner }`, the duplicate check over `attachments.forOwner(owner.asAttachmentOwner())`,
`Prepared.Ready.owner`, `commit` to `ready.owner.asAttachmentOwner()` — one fetch, one `AddAttachment`).
**Rulings:** R69-1, R69-10. **Interfaces produced:** `ReferenceOwner`, `asAttachmentOwner`, the owner-keyed port,
`AddReference.run(owner, …)`, `MaterializeReference.prepare(owner, …)`. **Greps:** §7's `AssetReference` lines; the
codec block's new position after `componentIds` (read it). **Untouched:** as §4. **Must NOT:** keep an asset-keyed
port method; add an `assetId` property; change the step order of `AddReference`; generalise a composable (B6a).
**Counted RED (7):** rows 21, 22, 23, 24, 25, 26, 27. **Caps:** 9 runs; 1 h / 2 h; fix round 4 runs, 45 min.
**Size:** about 120 production lines; 17 construction sites and ~15 call sites, arguments only; 300 test lines.
**Estimate:** 70 min — **split clause:** past a 1 h dispatch estimate, row 26 (materialize) goes to **B2b2** off B2b's
tip; B2b then writes `prepare`'s owner parameter with the asset-only check intact (`takeIf { it.owner == owner }`
already holds for an asset) and B2b2 adds the owner-scoped duplicate check and its rows.

## 13. B3a — the merge and the restore order (C14; core JVM, two app rows)

**Read:** audit §0.3, §0.5, H1; `C/merge/MergePlanner.kt:960-1036`, `:1395-1440`; `C/usecase/ImportBackupReplace.kt:160-262`;
`C/usecase/ApplyBackupMergePlan.kt:193-232`. **Rows:** 29–34. **Rulings:** R69-7 (M2). **Greps:** `MergeReason` diff →
empty; `'data.assetReferences.forEach'` in `ImportBackupReplace.kt` after `installedComponents.insert` (read it).
**Untouched:** as §4. **Must NOT:** add an older-archive exception; reorder the apply. **Counted RED (5):** rows 29,
30, 31, 32, 33. **Caps:** 7 runs; 1 h / 2 h. **Size:** about 30 production lines, 200 test lines. **Estimate:** 45 min.

## 14. B3b — the pack, the return's limit and the delete (C15, C16a; core JVM)

**Read:** audit §0.6–0.7, H2, H5; `C/transfer/TransferGraph.kt:30-70`, `:225-335`; `C/usecase/DeleteAsset.kt:40-80`;
`CT/transfer/{TransferGraphTest,TransferGraphRetainTest,ImportTransferPackTest}.kt`. **Rows:** 35–38. **Rulings:**
R69-7, R69-11. **Greps:** the two `CLASSES` lines unchanged; `DroppedRows` diff → empty; `DeleteAsset(` construction
sites all updated. **Untouched:** as §4. **Must NOT:** drop a SupplyItem-owned row in `retain`; change `DroppedRows` or
`entangledRefs`; sweep a SupplyItem's bytes. **Counted RED (4):** rows 35, 36, 38, and row 37's pin re-run. **Caps:** 6
runs; 1 h / 2 h. **Size:** about 35 production lines, 220 test lines. **Estimate:** 50 min.

## 15. B3c — the equipment scenario (C16b; core JVM)

**Read:** issue #69's examples and E1–E7; `CT/usecase/InstalledComponentFixtureTest.kt` (#47 row 37's shape);
`CT/usecase/SupplyItemFixtureTest.kt`. **Rows:** 39, 40. **Greps:** `git diff <base> -- '*/src/main/*'` → empty.
**Must NOT:** change production code (a failure is a finding: stop and report it against the brief that owns the
contract); use a real product, maker or URL. **Counted RED (1):** row 39. **Caps:** 4 runs; 1 h / 2 h. **Size:**
about 260 test lines. **Estimate:** 40 min.

## 16. B4 — the API and the wire document (C19–C22; app JVM, docs)

**Read:** audit §7, H9; `A/api/ApiRouter.kt:95-100`, `:215-330`, `:370-385`, `:429-436`; `A/api/{ReferenceDtos,
ReferenceHandlers,AttachmentHandlers,AttachmentOperationIds}.kt`; `docs/api/attachment-operation-ids.json`;
`docs/api/v1.md` (the reference, attachment, SupplyItem and component sections). **Rows:** 41–45 and B4's pins.
**Rulings:** R69-2 (C20's no-key rule), R69-12. **Greps:** §7's API lines; `command-shapes.json` diff → empty.
**Untouched:** as §4. **Must NOT:** add an error code; add a `preferred`/`vendor` key; change the v2 derivation or its
vectors; add a `DELETE`. **Counted RED (4):** rows 41, 42, 43, 44. **Caps:** 7 runs; 1 h / 2 h. **Size:** about 120
production lines, 280 test lines, ~60 doc lines. **Estimate:** 60 min — **split clause:** past a 1 h dispatch estimate,
C22 (the document) and row 45 go to **B4b** off B4's tip.

## 17. B5 — the MCP (C23–C24; pytest)

**Read:** audit §7 (MCP); `M/src/servicetag_mcp/server.py:185-245`, `:2100-2200`, `:3036-3050`, `:3100-3420`;
`M/tests/{test_reference_tools,test_attachment_tools,test_argument_guard,test_tools}.py`. **Rows:** 46–48.
**Rulings:** R69-12. **Greps:** §7's MCP lines. **Untouched:** as §4. **Must NOT:** add a tool; gate an asset call on
schema 20; read the golden `vectors` array differently. **Counted RED (2):** rows 46, 47. **Caps:** 6 runs; 1 h / 2 h.
**Size:** about 110 production lines, 260 test lines. **Estimate:** 55 min.

## 18. B6a — the sections keyed by owner (C25, C28; app JVM)

**Read:** audit §5.1, H4; `A/ui/references/*`; `A/ui/attachments/AttachmentsSectionViewModel.kt:60-95`, `:175-190`;
`T/ui/references/*`. **Rows:** 49 (and B6a's pins). **Rulings:** R69-6, R69-13. **Greps:** P69-11…16 each declared once
(`git grep -c` of each literal → 1); `AssetDetailScreen.kt` diff → the one call. **Untouched:** as §4. **Must NOT:**
change an asset's visible behaviour; reword an asset sentence. **Counted RED (2):** row 49's saved-mark and twin
cases. **Caps:** 6 runs; 1 h / 2 h. **Size:** about 80 production lines, 200 test lines. **Estimate:** 50 min.

## 19. B6b — the SupplyItem detail (C26; app JVM, Compose)

**Read:** audit §5.2; `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`; `AT/ui/supplies/SupplySurfacesTest.kt`;
`A/ui/asset/AssetDetailScreen.kt:440-500` (how the asset passes the sections their arguments). **Rows:** 50.
**Rulings:** R69-10. **Greps:** the KDoc's "#69's resources, which later sit below" replaced (read it).
**Untouched:** as §4. **Must NOT:** add a section above "Used by"; make an archived item read-only. **Counted RED
(1):** row 50's JVM case. **Caps:** 5 runs; 1 h / 2 h. **Size:** about 50 production lines, 120 test lines.
**Estimate:** 40 min.

## 20. B6c — the installed-component screen (C27; app JVM, Compose)

**Read:** audit §5.3, H7; `A/ui/installed/*`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `A/ui/supplies/SupplyDetailScreen.kt`
(the screen shape); `AT/ui/installed/InstalledComponentsSectionTest.kt`. **Rows:** 51, 52. **Rulings:** R69-8,
R69-9. **Greps:** `'data class InstalledComponentDetail\('` in `Route.kt` → 1; P69-1…4 each declared once.
**Untouched:** as §4. **Must NOT:** stack a sheet over the row sheet; draw an add or edit action in a SupplyItem group;
copy a SupplyItem's row into the component's list. **Counted RED (1):** row 51's dedupe case. **Caps:** 7 runs; 1 h /
2 h. **Size:** about 200 production lines, 360 test lines. **Estimate:** 65 min — **split clause:** past a 1 h
dispatch estimate, the Compose class (row 52) goes to **B6c2** off B6c's tip.

## 21. B7 — Share intake (C29–C30; app JVM, Compose)

**Read:** audit §6, H8; `A/share/*`; `A/ui/supplies/SupplyItemPicker.kt`; `A/ui/installed/InstalledComponentsSectionViewModel.kt`
(the current-tree derivation to reuse); `T/share/ShareIntakeViewModelTest.kt`; `AT/share/ShareIntakeScreenTest.kt`;
#93's plan (`docs/superpowers/plans/2026-10-01-issue-93-share-intake-asset-picker.md`). **Rows:** 53–55 and B7's
pins. **Rulings:** R69-3, R69-10, R69-13. **Greps:** the three boundary classes' diff → empty; `AndroidManifest.xml`
diff → empty; P69-5…10 each declared once. **Untouched:** as §4. **Must NOT:** offer a prose share a SupplyItem or
component; offer an archived SupplyItem, a removed component or a held asset's component; write before Save; change a
security rule. **Counted RED (2):** row 53's removed-rows case and its archived-item case. **Caps:** 7 runs; 1 h / 2 h.
**Size:** about 160 production lines, 320 test lines. **Estimate:** 65 min — **split clause:** past a 1 h dispatch
estimate, the Compose cases (row 54) go to **B7b** off B7's tip.

## 22. B8 — the documents and the release-proofs paragraph (C31–C32; docs)

**Read:** `docs/release-proofs.md:120-140`; `docs/design/04-domain-data-model.md:438-460`; `docs/design/14-asset-model.md`;
every report on the branch. **Rows:** 56. **Greps:** `'^\*\*The first signed release carrying Room schema 20'` in
`release-proofs.md` → 1; `'vendor_url. is #69'` in D4 → 0 (read it); `ReleaseProofPolicyTest` green.
**Untouched:** as §4. **Must NOT:** describe what was not built; name a purchasing, #95 or #96 word. **Counted RED
(0):** a tripwire row. **Caps:** 3 runs; 1 h / 2 h. **Size:** about 80 doc lines. **Estimate:** 35 min.
