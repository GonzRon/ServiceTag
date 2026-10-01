# #15 — the SupplyItem MVP: identity, generic specifications, Asset applicability and usage linkage: plan and briefs (rev 1.2, 2026-10-01)

> **Rev 1.2 (2026-10-01)** folds in the independent plan review (`.superpowers/sdd/2026-10-01-issue-15/plan-review.md`,
> APPROVE WITH CONDITIONS) and the owner's last rulings. **Owner:** R15-15 DECIDED as recommended (limit 7 cites
> #97 [LATER], non-blocking; §6); **§5 RATIFIED whole** (P15-1…23 with "Part number", the reused strings, G1–G3, D1);
> every R15 ruling is now DECIDED (§6). **Conditions:** C-1 constructor plumbing → §4's B2a/B2b/B2c/B4a/B4b/B5/B8
> rows, C11, C20, C33, "Briefs — common", and B4 **split by plan** into B4a + B4b (§14); C-2 the three exhaustive
> `when`s → C20 (B4a; P15-20 reused verbatim, no P15-24), row 40, §5; C-3 the planning position → C11, rows 18–19;
> C-4 the return → C13 (`ReturnScope`), row 67, B2c; C-5 the duplicate hint **dropped** → C11, limit 4a, R15-7, §7,
> B2b; C-6 the word-anchored, tip-vs-base tripwire → C37.2, row 39 renamed, C19, B1; C-7 the clearing rule → C3, C22,
> C25, C26, rows 47 and 54. **Notes:** N-1 → §7, B1; N-2 → C19, §7, B2a, B4a/B4b; N-3 → B7; N-4 → the pins table, rows
> 57, B2b, B5, B6; N-5 → C7; N-6 → C14, B2c; N-7 → C27, row 56; N-8 → C11, row 17; N-9 → C16, C20; N-10 → row 30;
> N-11 → C36; N-12 → §3's known cost. **Concerns:** limit 4 now states its consequence and is for the owner's eye;
> the "Supplies" / "Components" adjacency is flagged in C33; the B5 ∥ B7 disjointness holds because every `AppGraph`
> and `FakeGraph` edit lands in B1–B4a (§4). Fourteen dispatches: B1, B2a, B2b, B2c, B3, B4a, B4b, B5 ∥ (B7a → B7b),
> B6, B8a, B8b, B9.

> **Rev 1.1 (2026-10-01)** folds in the owner's rulings of 2026-10-01, recorded on issues #15 and #76. **DECIDED:**
> R15-0 (1.6.0 is cut now from the schema-17 line; #15 targets **1.7.0 / schema 18 / format 18** and does not merge
> to master before the 1.6.0 tag), R15-1, R15-2, R15-3, R15-5, R15-8, R15-9, R15-12 as recommended; **R15-4 as
> recommended with the owner's precision** (no sentence may claim that a minimal schedule completion creates an
> event-level SupplyItem usage record: it writes no material line — AC7, limit 2, C18, C21, C25, C28 and §8 reworded);
> **R15-14 decided differently** (the asset-model page records the current architecture, not the six-bucket wording;
> C36, B9). **CLOSED:** R15-13 (the owner corrected the 2026-09-29 comment). **Controller defaults, owner did not
> object:** R15-6, R15-7, R15-10, R15-11, R15-16. **OPEN:** R15-15 only (recommendation and cost-if-wrong in §6).
> §5's strings — P15-1…23, G1–G3, D1 — **await ratification as one block**, wording unchanged. Rev 1 follows.

> **HARD SCOPE (owner, binding across all ten briefs):** "#15 MVP = canonical SupplyItem identity + generic specs +
> Asset applicability + canonical maintenance usage linkage. Nothing more." No stock, quantity on hand, low-stock,
> reorder, lead time, procurement or stock-aware workflow (#95); no fitted-instance state — position, install or
> removal date, installed serial, containment (#47; #15 only provides the stable id #47 will reference); no resources
> on a SupplyItem — photos, manuals, files, vendor links (#69; #15 only provides the owner identity a later `accepts`
> rule plugs into). The words "component" and "part" are not used for a SupplyItem anywhere (audit §0.9: they mean
> child Assets today); "Part number" as the identity field's label is the one allowed "part" word (R15-1, R15-2 DECIDED).

> **Status: PLANNING ONLY.** Not authorized for execution until the owner dispatches it. Placement is the roadmap of
> record's (issue #76, Phase 2A). This plan assumes #93 merged (`bca5939a`, errata `fac0e951`) and nothing after it.
> **Release line (R15-0, DECIDED 2026-10-01):** ServiceTag **1.6.0 is being cut now from the schema-17 / format-17
> line** (#92 + #91 + #93, release branch `release-1.6.0`, in proofs); a schema-17 release tip and branch are
> preserved; **#15 targets 1.7.0 at schema 18 / format 18** and proceeds in parallel on its own branch, and **#15 does
> not merge to master before the 1.6.0 tag**. #15 lands Room schema 18 / backup format 18 **with no version bump and no
> release of its own**, validated on the emulator only (the R67-10 / R91-11 practice, `docs/versioning.md:22`), and
> owes a schema-18 paragraph in `docs/release-proofs.md` (C36). Every ruling is DECIDED (§6).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-01-issue-15/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-10-01-issue-15/audit.md`, every citation read on `fac0e951`;
> three sites are cited approximately (`~`) and **B2a / B2b confirm them before writing** (C10, C11). **Ten briefs on
> one branch `issue-15`**, four of them split by plan (B2b → B2b + B2c, B4 → B4a + B4b, B7 → B7a + B7b, B8 → B8a +
> B8b): B1, B2a, B2b, B2c, B3, B4a, B4b, then **B5 ∥ (B7a → B7b)**, then B6, B8a, B8b, B9 — each `<base>` the
> previous accepted tip; **B1's `<base>` is master at dispatch** (today `fac0e951`: 1.5.0 / code 18, Room
> schema 17 / backup format 17, MCP 76 tools, gitlink `7e0377a`) plus this plan's commit. The audit's ten briefs are
> kept by name and content, with two moves: the request-line link keys and the view models' row state join B4b (so one
> brief closes every full-replace writer, audit §0.8), and the AC11 end-to-end fixture moves from B3 to B4b (the first
> brief where every piece exists). One task review each, at most one bounded fix round each, one whole-branch review,
> the merge, one merged-tip gate.

**Goal:** one canonical **SupplyItem** — a product, replacement item, consumable, module or pack, never a second
"parts" table — with a stable id, identity fields and an ordered list of generic specifications; an **applicability**
relation saying which SupplyItems an Asset (any Asset, child Assets included) takes and in what role; and a nullable
**link** from the two shipped material lines (a quick action's suggested line and an event's used line) to a
SupplyItem, beside the line's own `name` and `unit`, which stay the readable snapshot. All of it stored in Room schema
18, carried by backup format 18 through export, replace, merge and the Transfer Pack, reachable over `/v1` and the
MCP, and drawn by the smallest useful phone surface. The scheduler, the completion and the templates are unchanged.

**Inputs:** issue #15 with the owner's binding 2026-09-29 clarification (`.superpowers/sdd/2026-10-01-issue-15/issue-15.md`,
AC1–AC12); `issue-95-later.md` and `issue-47-next.md` beside it (what is **not** in scope); the audit; the #91 plan
(shape, the PATCH conventions and the older-format merge exception,
`docs/superpowers/plans/2026-09-30-issue-91-reference-document-role.md`); `docs/api/v1.md`; `docs/release-proofs.md`;
`docs/versioning.md`; `docs/superpowers/planning-policy.md`. Paths: `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`,
`A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`,
`AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`, `M/` = `tools/servicetag-mcp/`, `S/` =
`tools/servicetag-schedules/`. Every `file:line` here was read on `fac0e951` (by the audit or by this planner); a `~`
marks the audit's three approximate sites.

## Global constraints

- **Reuse, never re-implement.** The aggregate copies `MaintenanceGroup`'s shape (`C/model/Maintenance.kt:19-45`:
  id-keyed, archived never deleted, ordered children with durable ids). The save copies `SaveGroup`/`SaveProfile`
  (collected problems before one `uow.write`, `C/usecase/SaveGroup.kt:57-60`; a child id kept only when this parent
  owns it, `C/usecase/SaveProfile.kt:81-95`). The archive copies `ArchiveGroup` (`C/usecase/ArchiveGroup.kt:21-33`).
  Applicability copies `AddReference`/`UpdateReference` (`C/usecase/ReferenceCommands.kt:10-13`, `:48-71`; the
  `Unchanged` refusal that writes nothing, `C/usecase/UpdateReference.kt:40-42`). The spec key is the definition slug:
  `KEY_PATTERN`, `slugify`, `dedupedKey` (`C/usecase/DefinitionCommands.kt:96`, `:108`, `:120`), never a second key
  rule. The role is cleaned by `CategoryKey.display` (`C/journal/CategoryKey.kt:94`), never a second cleaner. The
  picker is a stateless composable on `AssetPicker`'s pattern (`A/ui/asset/AssetPicker.kt:15-25`). Every write is one
  use-case call; no handler, view model or tool re-checks what a use case checks.
- **One schema step and one format step, forward-only.** Room 17 → 18 is **three new tables** (`supply_item`,
  `supply_specification`, `asset_supply`) and **two nullable `TEXT` columns** (`profile_consumable.supply_id`,
  `consumable_usage.supply_id`) with no FK, no default, no index and no backfill (the `MIGRATION_16_17` shape,
  `A/data/room/Migrations.kt:752-756`; the soft-link precedent R79-4, `:614-615`). Format 17 → 18 adds **two lists**
  (`supplyItems` with nested `specifications`, `assetSupplies`) and **one key** (`supplyId`) on each material line DTO;
  an older archive decodes with neither, and an older app refuses a format-18 archive before reading it
  (`C/backup/BackupCodec.kt:337-341`). `LAST_LEGACY_FORMAT` stays 7.
- **The 2.6 tombstones are never touched.** `external_link` / `ExternalLinkEntity` / `LinkKind` / `externalLinks` /
  `ExternalLinkDto` stay as they are. Tombstone check: `git diff <base> -- . ':!app/schemas' | grep -cE
  '^[-+].*(external_link|externalLinks)'` → 0.
- **API version stays 1, additive.** Every refusal is a stable `code`, plus `field` where one body key is at fault, in
  the shipped envelope; 422 = change this body, 409 = change another row first, 404 = no such row. Every new mapping is
  an arm of an exhaustive `when` over its sealed type, never an `else`. Unknown keys stay 400
  (`A/api/ApiJson.kt:89-96`). **The API returns codes, not sentences:** a `message` is developer-facing wire text,
  never drawn on a phone screen.
- **No inference, anywhere (C37).** No code path links a material line to a SupplyItem because their names match; no
  template, import or default creates a link; no role is derived from a SupplyItem's name or category; no spec is
  derived from a name. A link exists only because a person (phone, API, MCP) chose it, or because a row carrying it was
  copied verbatim (archive, merge, pack, #86's setup clone).
- **The #95 / #47 / #69 fence.** No column, field, key, route, tool, string or test about stock, quantity on hand,
  thresholds, reorder, lead time, procurement, installed position or date, fitted serial, SupplyItem containment, or
  SupplyItem-owned resources. `SubjectKey` gains no supply member (`C/reminders/ReminderPort.kt:23-28`) and no
  `supplies` channel is added (`A/reminders/NotificationChannels.kt:10`). C37 states the tripwire greps.
- **No personal data.** The owner's private Stage A source and Stage B manifest are never quoted, copied, counted into
  a fixture or loaded by a test. Fixtures are fictional ("Example RO System", "Example Prefilter Cartridge",
  manufacturer "Example Filters Co.", `https://supplies.example.invalid/…` if a URL is ever needed — and #15 needs
  none); no real name, host, serial or e-mail address in code, tests, docs or commits; home paths written `~`.
- **Strings.** Every user-visible string is ratified before it ships (§5). The plan proposes the new ones (P15-1…) and
  names every reused one with its one home. The API's developer-facing `message` texts are listed for ratification as
  #91's G2 was.
- **Tests.** JVM first (core, then app over the production router and the Room-backed `FakeGraph`); Compose
  instrumented tests only for what a composable draws or wires (two new classes, one grown case, §3); **no new
  device-boundary class** — #15 crosses no OS boundary (no intent, grant, permission or channel). No test touches the
  network. Every counted RED is a real mutation run with `--no-build-cache --rerun-tasks`, its failing assertion
  quoted, reverted before the commit. **No rerun-until-green:** a flaky or unrelated failure is reported, not retried.
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump**: `versionName` / `versionCode` stay
  `<base>`'s (1.5.0 / 18 at `fac0e951`; 1.7.0 is set by #15's release, never by a brief — R15-0). Commits: one
  casual lowercase subject line; no body, no
  trailers, no AI attribution. `tools/` never names a screen-driving tool (the `ReleaseProofPolicyTest` tripwire).
  **Implementers never write ledgers**; the controller does. No dependency is added.
- **Schema 18 on master.** Master debug builds carry schema 18 under the last released version name until 1.7.0 carries it:
  emulator only, never the development or production phone. The schema-18 release gate is written by B9 into
  `docs/release-proofs.md` (C36) and run by the release that carries it, not by this plan.
- **Time boxes:** every brief 1 h target, 2 h hard stop; fix rounds 45 min, findings' files only, stop on the first
  finding they cannot close. **Mutation caps** per brief (each brief states its own). **Review budget:** one task
  review per brief, at most one bounded fix round, a scoped re-review only for a substantive correctness finding,
  mechanical fixes (< ~50 lines) by controller inspection, one whole-branch review, merge, one gate.

## 1. Scope

**HARD SCOPE (owner, 2026-09-29/2026-10-01, binding):** "#15 MVP = canonical SupplyItem identity + generic specs +
Asset applicability + canonical maintenance usage linkage. Nothing more."

**In scope — the twelve acceptance criteria, each on its contracts:**

| AC | the issue's words (abridged) | contracts |
|---|---|---|
| AC1 | one canonical SupplyItem represents a product/part independent of stock tracking | C4, C5, C15, C37 (no stock field anywhere) |
| AC2 | a complete pack/module and an individual internal part are distinct SupplyItems | C4 (no SupplyItem-to-SupplyItem relation; two rows), C18 (row 45) |
| AC3 | manufacturer/model/SKU/part-number identity + generic specs, no product-specific schema | C4 (R15-2), C15 (R15-11), C8 |
| AC4 | an Asset references several SupplyItems with distinct roles | C4 `AssetSupply`, C6 `UNIQUE(asset_id, supply_id, role)`, C17 (R15-3) |
| AC5 | applicability creates no installed Component state | C4 (no position/date/serial), C37 |
| AC6 | usage/profile facts reference the SupplyItem, readable snapshot kept | C4, C19, C20 (R15-4) |
| AC7 | a replace-on-cadence item uses the ordinary #4 scheduler / profile / event path; the SupplyItem identity is retained on the profile's quick-action line and on any event line that carries it | C21 (the path unchanged), C19, C24 (the lines carry the link), C18 (row 45); **never** claimed for a minimal completion, which writes no material line (limit 2, R15-4) |
| AC8 | identity/specs/applicability/links survive backup/restore/merge | C8–C14 |
| AC9 | #47 references the same identity without duplicating product data | C4 (`SupplyId` stable, archive-only, R15-5), C13 (in-use pack class), §8 |
| AC10 | #69 uses the SupplyItem as a resource owner without redesign | C4, C30 (detail screen room), §8 |
| AC11 | the multi-stage treatment fixture: several replaceable products with own identity/specs/applicability, no child Assets | C18 |
| AC12 | ranged/conditional guidance is not collapsed here (#88) | C4 (a spec value is plain text; no range, condition or interval field), §8 |

**Out of scope — #95, verbatim from issue #15 ("Explicitly deferred to #95"):** stock ledger / COUNT / DELTA;
quantity-on-hand cache; low-stock thresholds or notifications; reorder quantities; procurement lead time; stock
decrement/reversal; order-by calculations; procurement readiness; stock-aware replacement convenience workflows.

**Out of scope — #47, verbatim from the issue and the clarification:** "What Component/assembly/position is physically
installed right now? Which SupplyItem does that installed instance represent? When was it installed/removed? What was
fitted there previously?" — and "position 3 currently contains battery X installed on date Y belongs to #47".

**Out of scope — #69, verbatim:** "SupplyItem-owned photos, manuals, PDFs, specsheets, receipts/invoices and web/vendor
references are #69." #15 adds no `AttachmentOwner` member and no `supply_id` owner column (audit §9).

**Also out** (follow-up candidates): the schedules loader and the bundle tool (R15-9; `S/` and
`tools/servicetag-bundle/` untouched); SupplyItems created by templates (R15-10); a SupplyItem-to-SupplyItem relation
of any kind (AC2 keeps a pack and its cell as two unrelated rows); a role catalog table or enum (R15-3); notes on an
applicability row (R15-3); #86's replace copying applicability (R15-15); a schedule naming a SupplyItem (#96); a
"where used" list of events per SupplyItem; search inside the SupplyItem picker; a `command-shapes.json` entry
(R15-16); a SupplyItem duplicate hint (C-5, limit 4a); a version bump or release (R15-0: 1.7.0 is cut
later, by its own release work); renaming the shipped "Components" (child Assets) words (#47's).

**Recorded limits (stated, not fixed):**
1. **A pre-#15 client clears links it does not carry (Q12, R15-12).** A pre-#15 MCP's `save_profile` edit rebuilds
   kept lines from four keys (`M/src/servicetag_mcp/server.py:1018-1048`), and any `/v1` client that sends a profile
   or event without `supplyId` on a line sends an unlinked line (the full-replace requests, `A/api/ApiDtos.kt:354-359`,
   `:386-390`). Event lines cannot be protected at all: they are matched by position (`C/usecase/EventCommands.kt:232`).
2. **A minimal schedule completion writes no material line, so it records no SupplyItem usage (R15-4, the owner's
   precision).** "When was this done?" carries no consumables (`A/ui/maintenance/CompletionFlow.kt:359-369`). AC7
   holds as the owner worded it: a replace-on-cadence item uses the ordinary scheduler / profile / event path, and the
   SupplyItem identity is retained on the profile's quick-action line and on any event line that carries it (an event
   entered on the form from the quick action's chip, or a `/v1` / MCP completion that sends lines). A dialog-completed
   event carries no SupplyItem usage of its own; its shipped `profileId` says which quick action was used and is never
   read, documented or tested as a usage record.
3. **A link given and then removed** leaves the line's parent (`EventProfile` / `AssetEvent`) with `updatedAt` moved
   and no link: a format-17 export then re-plans that row `CONFLICT`, because C12's exception applies only while a line
   here carries a link (#91's limit 1, mirrored).
4. **A SupplyItem edited on two phones under one id is a `CONFLICT`** that refuses the whole archive — and the whole
   Transfer Pack (R15-7): a SupplyItem edited on the borrowing phone refuses the **whole return pack**, and the person
   sees a plain `CONFLICT` naming that SupplyItem, with no surface that shows the difference or reconciles it (the
   review's concern; the owner is told at the gate).
4a. **No duplicate hint for SupplyItems (C-5).** Two ids for the same product (same manufacturer and part number)
   both insert silently; the shipped hint carrier is asset-typed (`DuplicateCandidate`, `C/merge/MergePlan.kt:371-376`),
   and a typed SupplyItem carrier is a later issue.
5. **A format-18 archive or Transfer Pack is refused by every schema-17 build** (1.5.0 and 1.6.0 included) as a newer format —
   forward-only, MINOR by `docs/versioning.md`.
6. **The MCP refuses supply tools, and any material line carrying a link, for a phone below schema 18 locally**
   (C27); a hand-made request to such a phone gets that phone's strict 400 for the unknown key.
7. **#86's replace clones quick actions with their links but not the Asset's applicability** (R15-15, DECIDED): the
   successor starts with no Supplies rows; **#97 [LATER]** (filed, non-blocking) may add applicability to #86's
   reviewed carry-forward.
8. **A linked line looks like any other line on event detail and the journal row** (R15-8): the snapshot `name` is the
   readable fact; the link is drawn on the two editors (C34, C35) and returned over the API.
9. **Role uniqueness is exact on the cleaned text** (R15-3): "Oil filter" and "oil filter" are two roles.
10. **What the emulator cannot observe** (§7): the schema 17 → 18 in-place upgrade of a signed build (the next
    release's gate, C36), the development and production phones (untouched), and an on-device export (`/v1` has no
    export route, `docs/release-proofs.md:119`).

## 2. Contracts

### Common (C1–C3)

- **C1, the words and the fence (audit §0.9, §0.1; R15-1).** Domain `SupplyItem` / `SupplyId` /
  `SupplySpecification` / `AssetSupply`; tables `supply_item`, `supply_specification`, `asset_supply`; the link column
  `supply_id`; archive lists `supplyItems` (specs nested as `specifications`) and `assetSupplies`; wire key `supplyId`
  everywhere a row names a SupplyItem; routes `/v1/supply-items`, `/v1/asset-supplies`,
  `/v1/assets/{id}/supply-items`; `MergeTable.SUPPLY_ITEMS`, `MergeTable.ASSET_SUPPLIES`; phone words "Supplies" /
  "Supply item" / "supply" (§5). Never "component", "part" (bar the field label "Part number"), "consumable" as a
  phone word for a SupplyItem, or any #95 word. The shipped phone words "Materials used" / "Materials" / "Material"
  stay the line's words.
- **C2, the codes.** New codes, each with its status, `field` and trigger; every `message` is developer-facing and
  awaits ratification with §5's G-list:

| code | status | field | when |
|---|---|---|---|
| `SUPPLY_ITEM_NAME_REQUIRED` | 422 | `name` | `SupplyItemProblem.NameRequired`: a blank name after trimming (POST, PATCH) |
| `SPECIFICATION_LABEL_REQUIRED` | 422 | `specifications` | `SpecLabelRequired(index)`: a row's label blank |
| `SPECIFICATION_VALUE_REQUIRED` | 422 | `specifications` | `SpecValueRequired(index)`: a row's value blank |
| `SPECIFICATION_KEY_INVALID` | 422 | `specifications` | `SpecKeyInvalid(index)`: a **typed** key outside `KEY_PATTERN` |
| `SPECIFICATION_KEY_TAKEN` | 422 | `specifications` | `SpecKeyTaken(index)`: a **typed** key another row of this SupplyItem holds |
| `SUPPLY_ITEM_INVALID` | 422 | — | fallback for a validation naming no problem: unreachable, documented (the `GROUP_INVALID` precedent, `A/api/ApiJson.kt:275-280`) |
| `NO_SUCH_SUPPLY_ITEM` | 404 | — | `NoSuchSupplyItem` (path id), and `AssetSupplyProblem.SupplyItemMissing` (body `supplyId`) |
| `SUPPLY_ITEM_ARCHIVED` | 409 | `supplyId` | `AssetSupplyProblem.SupplyItemArchived`: a **new** applicability row naming an archived SupplyItem (R15-6) |
| `ASSET_SUPPLY_ROLE_REQUIRED` | 422 | `role` | `AssetSupplyProblem.RoleRequired`: the role blank after cleaning |
| `ASSET_SUPPLY_TAKEN` | 409 | `role` | `AssetSupplyProblem.Taken`: another row holds this `(assetId, supplyId, role)` |
| `NO_SUCH_ASSET_SUPPLY` | 404 | — | `AssetSupplyProblem.NoSuchAssetSupply` |

  Reused unchanged: `no_such_asset` (404, `AssetSupplyProblem.OwnerMissing`, the references precedent); the guard's
  409 `asset_transferred_out` (`C/transfer/HeldWriteGuard.kt:55-62`); `400 bad_request` for an unknown key or a
  malformed body; **`PROFILE_VALIDATION` and `EVENT_VALIDATION` with `field` `consumables`** for a line whose
  `supplyId` names no SupplyItem (`ProfileProblem.UnknownSupplyItem(index)`, `FieldProblem.UnknownSupplyItem(index)`;
  the `BadConsumable` arms' shape, `A/api/ValidationRefusals.kt:91-92`, `:149-153`) — no new code for a line; the MCP's
  local `APP_SCHEMA_TOO_OLD` (C27). An edit that changes nothing answers **200 with the stored row** and writes
  nothing (`Unchanged`).
- **C3, the wire shapes.** Additive, API version 1. Every answer reuses the archive's own row DTOs (C8), as every `/v1`
  answer does (`A/api/MaintenanceDtos.kt:33`, `A/api/ApiDtos.kt:33`):

| surface | shape |
|---|---|
| `GET /v1/supply-items` | `{supplyItems: [SupplyItemDto]}`, archived included, in `(name casefolded, id)` order |
| `POST /v1/supply-items` | body `{name, category?, manufacturer?, model?, partNumber?, preferredUnit?, notes?, specifications?: [{id?, key?, label, value, unit?}]}`; absent text = `""`, absent list = none → 201 `{supplyItem}` |
| `GET /v1/supply-items/{id}` | `{supplyItem, assetSupplies: [AssetSupplyDto]}` — every applicability row naming it, in `(assetId, role, id)` order |
| `PATCH /v1/supply-items/{id}` | the same keys, **every one optional; absent or `null` = unchanged** (#91's shipped "null = unchanged" for non-nullable text); **a given `""` clears** an optional text field (`category`, `manufacturer`, `model`, `partNumber`, `preferredUnit`, `notes`: trimmed, stored empty); `name` can never be blank (`""` → 422 `SUPPLY_ITEM_NAME_REQUIRED`); `specifications` given = the whole ordered list, **`[]` empties it** (a row sent with its `id` keeps its id and stored key unless a key is typed) → 200 `{supplyItem}` (R15-16, C-7) |
| `POST /v1/supply-items/{id}/archive` | `{archived: Boolean}` (the shared `ArchiveRequest`, `A/api/ApiDtos.kt:304-306`) → 200 `{supplyItem}` |
| `GET /v1/assets/{id}/supply-items` | the 26th asset sub-resource: `{assetSupplies: [AssetSupplyDto], supplyItems: [SupplyItemDto]}` — the asset's rows, and each SupplyItem they name once |
| `POST /v1/asset-supplies` | `{assetId, supplyId, role}` → 201 `{assetSupply}` |
| `PATCH /v1/asset-supplies/{id}` | `{role}` (required: it is the row's only editable field) → 200 `{assetSupply}` |
| `DELETE /v1/asset-supplies/{id}` | → 204 No Content (the `DELETE /v1/events/{id}` answer, `A/api/ApiHandlers.kt:362`) (R15-5 DECIDED) |
| profile and event lines | `ProfileConsumableRequest`, `ConsumableRequest` gain `supplyId: String? = null` (absent = unlinked: the requests are full-replace, limit 1); every profile and event answer carries `supplyId` on each line, `null` when unlinked (`encodeDefaults`) |
| `POST /v1/schedules/{id}/complete` | `consumables[]` take `supplyId` the same way (`CompletionRequest`, `A/api/MaintenanceDtos.kt:410-418`) |
| `GET /v1/status` | `schemaVersion` 18, `backupFormatVersion` 18; `counts` gains `supplyItems` and `assetSupplies` |
| `data.json` | `supplyItems[]`, `assetSupplies[]`; each `eventProfiles[].consumables[]` and `assetEvents[].consumables[]` row appends `supplyId`, written `null` when unlinked |

### B1 — the domain and Room schema 18 (C4–C7)

- **C4, the domain (audit §2.1, §0.4; R15-1, R15-2, R15-5).** In `C/model/` (a new `SupplyItem.kt`; `SupplyId` beside
  the other ids in `Ids.kt:3-34`):

  ```kotlin
  @JvmInline
  value class SupplyId(val value: String)

  data class SupplyItem(                       // aggregate root; saved and loaded with its specifications
      val id: SupplyId, val name: String,
      val category: String, val manufacturer: String, val model: String, val partNumber: String,
      val preferredUnit: String, val notes: String,          // "" = none, the Journal.kt:12 convention
      val archivedAt: Long?, val createdAt: Long, val updatedAt: Long,
      val specifications: List<SupplySpecification>,      // in (sortOrder, id) order
  )
  data class SupplySpecification(val id: String, val key: String, val label: String,
                                 val value: String, val unit: String, val sortOrder: Int)
  data class AssetSupply(val id: String, val assetId: AssetId, val supplyId: SupplyId,
                         val role: String, val createdAt: Long, val updatedAt: Long)
  ```

  No constructor defaults (the `MaintenanceGroup` shape). **`ProfileConsumable` and `ConsumableUsage`
  (`C/model/Journal.kt:25`, `:53`) each append `val supplyId: SupplyId?` with no default**, so every constructor site
  decides (C19). There is no quantity, threshold, price, URL, position, install date, serial, parent SupplyItem or
  owner column on any of these types (C37). `category` is free text, **not** the Asset category catalog (#74). A spec
  `value` is plain text: no type, range, condition or interval (AC12, #88). Ports in `C/ports/Repositories.kt` beside
  `GroupRepository` (`:130`) and `ReferenceRepository` (`:329`): `SupplyItemRepository` (`get`, `all`, `upsert` —
  replacing the specification list, as profiles replace their lines, `:97` — `setArchived`) and
  `AssetSupplyRepository` (`get`, `forAsset`, `forSupply`, `all`, `insert`, `update`, `delete`). **Neither has a
  delete for a SupplyItem** (R15-5).
- **C5, Room (audit §2.3).** Three entities beside `A/data/room/entities/AssetReferenceEntity.kt:32-47`, column order
  as the domain's:
  - `supply_item`: `id TEXT PK`, `name`, `category`, `manufacturer`, `model`, `part_number`, `preferred_unit`,
    `notes` (all `TEXT NOT NULL`), `archived_at INTEGER`, `created_at`, `updated_at` (`INTEGER NOT NULL`). No index.
  - `supply_specification`: `id TEXT PK`, `supply_id TEXT NOT NULL REFERENCES supply_item(id) ON DELETE CASCADE`,
    `key`, `label`, `value`, `unit` (`TEXT NOT NULL`), `sort_order INTEGER NOT NULL`; `UNIQUE(supply_id, key)`.
  - `asset_supply`: `id TEXT PK`, `asset_id TEXT NOT NULL REFERENCES asset(id) ON DELETE CASCADE`,
    `supply_id TEXT NOT NULL REFERENCES supply_item(id) ON DELETE RESTRICT`, `role TEXT NOT NULL`, `created_at`,
    `updated_at`; `UNIQUE(asset_id, supply_id, role)`; an index on `supply_id` (the FK's, Room's warning).
  - `ProfileConsumableEntity` / `ConsumableUsageEntity` (`A/data/room/entities/JournalEntities.kt:125-145`, `:244-262`)
    append `supplyId: String?` → `supply_id TEXT`, **no FK and no index** (R15-4).

  DAOs follow `dao/AssetReferenceDao.kt` and `dao/JournalDaos.kt:80-115` (the aggregate's child list replaced in the
  upsert's transaction); repositories and mappers beside the reference ones; `JournalMappers.kt:109`, `:174` carry the
  link both ways. All of it wired in `A/di/AppGraph.kt` (the reference repository's shape). The asset-owned port is
  wrapped by the guard in C14, not here.
- **C6, `MIGRATION_17_18` and `18.json` (audit §0.5).** One migration, registered in the chain at `AppGraph.kt:258`
  and `T/data/room/MigrationTestSupport.kt:68`: three `CREATE TABLE` (and the `CREATE UNIQUE INDEX` / `CREATE INDEX`
  statements) **copied from the exported `18.json`**, as `MIGRATION_11_12` / `MIGRATION_12_13` were
  (`Migrations.kt:620-645`, `:659-675`), then exactly two ``ALTER TABLE `profile_consumable` ADD COLUMN `supply_id`
  TEXT`` / ``… `consumable_usage` …`` — no `DEFAULT`, `UPDATE`, `CREATE INDEX` on the line tables, `REFERENCES` or
  recreate. `AppDatabase` `version = 18` (`:90`); `AppGraph.SCHEMA_VERSION = 18` (`:1029`); `17.json` unchanged.
- **C7, the chain pins (audit §2.6).** `T/data/room/Migration17To18Test` (new, on `Migration16To17Test`'s shape,
  `:30`, `:64-74`) and the `V18_*` sets in `MigrationTestSupport.kt:205-240`. The six whole-chain table-set pins
  (`Migration10To11Test.kt:101`, `Migration11To12Test.kt:167`, `Migration12To13Test.kt:150`,
  `Migration13To14Test.kt:109`, `Migration14To15Test.kt:114`, `ReferenceMigrationTest.kt:104-105`) gain the three
  tables; `ReferenceMigrationTest.kt:73-79`'s whole-row comparison (it seeds both line tables, `:169`, `:209`) gains a
  V18 arm treating `supply_id` (NULL) as a known delta. `MaintenanceMigrationTest.kt:71-77` holds because it compares only
  the columns each row had before (it does seed line rows, `:150-168`), and `Migration8To9Test.kt:36-41` seeds no line
  row; both are re-run, not edited.

### B2a — backup format 18 (C8–C10)

- **C8, the DTOs (audit §2.4).** In `C/backup/BackupFormat.kt`, each the table's columns in column order with **no
  defaults** (the `AssetLoanDto` shape, `:546-567`): `SupplyItemDto(id, name, category, manufacturer, model,
  partNumber, preferredUnit, notes, archivedAt, createdAt, updatedAt, specifications: List<SupplySpecificationDto>)`,
  `SupplySpecificationDto(id, key, label, value, unit, sortOrder)`, `AssetSupplyDto(id, assetId, supplyId, role,
  createdAt, updatedAt)`. `BackupData` (`:603-656`) appends `supplyItems` and `assetSupplies`, each `= emptyList()`
  (an older archive has neither key). `ProfileConsumableDto` (`:215-221`) and `ConsumableUsageDto` (`:250-256`)
  **append `supplyId: String? = null` last** — the one place a default is required, because a format ≤ 17 line has no
  key (the format-6 append precedent, `:258-281`); it is written explicitly as `"supplyId": null` (`explicitNulls`
  default). `toDto` / `toDomain` both ways; the line `toDomain`s (`:880`, `:944`) carry the link.
- **C9, the codec (audit §2.4 steps 1–5).** In `C/backup/BackupCodec.kt`:
  1. `FORMAT_VERSION = 18` (`:152`); `internal const val FIRST_SUPPLY_FORMAT = 18` beside
     `FIRST_REFERENCE_ROLE_FORMAT` (`:186-190`), `internal` because the merge reads it (C12). The KDoc gains a "Format
     18 adds two lists and one line key, and no upgrade" paragraph in the shape of `:100-147`.
  2. **The ≤ 17 gate:** an archive below `FIRST_SUPPLY_FORMAT` carrying a non-empty `supplyItems` or `assetSupplies`
     is `BackupCorrupt` naming the list (the list pattern, `:426-442`); one carrying a non-null `supplyId` on any line
     is `BackupCorrupt` naming the event or quick action (the field pattern, `:391-400`). An explicit
     `"supplyId": null` in an older archive is accepted.
  3. **Sort on encode** (`:213-265`): `supplyItems` by id, each `specifications` in `(sortOrder, id)` (the group
     members' rule, `:240-244`); `assetSupplies` by id. **Counts** (`:270-300`): `supplyItems` and `assetSupplies`,
     specifications counted or not exactly as group members are.
  4. **Graph** (`validateGraph`, `:497`): ids unique within `supplyItems`, within `assetSupplies`, and **specification
     ids unique across every SupplyItem** (the child-id pattern, `:643-645`); every `assetSupplies[].assetId` names an
     asset in the file and every `supplyId` a SupplyItem in the file; **every non-null line `supplyId` names a
     SupplyItem in the file** (R15-6: a link always resolves; there is no FK, so the codec holds it).
  5. **Content** (`C/backup/BackupContentCheck.kt:58-62`, `:83`): a SupplyItem name non-blank; each specification's
     label and value non-blank, key matching `KEY_PATTERN`, keys unique within the SupplyItem; each role non-blank
     and equal to `CategoryKey.display(role)`; `(assetId, supplyId, role)` unique across the file.

  A spec on an unknown SupplyItem is unrepresentable (specs are nested). An `assetSupplies` row or a line naming an
  **archived** SupplyItem is valid (R15-6). Every refusal is the shipped `BackupCorrupt`, its message naming the list
  and row id in the shape of the existing ones (developer-facing; §5).
- **C10, export and replace import (audit §2.4; two `~` sites).** `C/usecase/ExportBackupSet.kt:~170-191` reads both
  new lists; `C/usecase/ImportBackupReplace.kt` wipes and writes them. **B2a confirms both sites before writing** and
  reports the read lines. The wipe deletes `asset_supply` before `supply_item` (the RESTRICT FK) — or relies on the
  asset cascade, whichever the confirmed order already gives — and writes `supply_item` (with specifications) before
  `asset_supply`, after assets. A replace import of a format-18 archive restores every SupplyItem, specification,
  applicability row and line link byte-equal to the file.

### B2b — the merge (C11–C12)

- **C11, the tables and rules (audit §0.6, §2.5; R15-7).** In `C/merge/`:
  - **Tables.** `MergeTable` appends `SUPPLY_ITEMS` then `ASSET_SUPPLIES` after `SUCCESSIONS` (`MergePlan.kt:71-75`),
    so no shipped ordinal moves; the KDoc gains a "#15 appended …" paragraph like `:60-70`.
  - **A SupplyItem, by id, every field compared, no UPDATE** (the loan rule, `MergePlanner.kt:1040-1063`):
    `IDENTICAL` when the ordered DTO equals the local one; `CONFLICT CONTENT_DIFFERS` when not; otherwise
    `CHILD_ROW_ID_TAKEN` when a specification id is held by another SupplyItem here or claimed earlier in this plan
    (the profile pattern, `:507-546`); else `INSERT`. Archived state is a field like any other.
  - **The planning position (C-3).** `mergePlanOf` plans tables in code order with `accepted*` sets
    (`MergePlanner.kt:236-1160`). **The SupplyItem section is planned before the profiles section (`:507`) and so
    before events (`:716`)**, after the definitions (`:505`); the applicability section follows it (assets are
    planned first, so both owners are known). Placed after profiles, every archive or pack that inserts a SupplyItem
    named by a line would plan `CONFLICT OWNER_NOT_AVAILABLE` (row 19).
  - **An applicability row, by id:** `IDENTICAL` / `CONFLICT CONTENT_DIFFERS` as above; then `OWNER_NOT_AVAILABLE`
    naming the asset, or the SupplyItem, when either is neither here nor accepted by this plan; then **the triple
    arm**, mirroring the reference pair arm exactly (`MergePlanner.kt:872-881`, N-8): a local row holding the same
    `(assetId, supplyId, role)` under another id is **`IDENTICAL` with the new reason
    `ASSET_SUPPLY_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`** only when every other field (timestamps included) is equal, and
    otherwise **`SKIPPED` with the new reason `ASSET_SUPPLY_HELD_BY_A_LOCAL_ROW`** (the local row wins, D-18 C's
    rule); nothing is written either way; else `INSERT`. (No incoming row can meet a triple accepted earlier in this
    plan: C9.5 makes the triple unique per file.)
  - **A material line's link (R15-6):** an incoming profile or event whose line names a `supplyId` neither here nor
    accepted by this plan is `CONFLICT OWNER_NOT_AVAILABLE` naming that id — a new arm after the definition arm
    (`MergePlanner.kt:755`), in the same shape. From a well-formed file it fires only when the named SupplyItem is
    itself new and refused (`CHILD_ROW_ID_TAKEN`), as a second `CONFLICT`; the archive is refused either way.
  - **No duplicate hint (C-5, limit 4a).** No `MergeHint` member is added: the shipped carrier is asset-typed
    (`MergePlan.kt:371-376`; `DuplicateCandidateDto`, `A/api/ApiDtos.kt:156-158`; `docs/api/v1.md:588`), and a typed
    SupplyItem carrier is a later issue. A same-id divergence is a plain `CONFLICT` naming the SupplyItem.
  - **The snapshot and its builders (C-1, C-4):** `MergeSnapshot` (`MergePlan.kt:431`) gains `supplyItems` and
    `assetSupplies`; `mergeSnapshotOf` (`MergePlanner.kt:1448-1470`) reads them, and its two callers,
    `ApplyBackupMergePlan.kt:164` and `BuildBackupMergePlan.kt:98`, pass the two repositories (constructor arguments
    only, plus the `AppGraph` wiring line and the `FakeGraph` / test construction sites `git grep` names).
  - **Ownership (M2) and held assets:** `ASSET_SUPPLIES` joins the asset-owned list at `MergePlanner.kt:1199-1219`;
    `SUPPLY_ITEMS` is global and is not.
  - **Apply and report.** `C/usecase/ApplyBackupMergePlan.kt:~160-215` (**B2b confirms the write order before
    writing**) writes SupplyItems (with specifications) **before** applicability rows and applicability rows after
    assets; line links need no order (no FK). The tally gains both tables (`MergePlan.kt:552-576`, 20 → 22 tables) and
    the report its rows (`A/api/ApiDtos.kt:167-240`, the report rows only — not `DuplicateCandidateDto`), each named
    exactly as the shipped tables are, appended so the report's wire key order (`MaintenanceRoutesTest.kt:1194-1215`)
    moves only by the two new keys at its end.
- **C12, the older-archive exception for links (audit §0.6; R67-12 option B, R91-7 mirrored).** A link written through
  `SaveProfile` or `UpdateEvent` moves the parent's `updatedAt`, and profiles and events compare
  `dto.ordered() == local.toDto().ordered()` (`MergePlanner.kt:522-526`, `:740-743`). So, against an archive below
  `FIRST_SUPPLY_FORMAT`, a profile or event **here with at least one linked line** is compared with every line's
  `supplyId` cleared and the incoming `updatedAt` taken as this row's; a row here with no linked line compares as
  always; a format-18 archive compares everything. The fragment pinning it (the `referenceRolesCompared` shape,
  `MergePlanner.kt:854-858`):

  ```kotlin
  val supplyLinksCompared = backup.manifest.formatVersion >= BackupCodec.FIRST_SUPPLY_FORMAT
  fun sameProfile(incoming: EventProfileDto, here: EventProfileDto): Boolean = when {
      supplyLinksCompared || here.consumables.none { it.supplyId != null } -> incoming.ordered() == here.ordered()
      else -> incoming.ordered() == here.withoutLinks().copy(updatedAt = incoming.updatedAt).ordered()
  }
  ```

  `sameEvent` is its twin. The exception applies only to the id arm (profiles and events have no pair arm). A line
  that is otherwise different still conflicts (limit 3 is the given-then-removed case).

### B2c (B2b's split clause) — the Transfer Pack and the held guard (C13–C14)

**Split clause:** B2b carries C11–C14 unless the controller's dispatch estimate passes 1 h — this plan's is 75 min
(§4) — in which case C13–C14 are dispatched as **B2c** off B2b's accepted tip, with the files, rows and greps listed
under B2c in §12.

- **C13, the pack (audit §0.7, §2.5).** `C/transfer/TransferGraph.kt`: `TransferTables.CLASSES` gains
  `"supplyItems" to GLOBAL_IN_USE` and `"assetSupplies" to ASSET_OWNED` (`:36-59`); the `GLOBAL_IN_USE` KDoc ("Global
  rows keyed by name", `:20`) becomes owner-neutral ("global rows: only the rows a pack asset uses travel"), and the
  file KDoc (`:9-11`) names #15's supply items as classified here and loses the words "and stock" (#95's; C37's
  tripwire counts added lines only). **Selection:**
  `assetSupplies = carry(…) { it.assetId in selected }`; `supplyItems = carry(…) { it.id in inUse }`, where `inUse`
  is every `supplyId` named by a carried `assetSupplies` row or a carried profile or event line — the categories'
  shape (`:224`, `:241`), **"by naming row"**, so #47's Component table later only adds itself as a naming row (§8).
  **`retain`** (`:263-297`): `assetSupplies.filterNot { it.assetId in heldIds }`; `supplyItems` kept whole (the copy
  rule, mn-2); no new entanglement (a SupplyItem is never dropped). `TransferOwnership.of(…)` gains one overload for
  `AssetSupply` → its asset (`C/transfer/TransferOwnership.kt:76-113`). A carried SupplyItem that diverges on the
  receiving phone refuses the pack import (limit 4). **The return (C-4):** `ReturnScope.of`
  (`C/usecase/ApplyBackupMergePlan.kt:261-343`) builds `reduced = full.copy(...)` minus each asset-owned list of the
  returning assets and keeps any list it does not name whole; it gains `assetSupplies = full.assetSupplies.filterNot
  { it.assetId in returning }` (the `references` line's shape), so the stale local rows never reach the planner, the
  pack's rows plan `INSERT`, the apply's delete of the returning assets (`:180-182`) cascades the old rows away, and a
  row re-roled on the borrowing phone lands instead of refusing the return (row 67). `supplyItems` is not reduced
  (global, never dropped).
- **C14, the guard (audit §0.7).** `HeldWriteGuard` wraps `AssetSupplyRepository` beside the sixteen asset-owned ports
  (`C/transfer/HeldWriteGuard.kt:65-69`; "sixteen" at `:67`, `:99` and `A/di/AppGraph.kt:284` moves), checking the row written **and** the stored row it
  replaces or deletes. `SupplyItemRepository` is global and **not** wrapped: archiving or editing a SupplyItem that a
  held asset's rows name is allowed (it writes no held row). Line links ride the already-wrapped profile and event
  ports, so a held asset's lines are guarded as today.

### B3 — the SupplyItem and applicability use cases (C15–C18)

- **C15, `SaveSupplyItem` (audit §2.2, §5; R15-2, R15-11).** One use case for create and edit,
  `run(id: SupplyId?, cmd: SupplyItemCommand)`, with a `saveInTransaction` variant (the `SaveGroup` shape,
  `SaveGroup.kt:36-60`). The command and its rows carry **no defaults**:

  ```kotlin
  data class SupplyItemCommand(val name: String, val category: String, val manufacturer: String, val model: String,
                               val partNumber: String, val preferredUnit: String, val notes: String,
                               val specifications: List<SpecificationInput>)
  data class SpecificationInput(val id: String?, val key: String, val label: String, val value: String, val unit: String)
  ```

  Rules, all problems **collected** before one `uow.write` (`SupplyItemValidation(problems)`, the `GroupValidation` /
  `ProfileValidation` shape, `C/usecase/ProfileCommands.kt:33-45`): every text trimmed; `name` non-blank
  (`NameRequired`); per row, `label` non-blank (`SpecLabelRequired(i)`), `value` non-blank (`SpecValueRequired(i)`),
  `unit` trimmed (`""` = none). **The key (R15-11):** a typed key (trimmed, non-blank) must match `KEY_PATTERN`
  (`SpecKeyInvalid(i)`) and differ from every other row's key (`SpecKeyTaken(i)`); a blank key on a row this
  SupplyItem already owns keeps the stored key; a blank key on a new row is `slugify(label)`, or **`"spec"` when the
  slug is empty** (a label such as "Ø" or "°C"; no phone user ever sees a key problem), then `dedupedKey` against the
  keys already taken in this command (typed first, then kept, then derived, in row order). **Ids:** a row's id is kept
  only when this SupplyItem owns it, else freshly minted (`SaveProfile.kt:89-93`'s rule); `sortOrder` = the row's
  index. An edit of a missing id throws `NoSuchSupplyItem`. **`Unchanged`:** an edit whose result equals the stored
  item in every field but `updatedAt` writes nothing and returns the stored item marked unchanged (the `UpdateReference`
  rule, `:22-26`, `:40-42`), so a re-import stays `IDENTICAL`. The save writes only `supply_item` and
  `supply_specification`: **no material line is read or written** (C20).
- **C16, `ArchiveSupplyItem` (R15-5, R15-6).** `run(id, archived: Boolean)`: the `ArchiveGroup` shape
  (`ArchiveGroup.kt:21-33`) — refuses nothing, writes `archived_at` and moves `updated_at` (`ArchiveGroup.kt:29`), reversible. Applicability rows and line links
  naming it are untouched. There is no delete use case, port method, route, tool or button (R15-5).
- **C17, applicability (audit §4; R15-3, R15-5, R15-6).** `AddAssetSupply`, `UpdateAssetSupply`, `RemoveAssetSupply`,
  each returning `AssetSupplyResult` (`Ok(row)` / `Refused(problem)`, the `ReferenceCommands` shape, one problem, a
  fixed step order). Commands with no defaults: `AddAssetSupplyCommand(assetId, supplyId, role)`,
  `UpdateAssetSupplyCommand(role)`. **Add:** `OwnerMissing` (no such asset) → `SupplyItemMissing` →
  `SupplyItemArchived` → `RoleRequired` (`CategoryKey.display(role)` empty) → `Taken` (another row holds the cleaned
  triple) → insert with the **cleaned** role. **Update:** `NoSuchAssetSupply` → `RoleRequired` → `Taken` (another row)
  → `Unchanged` (same cleaned role; writes nothing) → write, moving `updatedAt`; an archived SupplyItem's row may be
  re-roled (it is not a new link). **Remove:** `NoSuchAssetSupply` → delete. Every write goes through the guarded port
  (C14), so a held asset's row throws `AssetTransferredOut`. A **role suggestion** list —
  `AssetSupplyRoles.suggestions(rows)`, the distinct cleaned roles in use on any asset, in `CategorySuggestions`'
  order (`C/journal/CategorySuggestions.kt:14`) — is a pure function the sheet reads (C33); it never fills a role.
  Applicability works on **any** Asset, a child Asset included (the clarification); it never touches a schedule,
  profile, event or line.
- **C18, the AC11 fixture and the AC2/AC7 shape (audit §4; owned by B4b, §14).** One JVM class,
  `CT/usecase/SupplyItemFixtureTest`, fictional throughout: an "Example RO System" asset (from the RO water template,
  `C/journal/SeedTemplates.kt:136-157`) with three SupplyItems ("Example Prefilter Cartridge", "Example RO Membrane",
  "Example Post-filter"), each with its own specifications, three applicability rows with three stage roles, three
  REPLACEMENT quick actions each carrying one line linked to its SupplyItem, and three schedules naming them; plus a
  second asset with a "Example Battery Pack" and an "Example Battery Cell" as two unrelated SupplyItems (AC2). Proven:
  no child Asset is created; export → replace import is byte-equal; a merge into an empty install INSERTs every row;
  a re-plan of the same archive is all `IDENTICAL`; the identity sits on each quick action's line (AC7); a completion
  through `CompleteSchedule` **sending** a linked line writes an event line carrying that `supplyId`; and a minimal
  completion (no lines) writes an event with **no material line and no `supplyId` anywhere on it** — the test asserts
  that absence, so no usage record is ever implied (limit 2).

### B4a / B4b — usage linkage in core, and every full-replace writer (C19–C21)

- **C19, the inputs carry the link, with no default (audit §0.8, §3; R15-4, R15-12).**
  `ProfileConsumableInput` (`C/usecase/ProfileCommands.kt:16-21`) and `ConsumableInput`
  (`C/usecase/EventCommands.kt:25`) each append `val supplyId: SupplyId?` **with no default**, so forgetting it is a
  compile error (the `ReferenceCommands.kt:35-38` rule). **Every writer of a material line carries it** — the
  complete list, confirmed by B4a with `git grep -nE 'ProfileConsumableInput\(|ConsumableInput\(|ProfileConsumable\(|ConsumableUsage\('
  -- core/src/main app/src/main`:
  - `SaveProfile` stores `input.supplyId` on the line it builds (`SaveProfile.kt:89`);
  - `buildEvent` stores it on each `ConsumableUsage` (`EventCommands.kt:231`); the link travels with the input row,
    so the by-position re-identification (`:232`) keeps it correct when rows are moved;
  - the API's request lines (`A/api/ApiDtos.kt:380`, `:421`; `A/api/MaintenanceDtos.kt:426`) pass the request's
    `supplyId` (C24 adds the keys; B4b owns these three mapping lines and their route rows; B4a passes
    `supplyId = null` there to compile);
  - the view models' row state: `EventEntryViewModel` (`:86-92` the row, `:262-265` the load, `:390-400`
    `addSuggested`, `:635-637` the save) and `ProfileEditViewModel` (`:160` the row, `:340-343` the save) carry
    `supplyId` from load (or the profile chip) to save, invisibly — B4b (B4a passes `supplyId = null` at
    `EventEntryViewModel.kt:637` and `ProfileEditViewModel.kt:340` to compile); B8 draws it;
  - `ApplyTemplate.kt:102` passes `supplyId = null` (R15-10, C21); `ReplaceSetup.kt:84`'s `copy(id = …)` keeps it
    (C21); the codec's `toDomain`s (C8) and `JournalMappers` (C5) carry it.

  **B1's placeholders.** B1 adds the domain field and passes `supplyId = null` at each main constructor it cannot yet
  feed (`BackupFormat.kt:880`, `:944`, `SaveProfile.kt:89`, `EventCommands.kt:231`); B2a replaces the codec two and
  B4a the use-case two. B1 also writes `ApplyTemplate.kt:102`'s `supplyId = null`, which is final (R15-10), and the
  real mapping in `JournalMappers.kt:109`, `:174`. B4a's own five input placeholders (`ApiDtos.kt:380`, `:421`,
  `MaintenanceDtos.kt:426`, `EventEntryViewModel.kt:637`, `ProfileEditViewModel.kt:340`) are replaced by B4b. **The
  check is per site (N-2):** each named placeholder line is read and no longer passes a literal `null` after its
  replacing brief; a repo-wide `supplyId = null` grep is not used (C12's `withoutLinks()`, B8's unlink and new rows
  write it legitimately).
- **C20, the snapshot rule and link resolution (audit §3; D4 `04-domain-data-model.md:266-275`, `:346-353`; R15-6).**
  A line's `name` stays **required even when linked** (the shipped `BadConsumable` rules unchanged) and `name` /
  `unit` are stored as the shipped rules store them (trimmed, `SaveProfile.kt:83`, `:94`) — the core never fills them from the SupplyItem (the phone pre-fills blanks,
  C34). **A non-null `supplyId` must name an existing SupplyItem, archived or not:** `SaveProfile` collects
  `ProfileProblem.UnknownSupplyItem(index)`; `buildEvent` collects `FieldProblem.UnknownSupplyItem(index)` (beside
  `BadConsumable`, `EventCommands.kt:58-68`); each refuses before any write. `buildEvent` gains a
  `SupplyItemRepository` parameter and its five callers (`LogEvent.kt:37`, `UpdateEvent.kt:45`,
  `CompleteSchedule.kt:102`, `CompleteGroupMembers.kt:143`, `RecordConditionWithIncident.kt:87`) pass it; **those
  five and `SaveProfile` gain it as a constructor parameter (C-1)**, so B4a also owns the `AppGraph` wiring, the
  `FakeGraph` and the ~12 test construction sites (`HeldWriteGuardTest`, `ConditionHealthHarness`,
  `CrossConceptWriteTest`, `GroupCompletionTest`, `ScheduleOperationsTest`, `SeasonCommandHarness`, `CloseRoundTest`,
  `GroupMembershipTest`, `ScheduleCommandRulesTest`, `IncidentWorkflowRoomTest`, `RecordConditionWithIncidentTest`,
  confirmed by `git grep`) — constructor arguments only. **The three exhaustive `when`s (C-2), B4a's:**
  `eventRefusal` and `profileRefusal` (`A/api/ValidationRefusals.kt:72-93`, `:138-153`) each gain the
  `UnknownSupplyItem` arm (C2's `EVENT_VALIDATION` / `PROFILE_VALIDATION`, `field` `consumables`, G2's message);
  `ProfileEditViewModel.asProblems()` (`A/ui/setup/ProfileEditViewModel.kt:351-362`) maps it to the named row with
  **P15-20 reused verbatim** ("That supply item is no longer available." — the same meaning: the row's SupplyItem is
  gone), declared by B4a in `A/ui/supplies/SupplyStrings.kt` (new) and imported by B7 and B8; no new string. The
  event form's `firstProblemText` (`A/ui/journal/EventEntryViewModel.kt:666-683`) keeps its `else -> null`, so the
  event form draws the shipped `CANNOT_SAVE` sentence and marks no row — reachable only by a race, since a SupplyItem
  is never deleted (R15-5). **No write
  path ever rewrites a stored line because its SupplyItem changed:** a rename, spec edit or archive of a SupplyItem
  leaves every past line's `name`, `unit` and `supplyId` byte-equal (C15 writes only its own tables).
- **C21, what stays unchanged (audit §3; R15-4, R15-10; #86).** Recurrence, due state and completion are untouched:
  `CompleteSchedule` (`:72-88`), `ScheduleCommands` (`:63-71`) and the "minimal" rule (`:73-74`) do not move; the
  dialog's command still carries no lines (`CompletionFlow.kt:359-369`), so a minimal completion records no
  SupplyItem usage (limit 2). `ApplyTemplate` creates unlinked lines
  (`:101-102`): templates stay name-only and create no SupplyItem. #86's setup clone copies each cloned quick action's
  lines **with their links** (`ReplaceSetup.kt:84`, the same product on the successor) and copies **no applicability
  row** (R15-15, limit 7).

### B5 — the API (C22–C25)

- **C22, the SupplyItem routes (audit §6; C3; R15-16).** New `A/api/SupplyDtos.kt` and `A/api/SupplyHandlers.kt`,
  routed in `A/api/ApiRouter.kt` beside the group triad (`:239-255`): `GET|POST /v1/supply-items`,
  `GET|PATCH /v1/supply-items/{id}`, `POST /v1/supply-items/{id}/archive`; any other verb is the shipped 405, any other
  sub-path the shipped 404. `POST` maps its body to a `SupplyItemCommand` (absent text = `""`, absent list = none) and
  calls `SaveSupplyItem.run(null, …)`. **`PATCH` is an overlay assembled in the handler** from the stored row: each
  given key replaces the stored value, absent or `null` keeps it, and an absent `specifications` sends the stored rows
  back as inputs with their ids and keys; the handler then calls `SaveSupplyItem.run(id, …)` and re-checks nothing.
  **The clearing rule (C-7):** a given `""` clears an optional text field (trimmed, stored empty); `name: ""` is the
  use case's `NameRequired` (422); `"specifications": []` removes every row; blank is never read as "unchanged".
  No SupplyItem field is nullable, so **no raw-key tri-state is needed** (the #91 reader,
  `A/api/ScheduleForms.kt:96-118`, is not used); a later nullable field would bring it. `Unchanged` → 200 with the
  stored row. `archive` calls `ArchiveSupplyItem`. Problems map through `supplyItemProblemCode` (the
  `groupProblemCode` shape, `A/api/ApiJson.kt:761-766`) under one exhaustive `is SupplyItemValidation` arm (the
  `GroupValidation` arm's shape, `:275-280`: the first problem's code, `problems` listing every problem by its domain
  name); `NoSuchSupplyItem` → 404.
- **C23, the applicability routes (C3; R15-5).** `GET /v1/assets/{id}/supply-items` (the 26th asset sub-resource,
  `ApiRouter.kt:193-236`; a held asset reads as any other); `POST /v1/asset-supplies` → `AddAssetSupply`;
  `PATCH /v1/asset-supplies/{id}` → `UpdateAssetSupply` (`role` required in the body: a missing `role` is the
  decoder's 400); **`DELETE /v1/asset-supplies/{id}` → `RemoveAssetSupply`, 204** (R15-5 DECIDED)
  (the shipped "the API adds and amends, the phone removes" rule for references, `ApiRouter.kt:112-113`, is
  deliberately not followed: an applicability row is configuration, not a record; `DELETE /v1/events/{id}` is the
  shipped precedent for a delete verb, `:299`). `AssetSupplyProblem` maps through one exhaustive `when` to C2's rows
  (`OwnerMissing` → `no_such_asset`); `AssetTransferredOut` → the shipped 409.
- **C24, the link keys on the line requests (C19; R15-4, R15-12).** `ProfileConsumableRequest` (`ApiDtos.kt:354-359`)
  and `ConsumableRequest` (`:386-390`, used by `EventRequest` and `CompletionRequest`) append
  `val supplyId: String? = null` — absent = unlinked, because every one of these requests is a full replace (limit 1).
  **B4b adds the two keys and the three mapping lines** (C19) with their route rows; B5 documents them. A `supplyId`
  naming no SupplyItem is C2's `PROFILE_VALIDATION` / `EVENT_VALIDATION` arm with `field` `consumables` and a new
  message (§5 G-list). Every profile and event answer carries `supplyId` on each line through the archive DTOs (C8).
- **C25, status, history and the wire document.** `GET /v1/status` `counts` gains `"supplyItems"` and
  `"assetSupplies"` (`A/api/ApiHandlers.kt:201-219`). The router's history KDoc (`ApiRouter.kt:100-164`, prose that
  pins nothing) gains a "#15 added …" paragraph naming the five rows, the 26th sub-resource and the one delete verb.
  `docs/api/v1.md`: a SupplyItem section and an applicability section in the shape of the group and reference
  sections, the SupplyItem PATCH stating C3's clearing rule (`""` clears, `[]` empties, `name` never blank); the "#15 family" codes table after the loan family (`:1861`), every C2 row as `| status | code | when |`;
  the line key on the profile, event and completion bodies — the completion section says a completion's lines may
  carry `supplyId` and **never** that a completion without lines records a SupplyItem (limit 2); the two `counts`
  keys; the import range "1–17" → "1–18"
  (`:208`, `:1971`) and the tally's table count 20 → 22 (`:577`) with the two table names. **No
  `command-shapes.json` entry** (R15-16: the PATCH is an overlay, so no client rebuilds a whole command; R91-12's
  precedent), so `CommandShapesGoldenTest.kt:44-60` does not move.

### B6 — the MCP (C26–C28)

- **C26, the tools (audit §6; R15-5).** In `M/src/servicetag_mcp/server.py`, on the group tools' conventions
  (`:1328-1450`) and the loan tools' (`:2709-2811`), appended to `TOOL_NAMES` (`:78`): `list_supply_items`,
  `get_supply_item`, `create_supply_item`, `update_supply_item` (overlay: only the arguments given are sent;
  `specifications` replaces the whole list and its docstring says to pass each kept row's `id`; the docstring states
  the clearing rule — pass `""` to clear a text field, `[]` to remove every specification, `name` never blank — so no
  `clear_fields` argument is needed, unlike `update_reference`), `archive_supply_item`
  (`archived: bool = True`), `list_asset_supplies(asset_id)`, `set_asset_supply` (create when no `asset_supply_id`,
  re-role when given — the `save_profile` create/edit precedent, `:946-975`) and `remove_asset_supply` — **eight
  tools, 76 → 84** (R15-5 DECIDED: the removal tool stays). No delete tool for a SupplyItem. Docstrings name the
  HARD SCOPE's words and never a #95 word.
- **C27, the gates (audit §0.8; the #91 pattern, `:206-212`, `:305-308`).** `_MIN_SUPPLY_SCHEMA_VERSION = 18` beside
  `_MIN_REFERENCE_ROLE_SCHEMA_VERSION` (`:208`); `_require_supply_schema(tool, feature)` over `_require_tool_schema`
  (`:262-280`). **All eight tools** call it before any request. **The four line-writing tools** — `save_profile`
  (`:946`), `log_event` (`:1082`), `update_event` (`:1123`), `complete_schedule` (`:1858`) — call it **only when a
  consumable they will send carries a `supplyId` key at all** (presence, not non-null, N-7: an explicit `null` sent to
  a schema-17 phone would otherwise meet that phone's raw 400 instead of `APP_SCHEMA_TOO_OLD`); without one they reach any phone they reach today (a
  schema-17 test in each file stays green unchanged). `save_profile`'s `kept_consumables_from` (`:1018-1048`) keeps
  `supplyId` **exactly as read**: the key travels iff the stored row had it (a schema-17 row has none, so nothing new is
  sent to a schema-17 phone), and a kept key counts as "sent" for the gate. The three event tools' line type widens from `dict[str, str]` to
  `dict[str, Any]` only so an explicit `"supplyId": null` is admitted; a returned event line still cannot be passed
  back verbatim (it carries `id`, `sortOrder` and a numeric `quantity`, and `ConsumableRequest` is strict) (B6
  confirms the argument-guard pins first, `M/tests/test_argument_guard.py`).
- **C28, documents and pins.** `import_merge`'s docstring (`:1183-1222`): format "1–17" → "1–18", the tally's table
  list and count 20 → 22. `M/README.md`: the eight tools, the gate, the line key, the range; `complete_schedule`'s docstring says a link
  travels only on a line it sends (limit 2). The count pins
  (`test_argument_guard.py:51`, `:203-204`, `:256-258`) move 76 → 84. `S/`'s suite runs unchanged and green
  (the loader imports the MCP in process, `S/src/servicetag_schedules/phone.py:1-3`; R15-9: no loader change).

### B7 — the phone catalog (C29–C31)

- **C29, the Maintenance "Supplies" row and the routes (audit §0.10, §7; R15-8).** A fifth Maintenance section: one
  `NavigatingRow` titled `SUPPLIES_SECTION` (P15-1), declared beside the four ratified labels
  (`A/ui/maintenance/MaintenanceScreen.kt:38-41`) and drawn **after the groups and before Reminders** (`:153-163`),
  with no heading of its own (Reminders' rule, `:160-161`); the screen's KDoc ("Four sections", `:52`) says five. Three pushed
  routes in `A/ui/nav/Route.kt` beside `GroupDetail` / `GroupEdit` (`:156-159`): `Route.Supplies`,
  `Route.SupplyDetail(id: String)`, `Route.SupplyEdit(id: String?)`, wired in `ServiceTagRoot.kt`. **The three primary
  destinations do not move** (`T/VersionAgreementTest.kt:432`); no tab, Settings row or drawer entry is added.
- **C30, the list and the detail (audit §7; R15-6).** New `A/ui/supplies/SupplyListScreen.kt` on `GroupListScreen`'s
  shape (104 lines): title P15-1; every SupplyItem in `(name casefolded, id)` order, the name with, when non-blank,
  "manufacturer · part number" as a quiet second line; an archived row keeps its place and draws the shipped
  "Archived" badge (`GroupListScreen.kt:96`); the add button reads P15-2 (the `MAINTENANCE_GROUP` precedent,
  `:27`, `:68`); empty: P15-3. New `SupplyDetailScreen.kt` + `SupplyDetailViewModel`: top bar the name, "Back", "Edit"
  and "Archive" / "Unarchive" (`A/ui/maintenance/GroupDetailScreen.kt:121`, `:128`, `:131`); the identity facts, each
  only when non-blank, under the shipped labels (C31); **"Specifications"** (P15-6): one row per spec, "label —
  value unit", or "No specifications" (P15-9); **"Used by"** (P15-10): one row per applicability row, the asset's name
  and the role, a tap opening the asset; empty: P15-11. Nothing else is drawn: #69's resources section later sits
  below "Used by" (§8), and no placeholder is drawn for it.
- **C31, the editor (audit §7).** New `SupplyEditScreen.kt` + `SupplyEditViewModel` on `GroupEditScreen` /
  `GroupEditViewModel`'s shape: title P15-2, "Cancel" and "Save" (`GroupEditScreen.kt:70`, `:77`); fields "Name",
  "Category", "Manufacturer", "Model", "Part number" (P15-4), "Preferred unit" (P15-5), "Notes"; "Specifications"
  (P15-6) as rows of "Label", "Value", "Unit" with a close glyph labelled P15-8 (the Materials rows' shape,
  `A/ui/setup/ProfileEditScreen.kt:370-400`) and an "Add specification" row button (P15-7, the `AddRowButton`
  precedent, `:255`, `:491`). Rows keep their loaded ids and keys and are sent in screen order; **no reorder control
  and no key field** (R15-8, R15-11). Save is enabled while the trimmed name is non-blank and nothing is saving (the
  `canSave` shape); a `SupplyItemValidation` naming a row's label or value draws P15-12 under the rows and marks each
  named row; Cancel and Back write nothing. A `NoSuchSupplyItem` on save draws P15-20.

### B8 — the phone surfaces on the asset and the lines (C32–C35)

- **C32, the SupplyItem picker (audit §7; the #93 pattern).** New `A/ui/supplies/SupplyItemPicker.kt`: a stateless,
  view-model-free composable (`AssetPicker`'s contract, `A/ui/asset/AssetPicker.kt:15-25`) taking the rows to draw and
  an `onPick`; title P15-15; rows as C30's list rows; empty P15-16. **The hosts pass unarchived SupplyItems only**
  (R15-6) — the filter is each host view model's, JVM-proven — and the picker decides nothing. No search box (out of
  scope); no "new supply item" row (P15-16 points to Maintenance).
- **C33, the asset-detail "Supplies" section (audit §4, §7; R15-3, R15-8).** New `A/ui/supplies/AssetSuppliesSection.kt`
  + `AssetSuppliesSectionViewModel.kt` on the `ReferencesSection` pair's shape, placed in
  `A/ui/asset/AssetDetailScreen.kt` as the first section of the padded column, **before "Components"**
  (`:441-446`), with `readOnly = !current.offersWrites` (`:466-472`). A new `onOpenSupply` parameter on
  `AssetDetailScreen` (`:184-206`), wired at `A/ui/nav/ServiceTagRoot.kt:200`, carries the row tap (C-1). Header
  P15-1 — the section reads "Supplies" with no section subtitle; its placement directly above "Components" is R15-8's
  and is **flagged for the owner's eye at the merged gate** (the two words may read as one concept until #47 renames
  "Components"); when writes are offered, the
  header's trailing add glyph labelled P15-13 (the maintenance sections' glyph rule, `:430-433`). Rows in
  `(role casefolded, SupplyItem name casefolded, id)` order: the SupplyItem's name (with the "Archived" badge when
  archived) and the role as a quiet line; a tap opens `Route.SupplyDetail`; the overflow ("More") offers "Edit role"
  (P15-17) and "Remove" (`A/ui/references/ReferencesSection.kt:255`). Empty: P15-14. **Add:** the picker, then a role
  sheet — "Role" (`ROLE_HEADER`, `A/ui/attachments/AttachmentsSectionViewModel.kt:75`) as a text field, the role
  suggestions (C17) as chips that fill it, "Save" / "Cancel" — calling `AddAssetSupply`; Save is enabled while the
  cleaned role is non-blank. **Edit role:** the same sheet, prefilled, calling `UpdateAssetSupply`; `Unchanged` closes
  it as saved. **Remove:** `RemoveAssetSupply`, immediately, no dialog (configuration, re-addable). Refusals: `Taken`
  → P15-18 under the field; `OwnerMissing` / `SupplyItemMissing` / `SupplyItemArchived` / `NoSuchAssetSupply` → P15-20.
  Read-only (a held asset) draws the rows and no glyph, overflow or sheet.
- **C34, the quick-action editor's link control (R15-4, R15-6, R15-8).** A shared stateless `SupplyLinkLine`
  composable in `A/ui/supplies/` under each Materials row of `ProfileEditScreen` (`:370-400`): an **unlinked** row
  draws a "Link supply" text button (P15-21) opening the picker; a pick sets the row's link and fills the row's `name`
  **only if blank** and `unit` **only if blank** (from `preferredUnit`), never overwriting typed text. A **linked** row
  draws "Linked to {name}" (P15-22), the "Archived" badge when that SupplyItem is archived, and a "Remove link" button
  (P15-23) that clears the link and nothing else. A link whose SupplyItem is not loaded draws no line and is kept.
  `ProfileEditViewModel` gains `linkSupply(index, id)` / `unlinkSupply(index)` and the SupplyItem rows it needs.
- **C35, the event form (R15-8).** `EventEntryScreen`'s Materials rows (`A/ui/journal/EventEntryScreen.kt:317-379`)
  draw `SupplyLinkLine` for a **linked** row only — the link the profile chip carried (C19) or the stored line's —
  with "Remove link"; the event form offers no "Link supply". Event detail (`EventDetailScreen.kt:239-260`) and the
  journal row (`A/ui/journal/JournalFormat.kt:126-128`) are unchanged: the snapshot `name` is the readable fact (limit
  8).

### B9 and cross-cutting (C36–C37)

- **C36, the documents (audit concerns; R15-0, R15-13, R15-14).** B9 writes, after `docs/release-proofs.md:119`, **the
  schema-18 paragraph** in that paragraph's shape: the first signed release carrying Room 18 / format 18 proves the
  direct in-place upgrade from each release a phone runs when 1.7.0 is cut — **1.6.0 (schema 17)** and any older
  release then installed, named at that time (R15-0: 1.6.0 carries schema 17 alone, so from 1.6.0 this is one step;
  from an older release the schema-17 paragraph's checks apply too); seeds, on the older side, profiles and
  events with material lines through that release's own tools; takes a pre-upgrade export on the device; then
  verifies `schemaVersion` / `backupFormatVersion` 18, `counts` gaining `supplyItems: 0` and `assetSupplies: 0`, every
  pre-existing line answering `"supplyId": null` and otherwise equal; then creates SupplyItems with specifications,
  applicability rows and links **after** the upgrade (the MCP and the phone's own editors), re-plans the pre-upgrade
  export **applicable with zero INSERT and every row `IDENTICAL`** (C12; no link given-then-removed before it, limit
  3) — **the seeded lines carry units, or the links are given through the MCP (N-11):** linking a unit-less line on
  the phone editor fills its unit (C34), a content change that re-plans that quick action `CONFLICT`; and proves the format-18 round trip (replace and merge). It names what the emulator does not observe (limit 10).
  `docs/versioning.md:22` already states the practice and is **not** edited. `README.md:54`'s sentence loses "with
  optional stock/reorder information" (§5's D-text). **The asset-model page (R15-14, decided differently):**
  `docs/design/14-asset-model.md` (the next free number after `13-compatibility-policy.md`) records the **current
  architecture** — "Asset → nested Component → optional SupplyItem identity → maintenance usage/history" — with the
  distinction that a SupplyItem needs a #47 Component record only when fitted-instance or current-position history
  actually matters; it does **not** reproduce the 2026-09-21 six-bucket wording (which conflicts with the simplified
  #47 model), quotes no private data, uses none of C37's #95 words, and claims no usage record for a minimal completion
  (limit 2). D4 §9 (`docs/design/04-domain-data-model.md:435-469`, `:568`) gains a two-line
  note that its fields beyond identity, specifications and applicability are #95's and its "Delete supply" row is
  superseded by archive-only (R15-5) — worded so C37's tripwire stays at 0.
- **C37, the invariants every brief keeps.**
  1. **No inference** (Global constraints): `CT/usecase/SupplyLinkInferenceTest` (row 37) proves a line named exactly
     as a SupplyItem stays unlinked through `SaveProfile`, `LogEvent`, `ApplyTemplate`, a merge and a replace import.
  2. **The fence tripwire (C-6), word-anchored and a tip-vs-base delta:** with
     `P='\b(stock(s|ed|ing)?|reorder(s|ed|ing)?|low[- ]?stock|lead[- ]?times?|on[-_ ]?hand|procure(ment|d)?)\b'`,
     `git grep -ciE "$P" -- . ':!docs/superpowers'` summed at the tip ≤ the same sum at `<base>` (base hits stay
     neutral; B9's README edit lowers it), and the same rule for
     `'\b(installed_?on|removed_?on|install_?date|parent_?supply)\b'`. List-order wording in code and tests says
     "move" or "resequence", never "reorder" (row 39). B1 proves both patterns hold on its own diff before committing.
  3. **The word tripwire:** no new user-visible string contains "component" or "consumable"; "part" appears only in
     "Part number" (§5's list is the whole set).
  4. **Untouched:** `git diff <base> --` `C/reminders/`, `A/reminders/`, `C/usecase/CompleteSchedule.kt` (bar C20's one
     argument), `C/usecase/ScheduleCommands.kt`, `A/ui/maintenance/CompletionFlow.kt`, `C/journal/SeedTemplates.kt`,
     `tools/servicetag-schedules/`, `tools/servicetag-bundle/`, `docs/api/command-shapes.json` → empty (or the one
     argument line).
  5. **The tombstones** (Global constraints) → 0.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED (the mutation that must fail it). App classes live in `T/` unless
named; core in `CT/`; MCP in `M/tests/`. "Pin" = a shipped assertion that moves or is re-run, with no RED. **JVM**
unless marked **Compose** (instrumented, runs at the merged-tip gate only). Case names are the implementer's to
refine; what each proves is not.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C6 the migration adds tables and NULL columns only | `T/data/room/Migration17To18Test` (new) · `everyV17MaterialLineSurvivesWithANullSupplyId` (several fictional profile and event lines, every column equal); `theThreeTablesExistEmptyWithTheirIndices`; `theMigratedSchemaEqualsAFreshVersion18` | a backfill `UPDATE consumable_usage SET supply_id = ''`, or `DEFAULT ''` on the column |
| 2 | C7 the whole chain | the six table-set pins and `ReferenceMigrationTest.kt:73-79`'s V18 arm (`supply_id` a known NULL delta) | none: pins |
| 3 | C5 the constraints | `T/data/room/SupplyDaoConstraintTest` (new) · `theTripleIsUnique`; `deletingAnAssetCascadesItsApplicability`; `aSupplyItemNamedByApplicabilityCannotBeDeleted` (raw SQL, RESTRICT); `specificationKeysAreUniquePerItem`; `theUpsertReplacesTheSpecificationList` | the `UNIQUE(asset_id, supply_id, role)` index dropped from the entity |
| 4 | C5 the mappers carry the link | `T/data/room/JournalDaoTest` (+1) · `aLinkedLineRoundTripsThroughRoomOnAProfileAndAnEvent` | `JournalMappers` drops `supplyId` |
| 5 | C6 the version agreement | `VersionAgreementTest:84`, `:153`; `MaintenanceRoutesTest:1505` → 18 | none: pins |
| 6 | C8/C9 the round trip | `CT/backup/BackupFormat18Test` (new) · `everySupplyRowSpecificationAndLinkRoundTrips` (`"supplyId": null` written explicitly on an unlinked line) | `toDto` omits `supplyId` |
| 7 | C4/C8 the fence, structurally | `theSupplyDtosCarryExactlyTheseKeys` (the three DTOs' descriptor names pinned, in order) | a `trackStock` field added to `SupplyItemDto` |
| 8 | C9 the ≤ 17 gate | `aFormat17ArchiveDecodesWithNoSupplies`; `aFormat17ArchiveCarryingSupplyItemsIsCorrupt`; `…CarryingAssetSuppliesIsCorrupt`; `aFormat17LineCarryingALinkIsCorruptNamingItsRow`; `anExplicitNullLinkInAFormat17ArchiveIsAccepted` | the gate removed |
| 9 | C9 the graph | `aDanglingLineLinkIsCorrupt`; `applicabilityNamingAnUnknownAssetIsCorrupt`; `…AnUnknownSupplyItemIsCorrupt`; `aSpecificationIdUsedByTwoItemsIsCorrupt`; `aLinkOrRowNamingAnArchivedItemIsValid` | the line-link resolution check removed |
| 10 | C9 the content | `aBlankSpecLabelOrValueIsCorrupt`; `aSpecKeyOutsideThePatternIsCorrupt`; `aDuplicateSpecKeyIsCorrupt`; `anUncleanedRoleIsCorrupt`; `aDuplicateTripleIsCorrupt` | the role-cleaning check removed |
| 11 | C9 sort and counts | `specificationsAreWrittenInSortOrderThenId`; `theManifestCountsBothLists` | the specification sort dropped |
| 12 | C9 newer refused | `aFormat19ArchiveIsRefusedAsNewer` (the shipped `BackupNewerFormat`) | none: pin |
| 13 | C10 export and replace | `ExportBackupSetTest` (+1) · `anExportCarriesSuppliesApplicabilityAndLinks`; `ImportBackupReplaceTest` (+1) · `aReplaceRestoresEverySupplyRowAndLinkByteEqual` | the export omits `assetSupplies` |
| 14 | C9 the moving format pins | §3's pins table, B2a's rows | none: pins |
| 15 | C11 a SupplyItem by id | `CT/merge/MergePlannerSupplyTest` (new) · `theSameItemIsIdentical`; `aRenamedItemIsAConflict`; `aSpecOnlyDifferenceIsAConflict`; `anArchiveOnlyDifferenceIsAConflict`; `aNewItemInserts` | the comparison drops `specifications` (a spec-only edit becomes `IDENTICAL`) |
| 16 | C11 the child id | `aSpecIdHeldByAnotherItemIsChildRowIdTaken`; `…claimedEarlierInThisPlan…` | the specification `firstTaken` removed |
| 17 | C11 applicability | `anAbsentAssetIsOwnerNotAvailable`; `anAbsentItemIsOwnerNotAvailable`; `theSameTripleUnderAnotherIdEqualInEveryFieldIsIdenticalWithItsReason`; `theSameTripleUnderAnotherIdWithOtherStampsIsSkippedWithItsReason` (nothing written either way); `aReRoledRowIsAConflict` | the triple arm removed (the second id plans `INSERT`) |
| 18 | C11 the line-link arm | `aProfileLineNamingAnUnknownItemIsOwnerNotAvailable`; the event twin; `aLineNamingANewItemRefusedForChildRowIdTakenIsASecondConflict` | the arm removed |
| 19 | C11 the planning position (C-3) | `MergePlannerSupplyTest` · `aProfileLineNamingAnItemThisPlanInsertsInserts`; the event twin | the SupplyItem section planned after profiles (placed last → a new SupplyItem with a linked line plans `CONFLICT OWNER_NOT_AVAILABLE`) |
| 20 | C11 the write order on Room | `T/api/ApiRouterTest` (+1) · `aMergeInsertingItemsAndTheirApplicabilityCommits` (the Room-backed `FakeGraph`; B2b confirms the shipped import-merge case to copy) | applicability written before the SupplyItems (the FK fails) |
| 21 | C11 tally and report | the tally pins (`MaintenanceRoutesTest:1488`, `:1541`, `:1551`, 20 → 22) and one new assertion naming both tables | none: pins |
| 22 | C12 pre-18, linked rows | `MergePlannerSupplyTest` · `` `a format-17 archive against a profile later linked is identical` ``; the event twin | the exception removed (→ `CONFLICT`) |
| 23 | C12 other fields still count | `` `a format-17 archive against a linked profile also renamed is a conflict` ``; the event twin | every field but the lines dropped from the comparison |
| 24 | C12 given then removed (limit 3) | `` `a format-17 archive against a profile linked then unlinked is a conflict` `` | `updatedAt` ignored whenever the archive is older |
| 25 | C12 format 18 | `` `a format-18 line linked here and unlinked there is a conflict` ``; `` `the same link is identical` `` | the exception applied to format 18 too (→ `IDENTICAL`) |
| 26 | C13 classification | `TransferTableClassificationTest:25-31` · the two new lists classified | none: pin (the class's own purpose) |
| 27 | C13 selection by naming row | `CT/transfer/TransferGraphTest` (+2) · `aPackCarriesOnlyTheSupplyItemsItsRowsName` (an unrelated item stays home); `anItemNamedOnlyByACarriedLineTravels` | `inUse` built from applicability rows only |
| 28 | C13 retain | `retainDropsAHeldAssetsApplicabilityAndKeepsEveryItem` | `supplyItems` filtered by the held set |
| 29 | C13 the pack end to end | `ImportTransferPackTest` (+1) · `aHeldAssetsSuppliesAndLinksArriveThroughThePack` | none of its own: row 27's mutation fails it (recorded, not counted twice) |
| 30 | C14 the guard | `HeldWriteGuardTest` (+3) · `addingApplicabilityToAHeldAssetThrows`; `removingAHeldAssetsApplicabilityThrows`; `archivingAnItemAHeldAssetUsesIsAllowed` | the guarded `AssetSupplyRepository` wrapper skips its held check (a core-side mutation; the test builds its own guarded ports, `:180-190`, so `AppGraph`'s wiring is mirrored by `FakeGraph`, not proven — N-10) |
| 31 | C15 save and collected problems | `CT/usecase/SupplyItemUseCasesTest` (new) · `createStoresTrimmedFieldsAndOrderedSpecs`; `aBlankNameIsRefusedAndNothingIsWritten` (no transaction, no id minted); `everyRowProblemIsCollectedInOneCall` | fail-fast on the first problem |
| 32 | C15 the key rule (R15-11) | `aBlankKeyIsTheLabelsSlug`; `twoLabelsWithOneSlugAreDeduped`; `anUnsluggableLabelGetsSpec`; `aTypedKeyOutsideThePatternIsRefused`; `aTypedKeyTakenIsRefused`; `aKeptRowKeepsItsKeyWhenItsLabelChanges` | the key re-derived on every save (the kept-key case fails) |
| 33 | C15 child ids | `aKeptRowKeepsItsId`; `anIdThisItemDoesNotOwnIsMintedFresh` | any sent id accepted |
| 34 | C15 `Unchanged` | `anEditChangingNothingWritesNothing` (`updatedAt` held, no transaction) | the save always writes |
| 35 | C16 archive | `archiveAndUnarchiveWriteOneColumn`; `archivingLeavesApplicabilityAndLinksIntact` | archiving deletes the item's applicability |
| 36 | C17 add, in step order | `CT/usecase/AssetSupplyUseCasesTest` (new) · `anUnknownAssetIsOwnerMissing`; `anUnknownItemIsSupplyItemMissing`; `anArchivedItemIsRefusedForANewRow`; `aBlankRoleIsRoleRequired`; `theRoleIsStoredCleaned` (no-break space, zero-width space, doubled spaces); `theSameCleanedTripleIsTaken`; `aChildAssetTakesApplicability` | the raw role stored |
| 37 | C37 no inference | `CT/usecase/SupplyLinkInferenceTest` (new) · a line named exactly as a SupplyItem stays unlinked through `SaveProfile`, `LogEvent`, `ApplyTemplate`, a merge and a replace import | a name matcher (`supplyItems.firstOrNull { it.name == line.name }?.id`) in `SaveProfile` |
| 38 | C17 update, remove, suggestions | `reRoleMovesUpdatedAt`; `theSameCleanedRoleIsUnchanged`; `reRoleOntoATakenTripleIsTaken`; `anArchivedItemsRowMayBeReRoled`; `removeDeletes`; `removeOfAnUnknownRowIsRefused`; `suggestionsAreTheDistinctCleanedRolesInUse` | `Unchanged` compares the raw role |
| 39 | C19 the link is stored and carried | `CT/usecase/ProfileUseCasesTest` (+3) · `aLinkedLineIsStored`; `anEditCarryingTheLinkKeepsIt`; `anEditWithoutTheLinkClearsIt` (full replace, limit 1); `EventUseCasesTest` (+2) · `logEventStoresTheLink`; `aResequencedEditKeepsEachLinkWithItsRow` | `SaveProfile` drops `input.supplyId` |
| 40 | C20 resolution and its three arms (C-2) | `anUnknownSupplyIdIsRefusedAndNothingIsWritten` (profile; event; `CompleteSchedule` with a line); `anArchivedItemMayBeLinked`; `ProfileEditViewModelTest` (+1, B4b) · `anUnknownSupplyItemMarksItsRowWithP15_20`; `EventEntryViewModelTest` (+1, B4b) · `anUnknownSupplyItemDrawsCannotSaveAndMarksNoRow` | the resolution check removed |
| 41 | C20 the snapshot survives | `renamingEditingOrArchivingAnItemLeavesEveryPastLineByteEqual` | `SaveSupplyItem` rewrites linked lines' names (a "sync") |
| 42 | C21 what stays unchanged | `ApplyTemplateTest` (+1) · `templatesCreateNoLinkAndNoSupplyItem`; `ReplaceAssetTest` (+1) · `theSuccessorsClonedQuickActionsKeepTheirLinksAndNoApplicabilityIsCopied` | `ReplaceSetup` clones lines with `supplyId = null` |
| 43 | C19 the view models carry the link | `EventEntryViewModelTest` (+3) · `aProfileChipCarriesItsLink`; `anEditedEventKeepsItsLinksOnSave`; `editingTheNameKeepsTheLink`; `ProfileEditViewModelTest` (+2) · `aLoadedLinkIsSavedBack`; `anAddedRowIsUnlinked` | `addSuggested` drops the link |
| 44 | C24 the request keys (B4b) | `T/api/ApiRouterTest` (+2) · `aProfileLineWithSupplyIdIsStoredAndAnswered`; `anEventLineWithSupplyIdRoundTrips`; `MaintenanceRoutesTest` (+1) · `aCompletionLineCarriesItsLink` | `ConsumableRequest.toCommand` drops `supplyId` |
| 45 | C18 AC11, AC2, AC7 | `CT/usecase/SupplyItemFixtureTest` (new) · as C18 | the merge apply skips `assetSupplies` writes |
| 46 | C22 create and read | `T/api/SupplyRoutesTest` (new) · `aPostIs201AndReadsBack`; `theListIsOrderedAndIncludesArchived`; `getAnswersTheItemAndItsApplicability` | the list left unsorted |
| 47 | C22 the PATCH overlay and the clearing rule (C-7) | `aPatchWithOnlyNotesLeavesEverythingElse`; `aNullKeyIsUnchanged`; `anEmptyStringClearsAnOptionalField`; `aBlankNameIs422`; `anAbsentSpecificationsKeepsTheRowsTheirIdsAndKeys`; `aGivenListReplacesItWholly`; `anEmptySpecificationsListRemovesThem`; `aNoOpPatchIs200TheStoredRowAndWritesNothing` | PATCH as a full replace (an absent key becomes `""`), and `""` read as unchanged |
| 48 | C22 archive | `archiveThenUnarchive`; `anUnknownIdIs404NoSuchSupplyItem` | the route passes `!archived` |
| 49 | C2 every code | `everySupplyItemProblemHasItsOwnCode` and `everyAssetSupplyProblemHasItsCodeStatusAndField` (the mappers called directly, one case per sealed member); `everyNewCodeIsReachableOverTheWire` | `SupplyItemArchived` mapped to 422 |
| 50 | C23 applicability | `aPostIs201`; `aTakenTripleIs409`; `anArchivedItemIs409WithFieldSupplyId`; `aPatchReRoles`; `aDeleteIs204ThenGone`; `aHeldAssetIs409AssetTransferredOut`; `theAssetSubResourceListsRowsAndEachItemOnce` | the sub-resource repeats an item per row |
| 51 | C24 the line refusal | `aLineNamingNoItemIs422ProfileValidationWithFieldConsumables`; the `EVENT_VALIDATION` twin | mapped to a 404 |
| 52 | C25 status | the status test (+1) · `statusCountsBothNewLists` | `counts` omits `assetSupplies` |
| 53 | C25 the document agrees | `SupplyRoutesTest` · `everyNewCodeIsInV1md` (each C2 row as `^\| (404\|409\|422) \| `CODE` \|`) | a code missing from `v1.md` |
| 54 | C26 the tools | `M/tests/test_supply_tools.py` (new) · each tool's recorded method, path and body; `update_supply_item` sends only given keys; `update_supply_item_sends_an_empty_string_to_clear` and `…_an_empty_list_to_remove_specifications`; `set_asset_supply` creates without an id and re-roles with one; no delete tool for an item | `update_supply_item` sends every key |
| 55 | C27 the tool gate | `…_refuses_schema_17_with_nothing_sent` for all eight (only `/v1/status` read) | the gate removed from one tool |
| 56 | C27 link-only gating | `test_maintenance_tools.py` / `test_tools.py` (+5) · `save_profile_with_a_link_refuses_schema_17`; `log_event_with_an_explicit_null_supply_id_refuses_schema_17` (presence, N-7); `log_event_without_a_link_reaches_schema_17`; `kept_consumables_keep_supply_id_as_read`; `a_schema_17_row_rebuild_sends_no_supply_id_key` | `kept_consumables_from` drops `supplyId` |
| 57 | C28 pins | 76 → 84 (`test_argument_guard.py`, `test_tools.py:118-122`'s `EXPECTED_TOOLS`, `test_reference_tools.py:64-65`); `test_tools.py:803-815` → "format 1–18" / "format **1–18**", "the twenty-two tables" | none: pins |
| 58 | C29 the shell | **Compose** `AT/ui/maintenance/MaintenanceShellTest` (grown) · the fifth row reads P15-1 and opens the list | none in-brief: a device case |
| 59 | C30 list and detail state | `T/ui/supplies/SupplyListViewModelTest` (new) · `rowsAreOrderedAndArchivedOnesMarked`; `SupplyDetailViewModelTest` (new) · `usedByListsEachRowWithItsAssetName`; `archiveToggles` | archived rows filtered out of the list |
| 60 | C31 the editor state | `T/ui/supplies/SupplyEditViewModelTest` (new) · `canSaveFollowsTheTrimmedName`; `rowsKeepTheirIdsAndKeys`; `aValidationNamingARowMarksItAndDrawsP15_12`; `cancelWritesNothing` | `canSave` ignores trimming |
| 61 | C30/C31 drawn | **Compose** `AT/ui/supplies/SupplyScreensTest` (new, ~5 cases) · list rows, badge and empty line; detail sections and empty lines; editor Save enabling, add/remove a spec row, P15-12 drawn | none in-brief: device cases |
| 62 | C32/C33 the section | `T/ui/supplies/AssetSuppliesSectionViewModelTest` (new) · `thePickerOffersUnarchivedOnly`; `addPassesTheCommand`; `takenDrawsP15_18`; `everyGoneArmDrawsP15_20`; `readOnlyOffersNothing`; `removeCallsRemove`; `unchangedClosesAsSaved` | the picker list includes archived items |
| 63 | C34/C35 the line controls | `ProfileEditViewModelTest` (+3) · `aPickFillsOnlyABlankNameAndUnit`; `unlinkClearsOnlyTheLink`; `thePickerOffersUnarchivedOnly`; `EventEntryViewModelTest` (+1) · `removeLinkClearsOnlyTheLink` | a pick overwrites a typed name |
| 64 | C32–C35 drawn | **Compose** `AT/ui/supplies/SupplySurfacesTest` (new, ~6 cases) · the section's rows, empty line and add glyph; read-only draws no glyph or overflow; the role sheet's Save enabling and Cancel writing nothing; the picker's empty sentence; `SupplyLinkLine` linked, unlinked and archived | none in-brief: device cases |
| 65 | C36 the documents | `ReleaseProofPolicyTest` unchanged and green; B9's anchored greps (§7) | none: tripwire |
| 66 | C37 the fence | the tripwire, word and untouched greps (§7) at every brief | none: greps |
| 67 | C13 the return keeps applicability (C-4) | `ImportTransferPackTest` (+1, B2c) · `aReturningPackWithAReRoledSuppliesRowAppliesAndEveryRowLands` (a held asset with applicability comes back, one row re-roled on the borrowing phone; the apply succeeds, no row lost) | the `assetSupplies` filter removed from `ReturnScope.of` (the return refuses `CONFLICT`) |

**Moving pins — each named shipped assertion, the brief that may touch it, and why.** A brief touches no other
shipped assertion (the pin rule, "Briefs — common").

| pin | brief | moves, because |
|---|---|---|
| `T/VersionAgreementTest.kt:84`, `:153`; `T/api/MaintenanceRoutesTest.kt:1505` | B1 | the schema is 18 |
| `Migration10To11Test.kt:101`, `Migration11To12Test.kt:167`, `Migration12To13Test.kt:150`, `Migration13To14Test.kt:109`, `Migration14To15Test.kt:114`, `ReferenceMigrationTest.kt:104-105` | B1 | the whole chain's table set gains three tables |
| `ReferenceMigrationTest.kt:73-79` | B1 | it seeds both line tables and compares whole rows: `supply_id` is a known NULL delta |
| the test constructor sites of `ProfileConsumable(` (5, in 5 files) and `ConsumableUsage(` (5, in 4 files) | B1 | the domain field has no default; each passes the `null` it means |
| the 14 format literals (`VersionAgreementTest.kt:85`, `:154`; `MaintenanceRoutesTest.kt:1159`, `:1506`, `:1553`; `BackupCodecTest.kt:1063`; `BackupFormat13Test.kt:54`, `14Test.kt:48`, `15Test.kt:48`, `6Test.kt:358`, `9Test.kt:70`; `ExportBackupSetTest.kt:47`; `Format7ImportIdentityTest.kt:245`; `AT/backup/Format7RestoreContractTest.kt:164`) | B2a | the format is 18 |
| `BackupFormat8Test.kt:277`, `:280`, `:281`; `BackupFormat17Test.kt:218`, `:222`, `:223` (and their KDoc) | B2a | the "one format past this build" archive is 18, readable now: it becomes 19 |
| `BackupFormat6Test.kt:287` | B2a | the archive's list count is 22 (the event field count does **not** move: the key sits on the child DTOs) |
| `MaintenanceRoutesTest.kt:1488`, `:1541`, `:1551`; `:1194-1215` | B2b | the tally names 22 tables; the report's wire key order gains the two new keys at its end |
| `TransferTableClassificationTest.kt:25-31`; `HeldWriteGuardTest.kt:186` (and any port list beside it) | B2b/B2c | two lists classified; one more wrapped port |
| the test sites of `ProfileConsumableInput(` (7, in 1 file) and `ConsumableInput(` (4, in 2 files) | B4a | the input field has no default |
| `T/api/ReferenceRoutesTest.kt:~642-670` | B5 | the exact `/v1/status` `counts.keys` set gains `supplyItems` and `assetSupplies` |
| `T/api/CommandShapesGoldenTest.kt:132-172` | B5 | `v1.md`'s import range reads 1–18 and the tally 22 tables ("Format17AndTwentyTables" renamed); `:44-60` does **not** move (R15-16) |
| `M/tests/test_argument_guard.py:51`, `:203-204`, `:256-258`; `M/tests/test_tools.py:118-122`, `:803-815`; `M/tests/test_reference_tools.py:64-65` | B6 | 84 tools; the docstring and README read 1–18 and twenty-two tables |
| `AT/ui/maintenance/MaintenanceShellTest.kt` (one case grows) | B7 | the fifth row |
| `T/VersionAgreementTest.kt:432` | — | **does not move**: three primary destinations (C29) |

**Device rows.** No device-boundary class: #15 crosses no Android or OS boundary — no intent, grant, permission,
channel, picker or second UID (planning policy, `docs/superpowers/planning-policy.md:61-91`). Every rule — the
migration, codec, merge, pack, guard, use cases, routes, MCP, and each view model's state (rows 43, 59, 60, 62, 63) —
is JVM-proven first. What remains is Compose drawing an already-proven state: **two new Compose classes**
(`SupplyScreensTest` ~5 cases, `SupplySurfacesTest` ~6 cases) and **one grown case** (`MaintenanceShellTest`), the
new screens having no shipped host to grow (no Compose class hosts `ProfileEditScreen` or `EventEntryScreen` today).
`Format7RestoreContractTest` moves one literal. **Gate growth:** 55 → **57** device classes, 283 → ~295 device tests; at
#93's merged-tip record (11.65 min, `.superpowers/sdd/2026-10-01-issue-93/progress.md`) two small classes are
estimated at +0.5–1.0 min → **~12.2–12.7 min**, under the 14- and 15-minute reporting lines; #90's trigger is not
reached. **Known cost:** the new Compose cases first run at the merged-tip gate, so a red there costs one fix round
after the merge (accepted, as #91's N-10); and the new Supplies section above Components shifts the asset-detail
layout under shipped device classes — `ComponentsSmokeTest`, the `AssetDetail*Test` classes and
`ReferencesSectionTest` — whose fallout is first seen at that gate too (N-12).

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1 | `C/model/{Ids,SupplyItem (new),Journal}.kt`; `C/ports/Repositories.kt`; the four placeholder sites (C19); `A/data/room/entities/{SupplyEntities (new),JournalEntities}.kt`; `A/data/room/dao/SupplyDaos.kt` (new); `A/data/room/{SupplyRepositories (new),SupplyMappers (new),JournalMappers,Migrations,AppDatabase}.kt`; `A/di/AppGraph.kt`; `app/schemas/…AppDatabase/18.json` (generated); `T/data/room/{Migration17To18Test (new),SupplyDaoConstraintTest (new),MigrationTestSupport,JournalDaoTest}.kt` and B1's pins | `C/backup` (bar two placeholders), `C/merge`, `C/transfer`, `C/usecase` (bar two placeholders and `ApplyTemplate.kt:102`), `A/api`, `A/ui`, `docs`, `tools`, `17.json` |
| B2a | `C/backup/{BackupFormat,BackupCodec,BackupContentCheck}.kt`; `C/usecase/{ExportBackupSet,ImportBackupReplace}.kt`; **constructor arguments only (C-1):** `A/di/AppGraph.kt` (the two use cases' and `BackupRepositories`' wiring), `T/testing/FakeGraph.kt`, `CT/testing/BackupInstall.kt` and the ~10 test construction sites `git grep -nE 'ExportBackupSet\(\|ImportBackupReplace\(\|BackupRepositories\('` names; `CT/backup/BackupFormat18Test.kt` (new); `ExportBackupSetTest`, `ImportBackupReplaceTest` (+1 each); B2a's pins | `C/merge`, `C/transfer`, `C/usecase` (else), `A/**` main bar `AppGraph`'s wiring, `docs`, `tools` |
| B2b | `C/merge/{MergePlan,MergePlanner}.kt` (with `MergeSnapshot` and `mergeSnapshotOf`); `C/usecase/{ApplyBackupMergePlan,BuildBackupMergePlan}.kt` (the writes; the snapshot call, `:164`, `:98`); **constructor arguments only:** `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` and the test sites `git grep -nE 'ApplyBackupMergePlan\(\|BuildBackupMergePlan\(\|mergeSnapshotOf\('` names; `A/api/ApiDtos.kt` (the report rows only, `:167-240`); `CT/merge/MergePlannerSupplyTest.kt` (new); `T/api/ApiRouterTest.kt` (row 20 only); B2b's pins | `C/backup`, `C/usecase` (else), `A/ui`, `A/data`, `DuplicateCandidateDto`, `docs`, `tools` |
| B2c (split) | `C/transfer/{TransferGraph,TransferOwnership,HeldWriteGuard}.kt`; `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope.of` only, C-4); `A/di/AppGraph.kt` (the guard's port and the "sixteen" at `:284`); `T/testing/FakeGraph.kt` (the guarded port); `CT/transfer/{TransferGraphTest,ImportTransferPackTest,HeldWriteGuardTest,TransferTableClassificationTest}.kt` | `C/backup`, `C/merge`, `C/usecase` (else), `A/api`, `A/ui`, `docs`, `tools` |
| B3 | `C/usecase/{SupplyItemCommands (new),SaveSupplyItem (new),ArchiveSupplyItem (new),AssetSupplyCommands (new),AddAssetSupply (new),UpdateAssetSupply (new),RemoveAssetSupply (new),AssetSupplyRoles (new)}.kt`; `A/di/AppGraph.kt` (wiring); the test `FakeGraph` if it builds use cases; `CT/usecase/{SupplyItemUseCasesTest,AssetSupplyUseCasesTest}.kt` (new) | `C/backup`, `C/merge`, `C/transfer`, every shipped use case, `A/api`, `A/ui`, `docs`, `tools` |
| B4a | `C/usecase/{ProfileCommands,SaveProfile,EventCommands,LogEvent,UpdateEvent,CompleteSchedule,CompleteGroupMembers,RecordConditionWithIncident}.kt` (C19–C20; the last five and `SaveProfile` a constructor parameter); `A/api/ValidationRefusals.kt` (the two arms, C-2); `A/api/{ApiDtos,MaintenanceDtos}.kt`, `A/ui/journal/EventEntryViewModel.kt`, `A/ui/setup/ProfileEditViewModel.kt` (the five input placeholders; `asProblems`' arm); `A/ui/supplies/SupplyStrings.kt` (new: P15-20); `A/di/AppGraph.kt`; `T/testing/FakeGraph.kt` and the ~12 test construction sites (C20); `CT/usecase/{ProfileUseCasesTest,EventUseCasesTest,ApplyTemplateTest,ReplaceAssetTest}.kt`; B4a's pins | `ApplyTemplate.kt`, `ReplaceSetup.kt`, `ScheduleCommands.kt`, `CompletionFlow.kt`, `SeedTemplates.kt`, any `Screen.kt`, `docs`, `tools` |
| B4b | `A/api/{ApiDtos,MaintenanceDtos}.kt` (the two keys and three mappings only); `A/ui/journal/EventEntryViewModel.kt`, `A/ui/setup/ProfileEditViewModel.kt` (row state only); `CT/usecase/{SupplyLinkInferenceTest (new),SupplyItemFixtureTest (new)}.kt`; `T/api/{ApiRouterTest,MaintenanceRoutesTest}.kt` (row 44 only); `T/ui/journal/EventEntryViewModelTest.kt`, `T/ui/setup/ProfileEditViewModelTest.kt` | `C/**` main, `AppGraph`, `FakeGraph`, any `Screen.kt`, `docs`, `tools` |
| B5 | `A/api/{SupplyDtos (new),SupplyHandlers (new),ApiRouter,ApiHandlers,ApiJson}.kt` (production builds `ApiHandlers` through its `constructor(graph)`, `ApiHandlers.kt:174`, so `AppGraph` is not touched); the 8 test construction sites of `ApiHandlers`' primary constructor (`VersionAgreementTest:121`, `T/ui/api/DeveloperApiViewModelTest:47`, `ApiRouterTest:51`, `:627`, `MaintenanceFixtures:155`, `AttachmentUploadRoutesTest:114`, `MaintenanceCommandShapeTest:47`, `LoopbackApiServerTest:65`), arguments only; `docs/api/v1.md`; `T/api/{SupplyRoutesTest (new),CommandShapesGoldenTest,ReferenceRoutesTest}.kt` | `C/**`, `A/ui/**` main, `A/nav`, `A/data`, `A/di`, `FakeGraph`, `tools`, `docs/api/command-shapes.json` |
| B7 | `A/ui/supplies/{SupplyListScreen,SupplyDetailScreen,SupplyEditScreen,SupplyListViewModel,SupplyDetailViewModel,SupplyEditViewModel}.kt` (new); `A/ui/maintenance/MaintenanceScreen.kt`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `T/ui/supplies/*` (new); `AT/ui/supplies/SupplyScreensTest.kt` (new); `AT/ui/maintenance/MaintenanceShellTest.kt` | `A/api/**`, `docs/**`, `C/**`, `A/ui/asset`, `A/ui/setup`, `A/ui/journal`, `tools` |
| B6 | `M/src/servicetag_mcp/server.py`; `M/README.md`; `M/tests/{test_supply_tools (new),test_maintenance_tools,test_tools,test_argument_guard}.py` | `app/**`, `core/**`, `S/**`, `M/src/servicetag_mcp/command_shapes.py`, `docs` |
| B8 | `A/ui/supplies/{SupplyItemPicker,AssetSuppliesSection,AssetSuppliesSectionViewModel,SupplyLinkLine}.kt` (new); `A/ui/asset/AssetDetailScreen.kt` (the call site and the `onOpenSupply` parameter, `:184-206`); `A/ui/nav/ServiceTagRoot.kt` (the `onOpenSupply` wiring line, `:200`); `A/ui/setup/{ProfileEditScreen,ProfileEditViewModel}.kt`; `A/ui/journal/{EventEntryScreen,EventEntryViewModel}.kt`; `T/ui/supplies/AssetSuppliesSectionViewModelTest.kt` (new); `T/ui/setup/ProfileEditViewModelTest.kt`; `T/ui/journal/EventEntryViewModelTest.kt`; `AT/ui/supplies/SupplySurfacesTest.kt` (new) | `C/**`, `A/api`, `A/data`, `EventDetailScreen.kt`, `JournalFormat.kt`, `CompletionFlow.kt`, `docs`, `tools` |
| B9 | `docs/release-proofs.md`; `README.md` (`:54`); `docs/design/14-asset-model.md` (new, R15-14); `docs/design/04-domain-data-model.md` (the two-line note) | any `.kt`, `.py`, `tools`, `docs/api` |

**B5 ∥ B7 — the one parallel pair.** B5's files are `A/api/**`, `docs/api/v1.md` and `T/api/**`; B7's are
`A/ui/supplies/**`, `A/ui/maintenance/MaintenanceScreen.kt`, `A/ui/nav/{Route,ServiceTagRoot}.kt`, `T/ui/supplies/**`,
`AT/ui/supplies/SupplyScreensTest.kt` and `AT/ui/maintenance/MaintenanceShellTest.kt` — **no file in common**, and
both only consume what B1–B4a wired into `AppGraph` and `FakeGraph` (neither edits them). Under the owner's two-lane rule B7a → B7b may run in a
second worktree off B4b's tip while B5 runs on the branch; the controller lands B7's commits before B6, and B6's
`<base>` is the tip holding both. Sequential execution is equally valid.

**Order:** B1 → B2a → B2b → B2c → B3 → B4a → B4b → { B5 ∥ (B7a → B7b) } → B6 → B8a → B8b → B9 (the four splits
are taken by plan; §11, §14, §17, §18). **Every `AppGraph` and `FakeGraph` edit lands in B1–B4a**, so B5 and B7 share
no file (C-1). B1 makes the types, tables and the
placeholders; B2a needs the domain; B2b needs `FIRST_SUPPLY_FORMAT` and the DTOs; B2c needs the lists; B3 needs the
ports and the guarded port; B4a needs `SupplyItemRepository`; B4b needs B4a and B3's fixture pieces; B5 needs B3's use
cases and B4a's problems; B7 needs B3's use cases; B6 needs B5's routes; B8 needs B7's routes, B3's applicability and B4b's row state;
B9 needs everything it describes.

**Gate budget** at the merged tip (estimates; B1 records the base's exact counts — #93's merged tip: core 1696, app
1757, MCP 473, loader 154, 55 device classes): core +~80 (model/format 18, merge 18, pack/guard 7, use cases 25,
linkage 12); app +~50 (migration and DAO 10, routes 22, view models 18); MCP +~25; loader 154 unchanged; device
classes **57** (two new, ~11 cases; one case grown). The whole gate timed against #93's record, reporting only, no
rerun-until-green.

## 5. Strings

**RATIFIED (owner, 2026-10-01) — the whole block as proposed: P15-1…23 (with "Part number" as a product-identity
field label), the reused strings, G1–G3 and D1, wording unchanged from rev 1.** P15-20 is also the profile editor's
`UnknownSupplyItem` sentence (C-2: the same meaning, reused verbatim; no P15-24). **New phone strings — every one PROPOSED (the largest set since 1.4).** Each is declared once
as a `const val` in its owning file and imported, never copied (the `ROLE_HEADER` rule).

| id | proposed wording | where (contract) |
|---|---|---|
| P15-1 | "Supplies" | the Maintenance row, the list's title, the asset-detail section header (C29, C30, C33) |
| P15-2 | "Supply item" | the list's add button and the editor's title (C30, C31; the `MAINTENANCE_GROUP` precedent) |
| P15-3 | "No supplies yet." | the list, empty (C30) |
| P15-4 | "Part number" | the editor field and the detail fact (C31; the one "part" word, R15-2) |
| P15-5 | "Preferred unit" | the editor field and the detail fact (C31) |
| P15-6 | "Specifications" | the detail's and the editor's section header (C30, C31) |
| P15-7 | "Add specification" | the editor's row button (C31) |
| P15-8 | "Remove specification" | a spec row's close glyph, accessibility label (C31) |
| P15-9 | "No specifications" | the detail, none (C30) |
| P15-10 | "Used by" | the detail's applicability section header (C30) |
| P15-11 | "Not used by any asset" | the detail, no applicability row (C30) |
| P15-12 | "Each specification needs a label and a value." | the editor, under the rows, after a refused save (C31) |
| P15-13 | "Add supply" | the asset section's add glyph, accessibility label, and the add sheet's title (C33) |
| P15-14 | "No supplies" | the asset section, empty (C33; the "No components" shape) |
| P15-15 | "Choose a supply" | the picker's title (C32) |
| P15-16 | "No supply items yet. Add one under Maintenance › Supplies." | the picker, empty (C32) |
| P15-17 | "Edit role" | the asset row's overflow item and the sheet's title (C33) |
| P15-18 | "This asset already has that supply in that role." | the role sheet, `Taken` (C33) |
| P15-19 | *(reserved — not used: a blank role disables Save, so no sentence is drawn)* | — |
| P15-20 | "That supply item is no longer available." | the role sheet or editor, a gone row (C31, C33) |
| P15-21 | "Link supply" | an unlinked Materials row on the quick-action editor (C34) |
| P15-22 | "Linked to %s" — `%s` the SupplyItem's name | a linked Materials row on both editors (C34, C35) |
| P15-23 | "Remove link" | the linked row's action (C34, C35) |

**Reused verbatim from their one home, approved unchanged on the new surfaces (to confirm with the ratification):**

| string | home | new surface |
|---|---|---|
| "Name" | `A/ui/maintenance/GroupEditScreen.kt:94` | the editor (C31) |
| "Category", "Manufacturer", "Model" | `CATEGORY_FIELD`, `MANUFACTURER_FIELD`, `MODEL_FIELD`, `A/ui/asset/AssetEditScreen.kt:251`, `:230`, `:233` | the editor and the detail facts (C30, C31) |
| "Notes" | `NOTES_LABEL`, `A/ui/attachments/AttachmentEditSheet.kt:41` | the editor and the detail (C30, C31) |
| "Label" | `A/ui/setup/DefinitionEditScreen.kt:169` | a spec row (C31) |
| "Value" | `A/ui/components/InstrumentRow.kt:138` | a spec row (C31) |
| "Unit" | `A/ui/setup/ProfileEditScreen.kt:394` | a spec row (C31) |
| "Role" | `ROLE_HEADER`, `A/ui/attachments/AttachmentsSectionViewModel.kt:75` | the role sheet's field (C33) |
| "Archived" | `A/ui/maintenance/GroupListScreen.kt:96` | the list, the asset rows, the link line (C30, C33, C34) |
| "Edit", "Archive" / "Unarchive", "Back" | `A/ui/maintenance/GroupDetailScreen.kt:128`, `:131`, `:121` | the detail's top bar (C30) |
| "Save", "Cancel" | `GroupEditScreen.kt:77`, `:70` | the editor, the role sheet (C31, C33) |
| "More", "Remove" | `A/ui/settings/CategoriesScreen.kt:213`; `A/ui/references/ReferencesSection.kt:255` | the asset row's overflow (C33) |
| "Materials used", "Materials", "Material", "Qty", "Add material", "Remove material", "None recorded" | `EventEntryScreen.kt:317`, `ProfileEditScreen.kt:241`, `:374`, `:383`, `:255`, `:400`, `EventDetailScreen.kt:243` | unchanged; the link line sits under these rows (C34, C35) |

**Gaps and developer-facing texts (G-list, for the ratification with the P-list):**
- **G1** — each C2 code's `message`, in the shape of the shipped ones (`A/api/ApiJson.kt:282-283`): "no such supply
  item"; "no such applicability row"; "a supply item needs a name"; "every specification needs a label";
  "every specification needs a value"; "a specification key must be lower-case letters, digits and underscores,
  starting with a letter, at most 40"; "another specification of this supply item already has that key"; "the supply
  item was refused" (the fallback); "an archived supply item takes no new asset"; "an asset supply needs a role";
  "this asset already takes that supply item in that role".
- **G2** — the two line messages under the shipped families: "every supplyId in consumables must name a supply item"
  (`PROFILE_VALIDATION` and `EVENT_VALIDATION`).
- **G3** — the codec's `BackupCorrupt` messages (C9) and the MCP's `APP_SCHEMA_TOO_OLD` feature names ("supply items";
  "a supply link on a material line"), in the shipped templates.
- **D1** — `README.md:54`'s sentence becomes "…a canonical catalog for filters, batteries, belts, cartridges,
  chemicals, fluids and other service materials, with their specifications, the assets they fit, and
  replacement-on-cadence through ordinary maintenance schedules." (no #95 words; C36).

**Not drawn anywhere (decided by the contracts, no string needed):** a role-required sentence (Save disabled); a key
problem (no key field, the `"spec"` fallback); a name-required sentence (Save disabled); a delete confirmation (R15-5:
nothing deletes a SupplyItem; removing an applicability row is immediate).

## 6. Owner rulings (owner, 2026-10-01 — recorded on #15 and #76; every ruling DECIDED)

**DECIDED** rulings are binding as written; **controller default, owner did not object** rulings are binding the same
way; R15-13 is **CLOSED**; R15-15 was decided as recommended (#97 [LATER] filed, non-blocking). The HARD SCOPE sentence governs all.

| ruling | whose | the question, and the recommendation | where it lands |
|---|---|---|---|
| **R15-0** | owner — **DECIDED** | **The release vehicle.** **1.6.0 is being cut now from the schema-17 / format-17 line** (#92 + #91 + #93; branch `release-1.6.0`, in proofs); a schema-17 release tip and branch are preserved; **#15 targets 1.7.0 / schema 18 / format 18**, proceeds in parallel, and **does not merge to master before the 1.6.0 tag**. Each release carries one schema step and its own upgrade gate. | header, Global constraints, C36, §7 |
| **R15-1** (Q1) | owner — **DECIDED** as recommended | **The nouns and words.** Domain `SupplyItem`; tables `supply_item`, `supply_specification`, `asset_supply`; lists `supplyItems`, `assetSupplies`; routes `/v1/supply-items`, `/v1/asset-supplies`; wire key `supplyId`; phone words "Supplies" / "Supply item". Never "Part" or "Component" as the noun; "Part number" is the one allowed "part" word; not "consumables" (the clarification: a SupplyItem is not consumable-only). | C1, C3, §5 |
| **R15-2** (Q2) | owner — **DECIDED** as recommended | **The identity fields:** `name` (required), `category` (free text, not the Asset catalog), `manufacturer`, `model`, `partNumber` (one field for SKU / part number, label "Part number"), `preferredUnit`, `notes`, `archivedAt`, `createdAt`, `updatedAt`. **No URL** (URLs and resources are #69), **no price** (stock and procurement are #95). | C4, C5, C8, C31 |
| **R15-3** (Q3) | owner — **DECIDED** as recommended | **The role:** free text cleaned by `CategoryKey.display`, uniqueness exact on the cleaned text (limit 9), suggestions from roles in use, no role catalog or enum; **no notes on an applicability row** (D4 had one; the issue's AC4 key does not). | C4, C17, C33 |
| **R15-4** (Q4) | owner — **DECIDED** as recommended, **with the owner's precision** | **The linkage shape and the completion:** a soft nullable `supply_id` on both material-line types (no FK, no recreate), `name` and `unit` kept as the snapshot; the completion dialog unchanged. **Precision:** the plan, AC7's mapping and every document sentence **never** claim that a minimal schedule completion creates an event-level SupplyItem usage record — it writes no material line. AC7 reads: a replace-on-cadence item uses the ordinary scheduler / profile / event path, and the SupplyItem identity is retained on the profile's quick-action line and on any event line that carries it. | §1 AC7, limit 2; C4–C6, C18–C21, C25, C28; §8 |
| **R15-5** (Q5) | owner — **DECIDED** as recommended | **Archive-only:** a SupplyItem is never deleted (no use case, route, tool or button; D4's "Delete supply" superseded); `asset_supply.supply_id` RESTRICT. Applicability rows are removable **on the phone and over the API** (`DELETE /v1/asset-supplies/{id}`, `remove_asset_supply`) — configuration, not history. The `DELETE` route and the MCP tool stay (84 tools). | C16, C23, C26 |
| **R15-6** (Q6) | controller default, owner did not object | **An archived SupplyItem** stays on its Assets and its linked lines with the "Archived" badge, is left out of every picker, may still be named by a line (an edit re-sends existing links), is refused for a **new** applicability row (409), and can be unarchived. **A link always resolves**: no FK, so the use cases (C20), the codec (C9) and the planner (C11) hold it. | C2, C9, C11, C17, C20, C32 |
| **R15-7** (Q7) | controller default, owner did not object | **Divergence on merge:** the same id with different content is `CONFLICT` (every id-keyed row's rule) — which refuses a whole archive **or Transfer Pack** (limit 4); **no duplicate hint in the MVP** (C-5: the shipped carrier is asset-typed; limit 4a); the older-archive exception for link-only changes (C12). The alternative is the category rule (local wins, `SKIPPED`), which would silently keep a stale spec list. | C11, C12, C13 |
| **R15-8** (Q8) | owner — **DECIDED** as recommended | **Where the UI lives:** a fifth Maintenance row "Supplies" → list / detail (specs, "Used by") / editor, no reorder control; an asset-detail "Supplies" section before "Components" with add, edit role and remove; "Link supply" on the quick-action editor's Materials rows; the event form only shows and removes a link the chip carried; event detail and the journal row unchanged (limit 8); no stock or procurement UI anywhere. | C29–C35 |
| **R15-9** (Q9) | owner — **DECIDED** | **The loader:** no loader work in #15 (`S/` and the bundle tool untouched); the MCP and `/v1` populate the development phone. | C28, §1 |
| **R15-10** (Q10) | controller default, owner did not object | **Templates** stay name-only: applying one creates no SupplyItem and no link; #42 later emits SupplyItems explicitly. | C21 |
| **R15-11** (Q11) | controller default, owner did not object | **The spec key:** the definition slug rule (`KEY_PATTERN`, `slugify`, `dedupedKey`), unique per SupplyItem, kept across label edits, with **`"spec"` as the base when the label slugs to nothing**; the phone never shows a key. | C15, C31 |
| **R15-12** (Q12) | owner — **DECIDED** | **Older clients clear links (limit 1):** accepted and documented — the MCP ships with the app; **no per-line tri-state** (it could not protect event lines, matched by position). | C24, C27, limit 1 |
| **R15-13** | owner — **CLOSED** (done by the owner) | **The 2026-09-29 clarification's "How is it stocked/reordered?"** (`issue-15.md:204`, the snapshot this plan read): the owner corrected the comment on GitHub to point stocking and reordering at #95. Nothing in the plan changes. | §1 |
| **R15-14** | owner — **DECIDED differently** | **The asset-model page:** do **not** memorialize the six-bucket wording (it conflicts with the simplified #47 model). `docs/design/14-asset-model.md` records the **current architecture** — "Asset → nested Component → optional SupplyItem identity → maintenance usage/history" — and that a SupplyItem needs a #47 Component record only when fitted-instance or current-position history actually matters; worded without #95's words; plus the two-line D4 note. B9 owns it. | C36, §4, B9 |
| **R15-15** | owner — **DECIDED** as recommended (#97 [LATER] filed, non-blocking) | **#86's replace and applicability.** Replace does **not** copy applicability in the MVP; the cloned quick actions' lines already keep their links through `ReplaceSetup.kt:84`'s `.copy` as today; record it as limit 7; a later issue may add applicability to #86's reviewed carry-forward. **Cost if wrong:** after a replace the owner re-adds each Supplies row by hand (one add per row) until that issue lands; adding it later needs no schema or format change (new `asset_supply` rows for the successor), only a carry-forward option in the offer, the plan and apply digest input (`A/api/ReplaceDtos.kt:180-191`), a review row with its ratified strings and an MCP argument — about one brief. | C21, limit 7, row 42 |
| **R15-16** | controller default, owner did not object | **The API shape:** `PATCH /v1/supply-items/{id}` is an overlay with "absent or `null` = unchanged" (no tri-state: no nullable field); `PATCH /v1/asset-supplies/{id}` takes `role` only; the line refusals reuse the shipped `PROFILE_VALIDATION` / `EVENT_VALIDATION` codes; **no `command-shapes.json` entry** (R91-12's precedent). | C2, C3, C22–C25 |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin` (B2a, B7 and B8 edit `AT/` sources; every brief runs it); B6 `uv run --frozen
  pytest` in `M/` and in `S/` (unchanged, because the loader imports the MCP in process). No device run in any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **57 classes**, zero
  skips; rows 58, 61 and 64 run here for the first time — a red there is one post-merge fix round), R3 (the three
  Python suites), R5 (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps.
  `ReleaseProofPolicyTest` unchanged and green. Timed; the 14- and 15-minute lines are reporting only.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'^    const val FORMAT_VERSION = 18$'` in `C/backup/BackupCodec.kt` → 1; `'^    internal const val
    FIRST_SUPPLY_FORMAT = 18$'` → 1; `LegacyArchive.LAST_LEGACY_FORMAT` unchanged at 7 (`git diff <base>` of its file → empty).
  - `'^    version = 18,$'` in `A/data/room/AppDatabase.kt` → 1; `'^        const val SCHEMA_VERSION = 18$'` in
    `A/di/AppGraph.kt` → 1; `'MIGRATION_17_18'` in `AppGraph.kt` and `MigrationTestSupport.kt` → each equal to
    `'MIGRATION_16_17'`'s count there (2 and 1 at `fac0e951`); ``'ADD COLUMN `supply_id` TEXT'`` in `Migrations.kt` → 2;
    `'REFERENCES'` inside `MIGRATION_17_18` → only the two `CREATE TABLE`s' (read it); `18.json` present,
    `17.json` unchanged (`git diff <base> -- app/schemas/**/17.json` → empty).
  - `'^value class SupplyId\('` over `core/src/main` → 1 (the shipped two-line style, `Ids.kt:3-4`, N-1); `'^data class SupplyItem\('` → 1;
    `'fun delete'` in the SupplyItem port, DAO and repository → 0 (R15-5).
  - The nine named placeholder lines (C19) read, each no longer passing a literal `null` bar `ApplyTemplate.kt:102` (N-2).
  - `'SUPPLY_ITEMS, ASSET_SUPPLIES,?$'` or the appended pair after `SUCCESSIONS` in `MergePlan.kt` → 1;
    `'ASSET_SUPPLY_HELD_BY_AN_EQUIVALENT_LOCAL_ROW'` and `'ASSET_SUPPLY_HELD_BY_A_LOCAL_ROW'` → ≥ 1 each;
    `'^enum class MergeHint'`'s line unchanged (no new hint, C-5).
  - `'"supplyItems" to TransferTableClass.GLOBAL_IN_USE'` → 1; `'"assetSupplies" to TransferTableClass.ASSET_OWNED'` → 1.
  - Each C2 code in `A/api` → its arm(s) only; each as `'^\| (404|409|422) \| `CODE` \|'` in `docs/api/v1.md` → 1;
    `'else ->'` inside the new `when`s → 0.
  - `'^@mcp\.tool\('` in `M/src/servicetag_mcp/server.py` → 84; `'^_MIN_SUPPLY_SCHEMA_VERSION = 18$'`
    → 1; `'format 1–18'` in `server.py` → 1 and `'format 1–17'` → 0; `'format \*\*1–18\*\*'` in `M/README.md` → 1.
  - C37's tripwire, word and untouched greps; the tombstone check → 0; gitlink `7e0377a`; `versionName` /
    `versionCode` unchanged; `git diff <base> -- tools/servicetag-schedules tools/servicetag-bundle` → empty.
- **The release line (R15-0, DECIDED).** 1.6.0 is cut now from the schema-17 line (`release-1.6.0`: #92 + #91 + #93),
  and a schema-17 release tip and branch are preserved. #15 proceeds in parallel and **does not merge to master before
  the 1.6.0 tag**; the merged-tip gate runs after that merge, on master. 1.7.0 is the first release at schema 18 /
  format 18, cut by its own release work, not by this plan.
- **The emulator and the phones.** The merged-tip gate runs a debug build at schema 18 on the emulator only. The
  **schema 17 → 18 in-place upgrade (1.6.0 → 1.7.0) is not proven by this plan's gate**: it is 1.7.0's gate, written
  by B9 (C36). Not proven on the emulator by #15: the development and production phones (untouched; no install); a
  real owner's catalog (the private Stage A/B data never enters a test; the development phone is loaded through the
  MCP after a release, R15-9).

## 8. Relationships

- **#4 — recurrence.** Reused unchanged (AC7, C21); a replace-on-cadence item is an ordinary schedule naming a
  REPLACEMENT quick action whose line carries the link; an event records the SupplyItem only when one of its own lines
  carries the link — a minimal completion writes none (limit 2).
- **#42 — research/import** emits ordinary SupplyItems through C22/C26 later; nothing AI-owned is stored (R15-10).
- **#47 — fitted Components.** Gets a stable `SupplyId` that is never re-minted (archive-only, R15-5), an id-keyed
  merge identity, and a pack class carried "by naming row" (C13) — #47's Component table only adds itself as a naming
  row. #15 models no position, date, serial or containment (C37) and takes no "Component" word; renaming the shipped
  "Components" (child Assets) is #47's.
- **#69 — resources.** Plugs in later as `AttachmentOwner.OfSupply(SupplyId)` (`C/model/Attachment.kt:17-29`), a
  nullable owner column on `attachment`, the codec's owner check and `TransferOwnership.of(attachment)`; the
  SupplyItem detail (C30) has room below "Used by". #15 adds no column for it.
- **#74 — categories.** `CategoryKey.display` cleans roles (C17); the SupplyItem's `category` is free text and is not
  the Asset category catalog.
- **#76** — the roadmap of record places #15 at Phase 2A. **#79** — R79-4's soft link (no FK) is the precedent for
  `supply_id` (C6). **#85** — untouched. **#86** — `ReplaceSetup` clones links; applicability is not copied (R15-15).
- **#88 — ranged guidance** (AC12): a spec value is plain text; no range, condition or interval is modelled here.
- **#91** — the PATCH conventions (C22), the older-format merge exception (C12, R91-7 mirrored), the MCP's
  gate-only-when-sent (C27), schema on master with no release (R91-11 followed).
- **#93** — `AssetPicker`'s stateless pattern is the SupplyItem picker's (C32).
- **#95 — inventory.** Everything stock-shaped is deferred; C37's tripwire guards it; `SubjectKey` and the
  notification channels are untouched.
- **#96 — maintenance-material requirements.** A schedule naming a SupplyItem is #96's; #15 adds no schedule field.
- **#90** — the gate-time trigger is not reached (§3). **#62** — no black-box UI driving; rows 58, 61, 64 are tier-2
  Compose semantics.

## Briefs — common to every brief (fourteen dispatches)

Read §1–§8, the audit, issue #15 (with the 2026-09-29 clarification) and every earlier report on this branch.
**Dispatch precondition:** met — every R15 ruling is DECIDED or a standing controller default (§6) and §5 is
RATIFIED. R15-0 gates the **merge** (not before the 1.6.0 tag), not the briefs. **Constructor plumbing (C-1):** a
brief whose contract adds a constructor parameter owns that parameter's `AppGraph` wiring line, `FakeGraph` line and
every test construction site `git grep` names — arguments only, nothing else in those files; no `AppGraph` or
`FakeGraph` edit after B4a.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`, over
`app/src/main` and `core/src/main` unless named); the untouched diff (`git diff <base> --stat` against the brief's
"never touches"); C37's tripwire, word and untouched greps; the tombstone check → 0; `git ls-tree HEAD
libs/nfc-tag-core` → `7e0377a…`; the `versionName` / `versionCode` lines equal `<base>`'s; `git diff <base> -- app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml
tools/*/uv.lock app/src/main/AndroidManifest.xml` → empty; `git status` clean. B6 adds `uv run --frozen pytest` in
`M/` and `S/`.

**Pin rule.** A shipped assertion moves only where §3's moving-pins table names it for this brief. Confirm the set
first with two greps over `app/src/test app/src/androidTest core/src/test tools/servicetag-mcp/tests` —
`git grep -nE 'FORMAT_VERSION|formatVersion\)|schemaVersion\)|SCHEMA_VERSION|1–17|17 since|[Tt]wenty|sixteen'` and
`git grep -nE 'ProfileConsumable\(|ConsumableUsage\(|ConsumableInput\(|TOOL_NAMES|\b76\b'` — and report any hit the
table does not name before editing it.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each grep
with its count; the test counts before and after; the wall time against the box; what is done, proven and not proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin list; a JVM
or build failure the brief did not cause (reported, not retried); any network access from a test; a phone string not
in §5; a schema or format change, or a new table, outside B1/B2a; a `~` site (C10, C11) that reads differently from the
audit; an owner question the brief finds undecided; the cap reached, the 1-hour target passed with under half the rows
green, or the 2-hour hard stop.

**Must NOT, always:** write that a minimal schedule completion records a SupplyItem usage (R15-4's precision, limit
2); step outside the **HARD SCOPE** (owner, binding): "#15 MVP = canonical SupplyItem identity +
generic specs + Asset applicability + canonical maintenance usage linkage. Nothing more." — so never add stock,
quantity on hand, thresholds, reorder, lead time, procurement or a stock-aware flow (#95); a position, install or
removal date, fitted serial or SupplyItem containment (#47); an attachment, reference, URL or file on a SupplyItem
(#69); a SupplyItem delete; a schedule field naming a SupplyItem (#96). Also never: use "component", "part" (bar "Part
number") or "consumable" as a phone word for a SupplyItem; link a line by matching names (C37); delete a shipped
assertion; commit outside the brief's files; add a device-boundary class or run a device; bump a version; write a
repository from a handler or a view model; re-check in the API or UI a rule the use case owns; touch
`external_link`, `ExternalLinkEntity`, `LinkKind`, `ExternalLinkDto` or `externalLinks`; edit `17.json`; touch
`C/reminders`, `A/reminders`, `tools/servicetag-schedules`, `tools/servicetag-bundle`, `tools/emulator/*`, a harness
helper or the gate script; name a screen-driving tool under `tools/`; add a dependency; write a ledger; quote the
owner's private Stage A/B data or write a real name, host, serial or e-mail address (fixtures: `example.invalid`).

## 9. B1 — the domain, Room schema 18 and the placeholders (C4–C7; core and app JVM)

**Read:** audit §0.4, §0.5, §2.1, §2.3, §2.6; `C/model/{Ids,Maintenance,Journal,Asset}.kt`; `C/ports/Repositories.kt`;
`A/data/room/{Migrations,AppDatabase,JournalMappers}.kt`; `A/data/room/entities/{AssetReferenceEntity,
JournalEntities}.kt`; `A/data/room/dao/{AssetReferenceDao,JournalDaos}.kt`; `A/di/AppGraph.kt:253-260`, `:1029`;
`T/data/room/{Migration16To17Test,MigrationTestSupport,ReferenceMigrationTest,ReferenceDaoConstraintTest,
JournalDaoTest}.kt`. **`<base>`** = master at dispatch plus this plan's commit. **Rows:** 1–5. **Rulings:** R15-1,
R15-2, R15-4, R15-5. **Interfaces produced:** `SupplyId`, `SupplyItem`, `SupplySpecification`, `AssetSupply`, the
line types' `supplyId`, both ports, the Room tables and columns at schema 18 — consumed by every later brief.

**Placeholders (C19):** pass `supplyId = null` at `BackupFormat.kt:880`, `:944`, `SaveProfile.kt:89`,
`EventCommands.kt:231` (placeholders) and `ApplyTemplate.kt:102` (final), and nowhere else in main; report them. **Greps:** `'^value class SupplyId\('` → 1 (N-1); C37.2's two patterns hold on B1's own diff (C-6);
`'^    version = 18,$'` → 1; `'^        const val SCHEMA_VERSION = 18$'` → 1; ``'ADD COLUMN `supply_id` TEXT'`` → 2;
no `DEFAULT`, `UPDATE`, `CREATE INDEX` on a line table, or `REFERENCES` outside the two `CREATE TABLE`s inside
`MIGRATION_17_18` (read it); `'MIGRATION_17_18'` counts equal `'MIGRATION_16_17'`'s (2 and 1); `18.json` added,
`17.json` unchanged; `'FORMAT_VERSION = 17'` in `BackupCodec.kt` → 1 (the format is B2a's); `'fun delete'` in the
SupplyItem port, DAO and repository → 0. **Pin list:** §3's B1 rows. **Untouched:** `C/backup` and `C/usecase`
(bar the four placeholders and `ApplyTemplate.kt:102`), `C/merge`, `C/transfer`, `A/api`, `A/ui`, `docs`, `tools`. **Must NOT:** add a default to
any new domain field; add an FK, index or default to `supply_id`; recreate a line table; move the backup format; add a
delete for a SupplyItem anywhere. **Counted RED (3):** rows 1, 3, 4. **Caps:** 5 JVM mutation runs; **1 h target, 2 h
hard stop**; fix round 3 runs, 45 min. **Size:** about 280 production, 260 test lines (plus the generated `18.json`
and ~25 pin sites). **Estimate:** 55 min.

## 10. B2a — backup format 18 (C8–C10; core JVM)

**Read:** audit §2.4; `C/backup/BackupFormat.kt:206-281`, `:546-656`, `:870-950`; `C/backup/BackupCodec.kt:100-200`,
`:205-300`, `:330-445`, `:480-650`; `C/backup/BackupContentCheck.kt:50-90`; **confirm first** `ExportBackupSet.kt`
(`~170-191`) and `ImportBackupReplace.kt` (its wipe and write order) and report the lines read;
`CT/backup/BackupFormat17Test.kt`; the construction sites (§4, C-1). **Rows:** 6–14. **Rulings:** R15-6 (a link
resolves; archived is valid).
**Interfaces produced:** the three DTOs, `BackupData.supplyItems` / `assetSupplies`, the line DTOs' `supplyId`,
`FIRST_SUPPLY_FORMAT` — consumed by B2b, B2c, B4a, B5.

**Greps:** `'^    const val FORMAT_VERSION = 18$'` → 1; `'^    internal const val FIRST_SUPPLY_FORMAT = 18$'` → 1;
`BackupFormat.kt:880` and `:944` read, carrying the DTO's `supplyId` (N-2); `LegacyArchive.kt` diff → empty. **Pin
list:** §3's B2a rows (the 14 format literals, the two one-past triples, `BackupFormat6Test.kt:287`). **Untouched:**
`C/merge`, `C/transfer`, every use case but the two named, `A/**` main bar `AppGraph`'s wiring, `docs`, `tools`. **Must NOT:** give a new DTO a default
(only the two line keys); accept a non-null link or a supply row below format 18; skip the line-link resolution check.
**Counted RED (5):** rows 6, 8, 9, 10, 13. **Caps:** 7 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about
200 production, 320 test lines, plus ~13 mechanical construction sites. **Estimate:** 55–60 min. **Split clause:** if
the dispatch estimate passes 1 h, C10 (export and replace import, their construction sites, row 13) goes to a B2a2
off B2a's tip.

## 11. B2b — the merge (C11–C12; core JVM, one app row) — and its split clause

**Read:** audit §0.6, §2.5; `C/merge/MergePlan.kt:55-110`, `:230-350`, `:540-580`; `C/merge/MergePlanner.kt:500-560`,
`:730-800`, `:840-900`, `:1035-1070`, `:1195-1225`, `:1260-1340`; **confirm first** `ApplyBackupMergePlan.kt`
(`~160-215`, the write order) and report it; `C/usecase/BuildBackupMergePlan.kt:90-105`; `MergePlan.kt:425-440`
(`MergeSnapshot`); `MergePlanner.kt:1440-1475` (`mergeSnapshotOf`); `CT/merge/{MergePlannerLoanTest,
MergePlannerReferenceTest}.kt`; `T/api/ApiRouterTest.kt` (its import-merge case). **Rows:** 15–25. **Rulings:** R15-6,
R15-7 (no hint, C-5). **Interfaces produced:** `MergeTable.SUPPLY_ITEMS` / `ASSET_SUPPLIES`, the two new reasons, the
snapshot's two lists, the tally — consumed by B2c (the return), B5 (the report) and B6 (the docstring).

**Split clause (taken by this plan's estimate):** whole, C11–C14 is ~75 min; **B2b carries C11–C12 and B2c (§12)
carries C13–C14**, dispatched off B2b's accepted tip. **Greps:** `'ASSET_SUPPLY_HELD_BY_AN_EQUIVALENT_LOCAL_ROW'` in
`MergePlan.kt` → 1 declaration, and `'ASSET_SUPPLY_HELD_BY_A_LOCAL_ROW'` → 1; `'^enum class MergeHint'`'s line
unchanged; the SupplyItem section's first line precedes the profiles section's in `MergePlanner.kt` (read it, C-3);
`'FIRST_SUPPLY_FORMAT'` in `MergePlanner.kt` → 1; the `MergeTable` line ends `SUCCESSIONS, SUPPLY_ITEMS, ASSET_SUPPLIES,` (no shipped member
moved). **Pin list:** `MaintenanceRoutesTest.kt:1488`, `:1541`, `:1551`, `:1194-1215`. **Untouched:** `C/backup`,
`C/transfer`, every use case but `ApplyBackupMergePlan` and `BuildBackupMergePlan`, `A/ui`, `A/data`, `docs`, `tools`;
`A/api/ApiDtos.kt` beyond the report rows (never `DuplicateCandidateDto`).
**Must NOT:** add an `UPDATE` verdict or any write to an existing row; apply C12's exception to format 18 or to a row
with no linked line; move a shipped `MergeTable` or `MergeReason` member; add a `MergeHint` member; plan the
SupplyItem section after profiles. **Counted RED (7):** rows 15, 16, 17, 18, 19, 22, 25. **Caps:** 9 runs; 1 h / 2 h;
fix round 3 runs, 45 min. **Size:** about 190 production, 360 test lines, plus the construction sites. **Estimate:**
55 min.

## 12. B2c — the Transfer Pack and the held guard (C13–C14; core JVM)

**Read:** audit §0.7, §2.5; `C/transfer/TransferGraph.kt:1-60`, `:150-300`; `C/transfer/TransferOwnership.kt:70-115`;
`C/transfer/HeldWriteGuard.kt:55-120`, `:240-260`; `A/di/AppGraph.kt` (where the guard wraps the ports, `:284`);
`C/usecase/ApplyBackupMergePlan.kt:170-185`, `:255-345` (`ReturnScope`, C-4);
`CT/transfer/{TransferGraphTest,ImportTransferPackTest,HeldWriteGuardTest,TransferTableClassificationTest}.kt`.
`<base>` = B2b's accepted tip. **Rows:** 26–30, 67. **Rulings:** R15-7 (a diverged item refuses a pack, limit 4). **Interfaces
produced:** the guarded `AssetSupplyRepository` — consumed by B3.

**Greps:** `'"supplyItems" to TransferTableClass.GLOBAL_IN_USE'` → 1; `'"assetSupplies" to
TransferTableClass.ASSET_OWNED'` → 1; `'keyed by name'` in `TransferGraph.kt` → 0; `'sixteen'` in
`HeldWriteGuard.kt` and at `AppGraph.kt:284` → 0 (the count words say seventeen, N-6); `'assetSupplies ='` inside
`ReturnScope.of` → 1. **Pin list:** `TransferTableClassificationTest.kt:25-31`; `HeldWriteGuardTest.kt:186`.
**Untouched:** `C/backup`, `C/merge`, `C/usecase` bar `ReturnScope.of`, `A/api`, `A/ui`, `docs`, `tools`. **Must NOT:**
wrap `SupplyItemRepository`; carry an item no carried row names; drop an item in `retain`; reduce `supplyItems` in
`ReturnScope`. **Counted RED (4):** rows 27, 28, 30, 67. **Caps:** 6 runs; 1 h / 2 h; fix round 2 runs, 45 min.
**Size:** about 70 production, 200 test lines. **Estimate:** 45 min.

## 13. B3 — the SupplyItem and applicability use cases (C15–C17; core JVM)

**Read:** audit §2.2, §4, §5; `C/usecase/{SaveGroup,ArchiveGroup,SaveProfile,SaveDefinition,DefinitionCommands,
ReferenceCommands,AddReference,UpdateReference}.kt`; `C/journal/{CategoryKey,CategorySuggestions}.kt`;
`CT/usecase/{GroupMembershipTest,AddReferenceTest,UpdateReferenceTest}.kt`. **Rows:** 31–36, 38. **Rulings:** R15-3,
R15-5, R15-6, R15-11. **Interfaces produced:** `SaveSupplyItem`, `ArchiveSupplyItem`, `AddAssetSupply`,
`UpdateAssetSupply`, `RemoveAssetSupply`, their commands, `SupplyItemValidation` / `SupplyItemProblem`,
`AssetSupplyResult` / `AssetSupplyProblem`, `NoSuchSupplyItem`, `AssetSupplyRoles.suggestions`, all wired in
`AppGraph` — consumed by B4a, B4b, B5, B7, B8.

**Greps:** `'data class SupplyItemCommand\('` → 1 with no `=` default inside it (read it); `'fun slugify'` and
`'KEY_PATTERN = '` → 1 each, unchanged (no second key rule); `'fun display'` in `CategoryKey.kt` → 1 (no second
cleaner); `'class DeleteSupplyItem|fun deleteSupplyItem'` → 0. **Untouched:** every shipped use case, `C/backup`,
`C/merge`, `C/transfer`, `A/api`, `A/ui`, `docs`, `tools`. **Must NOT:** read or write a material line from these use
cases; fill a role from anything but the command; accept a new applicability row on an archived item. **Counted RED
(6):** rows 31, 32, 33, 34, 36, 38. **Caps:** 8 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 240
production, 330 test lines. **Estimate:** 55 min.

## 14. B4a / B4b — usage linkage (C18–C21, C24's keys; core and app JVM) — split by plan (C-1, C-2)

**B4a — core linkage and the constructor sites.** **Read:** audit §0.2, §0.3, §0.8, §3;
`C/usecase/{ProfileCommands,SaveProfile,EventCommands,LogEvent,UpdateEvent,CompleteSchedule,CompleteGroupMembers,
RecordConditionWithIncident,ApplyTemplate,ReplaceSetup}.kt`; `A/api/ValidationRefusals.kt:70-155`;
`A/ui/setup/ProfileEditViewModel.kt:335-365`; `A/ui/journal/EventEntryViewModel.kt:630-685`; `T/testing/FakeGraph.kt`;
`CT/usecase/{ProfileUseCasesTest,EventUseCasesTest,ApplyTemplateTest,ReplaceAssetTest}.kt`. Confirm the writer list
and the ~12 construction sites first with `git grep` and report every site. **Rows:** 39, 40 (core cases), 41, 42.
**Rulings:** R15-4 (with its precision), R15-6, R15-10, R15-15. **Interfaces produced:** the inputs' `supplyId`,
`UnknownSupplyItem` on both problem types and its three arms, the `SupplyItemRepository` constructor parameter, P15-20's
home — consumed by B4b, B5, B7, B8. **Greps:** `'^data class ConsumableInput\(val name: String, val quantity: String,
val unit: String, val supplyId: SupplyId\?\)$'` → 1; `ProfileConsumableInput`'s `supplyId` has no default (read it);
`SaveProfile.kt:89` and `EventCommands.kt:231` read, carrying the input's link (N-2); `'else ->'` count in
`ValidationRefusals.kt` and in `asProblems()` unchanged; `'^const val .* = "That supply item is no longer available\."$'`
→ 1, in `A/ui/supplies/SupplyStrings.kt`; `git diff <base> -- C/usecase/ApplyTemplate.kt C/usecase/ReplaceSetup.kt
C/usecase/ScheduleCommands.kt A/ui/maintenance/CompletionFlow.kt C/journal/SeedTemplates.kt` → empty. **Pin list:**
the input constructor sites (§3). **Untouched:** every `Screen.kt`, `C/backup`, `C/merge`, `C/transfer`, `docs`,
`tools`. **Must NOT:** fill a line's `name` or `unit` from a SupplyItem in core; refuse a link to an archived item;
change the completion command; add a phone string. **Counted RED (4):** rows 39, 40, 41, 42. **Caps:** 6 runs;
1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 110 production, 180 test lines, plus ~14 construction sites.
**Estimate:** 50 min.

**B4b — the request keys, the row state, inference and AC11.** `<base>` = B4a's accepted tip. **Read:**
`A/api/ApiDtos.kt:351-425`; `A/api/MaintenanceDtos.kt:405-430`; `A/ui/journal/EventEntryViewModel.kt:80-95`,
`:255-270`, `:385-405`, `:630-640`; `A/ui/setup/ProfileEditViewModel.kt:150-165`, `:335-345`; `T/api/ApiRouterTest.kt`
(the profile and event cases); B4a's report. **Rows:** 37, 40 (the two view-model cases), 43, 44, 45. **Rulings:**
R15-4, R15-12. **Interfaces produced:** the request keys and the row state — consumed by B5, B6, B8. **Greps:** B4a's
five input placeholder lines read, each carrying the request's or the row's link (N-2); `'supplyId'` in
`ProfileConsumableRequest` and `ConsumableRequest` → 1 each, `String? = null`. **Untouched:** `C/**` main, `AppGraph`,
`FakeGraph`, every `Screen.kt`, `docs`, `tools`. **Must NOT:** link by name (row 37); draw anything (B8 draws). **Counted
RED (4):** rows 37, 43, 44, 45. **Caps:** 6 runs; 1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 60 production,
260 test lines (the AC11 fixture ~110). **Estimate:** 50 min.

## 15. B5 — the API and the wire document (C22–C25; app JVM, docs)

**Read:** audit §6; `A/api/{ApiRouter,ApiHandlers,ApiJson,ApiDtos,MaintenanceHandlers,ReferenceDtos,
ReferenceHandlers,ValidationRefusals}.kt` at the cited lines; `docs/api/v1.md` (the group, reference and loan
sections, `:208`, `:577`, `:1861`, `:1971`); `T/api/{LoanRoutesTest,ReferenceRoutesTest,CommandShapesGoldenTest}.kt`.
`<base>` = B4b's accepted tip (B5's lane); the 8 `ApiHandlers` test construction sites (§4, C-1). **Rows:** 46–53. **Rulings:** R15-5 (DELETE), R15-16. **Interfaces
produced:** the five SupplyItem rows, the four applicability rows, the codes — consumed by B6.

**Greps:** each C2 code in `A/api` → its arm; `'else ->'` in the new `when`s → 0; `'"supplyItems" to'` and
`'"assetSupplies" to'` in `ApiHandlers.kt` → 1 each; each C2 row in `v1.md` as `'^\| (404|409|422) \| `CODE` \|'` → 1;
`'1–17'` in `v1.md` → 0; `git diff <base> -- docs/api/command-shapes.json` → empty. **Pin list:**
`CommandShapesGoldenTest.kt:132-172`; `ReferenceRoutesTest.kt:~642-670` (`counts.keys`). **Untouched:** `C/**`,
`A/ui/**` main, `A/data`, `A/di`, `FakeGraph`, `tools`, `command-shapes.json`.
**Must NOT:** add a SupplyItem delete or a tri-state reader; draw a sentence from a `message`; widen another family's
code. **Split clause:** if the dispatch estimate passes 1 h, the wire document (C25's `v1.md` part, row 53 and the
golden pin) goes to a B5b in the same lane. **Counted RED (6):** rows 46, 47, 49, 50, 51, 52. **Caps:** 8 runs;
1 h / 2 h; fix round 3 runs, 45 min. **Size:** about 300 production, 400 test, 170 document lines, plus 8
construction sites. **Estimate:** 60 min — at the target, so the split clause is the controller's call at dispatch.

## 16. B6 — the MCP (C26–C28; pytest)

**Read:** audit §6 (MCP); `M/src/servicetag_mcp/server.py:70-110`, `:170-320`, `:940-1130`, `:1180-1225`,
`:1320-1460`, `:1855-1880`, `:2700-2815`; `M/README.md`; `M/tests/{test_loan_tools,test_reference_tools,
test_argument_guard,test_tools,test_maintenance_tools}.py` (`test_reference_tools.py:64-65` and `test_tools.py:118-122`
pin the tool count, N-4). `<base>` = the tip holding B5 and B7. **Rows:** 54–57.
**Rulings:** R15-5, R15-9, R15-12.

**Greps:** `'^@mcp\.tool\('` → 84; `'^_MIN_SUPPLY_SCHEMA_VERSION = 18$'` → 1; `'_require_supply_schema\('` → the
definition, the eight tools and the four line tools' guarded calls; `'format 1–18'` in `server.py` → 1, `'format
1–17'` → 0; `'format \*\*1–18\*\*'` in `M/README.md` → 1; `git diff <base> -- tools/servicetag-schedules
tools/servicetag-mcp/src/servicetag_mcp/command_shapes.py` → empty. **Pin list:** `test_argument_guard.py:51`,
`:203-204`, `:256-258`; `test_tools.py:118-122`, `:803-815`; `test_reference_tools.py:64-65`. **Untouched:** `app/**`, `core/**`, `S/**`, `docs`. **Must NOT:** gate
a line tool that sends no `supplyId` key (presence, N-7); add a `clear_fields` argument to `update_supply_item`; send `supplyId` to a phone whose row had no such key; add a SupplyItem delete tool.
**Counted RED (3):** rows 54, 55, 56. **Caps:** 5 pytest mutation runs; 1 h / 2 h; fix round 3 runs, 45 min.
**Size:** about 200 production, 300 test lines. **Estimate:** 50 min.

## 17. B7 — the phone catalog (C29–C31; app JVM, Compose) — dispatched as B7a → B7b

**Read:** audit §7; `A/ui/maintenance/{MaintenanceScreen,GroupListScreen,GroupDetailScreen,GroupEditScreen,
GroupEditViewModel}.kt`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `A/ui/setup/ProfileEditScreen.kt:241-260`, `:370-400`,
`:491`; `AT/ui/maintenance/{GroupScreensTest,MaintenanceShellTest}.kt`. `<base>` = B4b's accepted tip (B7's lane).
**Rulings:** R15-1, R15-6, R15-8, R15-11; §5's P15-1…12, P15-20 ratified.

**Split (taken by this plan's estimate, ~75 min whole):** **B7a** = C29–C30 (the row, the routes, the list, the
detail; rows 58, 59, and 61's list and detail cases), about 300 production / 220 test lines, **40 min**; **B7b** = C31
(the editor; rows 60 and 61's editor cases) off B7a's tip, about 250 production / 200 test lines, **40 min**.
**Greps:** `'^const val SUPPLIES_SECTION = "Supplies"$'` → 1; the three routes → 1 each in `Route.kt`; the P-string
constants each declared once; **string literals only (N-3):** `git grep -hoE '"[^"]*"' -- A/ui/supplies | grep -iE
'component|consumable|\bparts?\b' | grep -vx '"Part number"'` → 0 (identifiers such as `partNumber` and the
`ui.components` imports are not phone words). **Pin list:** `MaintenanceShellTest.kt` (one grown case); `VersionAgreementTest.kt:432` unchanged.
**Untouched:** `A/api/**`, `docs/**`, `C/**`, `A/ui/{asset,setup,journal}`, `tools`. **Must NOT:** add a tab, a
Settings row or a reorder control; draw a key field; call a repository from a view model. **Counted RED (2):** rows
59 (B7a), 60 (B7b); rows 58 and 61 are device cases run at the merged gate. **Caps:** 5 runs per part; 1 h / 2 h each; fix round 3 runs, 45 min.

## 18. B8 — the asset section, the picker and the line controls (C32–C35; app JVM, Compose) — dispatched as B8a → B8b

**Read:** audit §7; `A/ui/asset/{AssetPicker,AssetDetailScreen}.kt` (`:15-25`; `:184-206`, `:430-476`);
`A/ui/nav/ServiceTagRoot.kt:190-210`; `A/ui/references/
{ReferencesSection,ReferencesSectionViewModel,ReferenceSheets}.kt`; `A/ui/setup/{ProfileEditScreen,
ProfileEditViewModel}.kt`; `A/ui/journal/{EventEntryScreen,EventEntryViewModel}.kt`; `AT/ui/references/
ReferencesSectionTest.kt`. `<base>` = B6's accepted tip. **Rulings:** R15-3, R15-6, R15-8; §5's P15-1, P15-13…23.

**Split (taken by this plan's estimate, ~75 min whole):** **B8a** = C32–C33 (the picker and the asset section; rows 62
and 64's section and picker cases), about 270 production / 260 test lines, **45 min**; **B8b** = C34–C35 (the shared
link line on both editors; rows 63 and 64's link-line cases) off B8a's tip, about 120 production / 150 test lines,
**35 min**. **Greps:** `'fun SupplyItemPicker\('` → 1 and no `ViewModel` import in its file; `'archivedAt == null'` (or
the hosts' equivalent filter) in each host view model → 1; the call site in `AssetDetailScreen.kt` before
`ComponentsSection(` → 1; `'onOpenSupply'` in `AssetDetailScreen.kt` (the parameter and its use) and in
`ServiceTagRoot.kt` (the wiring) → ≥ 1 each (C-1); `git diff <base> -- A/ui/journal/EventDetailScreen.kt A/ui/journal/JournalFormat.kt` → empty.
**Untouched:** `C/**`, `A/api`, `A/data`, `EventDetailScreen.kt`, `JournalFormat.kt`, `CompletionFlow.kt`, `docs`,
`tools`. **Must NOT:** offer an archived item in a picker; overwrite a typed name or unit on a pick; add "Link supply"
to the event form; add a confirmation dialog. **Counted RED (2):** rows 62, 63. **Caps:** 5 runs per part; 1 h / 2 h
each; fix round 3 runs, 45 min.

## 19. B9 — the documents and the release-proofs paragraph (C36; docs)

**Read:** `docs/release-proofs.md:100-125`; `docs/versioning.md:15-25`; `README.md:50-58`;
`docs/design/04-domain-data-model.md:430-470`, `:560-580`; every brief report on this branch. `<base>` = B8b's
accepted tip. **Rulings:** R15-0 (1.6.0 at schema 17 is the upgrade source the paragraph names), R15-4's precision,
R15-14 (decided differently: the current architecture, not the six-bucket wording); §5's D1 ratified.

**Greps:** the schema-18 paragraph present once after the schema-17 one (`'^\*\*The first signed release carrying
Room schema 18'` → 1); `'stock/reorder'` in `README.md` → 0; `docs/design/14-asset-model.md` present, carrying the chain "Asset → nested Component → optional SupplyItem identity →
maintenance usage/history", with no six-bucket wording, no private value and no #95 word (read it);
`git diff <base> -- docs/versioning.md` → empty. **Untouched:** every source and test file,
`docs/api`, `tools`. **Must NOT:** quote the owner's private data; add a #95 word; reproduce the six-bucket wording; claim a usage record for
a minimal completion; promise a release. **Counted RED:**
none (documents). **Caps:** 1 h / 2 h. **Size:** about 120 document lines. **Estimate:** 30 min.

## 20. Errata after the merge (controller, 2026-10-01; merged b20e86c9)

Rev 1.2 is the ratified spec; the code and the documents are as built (`docs/api/v1.md`, `docs/release-proofs.md`'s schema-18 paragraph, the MCP README, `docs/design/14-asset-model.md`). Where they differ, this section records the difference and who ruled it — collected by the whole-branch review from the thirteen task reviews, the reports and the ledger. Nothing above is rewritten. E-30's two wording fixes landed before the merge (9b418e68).

- **E-1** B1 needed `SupplyItemRepository.deleteAll()` for B2a's replace wipe (fix round `858118f5`); C5's R6 grep
  `'fun delete'` → 0 must read `'fun delete\('`, counted per type block (`AssetSupplyRepository.delete` is allowed).
- **E-2** C5's ports gained `observeAll()` (archived included, NOCASE order) and `observeForAsset()` (B1 fix round) —
  B7/B8 need live reads; no observe-by-item port, so "Used by" is re-read on resume (E-15).
- **E-3** The replace wipe order is assets first (their CASCADE takes `asset_supply`), then the catalog (RESTRICT);
  `AssetSupplyRepository` has no `deleteAll` by design.
- **E-4** The manifest count is **29**, not 28: `supplyItems`, `supplySpecifications` and `assetSupplies` (B2a C).
- **E-5** Pins outside §3's moving-pins table, granted: `BackupFormat17Test:99`, `BackupFormat13/14/15Test`
  `counts.size` (B2a); `MergePlannerTransferTest:383`, `MergePlannerSeasonHealthTest:135` (B2b);
  `ReferenceRoutesTest:721-765`, `CommandShapesGoldenTest:132-185` renamed + "18 since #15" (B5); and B2a's ~20
  assertions in 11 files (three more `counts.size` 26 pins, five whole-map count equalities, five from-the-end key
  positions, the format-8 strip fixture, `StageABundleConformanceTest:140/:159`, a `Format7RestoreContractTest`
  comment) under the format-15 precedent `78a3ab40`.
- **E-6** Twin pins (the B2a rule): `BackupFormat7Test:222` 20 → 22, `StageABundleConformanceTest:94-98` via
  `FORMAT_18_TABLES` (B2a); `MergePlannerMaintenanceTest` enum list, `MergePlannerReferenceTest` report mirror,
  `MergePlannerSuccessionTest:211`, `MaintenanceRoutesTest` `dropLast` 4 → 6 ×2 (B2b); four extra `ApiHandlers`
  construction sites (12, not 8; B5); the MCP argument-guard counts, the "76" mentions, `TWENTY_TWO_TALLIES` (B6).
- **E-7** `TransferGraph.kt`'s two `CLASSES` lines landed in B2a (pinned by `TransferTableClassificationTest`), not
  B2c; `TransferOwnership.of(AssetSupply)` + the `MergePlanner.kt:1349` swap in B2c (granted at B2b).
- **E-8** C-1's construction-site counts: B4a moved **32 sites in 14 files**; C37.4's "bar C20's one argument" on
  `CompleteSchedule.kt` is three lines (import, parameter, pass), the same on `CompleteGroupMembers.kt` (B4a review).
- **E-9** C3 "casefolded": Room's NOCASE folds ASCII only, the API sorts by `lowercase()` — two orders on non-ASCII
  names (B1 NOTE-3, B5; NOTE-5 above).
- **E-10** `ValidationRefusalsTest` (outside every list) gained two rows — G2's message, field `consumables` — a plan
  gap (B4a); `v1.md`'s families table lists both (`:1734`, `:1756`).
- **E-11** C17's suggestion-order cite is `CategoryCatalog.kt:33-37` (R74-10), not `CategorySuggestions.kt:14` (B3).
- **E-12** `SaveSupplyItem` is stricter than `SaveProfile`: a repeated owned spec id is a new row; `SavedSupplyItem`
  (item, unchanged) named by B3; the `Unchanged` comparison renumbers and trims, so a hand-made archive's item is
  rewritten by its first save (hence the gate's "app-made archives" order).
- **E-13** `AssetSupplyProblem.Unchanged` has no wire code (a no-op re-role answers 200 with the re-read row); no
  twelfth code; the API mapper is named `supplyItemRefusal` (B5).
- **E-14** `SupplyListViewModel.listRowsOf()` extracted and `SupplyRow` made internal so the picker reuses the list's
  rows; `SupplyStrings.kt` the one home — three files outside §4's B8 list (B8a, accepted).
- **E-15** "Used by" is re-read on resume, not observed (B7a ruling 6).
- **E-16** `SUPPLIES_SECTION` lives in `SupplyStrings.kt`, not the Maintenance screen (C29; B7a ruling 2).
- **E-17** The editor route `Route.SupplyEdit` registered by B7b, not B7a (the side-branch order; B7a's add button
  crashed until then); B7b's `clearInstall` catalog wipe in `AppSmokeTest.kt` (outside B7's fence, granted).
- **E-18** The Compose budget: `SupplyScreensTest` **7** (plan ~5), `SupplySurfacesTest` **10** (plan ~6); ~300
  device tests, not ~295.
- **E-19** The MCP tool names follow C26/row 54 — `archive_supply_item(archived=…)` and one `set_asset_supply` (create
  or re-role); the dispatch's list was wrong (B6).
- **E-20** `save_profile`'s edit gates **after its read** (the body to be sent decides; nothing written); C27's
  "nothing sent" is loose on that path (B6).
- **E-21** The MCP line type is `dict[str, str | None]` (admits the explicit null), not the plan's `Any` (B6); the C37
  regex kept out of the MCP tests (it raised the tripwire).
- **E-22** `""` as a line `supplyId` → 422 `UnknownSupplyItem` (field `consumables`); as an applicability `supplyId` →
  404 `NO_SUCH_SUPPLY_ITEM` (B4b/B5).
- **E-23** The import range moves 1–17 → 1–18 (the plan right, B5's dispatch wrong).
- **E-24** B5's `v1.md` needed a fix round for the merge section (nine non-conflict codes, the SKIPPED reason, the
  IDENTICAL row's C12 clause, the format-18 sentence, Tables 21–22, the kept-row unit) — §4's B5 list should have named
  it.
- **E-25** C34: a pick fills a blank **name** and a blank unit (the plan right; the dispatch said unit only) — the
  unit-fill trap N-11 the gate paragraph states.
- **E-26** B8b: a linked all-blank event row reaches validation (`&& row.supplyId == null`), a behaviour change on edit
  (NOTE-3); the event form's catalog parameter has a default (`supplyItems: SupplyItemRepository? = null`), an
  exception to the no-default style (production passes the graph's).
- **E-27** B2b: the triple arm checks local rows only (the codec keeps the triple unique per file); `SUPPLY_ITEMS` /
  `ASSET_SUPPLIES` listed last in `MergeTable` though planned before profiles (C-3).
- **E-28** B2c's `TransferImportTesting.kt` (outside its list) handed the merge the raw applicability port — fixed
  `57010387`; `HeldWriteGuardTest` +1 granted to B3; `dd8c1290` "a component" → "a child asset" in a test label.
- **E-29** C36 as built: the production phone is **1.5.0 (schema 16)** (`7c68b5ff`; the plan's "any older release";
  B9 first wrote 1.4.1 from master's record); `versioning.md` untouched (the dispatch asked for a sentence — the plan
  wins); the release line is **R15-0**, not R15-11 (the dispatch's and this brief's mis-cite); the D4 note cites §13,
  not `:568`; the design-index row; the paragraph adds a post-upgrade pre-link export, the merge tallies, the route and
  tool names, the hand-made-archive reason, and words limit 10 as this gate's blind spots.
- **E-30** The controller's queued "whole-branch mechanical fix" (the `deleteAll` KDoc) and B1's stale
  `SupplyItem.kt:4-5` sentence — MINOR-2.

**30 errata.**

- **Reviews and rounds.** Fourteen dispatches (nine briefs, three split by plan, two with split clauses not needed), one task review each; one fix round (B1: `deleteAll` + the observe flows); every other finding closed by controller inspection; three side branches (UI, MCP, docs) folded without a shared file; master merged into the branch before the whole-branch review (the 1.6.0 release and evidence; three doc/test conflicts resolved, both texts kept); one whole-branch review (MERGE WITH FIXES: 0 BLOCKER, 0 MAJOR, 2 MINOR wording, 12 NOTE) with B9's task review folded in. Controller rulings of record: the twin-pin rule (a twin of a listed pin moves and is listed; anything else stops); the manifest count 29; SupplyItems planned before profiles; `ReturnScope` keeps a returning asset's rows; the duplicate hint dropped (limit 4a); `""` as a `supplyId` refused; `save_profile`'s edit gated on the body it sends; the production phone's release corrected to 1.5.0 (schema 16) in C36. The gate is `.superpowers/sdd/2026-10-01-issue-15/gate/` (57 device classes — 55 + `SupplyScreensTest` + `SupplySurfacesTest`, never run before it — the MCP and loader pytests), run once on b20e86c9.
