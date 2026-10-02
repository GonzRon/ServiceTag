# #69 — resources owned by SupplyItems and installed components, and Share intake to all three owners: plan and briefs (rev 1.3, 2026-10-02)

> **Rev 1.4 (2026-10-02) — R69-3 final:** one "Share to" picker (type dropdown Assets / Installed components /
> Supplies, one search box, one list of that type, direct selection to the existing save form — no dialog) with
> browsing kept as the optional path; rev 1.3's readings and Share strings approved (P69-18, -21, -22 reworded).
> Landed: C29, C30, rows 53, 53a–53c, 54, §3's pins, §4 (B7 / B7c / **B7d** / B7b — twenty dispatches, each ≤ 1 h),
> the gate projection (~14.2–14.4 min), §5 (RATIFIED; P69-23…26 PROPOSED), §6 R69-3, briefs §24–§27 (B8 §28).
> Nothing outside Share changes. *(Rev 1.3, superseded: an asset-first hierarchical step.)*

> **Rev 1.2 (2026-10-02, the owner's rulings — mechanical reconciliation, no review round):** R69-0 the scope sentence
> APPROVED (one existing resource system extended to Asset, SupplyItem and InstalledComponent ownership; event-owned
> attachments stay supported); R69-2 OPTION C — ordinary SupplyItem-owned references, no preferred marker, no URL
> column; AC11 is satisfied by an owner-labelled vendor reference and there is no machine-readable preference; R69-3
> APPROVED (Attach to → Asset / Supply / Installed component, Asset first; shared prose Asset-only); R69-4 APPROVED
> (navigation-only from the Asset detail; the component screen shows its own resources and clearly separated,
> open-only SupplyItem groups). §5 RATIFIED as one block with "installed component" used consistently in P69-12/-14/-16;
> the row sheet's action reads "Documents and references" (C27, R69-9). The controller defaults ACCEPTED (schema 20 /
> format 20; the existing role vocabulary; owner-scoped materialization; the eighteen bounded briefs); version 1.6.0 /
> code 19 unchanged; emulator validation only; the carrying release's upgrade proof stays separate. **The Transfer Pack
> limitations are explicitly accepted** (a conflicting edit to a global SupplyItem resource can block the return; a
> resource deleted locally can be reintroduced by the returning pack) — documented and behaviourally pinned, rows and
> bytes. F-1: proceed and measure the ordinary merged-tip gate once (elapsed and device time separately).

> **Rev 1.1 (2026-10-01)** folded the independent plan review (`.superpowers/sdd/2026-10-01-issue-69/plan-review.md`,
> APPROVE WITH CONDITIONS; the seam split R69-14 upheld): its nine conditions (C-1…C-9) and eighteen notes (N-1…N-18)
> are tagged where they landed in the text below; the full landing map is rev 1.1's header (`git show 3709b4c3`).

> **Rev 1 — status (its open questions since ruled, rev 1.2–1.3). Release line, unchanged:** no version bump and no
> release — **1.6.0 / 19**; the vehicle is the owner's (the post-#98 fresh 1.0.0 on the train #47 → #69 → #16 → #98);
> #69 lands **Room schema 20 / backup format 20** (R69-5) on master, emulator only; master builds never go on a phone.

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
> places** (§4, "Audit corrections"). **Twenty dispatches (rev 1.4) on one branch `issue-69`:** B1a, B1b, B2a,
> B2a2, B2b, B2b2, B3a, B3b, B3c, then **B4 ∥ (B6a → B6b → B6c → B6c2 → B7 → B7c → B7d → B7b)**, then B5, B8 — each `<base>` the previous accepted tip; **B1a's `<base>`
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
  **The forced-arms rule (C-4):** a brief whose contract adds a member, changes a field or removes a port method owns
  **every compile error that change forces, in any source set** — arguments and receivers only, each listed in its
  report; §4 names the sites the review counted. The gaps this leaves between briefs are listed in §4 ("Between-brief
  gaps"), each unreachable on the branch (no UI or API path writes a new owner before B4, B6 or B7) and each closed by
  a named row.
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
  B1a–B3b** (#47 C-1): B3c, B4, B5, B6a–c2, B7, B7c, B7d, B7b and B8 edit neither.
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
2a. **A SupplyItem's file or link deleted at home while a pack naming its item is out comes back on the return (C-9).**
   The pack carries the row; locally it is absent; its owner is available; so the plan answers INSERT and the pack's
   own bytes land (`ImportTransferPack.kt:127`). Rows added on the borrowing phone land too; only edits refuse (limit
   2). Pinned by row 37a; the person removes it again after the return.
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
  E-19/E-20 precedent of a ratified developer-facing 400) and for any unknown key — a create that omits `assetId` now
  answers G1's 400 rather than the decoder's (a B4 twin, N-12). A refusal naming a SupplyItem or component the path
  names: 404 with no `field`; one the body names: 404 with `field` `supplyItemId` or `installedComponentId` (the two
  new keys only); the asset arm keeps today's `no_such_asset` with no `field` (C-6).
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
  columns, same index names — and records it in the report; it is not a stop (N-11: unlikely — `:329` validated).
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
  `:827`); `version = 20` (`A/data/room/AppDatabase.kt:103`); `SCHEMA_VERSION = 20` (`A/di/AppGraph.kt:1097`); the
  migration list in `AppGraph` (`:275`) and the test chain (`T/data/room/MigrationTestSupport.kt:69`); `20.json`
  generated. **No new table, so `V19_TABLES` and the whole-chain table sets do not move** — but **every
  `MigrationNToN+1Test` measures the latest schema** (`openMigrated` registers the whole chain), so the column-shape
  pins on the two changed tables move in B1a in #85's `dropLast` shape (C-1): `Migration9To10Test.kt:67`, `:69` and
  `Migration15To16Test.kt:80`, `:82` (`attachment` 20 → 22 columns; drop a `V20_ATTACHMENT_COLUMNS` list before the
  shipped take/drop), `Migration16To17Test.kt:74`, `:76` (`asset_reference` 10 → 12; drop a `V20_REFERENCE_COLUMNS`
  list), and `Migration18To19Test.kt:129-137` (`everyV18TableIsUnchanged`) **narrowed to every v18 table but
  `attachment` and `asset_reference`** — whose row preservation is rows 1–2's. The two entity constructions in
  `T/data/room/AttachmentDaoTest.kt:202` and `ReferenceDaoConstraintTest.kt:39` take the new fields (arguments
  only; the fields keep no default, the #85 precedent). The DAOs gain owner-keyed reads beside
  the shipped ones (`dao/AttachmentDao.kt:26-42`; `dao/AssetReferenceDao.kt:19-49`): rows and observed rows by
  `supply_item_id` and by `installed_component_id`, and `findByUri` per owner column, each ordered as its asset twin.
  **B1a is app-only and additive:** the domain is untouched, so the mappers still build only asset/event owners; the
  reference entity's nullable `assetId` maps through `requireNotNull` (interim, replaced by B2b — row 27).

### B1b — the format-20 envelope (C9–C10)

- **C9, the DTOs (fact 3).** `AttachmentDto` (`C/backup/BackupFormat.kt:395-416`) gains `supplyItemId: String? = null`
  and `installedComponentId: String? = null` **appended after `sourceName`**; `AssetReferenceDto` (`:428-439`) gains
  the same two **appended after `role`**; **its `assetId` stays `String` in B1b** (C-2: a nullable one breaks
  `C/merge/MergePlanner.kt:1061`, `:1087-1088`, which B1b never touches) and becomes `String?` **with no default** in
  B2b (C13), the brief that owns that planner pass. The KDocs name format 20 as the keys' first format, in the shipped
  `role`/`source` paragraph shape. Defaults keep a format ≤ 19 archive decoding.
- **C10, the gate and exactly-one.** `FORMAT_VERSION = 20` (`BackupCodec.kt:181`); `internal const val
  FIRST_RESOURCE_OWNER_FORMAT = 20` beside `:231`, KDoc "the first format that can carry a SupplyItem or installed
  component owner on an attachment or a reference (#69): an archive below it that carries one was built by hand".
  **The field gate**, in the shape of `:420-452`, after the reference-role gate: a format < 20 archive with a non-null
  `supplyItemId` or `installedComponentId` on any attachment or reference is `BackupCorrupt` naming the list and the
  row (G2). Explicit nulls are accepted. **Exactly-one in `toDomain`:** `AttachmentDto.toDomain` (`:1149-1154`)
  counts every owner key; `AssetReferenceDto.toDomain` (`:1340-1348`) refuses a row naming a new owner key beside its
  (still required) `assetId` (G2: "reference {id} must name exactly one owner, an asset, a supply item or an installed
  component"). **At B1b's tip the domain holds only the shipped owners**, so a row naming a new owner key is refused by
  that exactly-one rule (B2a and B2b widen it — rows 14 and 23); nothing writes such a row before then. The ~28 format pins
  move here (§3). No graph check, merge, pack or use case changes in B1b.

### B2a — the attachment owners, the seam (C4, C5; C11 is B2a2's)

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
    belongs on an asset's, a supply item's or an installed component's file, not an entry's"); so is
    `C/usecase/UpdateAttachment.kt:30`'s (G2, N-6).
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
  - **Two exhaustive `when`s in test sources (C-4):** `CT/usecase/BackupUseCasesTest.kt:651-654`
    (`FkCheckingAttachmentRepository`, which fails an upsert whose owner is not in yet) gains two arms asking the
    install's SupplyItem and component doubles, as the FK would (two `lateinit` lookups beside `assets` and `events`;
    the shipped case never reaches them); `CT/transfer/TransferImportTesting.kt:153-158` (`reproduceRoomCascades`,
    the asset delete's cascade over attachments) answers **`false`** for both — a SupplyItem's row never goes with an
    asset, and a component's goes by C11's registration, not by this fake.
  The guarded ports (`HeldWriteGuard.kt:409-420`) are unchanged: they already ask `TransferOwnership.of`.
- **C11, the core doubles (#47 C-2).** The core attachment and reference doubles cascade from nothing today
  (`CT/testing/InMemoryRepositories.kt:153-159`'s KDoc), so a test of "the asset delete takes its components'
  resources" would pass vacuously. **B2a2** adds (moved from B2a, C-7), in `CT/testing/`: the component double (`InstalledComponentFixtures.kt`)
  reports the ids its `cascadeFromAsset` removes to registered listeners, and `BackupInstall` registers the attachment
  double's `cascadeFromInstalledComponents(ids)` (removes rows owned by those ids — **only** those: asset-owned rows
  keep today's behaviour, so no shipped test moves). The KDoc at `InMemoryRepositories.kt:151-156` is restated to name
  the component-owned registration and the deliberate asymmetry (N-16); row 20 pins both halves. B2b registers the
  reference double's twin (its `findByUri` and uniqueness are B2b's; the cascade registration is a two-line twin). No
  owner-existence check in either double (Room proves the FKs, rows 4–5).

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
  the step order and every refusal are unchanged (I-2). `MaterializeReference` **stays asset-keyed in B2b** (C-7) by
  one-line compile fixes — `takeIf { it.owner == ReferenceOwner.OfAsset(assetId) }` at `:73`, and the API's `:282`
  reads the asset through `(reference.owner as? ReferenceOwner.OfAsset)` (any other owner answers the shipped
  no-such-reference) — and B2b2 takes C17 whole. `BackupFormat`: `AssetReferenceDto.assetId` becomes `String?` with no
  default (C-2, from B1b); `AssetReference.toDto` (`:1320-1331`) writes one owner key; `AssetReferenceDto.toDomain`
  builds the three owners (C10 widened). The codec's reference owner check (`BackupCodec.kt:808-816`) **moves after `componentIds` (`:997`)**
  — it runs before the SupplyItem and component id sets exist today (correction 2) — and checks each owner kind,
  shipped asset message unchanged. The merge's second identity (`MergePlanner.kt:310-311`, `:364-365`, `:1061`)
  becomes the pair **`(owner, uri)`** with the owner value-typed (H3: `(null, uri)` and an id string shared by an asset
  and a SupplyItem must never collide); the reference availability arm (`:1087-1088`) resolves each owner —
  `assetAvailable`, `supplyItemAvailable` (`:585`), a component "here or accepted" (`:653`, `:661`) — reusing
  `OWNER_NOT_AVAILABLE` with the owner's id. `ReturnScope.of` (`:315`) drops a reference owned by a returning asset or
  one of its components. `TransferOwnership.of(reference)` (`:106`) maps as C5. App: `ReferenceMappers`,
  `ReferenceRepositories` (`RoomReferenceRepository`, `:16`), `ReferenceHandlers.kt:83` (`ReferenceOwner.OfAsset`, the
  shipped create — C20 widens it), and the callers' arguments: `ReferencesSectionViewModel.kt:114`, `:225`
  (`OfAsset(assetId)`), `ShareIntakeViewModel.kt:367-376` — **arguments only**, no behaviour. **The compile-forced test
  sites (C-4):** the **16** `AssetReference(` constructions (2 in core main, 1 in app main, 9 in `CT/`, 4 in `T/`) and
  ~20 `.assetId` reads and removed-port calls in eight test files — `CT/backup/BackupFormat7Test.kt:192`, `:458`,
  `:492-493`; `CT/usecase/UpdateReferenceTest.kt:108`; `CT/testing/InMemoryRepositories.kt:690-693`;
  `CT/transfer/TransferImportTesting.kt:162`; `T/api/ReferenceRoutesTest.kt:167`, `:267`;
  `T/ui/references/ReferencesSectionViewModelTest.kt:441`, `:462`, `:480`; `T/share/ShareIntakeViewModelTest.kt:158`,
  `:185`, `:427`, `:461`, `:482`, `:633`, `:647` (`graph.references.forAsset`; B7's file, arguments here) — receivers
  and arguments only.
- **C17, materialize keyed by the reference's owner (fact 9; H4; AC13) — B2b2's whole contract (C-7).** The five
  asset-bound sites of
  `C/usecase/MaterializeReference.kt` take the owner, and the downloader stays one: `prepare(owner: ReferenceOwner,
  referenceId, onProgress)` (`:67-74`) answers `NoSuchReference` unless `reference.owner == owner`; eligibility, the
  store, the network permission and the one `FetchDocument` call (`:79-101`) are unchanged; the duplicate check (`:108`)
  reads `attachments.forOwner(owner.asAttachmentOwner())` — **the owner's own files only** (H4: the same bytes on the
  asset never refuse a SupplyItem's save, and the reverse); `Prepared.Ready.assetId` (`:182-183`) becomes `owner:
  ReferenceOwner`; `commit` (`:147`) calls `addAttachment.run(ready.owner.asAttachmentOwner(), …)` with the snapshot's
  role (R69-6 makes it acceptable on every owner a reference can have) and the shipped source. KDocs (`:61-66`,
  `:119-124`, `:175-181`) restated owner-neutrally ("on the reference's owner"). Callers' arguments: the API's
  `saveAsDocument` passes `reference.owner` (its held check stays B2b's interim asset arm until C21), and
  `MaterializeViewModel` passes its owner (B6a generalises the sheet). **The two shipped race doubles move with the
  check (C-5):** `CT/usecase/MaterializeReferenceTest.kt:771-785` (`WatchedAttachments`, asserted at `:662` and `:677`)
  and `T/ui/references/MaterializeViewModelTest.kt:170-173` (`afterDuplicateCheck`, used by the cancel race at `:483`)
  override `forOwner` instead of `forAsset`, the same gate; assertions unchanged; the report shows the gate still fires
  (e.g. `:662`'s `waiting == 1`).

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
    assets, SupplyItems and installed components"), as is the wipe's stale "References point only at assets" (`:179`,
    N-7). The wipe needs no change: attachments (`:171`) and references
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
  **The wire recognizer (C-3):** `isAttachmentUpload` (`A/api/HttpWire.kt:41-45`) matches only `POST
  /v1/assets/<id>/attachments`, and it decides both the body cap (`ApiRouter.kt:57-61`, `MAX_UPLOAD_BYTES` versus
  64 KiB) and whether the body stays on the socket as a stream (`HttpWire.kt:142-144`); it is widened to **exactly** the
  two new canonical POST shapes (`/v1/supply-items/<id>/attachments`, `/v1/installed-components/<id>/attachments`),
  its KDoc restated, every other method on those shapes still framed. Without it an upload over 64 KiB to a new owner
  is a 413 before authentication, invisible to the router-level row 43 (`upload` falls back to
  `ByteArrayInputStream(request.body)`, `:153`) — row 43a proves it on the real wire.
- **C20, the create.** `CreateReferenceRequest` (`A/api/ReferenceDtos.kt:44-51`) takes `assetId: String? = null`,
  `supplyItemId: String? = null`, `installedComponentId: String? = null`; the handler maps exactly one non-null key to
  a `ReferenceOwner` (none or several → 400 G1, before any read); the rest is `AddReference`'s. `ignoreUnknownKeys =
  false` stays. **The vendor link is one of these rows** (R69-2 as recommended): no `vendor`/`preferred` key.
  **`OwnerMissing` is mapped in the handler (C-6),** by the owner it passed, to the shipped typed 404s — as
  `addRefusal` already does (`A/api/AttachmentHandlers.kt:346-352`): `NoSuchAsset` (today's `no_such_asset`, no
  `field`), `NoSuchSupplyItem` (`A/api/ApiJson.kt:576`) and the installed-component 404 (`:863-866`), with the body
  key as `field` on the create (the two new keys only) and none on a path — an exhaustive `when` over the owner, no
  `else`; the handler never re-reads the owner. **`referenceProblemCode` and `ApiJson.kt:424-429` stay
  byte-identical**, so `T/api/ReferenceRoutesTest.kt:450-470` (`everyReferenceProblemHasItsOwnCode`) does not move.
- **C21, the held checks and the upload id (H9).** Materialize (`AttachmentHandlers.kt:280-283`) and upload (`:176`)
  ask the owner's asset: an asset itself; a component's `assetId`; a SupplyItem none (never held). A component on a
  held asset → 409 `asset_transferred_out` naming that asset. **The upload's derived id:** `attachmentOperationId`
  (`A/api/AttachmentOperationIds.kt:18`) stays **byte-identical for an asset** (v2 prefix, text and the four shipped
  vectors in `docs/api/attachment-operation-ids.json` unchanged). The two new owners derive with a **new prefix**
  `servicetag:attachment-upload:v3` and the text `prefix \n installationId \n ownerKind \n ownerId \n operationKey`,
  `ownerKind` ∈ {`supply-item`, `installed-component`} (domain separation: an id string shared across owner kinds can
  never derive the same row id). The golden file gains **new top-level keys** — `ownerPrefix` (the v3 prefix) beside
  `ownerVectors` (fictional ids, at least two per kind) — so its shipped readers are untouched; the vectors are
  **computed independently of the Kotlin code** (e.g. `printf '%s\n%s\n%s\n%s\n%s' … | sha256sum`, then the version
  and variant bits by hand; the command recorded in the report), never by running the function they pin (N-4); the MCP
  twin (C23) reads both.
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
  Writable for an archived item too; never read-only (a SupplyItem is never held). `SupplyDetailScreen` has no
  `onOpenSettings` today (`:52-58`), so it gains one and the link launcher, passed as `AssetDetailScreen`'s are, and
  **B6b edits `A/ui/nav/ServiceTagRoot.kt:580-589`** to pass them (C-4; B6c edits the same file later, in sequence).
  Nothing else on the screen moves.
- **C27, the installed-component screen (H7; R69-8, R69-9).** A component lives only inside a bottom sheet
  (`A/ui/installed/InstalledComponentSheets.kt:71-90`) and the sections open their own sheets (#47 E-30: one sheet
  at a time), so the smallest honest surface is **a route and a screen**: `Route.InstalledComponentDetail(val id:
  String)` beside `Route.SupplyDetail` (`A/ui/nav/Route.kt:168`), its `entry` in `A/ui/nav/ServiceTagRoot.kt` beside
  `:580-589`. Reached from the row sheet by a **"Documents and references" action (P69-1)** offered on **every** row — current,
  removed, and on a held asset (where it opens read-only) — through a new `onOpenDocuments(id)` from
  `InstalledComponentsSection` (`InstalledComponentsSection.kt:58-64`) via `AssetDetailScreen` to the root. **The
  action closes the row sheet, then navigates** (`model.closeRow()` first, as `onOpenSupply` does,
  `InstalledComponentsSection.kt:88`, `:98`), so Back returns to the asset with no sheet stacked (#47 E-30), and the new
  `AssetDetailScreen` parameter takes a default `= {}` as `onOpenSupply` does (`AssetDetailScreen.kt:233`), so its
  `AT/` call sites do not move (N-9). The screen: a `Scaffold` with a `SnackbarHost` (the sections take `snackbars`,
  C26's shape), a top bar with the component's name and the shipped "Back"; then **two ownerships, in order**:
  1. **its own** — the heading P69-2, then `AttachmentsSection(owner = OfInstalledComponent)` and
     `ReferencesSection(owner = ReferenceOwner.OfInstalledComponent)`, writable unless the component's asset is held
     (the shipped `readOnly` flags, `DocumentsSection.kt:150-154`; `ReferencesSection.kt:78-79`);
  2. **each referenced SupplyItem's** — one group per distinct SupplyItem named by the row's direct link and its
     composition entries (R69-8), ordered direct link first, then entries in composition order, deduplicated (one
     top-level pure function in `A/ui/installed/`, which C30's Share step calls too): the
     heading P69-3 "From {name}" (a tap opens `SupplyDetail`, writing nothing — the "Used by" rows' shape), the quiet
     line P69-4, then the same two sections for `OfSupplyItem` with **`readOnly = true`** (open-only; edits on the
     SupplyItem's own detail). An archived SupplyItem's group is drawn (its resources are still its product's).
  **Observed reads (N-8):** the view model reads the row through `observeForAsset(row.assetId)` filtered by id (one
  `get` first to learn the asset), the held state through the same flow the asset detail reads, and the catalog's
  names through the shipped list rows — so a component whose asset is deleted while the screen is open goes back (no
  sentence), and a hold or release while it is open flips `readOnly`. It writes nothing itself (the sections own their
  writes).
- **C28, the owner-worded sentences.** Four shipped sentences name "asset" and are reached from every owner; each
  keeps its asset wording and gains **a SupplyItem twin and a component twin** (P69-11…16), chosen by the owner kind
  in one small function per home (no shipped pin moves): `IntakeStrings.DUPLICATE_URI`
  (`A/share/ShareIntakeViewModel.kt:82`), the in-app duplicate (`ReferencesSectionViewModel.kt:283`), the remove
  confirmation (`A/ui/references/ReferenceSheets.kt:139`) and P85-17 (`A/ui/references/MaterializeStrings.kt:35`)
  (correction 5: the audit named one).

### B7 / B7c — Share intake (C29–C30)

**R69-3 (DECIDED 2026-10-02, final; quoted verbatim in §6)** drives C29–C30: one "Share to" picker — type dropdown,
one search box, one list of that type — with direct selection, optional browsing, and prose Asset-only.

- **C29, the destination (fact 12; H8; R69-3).** `AssetChoice` (`A/share/ShareIntakeViewModel.kt:110`) becomes a
  sealed **`ShareDestination`**, held in `destination` (was `chosen`, `:128`); each arm carries only what the save form
  draws — **ownership is the final selection**, and no browsing context reaches a command:

  ```kotlin
  internal sealed interface ShareDestination {
      data class Asset(val assetId: String, val assetName: String) : ShareDestination
      /** [path]: the asset's name, each ancestor component's, then this one's (display only). */
      data class Component(val assetId: String, val componentId: String, val path: List<String>) : ShareDestination
      /** [productLine]: the shipped identifying line (`listRowsOf`'s `detail`), display only. */
      data class Supply(val supplyId: String, val supplyName: String, val productLine: String) : ShareDestination
  }
  ```

  The three save arms map it once, an exhaustive `when` with no `else`: the link arm (`:367-376`) to
  `AddReference.run(ReferenceOwner.OfAsset | OfInstalledComponent | OfSupplyItem)`, the bytes arm (`:418-436`) to
  `AddAttachment.run(AttachmentOwner.…)` likewise; **the note arm (`:475-480`) takes an `Asset` only**, reached by
  selecting an asset on a prose share. A `Supply` destination stores the resource **once, on the catalog item**,
  however it was reached (directly or while browsing an asset). **The save form is the confirmation — no dialog:**
  #93's form (`ShareIntakeScreen.kt:249-254`) draws the destination line — an asset's name; a component's joined
  `path`; a SupplyItem's name, product line and **P69-21** — and "Change" (`:90`), back to the picker **where the
  destination was chosen** (type, query, level). Saved: "Saved to %s" (`:96`), or **P69-22** for a SupplyItem. `save`
  stays the only writer (`:338`); Cancel writes nothing (`:334`); the #43 rules run before any destination exists;
  every save meets the guard (`A/di/AppGraph.kt:316`, `:324`); the duplicate sentence is C28's owner-kind twin.
- **C30, the picker (R69-3 DECIDED, final).**
  1. **The type control** (link and bytes only), in the shape of the Assets tab's private `TypeChip`
     (`A/ui/asset/AssetsScreen.kt:219-245`: an `AssistChip` with the dropdown icon, `Role.DropdownList`, a fixed
     accessible name — P69-23 — and the choice as its state): "Assets" (default), "Installed components", "Supplies".
     A **prose** share draws none: Assets only, and an asset opens the note form directly, as today.
  2. **The search box:** #93's `SearchBox` (`A/ui/asset/AssetSearch.kt:51`), its placeholder made a defaulted
     parameter (today the private `SEARCH_ASSETS`, `:84`) so the hint follows the type ("Search assets" kept;
     P69-24, P69-25). One query, kept across a type switch, applied to the selected type only.
  3. **The lists, eligibility identical on both routes:**
     - **Assets** — #93's list, its flag `excludeHeld` (`A/ui/asset/AssetViewModels.kt:346-351`, `:448`; only caller
       `ShareIntakeActivity.kt:106`) becoming **`activeOnly`**: rows not **`Asset.maintainedHere(held)`**
       (`C/model/Asset.kt:49-60`) dropped first — archived, retired (every replaced asset is, R86-3; a never-replaced
       retired one too, owner-approved), transferred-out and held; season and DOWN play no part. Search: the shipped
       `Asset.matches` (`AssetSearch.kt:37`). The Archived control hidden in Share only (`AssetPicker.kt:27-39`, one
       defaulted parameter). None eligible → **P69-26**; no hit → "Nothing matches that." (`AssetsScreen.kt:286`).
     - **Installed components** — every **current** row (`removedOn == null`) whose asset is eligible above, from
       `InstalledComponentRepository.all()`; each row draws the component's name with a quiet line — the asset's name
       and its ancestors' names joined by P69-20, read whole by TalkBack; ordered by that path casefolded, then id.
       **The component matcher:** case-insensitive substring over the asset's, each ancestor's and its own name.
       None eligible → P47-2 "No installed components"; no hit → "Nothing matches that.".
     - **Supplies** — every **unarchived** SupplyItem, with or without an asset association, drawn by the shipped
       `SupplyRow` (`A/ui/supplies/SupplyListScreen.kt:112`) from `listRowsOf` (`SupplyListViewModel.kt:14-20`: the
       name, and manufacturer · part number as the product line). **The supply matcher** (none ships) is a
       case-insensitive substring over `name`, `manufacturer`, `model` and `partNumber` (`C/model/SupplyItem.kt:21-33`).
       None → the shipped `NO_SUPPLY_ITEMS_YET` (`A/ui/supplies/SupplyStrings.kt:63`); no hit → "Nothing matches that.".
  4. **Selecting** a component or a SupplyItem row opens the save form with it as the destination; **selecting an
     asset row opens browsing** at that asset ("Attach here" is P69-17 / P69-2):
     - **the asset level:** P69-17 "This asset" (→ `Asset`); the SupplyItems linked to it (its applicability rows,
       `AssetSupplyRepository.forAsset`, `C/ports/Repositories.kt:379-387`; unarchived, once each, by name) with the
       quiet line P69-18; its current root components;
     - **a component level**, headed P47-5 "Inside %s": P69-2 "This installed component" (→ `Component`); the
       SupplyItems its direct link and composition name, archived excluded, by **C27's derivation** (one pure
       function, B6c's, called); its current children;
     - a level's components are `InstalledComponentTree.current`'s entries (`C/model/InstalledComponentTree.kt:67`)
       whose parent is the level's component, in its order; tapping one opens its level (click label P69-19) and
       writes nothing; a **breadcrumb** over the level shows the path joined by P69-20; Back (system or the shipped
       "Back") goes up one level, then to the list with its type, query and scroll **preserved**.
  Data: `assets`, `installedComponents`, `supplyItems`, `assetSupplies`, already on `AppGraph` (`:370`, `:371`, `:380`)
  — passed as arguments; `AppGraph`/`FakeGraph` unedited. The pure pieces (eligibility, matchers, rows, paths) live in
  `A/share/ShareTargets.kt` (new). `NO_ASSETS` (`:76`) is the dead end only when nothing at all is eligible.

### B8 and cross-cutting (C31–C33)

- **C31, the documents.** `docs/release-proofs.md`: a **schema-20 paragraph** beside the schema-19 one (`:133`),
  naming the schema-18 and -19 paragraphs' checks as its own (the first release carrying 20 also carries 18 and 19),
  seeding nothing new on the older side, then making SupplyItem- and component-owned files and links through the
  phone (SupplyItem detail, the component screen, Share), the API and the MCP, materializing one of each, and proving
  the pre-upgrade export re-plans with zero INSERT and the format-20 round trip (export → import → re-plan) `IDENTICAL`.
  `docs/design/04-domain-data-model.md` §9: the table row listing `vendor_url` as a column (`:452`) is restated (N-13),
  and the amendment (`:440`): "`vendor_url` is #69's" becomes "#69 keeps a
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
| 8 | C10 the gate | same · `aFormat19ArchiveWithANewOwnerKeyIsCorrupt` (each key × each list); `explicitNullsInAFormat19ArchiveDecode` — each refusal **asserts G2's gate message** (N-3: without the gate, exactly-one still refuses, with another message) | the gate removed (the message assertion fails) |
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
| 19 | C5 the return, attachments (H2) | `CT/usecase/ReturnScopeTest` or `ImportTransferPackTest` (+2) · `aReturningAssetsComponentFilesLeaveTheSnapshotAndTheirLocatorsAreSwept`; `aSupplyItemsFileStays` — the component-owned rows are **local only**, never in the pack (N-15: the planner's attachment arm is B3a's) | the arm answers `false` for a component (the snapshot keeps the row, the locator is not listed) |
| 20 | C11 the doubles | `CT/testing/ResourceOwnerDoubleTest` (new) · `anAssetDeleteThroughBackupInstallTakesItsComponentsFilesAndLinks`; `assetOwnedRowsDoNotCascadeInTheDouble` (unchanged behaviour); B2b adds `aSecondOwnerUriPairThrows` and the reference twin's cascade case | the registration line removed |
| 21 | C12 the owner map | `CT/model/ReferenceOwnerTest` (new) · `asAttachmentOwnerIsTotalAndSameNamed`; `updateReferenceNeverChangesTheOwner` | `OfSupplyItem` mapped to `OfAsset` |
| 22 | C13 AddReference (H3) | `CT/usecase/AddReferenceTest` (+5) · `addsToEachOwner`; `theSameUriOnAnAssetAndASupplyItemSharingAnIdStringIsTwoRows`; `aDuplicateIsPerOwner`; `anArchivedSupplyItemAndARemovedComponentAccept`; `theStepOrderIsUnchanged` (owner-missing still after the name) | `findByUri` keyed by the id string alone |
| 23 | C13 the codec, references | `BackupFormat20Test` (+3) · `aSupplyItemsAndAComponentsLinksRoundTrip`; `anOwnerNotInTheFileIsCorrupt`; `theOwnerCheckSeesSupplyItemsAndComponents` (the moved block) | the moved check run before `supplyIds` exists (a valid file refused) |
| 24 | C13 the merge pair (H3) | `CT/merge/MergePlannerReferenceTest` (+3) · `aSupplyItemsLinkDoesNotMatchAnAssetsLinkSharingIdAndUri` (INSERT, not `REFERENCE_HELD_BY_A_LOCAL_ROW`); `theSecondIdentityIsPerOwnerInTheArchive`; `anAbsentSupplyItemOrComponentIsOwnerNotAvailable` | the pair keyed `(ownerIdString, uri)` |
| 25 | C13 the return, references | `ImportTransferPackTest` (+1) · `aReturningAssetsComponentLinkLeavesTheSnapshotAndTheReturnLands` | the `ReturnScope` reference filter checks the asset only (the borrowed-phone link edit refuses `CONFLICT`) |
| 26 | C17 materialize (AC13, H4) | `CT/usecase/MaterializeReferenceTest` (+5) · `prepareOnASupplyItemsLinkAndCommitLandsOnTheSupplyItem`; `…onAComponentsLink…`; `theDuplicateCheckIsTheOwnersOwnFiles` (the same bytes on the asset do not refuse a SupplyItem's save); `theRoleAndSourceTravel`; `aReferenceOfAnotherOwnerIsNoSuchReference`; the two race doubles on `forOwner` with their shipped assertions (`:662`, `:677`; `MaterializeViewModelTest.kt:483`, C-5) | `commit` writes `OfAsset` / the duplicate check reads another owner |
| 27 | C13 Room, references | `T/data/room/ReferenceDaoConstraintTest` (+2) · `threeOwnersRoundTrip`; `theMapperRefusesNoOwner` | `toEntity` writes `asset_id` for every owner |
| 28 | C13 pins and arguments | the 16 `AssetReference(` sites take `owner = ReferenceOwner.OfAsset(…)`; C13's ~20 test reads and port calls move to the owner (receivers and arguments); `ReferencesSectionViewModelTest`, `ShareIntakeViewModelTest` green unchanged in assertions | none: pins |
| 29 | C14 attachment availability | `CT/merge/MergePlannerTest` (+4) · `aSupplyItemOrComponentOwnerHereOrAcceptedIsAvailable`; `anAbsentOneIsOwnerNotAvailableNamingIt`; `aComponentThisPlanRefusesRefusesItsFiles` | `dto.assetId ?: dto.eventId!!` kept (the NPE) |
| 30 | C14 M2 | `MergePlannerTransferTest` (+2) · `aComponentsFileInsertedForAHeldAssetIsTransferredOut`; `aSupplyItemsFileIsNot` | the planner's `installedComponent` lookup answers `null` |
| 31 | C14 no older-archive exception | `MergePlannerTest` (+1) · `aFormat19ExportAgainstAnInstallWithNewOwnersResourcesIsApplicableAndIdentical` (no decision for rows the archive does not name) | `Attachment.toDto` writing a non-null new key on an asset row (N-2: the local DTO differs, `CONFLICT`) |
| 32 | C14 H1 restore order | `T/backup/ResourceOwnersRestoreTest` (new, Room-backed `FakeGraph`) · `aShuffledArchiveWithSupplyItemAndComponentLinksRestoresByteEqual` | the reference line moved back before the SupplyItems (SQLite's FK fails the restore) |
| 33 | C14 apply order on Room | `ApiRouterTest` (+1) · `aMergeInsertingSupplyItemsComponentsAndTheirResourcesCommits` | references applied before components (the FK fails) |
| 34 | C14 tallies | no new table: the report and tally pins **unchanged** and re-run | none: pins |
| 35 | C15 select | `CT/transfer/TransferGraphTest` (+3) · `aPackCarriesItsComponentsFilesAndLinks`; `aPackCarriesTheResourcesOfEverySupplyItemInUseWithBytes`; `anUnusedSupplyItemsResourcesStayHome` | the supply arm dropped from `select` |
| 36 | C15 retain | `TransferGraphRetainTest` (+2) · `aHeldAssetsComponentsResourcesDropAndTheKeptArchiveDecodes`; `aSupplyItemsResourcesAreKeptWhenOnlyHeldRowsNameIt` | `retain` keeps component-owned rows (the kept archive is corrupt: owner not in file) |
| 37 | C15 H5 | `ImportTransferPackTest` (+1) · `aSupplyItemsFileEditedHereWhileThePackIsOutRefusesTheReturnAsConflict` (the limit, pinned) | none: a limit's pin |
| 37a | C15, limit 2a (C-9) | same (+1) · `aSupplyItemsFileRemovedHereWhileThePackIsOutComesBackOnReturn` (row and bytes re-inserted) | none: a limit's pin |
| 38 | C16a delete (H2) | `CT/usecase/RetireDeleteAssetTest` (+2) · `deletingAnAssetSweepsItsComponentsBytesCurrentAndRemoved`; `aSupplyItemsBytesAreNotSwept` | the component locators not read inside the write |
| 39 | C16b the scenario (E1–E7, AC3, AC4, AC12) | `CT/usecase/ResourceOwnersFixtureTest` (new) · both components naming the battery SupplyItem read **the same** rows by `forOwner(OfSupplyItem)` (no copy: row and byte counts equal before and after); no component's resource appears under a SupplyItem and the reverse; export → replace import into an empty install reads every owner back; the export re-planned against the non-empty install `IDENTICAL`; a merge into an install without them inserts all, owners before resources | the replace import drops `installedComponentId` (the restore refuses the row by exactly-one, or the re-plan reads `CONFLICT` `CONTENT_DIFFERS` — N-5; quote whichever fires) |
| 40 | AC14 | same · `aChildAssetsFileIsAnAssetOwnedRow` | none of its own: C33.4's grep |
| 41 | C19 routes | `T/api/ResourceOwnerRoutesTest` (new) · each GET lists only that owner's rows in the shipped order; `aMissingSupplyItemOrComponentIs404`; `aHeldAssetsComponentReadsAsAnyOther`; `theAssetSubResourcesAreUnchanged` | the SupplyItem route reads the asset's rows |
| 42 | C20 the create | same · `exactlyOneOwnerKey` (none, two, a lone `null` → 400; each owner → 201); `anUnknownOwnerIs404WithItsCodeAndField` (each kind's own code, C20); `aDuplicateIsPerOwner`; `aPatchNamingAnOwnerKeyIs400` | two keys accepted, the first winning |
| 43 | C21 upload (H9) | same · `uploadToASupplyItemAndAComponent` (201, locator directory); `aHeldAssetsComponentIs409`; `theAssetDerivationIsByteIdenticalToTheShippedVectors`; `theNewOwnersMatchTheV3Vectors` (independently computed, N-4) | the v3 text omits `ownerKind` (the vectors fail) |
| 43a | C19 the wire (C-3) | `T/api/HttpWireTest` (+3) · `aPostOverSixtyFourKibToASupplyItemsAttachmentsStreams`; `…toAComponentsAttachmentsStreams` (no 413; the body is a stream); `otherMethodsOnThoseShapesAreFramed` | `isAttachmentUpload` left asset-only (413) |
| 44 | C21 materialize route (AC13) | `MaterializeRoutesTest` (+3) · `aSupplyItemsLinkSavesOnTheSupplyItem`; `aComponentsLinkSavesOnTheComponent`; `aHeldAssetsComponentLinkIs409` | the held check reads `(owner as OfAsset)` (a component's link on a held asset proceeds) |
| 45 | C22 the document | `everyNewRouteIsInV1md`; the import range and status pins (§3) | a route missing from `v1.md` |
| 46 | C23 the tools | `M/tests/test_reference_tools.py`, `test_attachment_tools.py` (+~12) · each widened tool's method, path and body per owner; none/several owners refused locally, nothing sent; `materialize_reference` recovers through the owner's routes; `add_attachment`'s v3 id equals the golden `ownerVectors` | `add_reference` sends `assetId` for a SupplyItem |
| 47 | C24 the gate | `…_refuses_a_new_owner_below_schema_20_with_nothing_sent` (all five); `an_asset_call_keeps_todays_gates` | the gate removed from one tool |
| 48 | C23 pins | 89 tools unchanged; "1–20"; the argument guard | none: pins |
| 49 | C25 sections by owner (H4) | `T/ui/references/ReferencesSectionViewModelTest` (+4) · `stateIsTheOwnersRows`; `theSavedMarkReadsTheOwnersOwnFiles`; `addPassesTheOwner`; `theDuplicateSentenceIsTheOwnersTwin`; `MaterializeViewModelTest` (+2) · `preparePassesTheOwner`; `alreadyHaveIsTheOwnersTwin`; `AttachmentsSectionViewModelTest` (+2) · `rolesOfferedOnASupplyItemAndAComponent`; `notOnAnEntry` | the saved mark reads `OfAsset` |
| 50 | C26 SupplyItem detail | `SupplyDetailViewModelTest` (+1) · `anArchivedItemIsWritable`; **Compose** `AT/ui/supplies/SupplySurfacesTest` (+3) · both sections drawn below "Used by"; add actions present with a folder; an archived item's sections draw | none in-brief: device cases |
| 51 | C27 the component screen | `T/ui/installed/InstalledComponentDetailViewModelTest` (new) · `ownSectionsAreKeyedByTheComponent`; `oneGroupPerDistinctSupplyItemDirectFirstThenEntries`; `anArchivedSupplyItemsGroupIsDrawn`; `aHeldAssetMakesOwnSectionsReadOnly`; `aRemovedComponentIsWritable`; `aDeletedAssetGoesBack` (observed, N-8); `aHoldWhileOpenMakesItReadOnly` | a SupplyItem named by the link and an entry drawn twice |
| 52 | C27 drawn | **Compose** `AT/ui/installed/InstalledComponentDetailTest` (new, ~10 cases) · the title; P69-2 then both own sections; each "From {name}" group with P69-4 and no add or edit action; a group tap opens the SupplyItem; held → no add action; empty sections' shipped lines; **Compose** `InstalledComponentsSectionTest` (+2) · the row sheet offers P69-1 on a current, a removed and a held row; the tap closes the sheet, then reports the id | none in-brief: device cases (B6c2; compiled there, first run at the merged-tip gate) |
| 53a | C30 the Assets list (B7) | `T/ui/asset/AssetPickerModelTest` (+4) · `anArchivedARetiredNeverReplacedAReplacedAndAHeldAssetAreNeverOffered`; `anOutOfSeasonOrDownAssetIsOffered`; `noArchivedReasonArisesInTheSharePicker`; `theTabListsRetiredAndArchivedAsShipped` (`activeOnly` false) | the filter left at `id !in held` (a retired asset offered) |
| 53c | C29 the saves and the save form (B7) | `T/share/ShareIntakeViewModelTest` (+~8) · each destination's link save calls `AddReference` and bytes save `AddAttachment` with **that** owner; a SupplyItem reached directly and one reached while browsing write identical commands; the form's destination line per kind (path joined by P69-20; product line and P69-21 for a SupplyItem); "Saved to %s" / P69-22; the duplicate twin per owner kind; prose saves a note on the asset only; Cancel writes nothing | the bytes arm maps a `Component` destination to its asset (`OfAsset`) |
| 53b | C30 the direct lists (B7c) | `T/share/ShareTargetsTest` (new, ~12) and `ShareIntakeViewModelTest` (+~4) · each type's list holds that type only; components: current rows of eligible assets only (a removed, a replaced, a held asset's and a retired asset's never), the context line, the path order; the component matcher hits the asset's name, an ancestor's name and its own name, not an unrelated row; supplies: unarchived only, with and without an asset association; the supply matcher over name, manufacturer, model, part number; one query kept across a type switch; per-type empty and no-hit lines; a component or SupplyItem pick opens the form; a prose share has no type control | (1) the component list fed from all types (a type leak); (2) a removed component offered; (3) an archived SupplyItem offered; (4) the matcher reading the own name only (an ancestor search misses) |
| 53 | C30 browsing (B7d) | `ShareIntakeViewModelTest` (+~8) · an asset row opens its level ("This asset", its unarchived linked SupplyItems once each, its current root components); a component level ("This installed component", direct and composition SupplyItems, current children); removed and replaced instances never appear; the breadcrumb path; **browsing writes nothing** (witnessed doubles: zero writes); Back goes up a level, then to the list with type, query and scroll preserved; "Change" returns to the level chosen from | (1) a level lists a removed child (`current` bypassed); (2) a navigation tap writes (a witnessed write) |
| 54 | C30 drawn (B7b) | **Compose** `AT/share/ShareIntakeScreenTest` (+11, counted): the type control's three labels with Assets chosen; a component row with its context line; a SupplyItem row with its product line; the per-type search hint; the per-type empty line and "Nothing matches that."; the asset level (P69-17, a SupplyItem with P69-18, a component with P69-19); a component level (P47-5, P69-2, the breadcrumb); the form's component destination line; the form's SupplyItem lines with P69-21; a prose share draws no type control and an asset opens the note form; no Archived control in the Share picker. The shipped `:166`, `:308`, `:368` keep their behaviour (arguments only, B7) | none in-brief: device cases (B7b; compiled there, first run at the gate) |
| 55 | the boundary unchanged | `AT/share/ShareBoundaryTest` (3), `ShareResolutionContractTest` (4), `SharedItemLiftTest` (7): untouched, re-run at the gate | none: re-run |
| 56 | C31 the documents | `ReleaseProofPolicyTest` unchanged and green; B8's anchored greps (§7) | none: tripwire |
| 57 | C33 the fence | the six greps at every brief | none: greps |

**Moving pins — each named shipped assertion, the brief that may touch it, and why** (the audit's §3 inventory,
re-read on `ad49b788`; each brief confirms by grep and lists twins).

| pin | brief | moves, because |
|---|---|---|
| `T/VersionAgreementTest.kt:86`, `:157`; `T/api/MaintenanceRoutesTest.kt:1551`; `T/data/room/MigrationTestSupport.kt:69` (the chain) | B1a | the schema is 20 |
| `Migration9To10Test.kt:67`, `:69`; `Migration15To16Test.kt:80`, `:82`; `Migration16To17Test.kt:74`, `:76` (in #85's `dropLast` shape, C8); `Migration18To19Test.kt:129-137` (`everyV18TableIsUnchanged`, narrowed to every v18 table but `attachment` and `asset_reference`); the entity constructions `AttachmentDaoTest.kt:202`, `ReferenceDaoConstraintTest.kt:39` (arguments) | B1a | the chain measures the latest schema: `attachment` 20 → 22 columns, `asset_reference` 10 → 12 (C-1, C-4) |
| the format literals: `VersionAgreementTest.kt:87`, `:158`; `MaintenanceRoutesTest.kt:1197`, `:1552`, `:1599`; `CT/backup/BackupCodecTest.kt:1071`; `BackupFormat6Test:363`, `8Test:281`, `9Test:70`, `13Test:54`, `14Test:48`, `15Test:48`, `17Test:99`, `:223`, `18Test:74`, `:350`, `19Test:111`, `:384`; `CT/usecase/ExportBackupSetTest.kt:51`; `Format7ImportIdentityTest.kt:248`; `AT/backup/Format7RestoreContractTest.kt:164` | B1b | the format is 20. The 26 `assertEquals(19,` hits on `ad49b788` are **24 pins** (3 schema in B1a, 21 format here) **plus 2 that never move**: `VersionAgreementTest.kt:61` (the versionCode) and `MaterializeStringsTest.kt:100` (a MIME label) |
| the one-past archives and their `refusal.found` lines: `BackupFormat8Test:277`, `:280`; `17Test:218`, `:222`; `18Test:345`, `:349`; `19Test:379`, `:383` | B1b | "one format past this build" becomes 21 |
| the strip lists `BackupFormat9Test:116`, `:120`; `BackupCodecTest.kt:1201` (the `sourceKeys` idiom); the reference key orders in `BackupFormat7Test` and `BackupFormat17Test` | B1b | two keys appended to each DTO (the reference's `assetId` stays `String`, C-2) |
| **unchanged, re-run:** `counts.size` 31 (`BackupFormat6Test:411`, `7Test:299`, `8Test:310`, `13Test:68`, `14Test:65`, `15Test:63`, `18Test:334`, `19Test:368`); `MergeTable` 23 (`6Test:292`, `7Test:225`, `MergePlannerTransferTest.kt:383`); the key positions; the manifest-count maps; `V19_TABLES` | — | columns only: no list, table, key or count moves (a hit here stops the brief) |
| the 16 `AssetReference(` construction sites (9 in `CT/`, 4 in `T/`, 3 in main) and C13's eight test files of `.assetId` reads and removed-port calls (`ShareIntakeViewModelTest` included) | B2b | the field is `owner`; the port is owner-keyed (receivers and arguments only, C-4) |
| `BackupUseCasesTest.kt:651-654`, `TransferImportTesting.kt:153-158` (two `when`s, C5) | B2a | two members added |
| `MaterializeReferenceTest.kt:771-785` (asserted `:662`, `:677`); `MaterializeViewModelTest.kt:170-173` (used `:483`) | B2b2 | the duplicate check is `forOwner` (C-5) |
| the `T/api` handler construction sites if a handler constructor grows: `MaintenanceFixtures.kt:60`, `:132`; `AttachmentUploadRoutesTest.kt:140`; `LoopbackApiServerTest.kt:717`, `:777`; `MaterializeRoutesTest.kt:106`; a create omitting `assetId` now G1's 400 | B4 | the new routes' handlers (C-4, N-12) |
| `T/api/CommandShapesGoldenTest.kt:137-139`, `:176-180`; `T/api/ReferenceRoutesTest.kt:748-760`; `InstalledComponentRoutesTest.kt:746-750`; `SupplyRoutesTest.kt:634`; `ApiRouter.kt:98`'s KDoc | B4 | range 1–20, "20 since #69", 81 shapes / 101 rows |
| `M/tests/test_tools.py:979-994`; `test_argument_guard.py`; the "89" docstrings (`test_reference_tools.py:35`, `test_installed_component_tools.py:45`, `test_maintenance_tools.py:45`, `test_supply_tools.py:45`); `server.py:1265` | B5 | range 1–20; the widened signatures (the count stays 89) |
| `T/ui/asset/AssetPickerModelTest.kt` (`excludeHeld` → `activeOnly`, one site); `T/share/ShareIntakeViewModelTest.kt` (47; `chosen` → `destination`); `AT/share/ShareIntakeScreenTest.kt` argument-only fixes, `:166`, `:308`, `:368` included — the Assets list stays the default first view, so their behaviour holds | B7 | the destination type and the active-asset rule (R69-3, final) |
| `ShareIntakeScreenTest` / `ShareIntakeViewModelTest` construction and `show(…)` arguments as the screen and model gain the picker's and browsing's inputs; any case that taps an asset on a link or bytes share and expects the form | B7c, B7d | the picker, then browsing, sit before the form (each a twin, listed) |
| `AT/ui/references/ReferencesSectionTest` (12) | B6a | the composable's parameter is an owner (arguments only) |

**Device rows.** No device-boundary class (planning policy's hard rule): the Share grant from another UID is
target-independent and already proven by `ShareBoundaryTest`; SAF directory creation under a new directory name is
`SafTreeAttachmentStore`'s generic segment walk (`:20`, `:37-40`, `:109-113`), already proven by
`SafTreeAttachmentStoreContractTest`. What remains is Compose drawing an already-proven state: **one new class**
(`InstalledComponentDetailTest`, ~10 cases; budget up to ~14, #47's E-33 overran by 4) and cases added to
`SupplySurfacesTest` (+3), `InstalledComponentsSectionTest` (+2) and `ShareIntakeScreenTest` (+5).
**Gate growth (C-8; rev 1.4's final UI):** 58 → **59** classes, 318 → ~344–348 tests (Share +11, the component screen
~10–14, `SupplySurfacesTest` +3, `InstalledComponentsSectionTest` +2). At #15's measured ~2.47 s per test (#47's base was
never timed cleanly) the base is ~13.1 min and #69's tip **~14.2–14.4 min — over the 14-minute warning, under 15**,
graph-backed cases dearer still. Reporting only; the merged-tip gate owes the clean single-run measurement (F-1). **Known cost:** the SupplyItem detail grows below "Used by", and the
installed-component row sheet gains one action, under the shipped `SupplySurfacesTest` and
`InstalledComponentsSectionTest` — first seen at the merged-tip gate (one fix round, accepted as #15's N-12).

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `A/data/room/entities/{AttachmentEntity,AssetReferenceEntity}.kt`; `A/data/room/dao/{AttachmentDao,AssetReferenceDao}.kt`; `A/data/room/{Migrations,AppDatabase,Mappers,ReferenceMappers}.kt` (the exactly-one twins and the interim `requireNotNull` only); `A/di/AppGraph.kt` (`SCHEMA_VERSION`, the migration list `:275`); `app/schemas/…/20.json` (generated); `T/data/room/{Migration19To20Test (new),ResourceOwnerDaoConstraintTest (new),MigrationTestSupport}.kt`; the C-1 pins `T/data/room/{Migration9To10Test,Migration15To16Test,Migration16To17Test,Migration18To19Test}.kt`; the C-4 constructions `T/data/room/{AttachmentDaoTest,ReferenceDaoConstraintTest}.kt` (arguments); B1a's other pins | `C/**`, `A/api`, `A/ui`, `A/share`, `19.json`, `docs`, `tools` |
| B1b | `C/backup/{BackupFormat,BackupCodec}.kt` (the DTO keys, the constant, the gate, exactly-one); `CT/backup/BackupFormat20Test.kt` (new); B1b's pins in `CT/`, `T/`, `AT/backup/` | `C/model`, `C/merge`, `C/transfer`, `C/usecase`, `A/**` main, `docs`, `tools` |
| B2a | `C/model/{Attachment,DocumentRole}.kt`; `C/usecase/{AddAttachment,UpdateAttachment}.kt` (the latter: its `require` message only); `C/backup/{BackupFormat,BackupCodec}.kt` (the attachment arms); `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of`'s attachment arm only); `C/transfer/{TransferOwnership,HeldWriteGuard}.kt`; `C/merge/MergePlanner.kt` (the lookup object only); `A/ui/maintenance/ScheduleDetailViewModel.kt` (`InHand`, one method); `A/data/room/{Mappers,RoomRepositories}.kt`; `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the two constructors); the construction sites (`AddAttachment(` 21, `HeldWriteGuard(` 8; arguments only); `CT/usecase/BackupUseCasesTest.kt` and `CT/transfer/TransferImportTesting.kt` (the two test `when`s, C5); `CT/transfer/TransferOwnershipTest.kt` (its lookup double, one method); the classes of rows 12–16 | `C/model/AssetReference.kt`, the reference arms, `C/merge` beyond the lookup, `CT/testing` doubles (B2a2), `A/api` bar arguments, `A/ui` bar `InHand`, `A/share`, `docs`, `tools` |
| B2a2 | `CT/testing/{InMemoryRepositories,InstalledComponentFixtures,BackupInstall}.kt` (C11 and its KDoc); `CT/testing/ResourceOwnerDoubleTest.kt` (new); `CT/transfer/{TransferOwnershipTest,HeldWriteGuardTest}.kt`; the return's test class (`CT/transfer/ImportTransferPackTest.kt` or `CT/usecase/ReturnScopeTest.kt`, new) — rows 17–20 | every main file |
| B2b | `C/model/AssetReference.kt`; `C/ports/Repositories.kt` (the reference port); `C/usecase/AddReference.kt`; `C/usecase/MaterializeReference.kt` (the one-line `takeIf` only); `C/backup/{BackupFormat,BackupCodec}.kt` (the reference arms; `assetId` nullable; the moved block); `C/merge/MergePlanner.kt` (the reference pair and availability); `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of`'s reference filter only); `C/transfer/TransferOwnership.kt` (`of(reference)`); `A/data/room/{ReferenceMappers,ReferenceRepositories}.kt`; `A/api/{ReferenceHandlers,AttachmentHandlers}.kt` (arguments and the interim `as? OfAsset` read); `A/ui/references/ReferencesSectionViewModel.kt`, `A/share/ShareIntakeViewModel.kt` (arguments only); `AppGraph`, `FakeGraph` (`AddReference`'s constructor, 7 sites); `CT/testing/{InMemoryRepositories,BackupInstall}.kt`; the 16 construction sites and C13's eight test files; the classes of rows 21–25, 27–28 | `C/model/Attachment.kt`, `C/transfer/TransferGraph.kt`, `A/ui` composables, `A/share/ShareIntakeScreen.kt`, `docs`, `tools` |
| B2b2 | `C/usecase/MaterializeReference.kt` (C17 whole); `A/api/AttachmentHandlers.kt` (`saveAsDocument`'s argument); `A/ui/references/MaterializeViewModel.kt` (argument); `CT/usecase/MaterializeReferenceTest.kt` (row 26; the race double, C-5); `T/ui/references/MaterializeViewModelTest.kt` (the race double); any other `prepare(` caller `git grep` names (arguments) | `C/backup`, `C/merge`, `C/transfer`, `A/ui` composables, `AppGraph`, `FakeGraph`, `docs`, `tools` |
| B3a | `C/merge/MergePlanner.kt` (the attachment availability); `C/usecase/ImportBackupReplace.kt` (one line moved, its comment); `CT/merge/{MergePlannerTest,MergePlannerTransferTest}.kt`; `T/backup/ResourceOwnersRestoreTest.kt` (new); `T/api/ApiRouterTest.kt` (row 33 only) | `C/backup`, `C/transfer`, `C/model`, other use cases, `A/**` main, `AppGraph`, `FakeGraph`, `docs`, `tools` |
| B3b | `C/transfer/TransferGraph.kt` (`select`, `retain`, the `CLASSES` KDoc); `C/usecase/DeleteAsset.kt`; `AppGraph`, `FakeGraph` (`DeleteAsset`'s constructor — **the last edit of either**); the construction sites; `CT/transfer/{TransferGraphTest,TransferGraphRetainTest,ImportTransferPackTest}.kt`, `CT/usecase/RetireDeleteAssetTest.kt` | `C/backup`, `C/merge`, `C/model`, `A/api`, `A/ui`, `docs`, `tools` |
| B3c | `CT/usecase/ResourceOwnersFixtureTest.kt` (new) only | every main file |
| B4 | `A/api/{ApiRouter,HttpWire,ReferenceHandlers,AttachmentHandlers,ReferenceDtos,AttachmentOperationIds}.kt`; `A/api/ApiJson.kt` (`:941-944`'s message only, G2 — `:424-429` and `referenceProblemCode` byte-identical); `docs/api/v1.md`; `docs/api/attachment-operation-ids.json` (the new keys only); `T/api/{ResourceOwnerRoutesTest (new),HttpWireTest,MaterializeRoutesTest,AttachmentRoutesTest,CommandShapesGoldenTest,ReferenceRoutesTest,InstalledComponentRoutesTest,SupplyRoutesTest}.kt`; the C-4 handler construction sites in `T/api` (arguments) | `C/**`, `A/ui/**`, `A/share`, `A/data`, `A/di`, `FakeGraph`, `tools`, `command-shapes.json` |
| B5 | `M/src/servicetag_mcp/server.py`; `M/README.md`; `M/tests/{test_reference_tools,test_attachment_tools,test_tools,test_argument_guard,test_installed_component_tools,test_maintenance_tools,test_supply_tools}.py` | `app/**`, `core/**`, `S/**`, `docs` |
| B6a | `A/ui/references/{ReferencesSection,ReferencesSectionViewModel,MaterializeSheet,MaterializeViewModel,MaterializeStrings,ReferenceSheets}.kt`; `A/ui/asset/AssetDetailScreen.kt` (the one call); `T/ui/references/*`; `AT/ui/references/ReferencesSectionTest.kt` (arguments) | `C/**`, `A/api`, `A/data`, `A/di`, `A/share`, `A/ui/supplies`, `A/ui/installed`, `docs`, `tools` |
| B6b | `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`; `A/ui/nav/ServiceTagRoot.kt` (`:580-589`'s arguments only, C-4); `T/ui/supplies/SupplyDetailViewModelTest.kt`; `AT/ui/supplies/SupplySurfacesTest.kt` | as B6a, and `A/ui/references` |
| B6c | `A/ui/installed/{InstalledComponentDetailScreen (new),InstalledComponentDetailViewModel (new),InstalledComponentSheets,InstalledComponentsSection,InstalledComponentStrings}.kt`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `A/ui/asset/AssetDetailScreen.kt` (one defaulted parameter); `T/ui/installed/InstalledComponentDetailViewModelTest.kt` (new) — row 51 | as B6b, and `A/ui/supplies`, `AT/**` |
| B6c2 | `AT/ui/installed/{InstalledComponentDetailTest (new),InstalledComponentsSectionTest}.kt` — row 52 | every main file |
| B7 | `A/ui/asset/{AssetViewModels,AssetPicker}.kt` (the `activeOnly` flag; one defaulted parameter); `A/share/{ShareIntakeScreen,ShareIntakeViewModel,ShareIntakeActivity}.kt` (the destination type, the save arms, the form's destination lines); `T/ui/asset/AssetPickerModelTest.kt`, `T/share/ShareIntakeViewModelTest.kt` — rows 53a, 53c; `AT/share/ShareIntakeScreenTest.kt` (arguments) | `C/**`, `A/api`, every other `A/ui/**` main file, the three boundary classes, the manifest, `docs`, `tools` |
| B7c | `A/share/ShareTargets.kt` (new: eligibility, matchers, rows, paths); `A/share/{ShareIntakeScreen,ShareIntakeViewModel,ShareIntakeActivity}.kt` (the type control, the three lists, direct selection); `A/ui/asset/AssetSearch.kt` (`SearchBox`'s defaulted placeholder only); `T/share/ShareTargetsTest.kt` (new), `T/share/ShareIntakeViewModelTest.kt` — row 53b; `AT/share/ShareIntakeScreenTest.kt` (arguments) | `C/**`, `A/api`, every other `A/ui/**` main file (`SupplyRow`, `listRowsOf`, `Asset.matches` called as they are), the boundary classes, the manifest, `docs`, `tools` |
| B7d | `A/share/{ShareTargets,ShareIntakeScreen,ShareIntakeViewModel}.kt` (browsing: levels, breadcrumb, Back); `T/share/{ShareTargetsTest,ShareIntakeViewModelTest}.kt` — row 53; `AT/share/ShareIntakeScreenTest.kt` (arguments) | `C/**`, `A/api`, `A/ui/**` main (`InstalledComponentTree.current` and C27's derivation called as they are), the boundary classes, the manifest, `docs`, `tools` |
| B7b | `AT/share/ShareIntakeScreenTest.kt` — row 54 | every main file |
| B8 | `docs/release-proofs.md`; `docs/design/{04-domain-data-model,14-asset-model}.md` | any `.kt`, `.py`, `tools`, `docs/api`, `docs/versioning.md`, `README.md` |

**B4 ∥ (B6a → B6b → B6c → B6c2 → B7 → B7c → B7d → B7b) — the one parallel pair.** B4's files are `A/api/**`, `docs/api/**` and
`T/api/**`; the
UI lane's are `A/ui/references/**`, `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`, `A/ui/installed/**`,
`A/ui/nav/{Route,ServiceTagRoot}.kt`, two lines of `AssetDetailScreen.kt`, `A/share/**` and their tests — **no file in
common**, and neither edits `AppGraph` or `FakeGraph`. Under the two-lane rule the UI lane may run in a second worktree
off B3c's tip; the controller lands it before B5, whose `<base>` holds both.

**Order:** B1a → B1b → B2a → B2a2 → B2b → B2b2 → B3a → B3b → B3c → { B4 ∥ (B6a → B6b → B6c → B6c2 → B7 → B7c → B7d → B7b) } →
B5 → B8 — **twenty dispatches** (rev 1.4), every one ≤ 1 h by estimate, split **up front** (C-7); no clause is left that
triggers past an hour. B1b needs nothing of B1a's (core only) but follows it so the schema and format move one at a
time; B2a needs both (its Room arms read B1a's DAO, its DTO arms B1b's keys); B2a2 B2a's arms; B2b B2a's `OwnerRef`
and B2a2's doubles; B2b2 B2b's owner type; B3a B2a's lookup; B3b B2b's reference owner; B3c everything core; B4 and B6 the use cases; B7 B6a's owner twins; B7c B7's destination type; B7d B7c's picker and B6c's supply derivation; B5 B4's
routes; B8 describes everything.

**The owner shape (R69-1, accepted).** (a) nullable FK columns: two `ALTER`s on `attachment` and one rebuild of
`asset_reference`; the schema's CASCADE keeps every removal path safe; two keys per DTO, no list, table or count; one
use-case, UI and #85 path with the owner a parameter. (b) a polymorphic pair loses the CASCADE (every removal deletes
by code) and (c) per-owner tables fork the use cases, views and #85 (+4 lists, tables, keys). Rev 1's full table:
`git show f260e31d:docs/superpowers/plans/2026-10-01-issue-69-resource-owners.md`, §4.

**The vendor URL (R69-2, DECIDED: option C).** Ordinary SupplyItem-owned references, no marker, no column: no cost
beyond §2 (`POST /v1/references` with `supplyItemId`, the shipped PATCH, `add_reference(supply_item_id=…)`). Options A
(a `vendor_url` column — a second link system), B (a new role) and D (a soft `preferred_reference_id`, +1 brief) were
costed in rev 1's §4 (same `git show`) and declined.

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
| moving pins | 24 literal + 4 one-past pairs + 7 migration-test assertions (C-1) + strip/key-order twins + the 16 construction sites and ~20 test reads (C-4) + 2 race doubles (C-5) + B4's and B5's ranges, plus 10–20 twins |
| construction sites (C-1) | `AddAttachment` (21), `HeldWriteGuard` (8) in B2a; `AddReference` (7) in B2b; `DeleteAsset` (5) in B3b — the review's counts, each re-counted by its brief |
| new phone strings | **16** (P69-1…16), 0 relabels |
| device classes | 58 → **59**, ~+26–30 cases, **~14.2–14.4 min** by #15's measured rate (C-8; rev 1.4) |
| dispatches | **20** (rev 1.4: Share in B7, B7c, B7d, B7b), all split up front |

**Gate budget** at the merged tip (estimates; B1a records the base's exact counts): core +~95 (format 15, domain and
seams 30, merge 10, pack, return and delete 12, scenario 6, use cases 22); app +~75 (Room 12, routes 25, view models
24, Share ~36, wire 3); MCP +~20; loader unchanged; device classes **59**, **~14.2–14.4 min** (§3's projection;
over the 14-minute warning, under 15; reporting only; F-1 stands).

**Between-brief gaps (N-1)** — each unreachable on the branch (no UI or API path writes a new owner before B4, B6 or
B7), each closed by its row:

| from → to | the gap | closed by |
|---|---|---|
| B2a → B3a | the planner's attachment arm `dto.assetId ?: dto.eventId!!` throws on a new owner (`MergePlanner.kt:998`) | row 29 |
| B2a → B3b | `select` carries no new-owner resource; `retain` keeps a held asset's component-owned rows (the kept archive is corrupt) | rows 35–36 |
| B2a → B3b | `DeleteAsset` orphans component-owned bytes | row 38 |
| B2a → B2a2 | the core doubles do not yet cascade component-owned rows (test-only) | row 20 |
| B2b → B2b2 | materializing a SupplyItem's or component's reference answers no-such-reference | row 26 |
| B2b → B3a | the replace restore writes references before SupplyItems and components (the FK fails) | row 32 |
| B2b → B4 | every `OwnerMissing` answers `no_such_asset`; the materialize route's held check is asset-only | rows 42, 44 |

## 5. Strings

**RATIFIED by the owner as one block (2026-10-02): P69-1…16 as written here, the reused words, G1–G3** — P69-6…10 withdrawn and P69-5 re-homed by R69-3's replacement; rev 1.3's Share strings RATIFIED and rev 1.4's PROPOSED (below). Each new string is declared once as a `const val` (or a
one-line function for a format) at the home named, and imported, never copied.

| id | proposed wording | home · where (contract) |
|---|---|---|
| P69-1 | "Documents and references" — **the recommendation** (rev 1 "Documents"; both words the shipped headings, `DocumentsSection.kt:93`, `ReferencesSection.kt:171`) | `InstalledComponentStrings.kt` · the row sheet's action opening the component screen, on every row (C27) |
| P69-2 | "This installed component" — **the recommendation** (rev 1 "This component"; P69-8's noun, since the asset detail keeps a child-Asset "Components" section until #98) | same · the heading over the component's own Documents and References (C27) |
| P69-3 | "From %s" — `%s` the SupplyItem's name | same · each open-only group's heading; a tap opens the SupplyItem (C27) |
| P69-4 | "Open the supply to add or change these." — **the recommendation** (rev 1 "Added and changed on the supply."; "Open" from `ReferencesSection.kt:245`) | same · the quiet line under each group's heading (C27) |
| P69-5 | "Attach to" — **RATIFIED 2026-10-02 (rev 1.3) in its new home** | `IntakeStrings` · the title over a browsing level (C30 step 4) |
| P69-6 … P69-10 | "Asset", "Supply", "Installed component", "Choose an installed component", "%1$s · %2$s" — **WITHDRAWN (rev 1.3)**: they named the three-way first step R69-3 replaced; P69-17…20 take their places | — |
| P69-11 | "That link is already on this supply" | `A/ui/references/ReferencesSectionViewModel.kt` beside `:283` (declared by B6a; `IntakeStrings` imports it in B7) (C28) |
| P69-12 | "That link is already on this installed component" | as P69-11 (C28) |
| P69-13 | "The link is removed from this supply. Nothing in the other app is changed." | `ReferenceSheets.kt` (C28) |
| P69-14 | "The link is removed from this installed component. Nothing in the other app is changed." | as P69-13 (C28) |
| P69-15 | "This supply already has this file: %s." | `MaterializeStrings.kt` (C28; P85-17's shape) |
| P69-16 | "This installed component already has this file: %s." | as P69-15 (C28) |

**RATIFIED 2026-10-02 (rev 1.3's Share strings, as the owner reworded them; kept by rev 1.4).** Homes: `IntakeStrings` (`A/share/ShareIntakeViewModel.kt:76-97`), each declared once:

| id | wording | where (contract) |
|---|---|---|
| P69-17 | "This asset" | an asset level's first row: the asset itself as the destination (C30 step 4) |
| P69-18 | "Supply — shared across uses" | the quiet line under each SupplyItem row on a browsing level (C30) |
| P69-19 | "Show what is inside %s" — `%s` the component's name | a component row's click label (TalkBack) on a browsing level (C30) |
| P69-20 | "%1$s › %2$s" — joins a path: the asset's name, then each component's | the breadcrumb, a component result's context line, the form's component line, the argument of "Saved to %s" (C29, C30) |
| P69-21 | "This will be saved on the supply %s and available wherever that supply is used." | on the save form under a SupplyItem destination — no dialog (C29) |
| P69-22 | "Saved to supply %s." | the saved line after a SupplyItem save (C29) |

**Reused and approved, verbatim from their homes:** P69-2 "This installed component" (a component level's first row); P47-5 "Inside %s" (a component level's heading, `A/ui/installed/InstalledComponentStrings.kt`); "Saved to %s" (`ShareIntakeViewModel.kt:96`); "Change" (`:90`); "Back"; P69-11/-12 for a duplicate link; `NO_ASSETS` (`:76`).

**PROPOSED (rev 1.4) — pending ratification:** only the words the combined picker adds (C30 steps 1–3); homes `IntakeStrings` unless named.

| id | proposed wording | where (contract) |
|---|---|---|
| P69-23 | "Share to" | the picker's title and the type control's accessible name (C30 step 1) |
| — | "Assets", "Installed components", "Supplies" — **the shipped words, reused** (`A/ui/nav/BottomBar.kt:52`; P47-1, `InstalledComponentStrings.kt:14`; `SUPPLIES_SECTION`, `A/ui/supplies/SupplyStrings.kt:18`) | the type control's three labels (C30 step 1) — to confirm |
| — | "Search assets" — **#93's hint, reused** (`A/ui/asset/AssetSearch.kt:84`) | the search box with Assets chosen (C30 step 2) |
| P69-24 | "Search installed components" | the search box with Installed components chosen (C30 step 2) |
| P69-25 | "Search supplies" | the search box with Supplies chosen (C30 step 2) |
| P69-26 | "No active assets" | the Assets list when no asset is eligible (C30 step 3) |
| — | P47-2 "No installed components"; `NO_SUPPLY_ITEMS_YET` "No supply items yet. Add one under Maintenance › Supplies." (`SupplyStrings.kt:63`); "Nothing matches that." (`A/ui/asset/AssetsScreen.kt:286`) — **reused** | the empty Installed components and Supplies lists; a query with no hit, every type (C30 step 3) |

**Reused verbatim from their one home:** "Documents", "No documents yet", "Add file", "Take photo"
(`A/ui/attachments/DocumentsSection.kt:93`, `:100`, `:108-109`); "References", "No references yet", "Add link", "Open"
(`A/ui/references/ReferencesSection.kt:171`, `:174`, `:197`, `:245`); the Name / Kind / Notes / Role labels and the
role values (`A/ui/attachments/AttachmentEditSheet.kt:39-43`; `AttachmentsSectionViewModel.kt:67-75`); every #85
string (`MaterializeStrings.kt:14-37`, P85-17 kept for an asset); (rev 1.3: the Share no longer uses "Choose a
supply", `SupplyItemPicker` or P47-2 — its own reused words are listed under the PROPOSED block); "Change", "Saved to %s" (`ShareIntakeViewModel.kt:90`, `:96`) and the rest of `IntakeStrings`
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
  at … which is not in …" lines; the owner-neutral `require`s in `AddAttachment` and `UpdateAttachment.kt:30` (C5);
  and the API's `ATTACHMENT_ROLE_NOT_ALLOWED` message (`A/api/ApiJson.kt:941-944`, "a document role belongs on an
  asset's attachment") restated owner-neutrally by B4 — "a document role cannot go on an entry's attachment" — code
  and status unchanged, any test pin of the text a B4 twin (N-6).
- **G3** — the MCP's `APP_SCHEMA_TOO_OLD` feature name "supply item and installed component resources".

**Not drawn anywhere (decided by the contracts):** a "move to another owner" action (I2); an aggregated resource list
on the Asset detail (R69-4); a "preferred" badge (R69-2); a held-asset dead end in Share (held assets are excluded).

## 6. Owner rulings

| ruling | whose | the question · the recommendation | where it lands |
|---|---|---|---|
| **R69-0** | **owner — DECIDED 2026-10-02** | **The scope sentence** (header): ratify as written, or amend. | every brief's must-nots |
| R69-1 | controller default | **The owner shape (a):** nullable owner FK columns, each CASCADE, exactly one set, enforced on the Room write and the backup read; the sealed `AttachmentOwner` gains two members; `asset_reference` rebuilt with one unique `(owner, uri)` index per owner. (b) loses referential safety exactly where #47 made rows leave only by CASCADE; (c) forks the use cases, views and #85 (§4's table). | C4–C13 |
| **R69-2** | **owner — DECIDED 2026-10-02** | **The vendor URL (AC11 against #15's "no URL"):** recommend **C — reference rows owned by the SupplyItem, no marker**; **D** (a soft `preferred_reference_id`) only if one link must be marked preferred (+1 brief B2d, a C12-style exception, a PATCH key and a `clear_fields` entry); **never A** (a second link system). §4's table costs all four. | C20, C31, §4 |
| **R69-3** | **owner — DECIDED 2026-10-02 (final; supersedes both earlier layouts)** | **The binding Share flow, the owner's words:** "One 'Share to' picker with (a) a type dropdown — Assets / Installed components / Supplies — defaulting to Assets; (b) a search box filtering the selected type; (c) ONE list showing only that type, with enough context to distinguish similarly named items. Direct selection is fast: choose the type, optionally search, select the destination, then the EXISTING save form — no additional confirmation dialog. Asset browsing remains available alongside: selecting an Asset offers 'Attach here' and access to its components and linked supplies; selecting a component offers 'Attach here', its nested components and its linked supplies; this continues recursively with a breadcrumb and Back preserving the position; selecting a SupplyItem makes it the destination. Direct component results show Asset name → component ancestry → component name, and search matches that context as well as the component's own name. Supplies show their identifying product information; selecting one attaches to the shared catalog item, with that ownership clear ON THE SAVE FORM. Eligibility, consistent across both routes: Assets — exclude transferred-out, replaced, archived and transfer-held; Components — current instances belonging to eligible Assets; Supplies — unarchived SupplyItems, reachable directly without an Asset association. Shared prose keeps the existing Asset-only journal-note path. Amend only the affected Share UI, strings and tests; the resource model stays." **Approved with it (2026-10-02):** "active" = `Asset.maintainedHere` (a retired asset is excluded even when never replaced) plus the transfer-hold restrictions; the Archived control hidden in Share only; browsing one level at a time with "This asset" / "This installed component" (the owner's "Attach here"), linked supplies, children and Back. | C29–C30, rows 53, 53a–53c, 54, §5 |
| **R69-4** | **owner — DECIDED 2026-10-02** | **The Asset detail:** recommend it **reaches** its components' and SupplyItems' resources by navigation only (a component row → its screen; a Supplies row → the SupplyItem detail) and never lists them (AC12: nothing reads as Asset-owned; the busiest screen does not grow). | C27, §1 out |
| R69-5 | controller default | **Schema 20 / format 20, a normal bump.** Never amend the unreleased 19 in place: emulator databases and format-19 exports exist, and Room refuses a hash mismatch with no migration. ~40 mechanical sites (§3). | C6–C10 |
| R69-6 | controller default | **Document roles on SupplyItem- and component-owned files too** (R67-11 widened to "anything but an entry's file"; `DocumentRole.kt:9-11` anticipates it); **no new `AttachmentKind`** — SPECIFICATION and DIAGRAM stay `DOCUMENT` (§5's table). Load-bearing for AC13: #85 carries the reference's role into its saved file. | C4, C5, C17, C25 |
| R69-7 | controller default | **The Transfer Pack:** a SupplyItem's resources, bytes included, travel with it as `GLOBAL_IN_USE` and are never held; a component's travel with its asset. Costs stated: pack size (limit 3); an edit while a pack is out refuses the return (limit 2, H5). | C15, C5, C13 |
| R69-8 | controller default | **The component screen shows** its own resources **and** the resources of every SupplyItem its direct link and its composition entries name, one open-only group per distinct SupplyItem, as two ownerships. | C27 |
| R69-9 | controller default | **The smallest honest component surface** is a route and a screen reached from the row sheet's "Documents and references" action on every row (a held asset's read-only); no section on the row sheet (sheets would stack, #47 E-30). No product choice remains beyond §5's words. | C27 |
| R69-10 | controller default | **Archived and removed owners:** an archived SupplyItem and a removed component keep their resources and may gain more from their screens and over the API/MCP; Share offers unarchived SupplyItems and current components only. | C5, C13, C26, C30 |
| R69-11 | controller default | **Bytes follow each owner's life** (C16a's table): kept with an archived SupplyItem and a removed component; swept with a deleted asset's components and a returning asset's. | C5, C16a |
| R69-12 | controller default | **API/MCP shape:** owner keys on the create (exactly one), +4 shapes / +6 rows, five MCP tools widened (89 stays, six new tools rejected), the v3 upload derivation for the new owners with v2 byte-identical for assets. | C19–C24 |
| R69-13 | controller default | **Owner-worded sentences:** per-owner twins for the four asset-worded sentences, the asset wording unchanged (no shipped pin moves), rather than one owner-neutral rewrite. | C28, P69-11…16 |
| R69-14 | controller default | **The brief split** (§4's order) replaces the audit's §13: substrate, two seams, then semantics — the sealed extension is compile-atomic. | §4, briefs |
| **F-1** | **owner — DECIDED 2026-10-02: proceed; measure the ordinary merged-tip gate once, total elapsed and device time recorded separately; at 14 min report the warning; above 15 min #90 comes before further feature work** | **#90 and the gate time:** at #15's measured ~2.47 s per test the base is already ~13.1 min and #69's tip ~13.9–14.1 min — at the 14-minute warning line, under 15. #47's base was never timed in a clean run; the #69 merged-tip gate records the first clean single-run time. Whether #90 (the gate's split or speed-up) moves ahead of the next feature is the owner's call once that number exists. | §3, §4, §7 |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; B5 `uv run --frozen pytest` in `M/` and in `S/` (unchanged). No device run in
  any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **59 classes**, zero
  skips; rows 50, 52 and 54 run here first — a red is one post-merge fix round), R3 (the three Python suites), R5
  (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps. `ReleaseProofPolicyTest`
  unchanged and green. **Record the environment** (#47 §20: which emulator, how started, whether one run). **A clean
  single-run wall time is owed here** (#47's base was never measured in one): estimated ~14.2–14.4 min at #15's
  ~2.47 s per test — at the 14-minute warning line, under 15; reporting only (C-8, §6 F-1). B6c2's and B7b's Compose
  cases (rows 52, 54) and B6b's (row 50) first run here.
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
  - `'"supply-items"'` and `'"installed-components"'` inside `isAttachmentUpload` (`HttpWire.kt`) → 1 each (read
    it); `git diff <base> -U0 -- A/api/ApiJson.kt` touches `:941-944` only (`referenceProblemCode` unchanged).
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

## Briefs — common to every brief (twenty dispatches)

Read §1–§8, the audit, issue #69 (`.superpowers/sdd/2026-10-01-issue-69/issue-69.md`, the FINAL clarification) and
every earlier report on this branch. **Dispatch precondition:** R69-0, R69-2, R69-3, R69-4 ruled and §5 ratified. **The
core doubles (C11):** every core brief from B2a2 builds its fixtures through `BackupInstall`, never a bare list, so a
missing cascade fails for the right reason. **Fenced words (C-3):** no KDoc, docstring, document or test name added by
a brief carries a purchasing, #95 or #96 word, even to deny it. **Constructor plumbing (C-1):** a brief whose contract
adds a constructor parameter owns its `AppGraph` line, its `FakeGraph` line and every construction site `git grep`
names — arguments only; no `AppGraph` or `FakeGraph` edit after B3b. **Forced arms (R69-14, C-4):** a brief whose
contract adds a member, changes a field or removes a port method owns **every compile error that change forces, in any
source set** — arguments and receivers only, each listed in its report — and writes exactly the arms its contract
names; §4 lists the sites the review counted, and a forced site §4 missed is fixed and reported, not a stop. A compile
error **not** caused by the brief's own change stops it.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (over `app/src/main` and `core/src/main` unless
named); the untouched diff against the brief's "never touches"; C33's greps; the tombstone check → 0; `git ls-tree
HEAD libs/nfc-tag-core` → `7e0377a…`; `versionName` / `versionCode` equal `<base>`'s; `git diff <base> --
app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml tools/*/uv.lock app/src/main/AndroidManifest.xml`
→ empty; `git status` clean. B5 adds `uv run --frozen pytest` in `M/` and `S/`.

**Pin rule and the twin rule.** A shipped assertion moves only where §3's table names it for this brief, or as its
twin. Confirm the set first with `git grep -nE 'FORMAT_VERSION|formatVersion\)|schemaVersion\)|SCHEMA_VERSION|1–19|19
since|counts\.size|dropLast|takeLast|columnsOf\(|snapshotOf|assetId = |findByUri|observeForAsset|forAsset\('` and `git grep -nE 'TOOL_NAMES|\b89\b'` over
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
`T/data/room/{Migration9To10Test,Migration15To16Test,Migration16To17Test,Migration18To19Test,ReferenceDaoConstraintTest,AttachmentDaoTest,MigrationTestSupport}.kt`
(#85's `dropLast` shape, C-1). **Rows:** 1–6 and C8's migration-test pins. **Rulings:** R69-1, R69-5. **Interfaces
produced:** the two entities' v20 shapes, the owner-keyed DAO reads, `MIGRATION_19_20`, `20.json`. **Greps:** §7's
schema lines; `'CREATE TABLE'` inside `MIGRATION_19_20` → 1; `git diff <base> -- core` → empty. **Untouched:** `C/**`,
`A/api`, `A/ui`, `A/share`, `19.json`. **Must NOT:** change a domain type; add a `CHECK`; default the new entity fields;
add a single-column index on `asset_reference`'s new owner columns (the unique pairs' left prefixes serve and Room's
check is a warning only, N-11; if the build refuses, stop and report). **Counted RED (4):** rows 1, 2, 4, 5. **Caps:**
7 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 120 production lines plus `20.json`, 260 test lines, 7 pin
edits. **Estimate:** 60 min.

## 10. B1b — the format-20 envelope (C9–C10; core JVM)

**Read:** audit §0.3–0.4, §3; `C/backup/BackupFormat.kt:385-439`, `:1119-1170`, `:1320-1350`;
`C/backup/BackupCodec.kt:176-236`, `:418-456`; `CT/backup/BackupFormat19Test.kt` (the shape to copy). **Rows:** 7–11.
**Rulings:** R69-5. **Interfaces produced:** the DTO keys, `FIRST_RESOURCE_OWNER_FORMAT`, the gate, exactly-one.
**Greps:** §7's format lines; `git diff <base> -- C/model C/merge C/transfer C/usecase app/src/main` → empty.
**Untouched:** as §4. **Must NOT:** map a new owner key to a domain owner (B2a/B2b do); add a list, table or count
key; make `AssetReferenceDto.assetId` nullable (B2b, C-2). **Counted RED (3):** rows 7, 8, 9. **Caps:** 6 runs;
1 h / 2 h. **Size:** about 45 production lines; ~28 pin edits; 220 test lines. **Estimate:** 55 min.

## 11. B2a — the attachment owners, the seam (C4, C5; core and app JVM)

**Read:** audit §0.1, §0.6–0.8, §0.10, §1.5, §2; this plan's C4, C5 and correction 1; `C/model/Attachment.kt`;
`C/usecase/{AddAttachment,UpdateAttachment}.kt`; `C/backup/BackupCodec.kt:585-600`, `:950-1000`, `:1120-1160`;
`C/usecase/ApplyBackupMergePlan.kt:270-369`; `C/transfer/{TransferOwnership,HeldWriteGuard}.kt`;
`C/merge/MergePlanner.kt:1365-1400`; `A/ui/maintenance/ScheduleDetailViewModel.kt:540-556`;
`A/data/room/{Mappers,RoomRepositories}.kt`; `CT/usecase/BackupUseCasesTest.kt:640-660`;
`CT/transfer/TransferImportTesting.kt:140-170`. **Rows:** 12–16. **Rulings:** R69-1, R69-6, R69-10, R69-11.
**Interfaces produced:** the two `AttachmentOwner` members, widened `accepts`, `dirFor`'s two directories,
`OwnerRef.OfInstalledComponent`, `OwnerLookup.installedComponent`, the guard's new reader — and every arm C5 names,
including those rows 17–19 test in B2a2. **Greps:** `'"supply-items/'`, `'"installed-components/'` → 1 each; `'fun
installedComponent\(id: InstalledComponentId\)'` → 4; `HeldWriteGuard(` (8) and `AddAttachment(` (21) construction
sites all updated (count before = after). **Untouched:** as §4. **Must NOT:** touch `AssetReference` or the core
doubles (B2a2); add the merge's attachment availability (B3a) or the pack's select/retain (B3b); give `OwnerLookup` a
default. **Counted RED (5):** rows 12, 13, 14, 15, 16. **Caps:** 8 runs; 1 h / 2 h; fix round 4 runs, 45 min.
**Size:** about 140 production lines, 200 test lines, ~30 construction-site arguments, two test `when` arms.
**Estimate:** 55 min.

## 12. B2a2 — the doubles, the guard and the return (C11; rows 17–20; core JVM tests)

**Read:** this plan's C5 (the arms B2a wrote), C11, N-15; `CT/testing/{InMemoryRepositories,InstalledComponentFixtures,BackupInstall}.kt`;
`CT/transfer/{TransferOwnershipTest,HeldWriteGuardTest,ImportTransferPackTest}.kt`. **Rows:** 17–20. **Rulings:**
R69-7, R69-11. **Interfaces produced:** the component double's listener and the attachment double's
`cascadeFromInstalledComponents` (C11), with the restated KDoc at `InMemoryRepositories.kt:151-156`. **Greps:**
`git diff <base_of_B2a2> -- '*/src/main/*'` → empty. **Untouched:** every main file. **Must NOT:** put a
component-owned row in a pack (N-15); cascade an asset-owned row in a double. **Counted RED (4):** rows 17, 18, 19 (by
mutation of B2a's arms), 20 (the registration removed). **Caps:** 6 runs; 1 h / 2 h. **Size:** about 60 double
lines, 200 test lines. **Estimate:** 45 min.

## 13. B2b — the reference owner, the seam (C12–C13; core and app JVM)

**Read:** audit §0.2, §1.2, H3; this plan's C12, C13 and correction 2; `C/model/AssetReference.kt`;
`C/ports/Repositories.kt:323-345`; `C/usecase/AddReference.kt`; `C/usecase/MaterializeReference.kt:67-74` (the one
line); `C/backup/BackupCodec.kt:800-820`, `:950-1000`; `C/merge/MergePlanner.kt:300-370`, `:460-470`, `:580-590`,
`:648-664`, `:1036-1095`; `A/data/room/{ReferenceMappers,ReferenceRepositories}.kt`; `A/api/ReferenceHandlers.kt:60-90`;
`A/api/AttachmentHandlers.kt:265-290`; C13's eight test files. **Rows:** 21–25, 27–28. **Rulings:** R69-1, R69-10.
**Interfaces produced:** `ReferenceOwner`, `asAttachmentOwner`, the owner-keyed port, `AddReference.run(owner, …)`,
the nullable `AssetReferenceDto.assetId`. **Greps:** §7's `AssetReference` lines; the codec block's new position after
`componentIds` (read it); `AddReference(` (7) sites all updated. **Untouched:** as §4. **Must NOT:** keep an
asset-keyed port method; add an `assetId` property; change the step order of `AddReference`; key `MaterializeReference`
by owner (B2b2); generalise a composable (B6a). **Counted RED (6):** rows 21, 22, 23, 24, 25, 27. **Caps:** 8 runs;
1 h / 2 h; fix round 4 runs, 45 min. **Size:** about 110 production lines; 16 construction sites and ~35 call and read
sites, receivers and arguments only; 260 test lines. **Estimate:** 60 min.

## 14. B2b2 — materialize keyed by the reference's owner (C17; core and app JVM)

**Read:** audit §0.9, H4; this plan's C17 and C-5; `C/usecase/MaterializeReference.kt`;
`CT/usecase/MaterializeReferenceTest.kt:650-690`, `:765-790`; `T/ui/references/MaterializeViewModelTest.kt:160-180`,
`:475-490`; `A/api/AttachmentHandlers.kt:265-345`. **Rows:** 26. **Rulings:** R69-6. **Interfaces produced:**
`MaterializeReference.prepare(owner, …)`, `Prepared.Ready.owner`. **Greps:** `'fun forAsset'` overridden in neither
race double (read them); `'OfAsset(ready'` in `MaterializeReference.kt` → 0. **Untouched:** as §4. **Must NOT:** add a
second fetch or provenance path; change the API's held check (C21, B4); weaken a race double's gate. **Counted RED
(1):** row 26 (the duplicate check reading another owner); the race doubles' gates shown firing (`:662`'s
`waiting == 1`). **Caps:** 5 runs; 1 h / 2 h. **Size:** about 40 production lines, 160 test lines. **Estimate:** 40 min.

## 15. B3a — the merge and the restore order (C14; core JVM, two app rows)

**Read:** audit §0.3, §0.5, H1; `C/merge/MergePlanner.kt:960-1036`, `:1395-1440`; `C/usecase/ImportBackupReplace.kt:160-262`;
`C/usecase/ApplyBackupMergePlan.kt:193-232`. **Rows:** 29–34. **Rulings:** R69-7 (M2). **Greps:** `MergeReason` diff →
empty; `'data.assetReferences.forEach'` in `ImportBackupReplace.kt` after `installedComponents.insert` (read it).
**Untouched:** as §4. **Must NOT:** add an older-archive exception; reorder the apply. **Counted RED (5):** rows 29,
30, 31 (N-2's mutation), 32, 33. **Caps:** 7 runs; 1 h / 2 h. **Size:** about 30 production lines, 200 test lines.
**Estimate:** 45 min.

## 16. B3b — the pack, the return's limits and the delete (C15, C16a; core JVM)

**Read:** audit §0.6–0.7, H2, H5; `C/transfer/TransferGraph.kt:30-70`, `:225-335`; `C/usecase/DeleteAsset.kt:40-80`;
`C/usecase/ImportTransferPack.kt` (around `:127`, C-9); `CT/transfer/{TransferGraphTest,TransferGraphRetainTest,ImportTransferPackTest}.kt`.
**Rows:** 35–38, 37a. **Rulings:** R69-7, R69-11. **Greps:** the two `CLASSES` lines unchanged; `DroppedRows` diff →
empty; `DeleteAsset(` (5) construction sites all updated. **Untouched:** as §4. **Must NOT:** drop a SupplyItem-owned
row in `retain`; change `DroppedRows` or `entangledRefs`; sweep a SupplyItem's bytes. **Counted RED (3):** rows 35, 36,
38; rows 37 and 37a are limit pins. **Caps:** 6 runs; 1 h / 2 h. **Size:** about 35 production lines, 260 test lines.
**Estimate:** 55 min.

## 17. B3c — the equipment scenario (C16b; core JVM)

**Read:** issue #69's examples and E1–E7; `CT/usecase/InstalledComponentFixtureTest.kt` (#47 row 37's shape);
`CT/usecase/SupplyItemFixtureTest.kt`. **Rows:** 39, 40. **Greps:** `git diff <base> -- '*/src/main/*'` → empty.
**Must NOT:** change production code (a failure is a finding: stop and report it against the brief that owns the
contract); use a real product, maker or URL. **Counted RED (1):** row 39 (quote whichever refusal fires, N-5).
**Caps:** 4 runs; 1 h / 2 h. **Size:** about 260 test lines. **Estimate:** 40 min.

## 18. B4 — the API, the wire and the document (C19–C22; app JVM, docs)

**Read:** audit §7, H9; this plan's C-3, C-6; `A/api/ApiRouter.kt:55-62`, `:95-100`, `:215-330`, `:370-385`,
`:429-436`; `A/api/HttpWire.kt:35-50`, `:135-150`; `A/api/{ReferenceDtos,ReferenceHandlers,AttachmentHandlers,AttachmentOperationIds}.kt`;
`A/api/ApiJson.kt:420-432`, `:570-580`, `:715-735`, `:860-870`, `:935-950`; `docs/api/attachment-operation-ids.json`;
`docs/api/v1.md` (the reference, attachment, SupplyItem and component sections). **Rows:** 41–45, 43a and B4's pins.
**Rulings:** R69-2 (C20's no-key rule), R69-12. **Greps:** §7's API and wire lines; `command-shapes.json` diff → empty;
`ReferenceRoutesTest.kt:450-470` diff → empty. **Untouched:** as §4. **Must NOT:** add an error code; change
`referenceProblemCode`; add a `preferred`/`vendor` key; change the v2 derivation or its vectors; compute the v3
vectors with the code they pin; widen `isAttachmentUpload` beyond the two canonical POST shapes; add a `DELETE`.
**Counted RED (5):** rows 41, 42, 43, 43a, 44. **Caps:** 8 runs; 1 h / 2 h. **Size:** about 120 production lines, 300
test lines, ~60 doc lines. **Estimate:** 60 min.

## 19. B5 — the MCP (C23–C24; pytest)

**Read:** audit §7 (MCP); `M/src/servicetag_mcp/server.py:185-245`, `:2100-2200`, `:3036-3050`, `:3100-3420`;
`M/tests/{test_reference_tools,test_attachment_tools,test_argument_guard,test_tools}.py`. **Rows:** 46–48.
**Rulings:** R69-12. **Greps:** §7's MCP lines. **Untouched:** as §4. **Must NOT:** add a tool; gate an asset call on
schema 20; read the golden `vectors` array differently. **Counted RED (2):** rows 46, 47. **Caps:** 6 runs; 1 h / 2 h.
**Size:** about 110 production lines, 260 test lines. **Estimate:** 55 min.

## 20. B6a — the sections keyed by owner (C25, C28; app JVM)

**Read:** audit §5.1, H4; `A/ui/references/*`; `A/ui/attachments/AttachmentsSectionViewModel.kt:60-95`, `:175-190`;
`T/ui/references/*`. **Rows:** 49 (and B6a's pins). **Rulings:** R69-6, R69-13. **Greps:** P69-11…16 each declared once
(`git grep -c` of each literal → 1); `AssetDetailScreen.kt` diff → the one call. **Untouched:** as §4. **Must NOT:**
change an asset's visible behaviour; reword an asset sentence. **Counted RED (2):** row 49's saved-mark and twin
cases. **Caps:** 6 runs; 1 h / 2 h. **Size:** about 80 production lines, 200 test lines. **Estimate:** 50 min.

## 21. B6b — the SupplyItem detail (C26; app JVM, Compose)

**Read:** audit §5.2; `A/ui/supplies/{SupplyDetailScreen,SupplyDetailViewModel}.kt`; `A/ui/nav/ServiceTagRoot.kt:575-595`;
`AT/ui/supplies/SupplySurfacesTest.kt`; `A/ui/asset/AssetDetailScreen.kt:440-500` (how the asset passes the sections
their arguments). **Rows:** 50. **Rulings:** R69-10. **Greps:** the KDoc's "#69's resources, which later sit below"
replaced (read it); `ServiceTagRoot.kt` diff → the `SupplyDetail` entry's arguments only. **Untouched:** as §4.
**Must NOT:** add a section above "Used by"; make an archived item read-only. **Counted RED (1):** row 50's JVM case.
**Caps:** 5 runs; 1 h / 2 h. **Size:** about 55 production lines, 120 test lines. **Estimate:** 45 min.

## 22. B6c — the installed-component screen (C27; app JVM)

**Read:** audit §5.3, H7; this plan's C27 (N-8, N-9); `A/ui/installed/*`; `A/ui/nav/{Route,ServiceTagRoot}.kt`;
`A/ui/supplies/SupplyDetailScreen.kt` (the screen shape); `A/ui/asset/AssetDetailScreen.kt:225-240`. **Rows:** 51.
**Rulings:** R69-8, R69-9. **Greps:** `'data class InstalledComponentDetail\('` in `Route.kt` → 1; P69-1…4 each
declared once; the `AT/` `AssetDetailScreen(` call sites' diff → empty (the defaulted parameter). **Untouched:** as §4,
and `AT/**` (B6c2). **Must NOT:** stack a sheet over the row sheet (close, then navigate); draw an add or edit action in
a SupplyItem group; copy a SupplyItem's row into the component's list; read the row once only. **Counted RED (1):** row
51's dedupe case. **Caps:** 6 runs; 1 h / 2 h. **Size:** about 200 production lines, 220 test lines. **Estimate:**
55 min.

## 23. B6c2 — the component screen drawn (row 52; Compose sources)

**Read:** this plan's C27; B6c's report; `AT/ui/installed/InstalledComponentsSectionTest.kt`; `AT/ui/supplies/SupplySurfacesTest.kt`
(the graph-backed screen shape). **Rows:** 52. **Greps:** `git diff <base_of_B6c2> -- '*/src/main/*'` → empty.
**Untouched:** every main file. **Must NOT:** run a device (the cases compile here and first run at the merged-tip
gate); add a device-boundary class. **Counted RED (0):** device cases. **Caps:** 3 runs (`compileDebugAndroidTestKotlin`);
1 h / 2 h. **Size:** ~12 cases, about 280 test lines. **Estimate:** 40 min.

## 24. B7 — Share: the destination, the active assets and the save form (C29; C30's Assets list; app JVM)

**Read:** audit §6, H8; C29, C30 and §6 R69-3; `A/share/*`; `A/ui/asset/AssetViewModels.kt:329-360`, `:440-476`,
`:515-526`; `A/ui/asset/AssetPicker.kt`; `C/model/Asset.kt:40-60`; `T/ui/asset/AssetPickerModelTest.kt`;
`T/share/ShareIntakeViewModelTest.kt`; `AT/share/ShareIntakeScreenTest.kt` (compile only); #93's plan. **Rows:** 53a,
53c, 55 (re-run), B7's pins. **Rulings:** R69-3, R69-13. **Interfaces produced:** `activeOnly`, `ShareDestination`, the
save form's destination lines (at B7's tip a share still goes asset → form). **Greps:** the boundary classes' and the
manifest's diff → empty; `'excludeHeld'` → 0; P69-21, P69-22 declared once. **Must NOT:** offer an archived, retired or
held asset; give prose a non-`Asset` destination; add a confirmation dialog; change a security rule or the Assets tab.
**Counted RED (2):** rows 53a, 53c. **Caps:** 7 runs; 1 h / 2 h. **Size:** ~100 production, 220 test lines.
**Estimate:** 55 min.

## 25. B7c — Share: the type control, the search and the direct lists (C30 steps 1–3; app JVM)

**Read:** C30; B7's report; `A/ui/asset/{AssetSearch,AssetsScreen}.kt` (`SearchBox`, `Asset.matches`, `TypeChip`
`:219-245`, "Nothing matches that." `:286`); `A/ui/supplies/{SupplyListScreen,SupplyListViewModel,SupplyStrings}.kt`;
`C/model/{SupplyItem,InstalledComponent}.kt`; `C/ports/Repositories.kt` (the component and catalog reads). **Rows:**
53b and its twins. **Rulings:** R69-3. **Interfaces produced:** `A/share/ShareTargets.kt`. **Greps:** `AssetSearch.kt`
diff → `SearchBox`'s defaulted placeholder only; `AppGraph`/`FakeGraph` diff → empty; P69-23…26 declared once.
**Must NOT:** let one type's list show another's rows; offer a removed component, a held or retired asset's component
or an archived SupplyItem; copy `TypeChip`, `SupplyRow` or `Asset.matches` instead of calling or mirroring as C30 says;
add a standalone entry point outside the picker. **Counted RED (4):** row 53b's four mutations. **Caps:** 8 runs;
1 h / 2 h. **Size:** ~140 production, 280 test lines. **Estimate:** 60 min.

## 26. B7d — Share: browsing from an asset or a component (C30 step 4; app JVM)

**Read:** C30, C27 (B6c's supply derivation), B7c's report; `C/model/InstalledComponentTree.kt`;
`C/ports/Repositories.kt:379-387`; `A/share/*`; `T/share/*`. **Rows:** 53. **Rulings:** R69-3, R69-10. **Greps:**
`git diff <base> -- app/src/main/kotlin/com/loosecannon/servicetag/ui core` → empty. **Must NOT:** list a removed or
replaced component or an archived SupplyItem; re-implement the current tree or the supply derivation; write on a
navigation tap; lose the list's type, query or scroll on Back. **Counted RED (2):** row 53's two mutations.
**Caps:** 6 runs; 1 h / 2 h. **Size:** ~110 production, 220 test lines. **Estimate:** 50 min.

## 27. B7b — Share drawn (row 54; Compose sources)

**Read:** C29–C30; the B7, B7c and B7d reports; `AT/share/ShareIntakeScreenTest.kt`. **Rows:** 54 (11 counted cases;
the shipped `:166`, `:308`, `:368` keep their behaviour). **Greps:** `git diff <base_of_B7b> -- '*/src/main/*'` →
empty. **Untouched:** every main file; the boundary classes. **Must NOT:** run a device. **Counted RED (0):** device
cases. **Caps:** 3 runs; 1 h / 2 h. **Size:** ~11 cases, about 300 test lines. **Estimate:** 50 min.

## 28. B8 — the documents and the release-proofs paragraph (C31–C32; docs)

**Read:** `docs/release-proofs.md:120-140`; `docs/design/04-domain-data-model.md:438-460`; `docs/design/14-asset-model.md`;
every report on the branch. **Rows:** 56. **Greps:** `'^\*\*The first signed release carrying Room schema 20'` in
`release-proofs.md` → 1; `'vendor_url. is #69'` in D4 → 0 and the `:452` column row restated (read it);
`ReleaseProofPolicyTest` green. **Untouched:** as §4. **Must NOT:** describe what was not built; name a purchasing,
#95 or #96 word. **Counted RED (0):** a tripwire row. **Caps:** 3 runs; 1 h / 2 h. **Size:** about 80 doc lines.
**Estimate:** 35 min.
