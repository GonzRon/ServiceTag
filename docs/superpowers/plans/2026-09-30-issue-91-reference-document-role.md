# #91 — a canonical document role on an HTTP(S) reference: plan and briefs (rev 1.1, 2026-09-30)

> **Rev 1.1** applies the owner's rulings of 2026-09-30 (recorded on issue #91) and the independent plan review's
> conditions C-1…C-4 and notes N-1…N-10 (`.superpowers/sdd/2026-09-30-issue-91/plan-review.md`).
> **HARD SCOPE (owner, binding across all nine briefs):** "#91 = add `DocumentRole?` to HTTP(S) References and
> propagate it faithfully through existing paths. Nothing more. No new role vocabulary, no inference, no new document
> model, no new API tools, no new device class, no redesign of References, no Key Documents treatment for links, no
> generalized resource work (#69)." The owner ruled **"the five as stated"** — (1) roles legal on web references only
> → R91-1; (2) editing nullable, explicit set/clear, never inferred → R91-3, R91-5, R91-6; (3) materialize inherits
> unless overridden or cleared, kind untouched → R91-2, R91-9; (4) old rows no role, old clients keep working, the
> merge handles pre-17 deliberately → R91-7, R91-10; (5) a quiet label, intake chips on web links, a link is not a Key
> Document until materialized → R91-4, R91-8 — and ratified R91-11, R91-12, R91-13 and R91-14. **R91-1…14 are DECIDED
> at their recommendations (§6)**; the reused strings are approved unchanged on the new surfaces. **The one open item
> is G2's developer-facing message (§5).** Review conditions: C-1 → §3's pins table, row 10, the B1b/B2a/B3b pin
> lists and confirm greps; C-2 → C20, B2b, row 39, R6; C-3 → C2, C26, §7 R6, the B2a/B3b/B4 greps; C-4 → C21, C23.
> Notes N-1…N-4 and N-6…N-10 applied (C14, C12/B2a, row 25, §12, C24, B1c, C26, limit 7, §3); **N-5 taken another
> way:** `roleOffered` is a derived getter over a stored `linkTakesRole` (the `noFolder` getter's style,
> `ShareIntakeViewModel.kt:133`), so a hand-built `BYTES` state still draws Role and the `form(…)` helper needs no new
> default.

> **Status: PLANNING ONLY.** Not authorized for execution until the owner dispatches it. Placement is the roadmap of
> record's (issue #76); this plan assumes #92 merged (`f55ffb5b`, errata `66aa9987`) and nothing after it. As
> decided, this plan **amends R67-9** (a web link share offers the Role chips, R91-4), **mirrors R67-12 option B**
> for references (R91-7), **follows R67-10** (schema 17 / format 17 on master with no version bump or release,
> validated on the emulator, R91-11), and **supersedes the #85 plan's row "`role` = none"**
> (`docs/superpowers/plans/2026-09-29-issue-85-materialize-references.md:633`) and `v1.md`'s "absent or `null` takes
> … no role" on the materialize body (`docs/api/v1.md:1350-1352`) with a three-state rule (R91-2).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-30-issue-91/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-09-30-issue-91/audit.md`, every citation read on
> `66aa9987`. **Nine briefs on one branch `issue-91`** — B1a, B1b, B1c, B2a, B2c, then B2b beside B3a → B3b, then
> B4 — each `<base>` the previous accepted tip; **B1a's `<base>` is master at dispatch** (today `66aa9987`: 1.5.0 /
> code 18, Room schema 16 / backup format 16, MCP 76 tools, gitlink `7e0377a`) plus this plan's commit. The audit's
> six briefs are kept by name; its B1b is split into B1b (format) and B1c (merge and pack), its B2a into B2a (use
> cases and reference routes) and B2c (materialize), and its B3 into B3a (share intake) and B3b (sheets and row),
> because each estimate crossed the 1-hour target. One task review each, at most one bounded fix round each, one
> whole-branch review, the merge, one merged-tip gate.

**Goal:** an asset's `WEB_URL` reference (http or https) can carry the #67 `DocumentRole` —
`PURCHASE_INVOICE_OR_RECEIPT`, `USER_MANUAL`, `SERVICE_MANUAL`, or none — set **only** by the owner's explicit choice
(share intake, Add link, Edit reference, `POST`/`PATCH /v1/references`, the MCP), stored in Room schema 17, carried by
backup format 17 through export, restore, merge and the Transfer Pack, and copied **exactly** into the attachment a
Save as document makes, where the Review can still change or clear it. The role is never inferred from anything.
`ReferenceKind` stays derived from the scheme and orthogonal to the role.

**Inputs:** issue #91 (`.superpowers/sdd/2026-09-30-issue-91/issue-91.md`, AC1–AC9); the audit; the #67 plan
(R67-9…12, `docs/superpowers/plans/2026-09-26-issue-67-key-documents.md:334-340`); the #85 plan (`:633`); the #92
plan (§2 Common, §8); `docs/api/v1.md`; `docs/release-proofs.md`; `docs/versioning.md`;
`docs/superpowers/planning-policy.md`. Paths: `C/` = `core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` =
`core/src/test/kotlin/com/loosecannon/servicetag/core/`, `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`,
`T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`, `AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`,
`M/` = `tools/servicetag-mcp/`, `S/` = `tools/servicetag-schedules/`.

## Global constraints

- **Reuse, never re-implement.** The role is `DocumentRole`, the #67 enum, unchanged in values and serialised names
  (`C/model/Attachment.kt:19`). The words and order are `DocumentRole?.label()`, `ROLE_HEADER` and `ROLE_CHOICES`
  (`A/ui/attachments/AttachmentsSectionViewModel.kt:62-78`), imported, never copied. The kind of a link is
  `ReferenceKinds.inferFrom(LinkLaunchPolicy.schemeOf(…))` — the two calls `AddReference` makes
  (`C/usecase/AddReference.kt:43`, `:72`) — and no surface carries a second classifier. Every reference write is one
  call of `AddReference.run` or `UpdateReference.run`; no handler, view model or tool re-checks what they check
  (`A/api/ReferenceHandlers.kt:17-25`).
- **One schema step and one format step, forward-only.** Room 16 → 17 is one nullable `TEXT` column,
  `asset_reference.document_role`, no default, no index, no backfill (the `MIGRATION_9_10` and `MIGRATION_15_16`
  shape, `A/data/room/Migrations.kt:576-587`, `:729-743`). Format 16 → 17 appends one key, `role`, to each
  `assetReferences[]` row; an older archive decodes with no roles, and a newer app's archive is refused by an older
  app before its data is read (`C/backup/BackupCodec.kt:324-327`). No table, count key, `MergeTable`, `MergeReason`,
  tally, route or tool is added. `LAST_LEGACY_FORMAT` stays 7.
- **The 2.6 tombstones are not the reference table and are never touched.** `external_link` /
  `ExternalLinkEntity` / `LinkKind` and `data.json`'s `externalLinks` / `ExternalLinkDto` are the retired note-link
  feature's (audit §1); `asset_reference` was created beside it and never reads it. Appending a field to
  `AssetReferenceDto` does not move the `externalLinks` → `measurementDefinitions` key order that
  `PreservedSetRestoreTest` pins (`AT/backup/PreservedSetRestoreTest.kt:44-46`, `:65-72`). Tombstone check:
  `git diff <base> -- . ':!app/schemas' | grep -cE '^[-+].*(external_link|externalLinks)'` → 0.
- **API version stays 1, additive.** Every refusal is a stable `code`, plus `field` where one body key is at fault,
  in the shipped envelope; 422 = change this body, 409 = change another row first. Every new mapping is an arm of an
  exhaustive `when` over its sealed type, never an `else`. Unknown keys stay 400 (`A/api/ApiJson.kt:95-99`). **The API
  returns codes, not sentences:** a `message` is developer-facing wire text, never drawn on a phone screen.
- **No inference, anywhere (AC3, AC7).** No code path produces a role from a URI, scheme, kind, name, title,
  description, MIME type, file name or model output. C25 states the invariant a test pins.
- **No personal data.** Fixtures are fictional ("Example Water Heater", `https://manuals.example.invalid/…`,
  `joplin://x-callback-url/openNote?id=example`); no real name, host, serial or e-mail address in code, tests, docs or
  commits.
- **Strings.** Every user-visible string is ratified before it ships (§5). This plan adds **no new phone sentence**
  (R91-5, R91-8, R91-14 decided); every phone word it draws is a shipped, ratified one reused verbatim, approved by
  the owner on the new surfaces. The one open text is G2's developer-facing wire message (§5).
- **Tests.** JVM first (core, then app over the production router and the Room-backed `FakeGraph`); **no new device
  class and no new device case** — three shipped Compose cases grow (§3, R91-13). Device rows exist only for
  platform-only facts, and #91 adds none. No test touches the network. Every counted RED is a real mutation run with
  `--no-build-cache --rerun-tasks`, its failing assertion quoted, reverted before the commit. **No
  rerun-until-green:** a flaky or unrelated failure is reported, not retried into a pass.
- **Standing rules.** Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump** (`versionName = "1.5.0"`,
  `versionCode = 18`; the release vehicle is the owner's, R91-11). Commits: one casual lowercase subject line; no
  body, no trailers, no AI attribution. `tools/` never names a screen-driving tool (the `ReleaseProofPolicyTest`
  tripwire). **Implementers never write ledgers**; the controller does.
- **Time boxes:** every brief 1 h target, 2 h hard stop; fix rounds 45 min, findings' files only, stop on the first
  finding they cannot close. **Mutation caps** per brief (each brief states its own). **Review budget:** one task
  review per brief, at most one bounded fix round, a scoped re-review only for a substantive correctness finding,
  mechanical fixes (< ~50 lines) by controller inspection, one whole-branch review, merge, one gate.

## 1. Scope

**HARD SCOPE (owner, 2026-09-30, binding):** "#91 = add `DocumentRole?` to HTTP(S) References and propagate it
faithfully through existing paths. Nothing more. No new role vocabulary, no inference, no new document model, no new
API tools, no new device class, no redesign of References, no Key Documents treatment for links, no generalized
resource work (#69)."

**In scope — the nine acceptance criteria, each on its contracts:**

| AC | the issue's words (abridged) | contracts |
|---|---|---|
| AC1 | an asset HTTP(S) reference can be classified with one of the three roles, or none | C1, C4, C5, C9–C11 (R91-1) |
| AC2 | share-URL intake and the reference editor can set/change it | C21 (R91-4), C22, C23 (R91-5), C13, C14, C18 |
| AC3 | the role is not inferred from URL/name/description/MIME | C25; C10, C16, C21, C23 |
| AC4 | backup/restore/merge preserve it | C6, C7 (R91-7), C8 |
| AC5 | materialization copies an exact source role by default | C16, C17 (R91-2) |
| AC6 | the Review can still change/clear it before Save | C16 (the shipped chips, unchanged), C17 |
| AC7 | a generic "manual" never becomes User or Service manual | C25; C16's grown `theReviewNeverGuessesARoleFromTheText` |
| AC8 | kind and role stay orthogonal | C1 (the predicate reads kind, never writes it), C16 (R91-9), C25 |
| AC9 | reusable for #69 owners | C1 (the enum in its own file, a per-owner predicate) |

**Out of scope** (follow-up candidates): a reference joining Key documents or the Details "Purchase document" fact
(R91-8; both stay attachment-only); a role on `NOTE_LINK` or `OTHER` references (R91-1); #69's owners (supplies,
components) and any predicate for them; moving `label()` / `ROLE_HEADER` / `ROLE_CHOICES` out of `ui/attachments`
(optional in the audit §9; deferred to #69, where a second owner makes the neutral home earn its four import lines);
any role-to-kind or kind-to-role derivation (R91-9; `editorKindFor` stays the editor's alone); a `command-shapes.json`
entry for references (R91-12); a reference `DELETE` route; the bundle tool (format 5) and the schedules loader (no
references in its manifest, `S/src/servicetag_schedules/manifest.py:33`); a version bump or release (R91-11);
any inference, heuristic or suggestion of a role.

**Recorded limits (stated, not fixed):**
1. **A role given and then cleared on the phone** leaves the row's `updatedAt` moved with no role: a pre-#91
   (format ≤ 16) export then re-plans that row `CONFLICT`, because C7's exception applies only while the row here
   carries a role. This is R67-12 option B's accepted shape for attachments, mirrored.
2. **An `http` link can carry a role it can never be saved as a document with** (Save as document takes `https`
   links the hop rule accepts, `C/usecase/MaterializeReference.kt:79-83`). R91-1's stated cost.
3. **A reference's role is visible only on its row** (R91-8): Key documents, the Details fact and the attachment
   pickers do not read it.
4. **A format-17 archive or Transfer Pack is refused by a 1.5.0 phone** as a newer format (forward-only, MINOR by
   `docs/versioning.md:21`).
5. **The MCP refuses a role for a schema-16 phone locally** (C19); a hand-made request to such a phone gets that
   phone's strict 400 for the unknown `role` key.
6. **No `/v1` export route exists**, so the schema-17 release gate's pre/post export comparison is a phone-side step
   (the 1.5.0 erratum's gap, `docs/release-proofs.md:114`), unchanged by #91 (C26).
7. **A pre-#91 MCP's `materialize_reference` without `role`, against a schema-17 phone, gets the source reference's
   role**, though that MCP's docstring says "no role" (N-9). This is AC5 working as intended and the one place the
   "API v1 additive" claim changes an existing request's result; no existing result moves until a role is given,
   because before #91 no source had one.

## 2. Contracts

### Common (C1–C3)

- **C1, the enum and the reference predicate (audit §0.1, §2, §9; R91-1).**
  - **The enum moves, unchanged, to `C/model/DocumentRole.kt`, same package** (`com.loosecannon.servicetag.core.model`).
    The fully qualified name, the three values and their order, and the serialised names are identical, so no import,
    call site, Room value or archive string moves. Its KDoc becomes owner-neutral ("what a document is *for*";
    R67-7's independence from `AttachmentKind`; R67-3's "any number may share a role"). The R67-11 sentence ("allowed
    on an asset-owned attachment only") stays in `Attachment.kt` beside `AttachmentOwner.accepts` (`:32-33`), which is
    unchanged.
  - **The reference rule is stated once**, in `C/references/ReferenceKinds.kt`, beside the rule that picks the kind
    (the reference policies' home; `C/model/AssetReference.kt` holds shapes and no rules, its KDoc `:12-14`):

    ```kotlin
    /** #91 (R91-1): only a web link — http or https — may carry a document role. */
    val ReferenceKind.takesRole: Boolean get() = this == ReferenceKind.WEB_URL

    /** The reference sibling of `AttachmentOwner.accepts`: no role at all, or a kind that takes one. */
    fun ReferenceKind.accepts(role: DocumentRole?): Boolean = role == null || takesRole
    ```

    The codec (C6), both use cases (C10, C11) and every phone surface (C21–C23) ask these two and nothing else.
    Nothing else compares a kind to `WEB_URL` **for a role** (#85's materialize eligibility,
    `MaterializeReference.kt:79`, is a different rule and stays).
- **C2, the codes.** One new code; everything else is shipped:

| code | status | field | when | `message` (developer-facing) |
|---|---|---|---|---|
| `REFERENCE_ROLE_NOT_ALLOWED` | 422 | `role` | `ReferenceProblem.RoleNotAllowed`: a role on a reference whose kind is not `WEB_URL`, on `POST` or `PATCH` | `a document role belongs on an http or https link` — **awaiting ratification** (G2, the only open string) |

  Reused unchanged: `400 bad_request` (the decoder's own message) for an unknown role name, since every request type
  types `role` as `DocumentRole?` (the `MaterializeRequest` precedent, `A/api/AttachmentHandlers.kt:436-441`);
  `NO_SUCH_REFERENCE`, `no_such_asset`, `REFERENCE_NAME_REQUIRED`, `REFERENCE_URI_INVALID`,
  `REFERENCE_SCHEME_BLOCKED`, `REFERENCE_URI_TAKEN`; the MCP's `APP_SCHEMA_TOO_OLD` (C19). `referenceError`
  (`A/api/ApiJson.kt:681-686`) gains an optional `field` parameter, default `null`, so every shipped reference arm
  answers exactly as today (the reference family carries no `field` now; this is its first).
- **C3, the wire shapes.** Additive, API version 1:

| surface | change |
|---|---|
| `GET /v1/assets/{id}/references`, and every reference in a `POST`/`PATCH` answer | each row gains `role`: a `DocumentRole` name, or `null` (always present: `encodeDefaults = true`, `ApiJson.kt:95-99`) |
| `POST /v1/references` | `{assetId, uri, displayName, description?, role?}`; `role` absent or `null` = no role; a name on a non-web link = C2's 422 |
| `PATCH /v1/references/{id}` | `{displayName?, description?, role?}`; **`role` is three-state** — absent: unchanged; `null`: clear; a name: set (C14, R91-3). `displayName`/`description` keep the shipped "`null` = unchanged" |
| `POST /v1/references/{id}/materialize` | **`role` is three-state** — absent: the source reference's role; `null`: no role; a name: that role (C17, R91-2). The other three keys keep "absent or `null` = the prefill" |
| `GET /v1/status` | `schemaVersion` 17, `backupFormatVersion` 17 (read from the constants); `counts` unchanged |
| `data.json` `assetReferences[]` | appends `role`, written explicitly as `"role": null` when unset (`explicitNulls` default) |

### B1a — the domain field and Room schema 17 (C4–C5)

- **C4, the domain field.** `AssetReference` appends `val role: DocumentRole? = null` — the default matches
  `Attachment.role` (`C/model/Attachment.kt:50-51`), so the eleven test files that build `AssetReference(...)` compile
  unchanged. Its KDoc: "only `displayName`, `description`, `role` and `updatedAt` are mutable" (today `:21-23` names
  three). `kind` and `scheme` stay derived exactly as today (`AddReference.kt:43`, `:72`, `:78`). The two core
  construction sites (`AddReference.kt:69`, `C/backup/BackupFormat.kt:1200`) and the Room mapper each pass the role
  explicitly; a default is not an excuse to drop it (rows 4, 6, 19 catch each).
- **C5, Room schema 17 (R91-6).**
  - `AssetReferenceEntity` (`A/data/room/entities/AssetReferenceEntity.kt:9-53`) appends
    `@ColumnInfo(name = "document_role") val documentRole: String?`, the attachment's column name
    (`AttachmentEntity.kt:69`). No CHECK, no index (nothing queries by role).
  - `ReferenceMappers.kt` maps it by `.name` / `valueOf`, the attachment mapper's shape (`A/data/room/Mappers.kt:190`,
    `:218`).
  - `MIGRATION_16_17` is exactly one statement, ``ALTER TABLE `asset_reference` ADD COLUMN `document_role` TEXT``, in
    `MIGRATION_9_10`'s spelling (`Migrations.kt:585`) — no `DEFAULT`, no `UPDATE`, no index; every existing reference
    has no role at upgrade and nothing is inferred (R91-6).
  - `@Database(version = 17)` (`A/data/room/AppDatabase.kt:90`), `AppGraph.SCHEMA_VERSION = 17`
    (`A/di/AppGraph.kt:1027`), the migration registered in **both** chains (`AppGraph.kt:253-256` and
    `T/data/room/MigrationTestSupport.kt:61-70`), and the build-generated `app/schemas/…AppDatabase/17.json`
    committed. `16.json` is never edited.
  - The whole-chain test `Migration8To9Test` seeds an `asset_reference` row and compares it after the whole chain
    (`T/data/room/Migration8To9Test.kt:37`, seeds `:264`, `:361`): `document_role` joins the filtered known deltas
    the way `V11_ASSET_COLUMNS` does. `ReferenceMigrationTest` is unaffected (it compares migrated against fresh
    columns and seeds no reference rows, audit §3).

### B1b — backup format 17 (C6)

- **C6, format 17 (the codec; R91-1).**
  - `AssetReferenceDto` (`C/backup/BackupFormat.kt:402-417`) **appends** `val role: String? = null` as its tenth and
    last field (column order). `AssetReference.toDto()` writes `role?.name`; the KDoc "nine columns" becomes ten.
  - `BackupCodec.FORMAT_VERSION = 17` (`BackupCodec.kt:144`); the stale "v15" KDoc header line (`:31`) is corrected
    alongside. `internal const val FIRST_REFERENCE_ROLE_FORMAT = 17`, internal because the merge reads it, in
    `FIRST_ROLE_FORMAT`'s shape (`:151-155`).
  - **The ≤ 16 gate**, in the decode beside #67's and #85's (`:351-360`, `:362-…`): an archive of format < 17 whose
    `assetReferences` carries a non-null `role` is `BackupCorrupt` naming the first such reference; an explicit
    `"role": null` in such an archive is accepted; an absent key decodes as `null`.
  - **`AssetReferenceDto.toDomain()`** (`:1200-1210`): the role name through `enumOrCorrupt<DocumentRole>(…,
    "document role", "reference $id")` — an unknown name is `BackupCorrupt`; then `kind.accepts(role)` (C1) — a role
    on a `NOTE_LINK` or `OTHER` row is `BackupCorrupt` naming the reference, in the shape of the attachment check
    (`:1023-1025`). The kind read is the row's stored kind, which never changes for a row's life (audit §1, hazard).
    The decode's naming pass and `validateGraph` both run through `toDomain`, so a bad archive is refused before
    anything is written.
  - **Strict names:** the codec keeps `ignoreUnknownKeys` unset (`:181-184`); nothing here loosens it.
  - The Transfer Pack needs no code: its `data.zip` is this codec's output unchanged (`C/transfer/TransferPack.kt:10-19`).
  - **Moving pins (format):** the **thirteen** literal-16 **format** assertions move to 17 in this brief —
    `CT/backup/{BackupFormat6Test:358, BackupFormat8Test:281, BackupFormat9Test:70, BackupFormat13Test:54,
    BackupFormat14Test:48, BackupFormat15Test:48, BackupCodecTest:1063}`, `CT/usecase/Format7ImportIdentityTest:245`,
    `CT/usecase/ExportBackupSetTest:47`, `AT/backup/Format7RestoreContractTest:164`, `T/api/MaintenanceRoutesTest:1159`,
    `:1506`, `:1553` — and the format halves of `T/VersionAgreementTest:83-86` (`:85`) and `:153-154` (`:154`). The
    schema halves moved in B1a. Two structural pins move too (C-1): `CT/backup/BackupFormat7Test.kt:204-212` pins
    `AssetReferenceDto`'s element names in order and `elementsCount == 9` — both become ten with `"role"` last, and its
    KDoc (`:195-200`) says ten; `CT/backup/BackupFormat8Test.kt:270-281` seals its "one past this build" archive at
    **literal 17** (`:277`) and asserts `found == 17` (`:280`) — both become 18 with its KDoc, beside `:281`'s 16 → 17.

### B1c — the merge and the Transfer Pack (C7–C8)

- **C7, the merge: R67-12 option B, mirrored for references, on both arms (R91-7).** Once `role` is in the
  DTO, the reference pass (`C/merge/MergePlanner.kt:838-875`) compares it automatically on the id arm (`:846`) and the
  second-identity arm (`:851`). The exception, a sibling of `sameAttachment` (`:776-781`) in its shape and not a
  generalisation of it:

  ```kotlin
  // #91 (R91-7): R67-12 option B for references — an archive older than format 17 cannot speak about roles.
  val referenceRolesCompared = backup.manifest.formatVersion >= BackupCodec.FIRST_REFERENCE_ROLE_FORMAT
  fun sameReference(incoming: AssetReferenceDto, here: AssetReferenceDto): Boolean = when {
      referenceRolesCompared -> incoming == here
      here.role == null -> incoming.copy(role = null) == here
      else -> incoming.copy(role = null, updatedAt = here.updatedAt) == here.copy(role = null)
  }
  // arm 1:  local != null && sameReference(dto, local.toDto())
  // arm 3:  samePair != null && sameReference(dto.copy(id = samePair.id.value), samePair.toDto())
  ```

  Outcomes. **Format ≤ 16 archive:** against a row here that has since been given a role → `IDENTICAL` on the id arm
  and `IDENTICAL` `REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` on the pair arm; any other field that differs (name,
  description, `createdAt`) still reads `CONFLICT` / `SKIPPED` as today; against a row with no role the stamp is
  compared as today (limit 1). **Format 17 archive:** same role → `IDENTICAL`; a different role, a role against none,
  or none against a role on the same id → `CONFLICT` / `CONTENT_DIFFERS` (blocking); the same pair under another id
  with a different role → `SKIPPED` `REFERENCE_HELD_BY_A_LOCAL_ROW` (D-18 C); a new row → `INSERT` carrying its role.
  **There is no update path**: a role travels by merge only on a row the destination does not have
  (`:832-834`). Arms 5–7 are untouched. The planner's KDoc gains the reference paragraph beside #67's and #79's
  (`:131-154`; its "four normalisations" becomes five).
- **C8, the Transfer Pack.** No code. A held asset's reference carries its role through the pack's create and import,
  because the pack is the codec's archive (`C/transfer/TransferPack.kt:10-19`) and its import plans through the same
  merge (`C/usecase/ImportTransferPack.kt:76-94`). Proven by one core case (row 18), in the shape of #85's source
  check in `CT/transfer/ImportTransferPackTest.kt:133-135`.

### B2a — the use cases and the reference routes (C9–C15)

- **C9, the commands (the audit's concern).**

  ```kotlin
  data class AddReferenceCommand(
      val uri: String,
      val displayName: String,
      val description: String = "",
      val confirmedUnknownScheme: Boolean = false,
      /** #91: set only by an owner's explicit pick; null is "no role", the shipped create. Never inferred. */
      val role: DocumentRole? = null,
  )

  /** What the edit sheet can change. `role` has **no default** (#67 C2's rule): a caller that forgot it would clear it. */
  data class UpdateReferenceCommand(val displayName: String, val description: String, val role: DocumentRole?)
  ```

  Every `UpdateReferenceCommand(` site passes a role explicitly. The shipped test sites move (§3, pins): the four in
  `CT/usecase/UpdateReferenceTest.kt`, `T/ui/references/ReferencesSectionViewModelTest.kt:405`, `:424`, `:495`,
  `AT/ui/references/ReferencesSectionTest.kt:245`, `CT/transfer/HeldWriteGuardTest.kt:412`. The one production site,
  `ReferenceEditSheet` (`A/ui/references/ReferenceSheets.kt:55`), passes `role = row.role` in this brief — a rename
  keeps the stored role — and gains its picker in B3b.
- **C10, `AddReference` (R91-1).** The step order stays the contract (`AddReference.kt:19-32`), with one step
  inserted after the tier: **structural → length → tier → role → name → owner → identity**. The role step:
  `ReferenceKinds.inferFrom(scheme).accepts(cmd.role)` is false → `Refused(RoleNotAllowed)`, nothing generated or
  written. The row stores `role = cmd.role` exactly. An unknown scheme confirmed by the person classifies `OTHER`, so a
  role on it is refused after the confirmation (the phone never offers one there, C23). The KDoc names the new step.
- **C11, `UpdateReference`.** Order: `NoSuchReference` → `BlankName` → `RoleNotAllowed` (`row.kind.accepts(cmd.role)`)
  → `Unchanged` → write. **`Unchanged` includes the role**: name, description **and** role equal the row's → refused,
  nothing written, `updatedAt` held (`UpdateReference.kt:32`; its KDoc `:11-20` names the role). A role-only change
  writes `role = cmd.role` and moves `updatedAt` (`:36-41`) — which is exactly why C7 exists.
- **C12, the problem and its four exhaustive `when`s.** `ReferenceProblem` (`C/usecase/ReferenceCommands.kt:39-59`)
  gains `data object RoleNotAllowed`, KDoc "a role on a reference that is not a web link (R91-1)". The audit named
  three `when`s; the tree has **four**, and each gains one arm (no `else` added):
  1. `ApiJson.kt:419-447` (status and sentence): `RoleNotAllowed -> referenceError(422, …, field = "role")` with C2's
     message.
  2. `referenceProblemCode`, `ApiJson.kt:703-715`: `-> "REFERENCE_ROLE_NOT_ALLOWED"`.
  3. `ReferencesSectionViewModel.say`, `A/ui/references/ReferencesSectionViewModel.kt:259-270`: `-> return` — says
     nothing, the shipped treatment of an arm the surface cannot reach (`:253-258`), because the sheets never send a
     role a link cannot take (C22, C23). (R91-14)
  4. `ShareIntakeViewModel.saveLink`, `A/share/ShareIntakeViewModel.kt:330-344`: joins the `OwnerMissing,
     NoSuchReference, Unchanged -> ownerGone()` group — the shipped treatment of arms a create cannot reach — because
     the intake never sends a role a link cannot take (C21). (R91-14)
  No new sentence exists for arms 3 and 4 (G1 avoided, R91-5). Comments that count the arms move with them (N-2):
  `ApiJson.kt:410` "Nine arms" → ten; `say`'s KDoc (`ReferencesSectionViewModel.kt:253-258`) "Four of the nine say
  nothing" → five of ten.
- **C13, `POST /v1/references`.** `CreateReferenceRequest` (`A/api/ReferenceDtos.kt:38-44`) appends
  `val role: DocumentRole? = null` — typed, so an unknown name is the decoder's 400; its KDoc becomes "five fields,
  and no `kind`". The handler passes `role = body.role` into the one `addReference.run` (`ReferenceHandlers.kt:78-90`)
  and re-checks nothing. `kind` stays an unknown field (400).
- **C14, `PATCH /v1/references/{id}`: the tri-state (R91-3).** `UpdateReferenceRequest` (`:56-60`) appends
  `val role: DocumentRole? = null`. The handler reads the body **once as a `JsonObject`** and then decodes it strictly
  from that object — the `ScheduleForms` precedent for telling a JSON `null` from an absent key
  (`A/api/ScheduleForms.kt:86`, `:103-115`, `:156-157`):

  ```kotlin
  // ReferenceHandlers.update (#91, R91-3): `role` is the one key whose null is a value.
  val raw = request.decode(JsonObject.serializer())                           // 415 / 400 as today
  val body = decodeOr400(UpdateReferenceRequest.serializer(), raw.toString()) // strict: unknown keys 400
  val stored = references.get(ReferenceId(id)) ?: throw ReferenceRefused(ReferenceProblem.NoSuchReference)
  val command = UpdateReferenceCommand(
      displayName = body.displayName ?: stored.displayName,   // null still "unchanged" (shipped, v1.md:600)
      description = body.description ?: stored.description,
      role = if ("role" in raw) body.role else stored.role,   // absent: unchanged; null: clear; name: set
  )
  ```

  Then exactly one `updateReference.run`; `Unchanged` stays the 200 with the stored row (`:110-115`). **The second
  parse is deliberate** (N-1): `raw.toString()` through `decodeOr400` is exactly `ScheduleForms.strict`, so the 400s
  keep the shipped decoder messages (`decodeFromJsonElement` would change them), and the 64 KiB tier makes the cost
  negligible. The asymmetry is stated once in each KDoc that counts or describes the fields (N-2):
  `CreateReferenceRequest`'s, `UpdateReferenceRequest`'s "Two fields" (`ReferenceDtos.kt:47`) and
  `ReferenceHandlers.update`'s "full pair" (`ReferenceHandlers.kt:92-98`).
- **C15, reads.** `listForAsset` and both write answers reuse `AssetReferenceDto` verbatim (`ReferenceDtos.kt:23-27`),
  so `role` appears with no handler change; `/v1/status` reports 17/17 from the constants. `ReferenceRowState`
  (`ReferencesSectionViewModel.kt:45-57`) appends `val role: DocumentRole? = null`, mapped in `row()` (`:237-251`) —
  B2a adds it because C9's edit-sheet site needs it; nothing draws it until B3b.

### B2c — materialize carries the source role (C16–C17)

- **C16, the snapshot and the prefill (R91-2, R91-9).** `SourceSnapshot`
  (`C/usecase/MaterializeReference.kt:204-206`) appends `val role: DocumentRole?` with **no default** (one production
  site, `prepare` at `:112`, passes `reference.role`; the three test sites pass it explicitly); `toString` stays
  host-only. `reviewPrefill` (`A/ui/references/MaterializeViewModel.kt:210-215`) sets `role = snapshot.role` and
  nothing else changes: the name, the description as notes, and **the kind the proven type implies** (`:212`) stay —
  never `editorKindFor(role)` (R91-9). Its KDoc's last sentence becomes "copied here exactly (#91)". The Review
  already offers "No role" and every role (`MaterializeSheet.kt:163-164`, `chooseRole` `:129`), so the owner can change
  or clear the copied role before Save (AC6) with **no UI change**; `commit` carries `review.role` into
  `AddAttachmentCommand` as today (`MaterializeReference.kt:144`).
- **C17, `POST /v1/references/{id}/materialize`: the tri-state (R91-2).** The handler
  (`A/api/AttachmentHandlers.kt:262-320`) reads the body once as a `JsonObject` and strictly decodes `MaterializeRequest`
  from it (C14's idiom); in `saveAsDocument` the review's role is `if ("role" in raw) given.role else prefilled.role`
  (today `given.role ?: prefilled.role`, `:318`). `displayName`, `kind`, `notes` keep "absent or `null` = the
  prefill". The raw object travels to `saveAsDocument` beside `given` (or a small value holding "role given: yes/no");
  nothing else in C14–C18 of #92 moves (the lock, the hand-off, `NonCancellable`, the `finally` discard). Every
  pre-#91 call keeps its result: no reference had a role, so the prefill was null either way.

### B2b — the MCP (C18–C20)

- **C18, three tools gain arguments; the count stays 76.**
  - `add_reference(…, role: str | None = None)` (`M/src/servicetag_mcp/server.py:1996-2028`): `role` in the body only
    when given (`_body` drops `None`, `:356`). No `clear_fields` (nothing to clear on a create).
  - `update_reference(…, role: str | None = None, clear_fields: list[str] | None = None)` (`:2031-2056`,
    R91-3): `clear_fields` takes **`role` only**, validated by the shipped `_validate_clear_fields` (`:385`) against a
    new `_REFERENCE_CLEARABLE_FIELDS = frozenset({"role"})`; a cleared role is sent as `"role": null` after `_body`
    (the `update_attachment` idiom, `:2985-3009`). `display_name` still cannot be cleared and `description` is still
    cleared by `""`; the docstring's "There is no `clear_fields` here" paragraph (`:2042-2045`) is rewritten to say
    exactly that.
  - `materialize_reference(…, clear_fields: list[str] | None = None)` (`:3183-3216`, R91-2): `clear_fields`
    takes `role` only and sends `"role": null`; `role` given sends it; neither sends no `role` key, and the phone copies
    the source role. Its docstring's "absent, the phone uses … no role" (`:3208-3210`) becomes "the reference's own
    role (none before schema 17)". The `IDENTICAL` short-circuit (`:3240-3242`) is unchanged.
  - `list_references` (`:1977-1993`): `_REFERENCE_FIELDS` (`:1962-1972`) stays the nine — **`role` is never
    required**, so a schema-16 phone's rows still pass; the docstring says rows carry `role` from schema 17.
  - Unchanged: `kind` is an argument of neither reference write tool; no delete tool; `TOOL_NAMES` and the 76-tool
    pins (`M/tests/test_argument_guard.py:51`, `:256-258`; `M/tests/test_reference_tools.py:54-59`).
  - The role names are the phone's to validate (its decoder's 400); the MCP carries no second list of them unless
    `update_attachment` already has one, which it then reuses.
- **C19, the schema-17 gate (R91-10 — a correction to the audit's Q10).** `_MIN_REFERENCE_ROLE_SCHEMA_VERSION =
  17`, documented in `_MIN_ATTACHMENT_SCHEMA_VERSION`'s pattern (`:202-205`), and `_require_reference_role_schema(tool)`
  → `_require_tool_schema(tool, 17, "the reference document role")` (`:254-270`). Called by `add_reference` **only when
  `role` is given**, and by `update_reference` **only when `role` is given or `clear_fields` names it**: an
  `APP_SCHEMA_TOO_OLD` with nothing sent but the shared `/v1/status` read. Without a role both behave exactly as today
  against any phone (`M/tests/test_season_health_tools.py:371` expects `add_reference` against an older phone).
  **`materialize_reference` gets no new gate:** its `role` is #92's and a schema-16 phone already accepts it, and a
  `"role": null` there takes a prefill that is null on schema 16 — the same "no role" — so gating it at 17 would take a
  working call away from a 16 phone. It keeps `_require_attachment_schema` at 16 (`:3220`).
- **C20, the MCP documents.** `M/README.md:112`'s references paragraph names `role` and `clear_fields=["role"]`; the
  three docstrings as above; `M/tests/test_reference_tools.py`'s module docstring (`:1-12`) is rewritten (its "no
  `clear_fields`" rationale now holds for `add_reference` and for the name and description only). **The import range
  (C-2, the format-16 precedent `05dc90b0`):** `import_merge`'s docstring (`M/src/servicetag_mcp/server.py:1169`) and
  `M/README.md:356` move "format 1–16" / "format **1–16**" to 1–17, each with one clause in #67's words — format 17
  adds each reference's document role, and an older archive's references are compared without it; `test_tools.py`'s
  two assertions (`:806`, `:815`) move with them (the function's name may follow).

### B3a — share intake (C21)

- **C21, a web link share offers the Role chips (R91-4; amends R67-9).**
  - **The policy (C-4):** `ShareIntakeViewModel` (`:165-178`) holds none today, so it gains a trailing constructor
    parameter `private val linkPolicy: LinkLaunchPolicy = LinkLaunchPolicy()` — stateless (`schemeOf` is pure), so
    `ShareIntakeActivity.kt` and the four `ShareIntakeViewModelTest` construction sites stay untouched and B3a's file
    list is unchanged.
  - `ShareIntakeState` gains a stored `val linkTakesRole: Boolean = false`, decided once where the share is
    classified (`ShareIntakeViewModel.kt:466-480`): true iff the link's
    `ReferenceKinds.inferFrom(linkPolicy.schemeOf(uri.trim())).takesRole` — the shipped classifier, never a prefix
    test. **`roleOffered` is a derived getter** in the `noFolder` getter's style (`:133`): `path == BYTES || (path ==
    LINK && linkTakesRole)` — so `BYTES` is unchanged and a hand-built `BYTES` state still draws Role (N-5, taken this
    way); false for `NOTE` and `TRANSFER_PACK`. "No role" is preselected (`role = null`, `:116-117`).
  - `role(value)` (`:270-272`) records a pick iff `roleOffered` (today: iff `BYTES`).
  - `saveLink` (`:308-324`) passes `role = current.role.takeIf { current.roleOffered }` into `AddReferenceCommand`; the
    unknown-scheme re-submit (`:286-291`) is the same path.
  - **The link's suggested name** (`suggestedName`, `:471-475`) feeds the name only, never the role (C25).
  - `ShareIntakeScreen` (`A/share/ShareIntakeScreen.kt:158-190`): the Type block stays `BYTES`-only; the Role block
    (`SectionHeader(IntakeStrings.ROLE)` + `FlowRow` over `ROLE_CHOICES`, `:175-189`, unchanged) moves out of the
    `BYTES` condition under `if (state.roleOffered)`, so a byte share still draws Type then Role, and a web-link share
    draws Role after Description. The screen stays stateless. Comments and KDocs naming R67-9 (`Screen:175-176`,
    `ViewModel:116`, `:266-269`) name R91-4 beside it.

### B3b — the reference sheets and the row (C22–C24)

- **C22, Edit reference.** `ReferenceEditSheet` (`ReferenceSheets.kt:30-60`): `var role by remember(row.id) {
  mutableStateOf(row.role) }`; the Role section — `SectionHeader(ROLE_HEADER)` then a `FlowRow` of `FilterChip` over
  `ROLE_CHOICES`, `selected = role == option`, label `option.label()`, the `AttachmentEditSheet` block
  (`A/ui/attachments/AttachmentEditSheet.kt:104-120`) — drawn **iff `row.kind.takesRole`**, under Description. Save
  builds `UpdateReferenceCommand(name, description, role)`; on a non-web row `role` is the row's (null) and never a
  pick. **Both reference sheets take the attachment sheet's shape** (`:71-83`):
  `rememberModalBottomSheetState(skipPartiallyExpanded = true)` and a `verticalScroll(rememberScrollState())` column,
  applied once in `SheetColumn` (`ReferenceSheets.kt:145-154`) and `ModalBottomSheet`'s state at `:47` and `:80`, so
  Save stays reachable on a narrow phone at a large text size.
- **C23, Add link (R91-5).** The sheet's state is a **plain class the JVM tests** (the sheet renders it):

  ```kotlin
  // A/ui/references/AddLinkForm.kt (#91, R91-5) — shape, not code; the implementer names the members.
  internal data class AddLinkForm(val link: String = "", val role: DocumentRole? = null)
  //  roleOffered: given by a (String) -> Boolean the view model supplies (below) — never a second classifier
  //  withLink(text): the new text; a pick is CLEARED when the new text is not offered (so re-offering shows "No role")
  //  withRole(pick): recorded only while offered
  //  role at Save:   role.takeIf { roleOffered } — a non-web link is never sent with a role
  ```

  The chips (the C22 block) are drawn **only while `roleOffered`**; no refusal sentence is needed (G1). The view model's
  `addLink(uri, displayName, description, role)` (`ReferencesSectionViewModel.kt:144-146`) passes the role into
  `AddReferenceCommand`; `confirmUnknownScheme` re-submits the same command (`:148-153`), which for an `OTHER` link
  carries no role by construction. **Where the classification comes from (C-4):** the view model, which already holds
  `policy` (`ReferencesSectionViewModel.kt:80`), exposes `fun roleOffered(link: String): Boolean` =
  `ReferenceKinds.inferFrom(policy.schemeOf(link.trim())).takesRole`; `AddLinkSheet` gains a `roleOffered: (String) ->
  Boolean` parameter (the section passes the view model's) and its `onSave` gains the role, `(uri, name, description,
  role) -> Unit`. The sheet constructs no policy. That signature is the C-1 pin at `ReferencesSectionTest.kt:281-303`.
- **C24, the row's role line (R91-8).** `ReferenceRow` (`A/ui/references/ReferencesSection.kt:203-234`) draws
  `QuietLine(role.label())` when `row.role != null`, in the order **kind, role, "Saved as document", description**
  (N-6: after `:228`, before #85's line at `:230`), the label verbatim; never "No
  role"; no composed "Web link · User manual". The overflow's `contentDescription` stays `"More"` (`:237`; G3 not
  taken). Key documents (`DocumentsSection`) and the Details "Purchase document" fact (`AssetDetailScreen.kt:911`) are
  untouched.

### Cross-cutting (C25–C26)

- **C25, the no-inference invariant (AC3, AC7, AC8), stated so a test pins it.** *The stored role of a reference is
  exactly the role in the command that wrote it, or null.* For every input, a create or update with `role = null`
  yields `role == null`, whatever the uri, scheme, kind, name, description or title — including names "User manual",
  "Service manual", "manual", "Invoice", "Purchase receipt", and a uri ending `/user-manual.pdf`. The same holds for the
  share intake (its `suggestedName`), Add link, `POST /v1/references`, `add_reference`, and a restore or merge of a
  format-16 archive (every role null). `reviewPrefill`'s role is exactly `snapshot.role`. Pinned by rows 22, 31, 40, 7.
  **Baseline-equal greps:** `git grep -ciE 'manual|invoice|receipt' -- C/usecase/AddReference.kt
  C/usecase/UpdateReference.kt A/api/ReferenceHandlers.kt A/share/ShareIntakeViewModel.kt
  A/ui/references/ReferencesSectionViewModel.kt A/ui/references/MaterializeViewModel.kt` — each brief records the
  count at its base and its tip; the count must not grow except by a KDoc line the brief names.
- **C26, the documents (B4; R91-11, R91-12).**
  - `docs/api/v1.md`: the reference surface (`:589-616`) — `role?` on `POST`, the `PATCH` tri-state and its asymmetry,
    `role` on every row; the reference codes (`:1703-1724`) — a row `| 422 | `REFERENCE_ROLE_NOT_ALLOWED` | … |` in the
    table's own `| status | code | when |` shape (`:1709-1715`; it has no `field` column, so the "when" cell names
    `field` `role`, C-3a); the import
    range "format **1–16**" / "**format 1–16**" → 1–17 wherever it stands (`:208`, `:1946`, and each emphasis
    spelling); both status lines (`:213`, `:215`) gain "17 since #91 (reference document role)"; the `IDENTICAL` row
    (`:1971`) gains the reference-role sentence in #67's words; the materialize body (`:1350-1352`) states R91-2's
    three states.
  - `docs/release-proofs.md`: a new paragraph after the #92 note (`:116`) and before "## Environment notes", **the
    first signed release carrying Room schema 17 / backup format 17**: the direct upgrade from **each installed
    release** (the development phone's 1.5.0 and whatever the production phone runs, on the older-release path,
    `:100`) on the emulator before any phone; seed references on the older side (N-8); give roles to some
    of them **after** the upgrade through `POST`/`PATCH /v1/references` and at least
    one through the edit sheet; show a post-upgrade export carries `"role": null` on every pre-existing reference and
    every other reference field equal; the pre-upgrade export re-plans **applicable with zero INSERT, every row
    `IDENTICAL`** (the role-bearing references included, by C7); the format-17 round trip restores every role (a
    replace restore, and a merge into an install without the rows). It names the export step as a phone-side step
    (limit 6). Master builds carry schema 17 under `versionName` 1.5.0 until that release: emulator only, never a
    phone.
  - `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`: an "Amendment (#91, R91-4)" paragraph after #67's
    (`:459-466`): a web-link share draws the Role section after Description, "No role" chosen; a note-link and a note
    share draw none.
  - `docs/versioning.md`: **R91-11** — one sentence recording R67-10's shipped practice (a forward-only schema
    and format bump may land on master with no version bump or release, validated on the emulator; the release
    vehicle and phone promotion are separate decisions). No new rule beyond what R67-10, R91-11 and
    `release-proofs.md` already say.
  - Not touched: `docs/api/command-shapes.json` (R91-12: no entry), `README.md`, the manifest.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED (the mutation that must fail it). App classes live in `T/` unless
named; core in `CT/`; MCP in `M/tests/`. "Pin" = a shipped assertion that moves or is re-run, with no RED.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C1 the rule is per kind and stated once | `CT/references/ReferenceRolesTest` (new) · `onlyAWebLinkTakesARole` (`takesRole` for each `ReferenceKind`); `noRoleIsAcceptedOnEveryKind`; `eachRoleIsAcceptedOnAWebLinkOnly` | `takesRole` true for `NOTE_LINK` |
| 2 | C5 the migration adds a NULL column only (R91-6) | `T/data/room/Migration16To17Test` (new) · `everyV16ReferenceSurvivesWithNoRole` (several fictional rows of each kind; every column equal, `document_role` NULL); `theMigratedSchemaEqualsAFreshVersion17`; `theMigratedRowsReadBackThroughTheAdapterWithNoRole` | a backfill `UPDATE asset_reference SET document_role = 'USER_MANUAL' WHERE kind = 'WEB_URL'` in the migration |
| 3 | C5 the whole chain | `Migration8To9Test` · the reference row after the whole chain equals the row before, `document_role` filtered as a known delta | none: pin |
| 4 | C4/C5 the mapper carries the role | `ReferenceDaoConstraintTest` (+1) · `eachRoleAndNoneRoundTripsThroughRoom` | the entity→domain mapper drops `documentRole` |
| 5 | C5 the version agreement | `VersionAgreementTest` · the schema halves to 17 (`:84`, `:153`); `MaintenanceRoutesTest:1505` → 17 | none: pins (the class's own purpose) |
| 6 | C6 the round trip | `CT/backup/BackupFormat17Test` (new) · `eachRoleAndNoneRoundTrips` (a `"role": null` is written explicitly; each role by name) | `toDto` omits `role` |
| 7 | C6 the ≤ 16 gate | `aFormat16ArchiveDecodesWithNoRoles` (the key absent); `aFormat16ArchiveCarryingARoleIsCorrupt` (names the reference); `anExplicitNullInAFormat16ArchiveIsAccepted` | the gate removed |
| 8 | C6 eligibility and names at decode | `aRoleOnANoteLinkIsCorruptNamingTheReference`; `aRoleOnAnOtherLinkIsCorrupt`; `anUnknownRoleNameIsCorrupt` | the `accepts` check removed from `toDomain` |
| 9 | C6 newer refused | `aFormat18ArchiveIsRefusedAsNewer` (the shipped `BackupNewerFormat`, now at 17) | none: pin |
| 10 | C6 the moving format pins | the **thirteen** literal-16 format assertions (C6's list, `ExportBackupSetTest:47` included) → 17; `BackupFormat8Test:277`/`:280`'s "newer" 17 → 18; `BackupFormat7Test:204-212`'s names and count → ten, `"role"` last | none: pins |
| 11 | C6 the tombstones | `PreservedSetRestoreTest` untouched (format 5, no references); the tombstone grep → 0 | none: the device class runs at the merged gate |
| 12 | C7 pre-17, id arm | `MergePlannerReferenceTest` · `` `a format-16 archive against a row later given a role is identical` `` | the exception removed (→ `CONFLICT`) |
| 13 | C7 pre-17, pair arm | `` `a format-16 archive against an equivalent row under another id later given a role is identical` `` | the exception on the id arm only (→ `SKIPPED`) |
| 14 | C7 pre-17, other fields still count | `` `a format-16 archive against a renamed row with a role is still a conflict` ``; the pair-arm twin → `SKIPPED` | every field but the pair dropped from the comparison |
| 15 | C7 pre-17, a row with no role compares its stamp (limit 1) | `` `a format-16 archive against a row whose role was given then cleared is a conflict` `` | `updatedAt` dropped whenever the archive is older |
| 16 | C7 format 17 | `` `same role is identical` ``; `` `a different role is a conflict` ``; `` `a role against none is a conflict` ``; `` `none against a role is a conflict` ``; `` `the same pair under another id with a different role is skipped` `` | the exception applied to format 17 too (→ `IDENTICAL`) |
| 17 | C7 insert carries the role | `` `a new reference with a role inserts with its role` `` (the writes' row) | none beyond row 6's |
| 18 | C8 the Transfer Pack | `ImportTransferPackTest` (+1) · `aHeldAssetsReferenceCarriesItsRoleThroughThePack` (create → import; the row's role equal) | none of its own: row 6's mutation fails it too (recorded, not counted twice) |
| 19 | C10 the role is stored as given | `CT/usecase/AddReferenceTest` · `aRoleOnAWebLinkIsStoredExactly` (each of the three, http and https); `noRoleByDefault` | the row built without `cmd.role` |
| 20 | C10 refused on a non-web link, nothing written | `aRoleOnANoteLinkIsRefusedAndNothingIsWritten` (no row, no id minted, no transaction); `aRoleOnAConfirmedUnknownSchemeIsRefused` | the role step removed (a `NOTE_LINK` row with a role is written) |
| 21 | C10 the step order | `aBlockedSchemeWithARoleIsSchemeBlocked`; `aBlankNameWithARoleOnANoteLinkIsRoleNotAllowed` | the role step moved after the name |
| 22 | C25 no inference (core) | `AddReferenceTest` · `noRoleIsEverInferred` — a table of names ("User manual", "Service manual", "manual", "Invoice", "Purchase receipt"), descriptions and a uri ending `/user-manual.pdf`, each with `role = null` → the row's role null | a name matcher (`"manual" in name.lowercase()` → `USER_MANUAL`) |
| 23 | C11 update | `CT/usecase/UpdateReferenceTest` · `aRoleOnlyChangeWritesAndMovesUpdatedAt`; `theSameRoleIsUnchangedAndWritesNothing`; `clearingARoleWrites`; `aRenameCarryingTheStoredRoleKeepsIt`; `aRoleOnANoteRowIsRefusedAndNothingWritten` | `Unchanged` compares name and description only (a role-only change is refused) |
| 24 | C9 no default on the update | anchored grep (B2a) · `'^data class UpdateReferenceCommand\(val displayName: String, val description: String, val role: DocumentRole\?\)$'` → 1 | none: grep |
| 25 | C12 every problem has its code and status | `T/api/ReferenceRoutesTest` · `everyReferenceProblemHasItsOwnCode` (`:428`) grows `RoleNotAllowed` → `REFERENCE_ROLE_NOT_ALLOWED`; the status arm → 422 with `field` `role`, calling the mapper directly; `everyReferenceCodeIsReachableOverTheWire` (`:342-388`, "five of them" → six) gains a live `REFERENCE_ROLE_NOT_ALLOWED` (N-3) | `RoleNotAllowed` mapped to 409, or no `field` |
| 26 | C13 POST | `aPostWithARoleIs201AndReadsBack` (list and answer carry it); `aPostWithARoleOnANoteLinkIs422AndWritesNothing`; `anUnknownRoleNameIs400`; `aPostWithANullRoleIsNoRole`; `kindIsRefused…` (`:193`) unchanged | the handler drops `body.role` |
| 27 | C14 PATCH tri-state | `aPatchWithoutRoleLeavesItAlone`; `aPatchWithANullRoleClearsIt`; `aPatchWithARoleSetsIt`; `aRoleOnANoteRowIs422`; `aRoleOnlyNoOpIs200TheStoredRowAndWritesNothing`; `nullLeavesAFieldAlone…` (`:263`) unchanged for the name and description | `role = body.role ?: stored.role` (a `null` can no longer clear) |
| 28 | C15 reads carry the key | `theListCarriesRoleAndNullWhenNone` (the key present on every row) | none: `AssetReferenceDto` reuse |
| 29 | C15/C9 the sheet keeps the role on a rename | `ReferencesSectionViewModelTest` · `aRowCarriesItsStoredRole` | `row()` drops the role |
| 30 | C16 the snapshot | `CT/usecase/MaterializeReferenceTest` (+1) · `theSnapshotCarriesTheReferencesRole` | `prepare` passes `null` |
| 31 | C16 the prefill, no guessing (AC5–AC7) | `MaterializeViewModelTest` · the prefill case (`:221-225`) now `role == snapshot.role`; `theReviewNeverGuessesARoleFromTheText` (`:232-245`) **grows**: `snapshot.role = null` with a "Service manual" name → `null`; `USER_MANUAL` on a snapshot named "Service manual" → `USER_MANUAL`; `theCopiedRoleCanBeChangedOrClearedBeforeSave` (`chooseRole(null)` → the committed review's role `null`) | `reviewPrefill` keeps `role = null` |
| 32 | C17 the route tri-state | `MaterializeRoutesTest` · `anAbsentRoleCopiesTheSourceRole`; `aGivenRoleWins`; `anExplicitNullRoleIsNoRole`; `absentFieldsTakeThePrefill` (`:249-256`) keeps its meaning on a source with no role; `aNullNameStillTakesThePrefill` | `given.role ?: prefilled.role` (an explicit `null` copies the source) |
| 33 | C16 the kind stays the proven type's (R91-9) | `MaterializeViewModelTest` · `theKindIsTheProvenTypesWhateverTheRole` (a PDF with source role `SERVICE_MANUAL` → `DOCUMENT`) | the prefill kind from `editorKindFor(role)` |
| 34 | C18/C19 create | `test_reference_tools.py` · `add_reference(role="USER_MANUAL")` sends it; no role sends no key; `add_reference` without a role on a schema-16 phone POSTs with no gate (`test_season_health_tools.py:371` unchanged and green) | the gate applied on every call |
| 35 | C19 the gate | `…_a_role_refuses_schema_16_with_nothing_sent` for `add_reference(role=…)`, `update_reference(role=…)` and `update_reference(clear_fields=["role"])` (only `/v1/status` read) | the gate removed (a POST/PATCH is recorded) |
| 36 | C18 update | `update_reference(clear_fields=["role"])` → `{"role": null}`; `role` given → sent; `clear_fields=["description"]` refused locally; a field both given and cleared refused (the shipped helper's rule); `test_update_reference_sends_only_the_fields_it_was_given` (`:145-158`) unchanged | the cleared null dropped by `_body` (body `{}`) |
| 37 | C18 materialize | `test_attachment_tools.py` · `materialize_reference(clear_fields=["role"])` → `{"role": null}`; no role → `{}` (`:610-611` unchanged); **a schema-16 phone accepts both** (the gate stays 16) | `clear_fields` ignored (body `{}`) |
| 38 | C18 reads | `list_references` passes a schema-16 row without `role` and a schema-17 row with one | `role` added to `_REFERENCE_FIELDS` |
| 39 | C18 pins | 76 tools (both files); `kind` on neither reference write tool; no delete tool; **`clear_fields` absent on `add_reference` only** (`:73` moves); `materialize_reference`'s names (`test_attachment_tools.py:147-149`) gain `clear_fields`; the materialize docstring words (`:680-695`) unchanged and green; `test_tools.py:801-815` → "format 1–17" and "format **1–17**" (C-2) | none: pins |
| 40 | C21 share intake (R91-4) | `ShareIntakeViewModelTest` · `aWebLinkShareCarriesTheChosenRole` (the `AddReferenceCommand` role); `aWebLinkShareStartsWithNoRole`; `aLinkShareHasNoRole` (`:410-428`) **becomes** `aNoteLinkShareOffersNoRoleAndRecordsNone` (its note-share arm kept); `aSharedTitleNeverBecomesARole` (a link whose suggested name is "Service manual" → role `null`) | `role()` still gated on `BYTES` (row 1 of this set fails); `linkTakesRole` true for every link (the note-link case fails) |
| 41 | C21 the screen | `AT/share/ShareIntakeScreenTest` · `theRoleControlIsDrawnOnBytesAndNeverOnALink` (`:179-193`) **grows** into `…OnBytesAndOnAWebLinkAndNeverOnANoteLink`: a `LINK` state with `linkTakesRole = true` draws the header and four chips with "No role" selected; the shipped `LINK` arm (`linkTakesRole` false by default) still draws none, and the `BYTES` arm is unchanged (the derived getter, N-5) | none in-brief: a device case, run at the merged gate |
| 42 | C23 Add link (R91-5) | `T/ui/references/AddLinkFormTest` (new), the form driven by the view model's real `roleOffered` (C-4) · `noChipsUntilTheTextIsAWebLink` (empty, `joplin://…`, `mailto:` → not offered; `https://…`, `HTTP://…` with leading spaces → offered); `aPickIsClearedWhenTheTextStopsBeingAWebLink`; `aPickIsIgnoredWhileNotOffered`; `theRoleAtSaveIsNullForANonWebLink` | the pick kept when the text stops classifying `WEB_URL`; `offered` by `startsWith("http")` (the `HTTP://` and `http:`-less cases) |
| 43 | C22/C23 the view model | `ReferencesSectionViewModelTest` · `addLinkPassesTheRole`; `saveCarriesTheCommandsRole`; `roleOfferedIsTheKindAddReferenceWouldDerive`; the three command sites (`:405`, `:424`, `:495`) pass `role` | `addLink` drops the role |
| 44 | C22–C24 the sheets and the row | `AT/ui/references/ReferencesSectionTest` · `theEditSheetHasExactlyTwoFieldsAndTheUriIsNotOneOfThem` (`:223-246`) **grows**: a web-link row draws "Role" and its role's chip selected, a pick reaches the saved command, Save found by `performScrollTo()`; a note-link row draws no "Role"; still exactly two text fields. `eachKindDrawsItsOwnRatifiedWord` (`:132`) **grows**: a row with a role draws its label once; a row with none never draws "No role" | none in-brief: device cases, run at the merged gate |
| 45 | C26 the documents | `CommandShapesGoldenTest` · the range pins (`:132-166`: "format **1–17**", "17 since #91") and the `IDENTICAL` row (`:167-172`) gain the reference-role sentence; every code named in `v1.md` (`REFERENCE_ROLE_NOT_ALLOWED` included); `ReferenceRoutesTest:556-566` → 1–17; `theApiDocumentAgreesWithTheRouter`'s row list (`ReferenceRoutesTest.kt:597-604`) gains `^\| 422 \| `REFERENCE_ROLE_NOT_ALLOWED` \|` | `REFERENCE_ROLE_NOT_ALLOWED` missing from `v1.md` |
| 46 | policy | `ReleaseProofPolicyTest` unchanged and green | none: the tripwire |

**Moving pins — each named shipped assertion, the brief that may touch it, and why.** A brief touches no other
shipped assertion (the pin rule, "Briefs — common").

| pin | brief | moves, because |
|---|---|---|
| `T/VersionAgreementTest.kt:84`, `:153`; `T/api/MaintenanceRoutesTest.kt:1505` | B1a | the schema is 17 (the test method's name follows: "Seventeen…Sixteen", then B1b "…Seventeen") |
| `T/data/room/Migration8To9Test.kt:37` | B1a | the whole chain now adds `document_role`, a known delta like `V11_ASSET_COLUMNS` |
| C6's thirteen format literals (`CT/usecase/ExportBackupSetTest.kt:47` included); `VersionAgreementTest.kt:85`, `:154` | B1b | the format is 17 |
| `CT/backup/BackupFormat8Test.kt:277`, `:280` (and its KDoc) | B1b | its "one format past this build" archive is literal 17, readable at 17: it becomes 18 (C-1) |
| `CT/backup/BackupFormat7Test.kt:195-212` | B1b | `AssetReferenceDto`'s pinned element names and `elementsCount` become ten, `"role"` last (C-1) |
| `CT/usecase/UpdateReferenceTest.kt` (4 sites), `T/ui/references/ReferencesSectionViewModelTest.kt:405`, `:424`, `:495`, `AT/ui/references/ReferencesSectionTest.kt:245`, `CT/transfer/HeldWriteGuardTest.kt:412` | B2a | `UpdateReferenceCommand.role` has no default (C9); each passes the role it means (null on those rows) |
| `T/api/ReferenceRoutesTest.kt:428` (`everyReferenceProblemHasItsOwnCode`); `:342-388` (`everyReferenceCodeIsReachableOverTheWire`, "five" → six) | B2a | one more `ReferenceProblem` member and one more live code (N-3) |
| `CT/usecase/AddReferenceTest.kt:279-282`; `CT/usecase/UpdateReferenceTest.kt:115-118` | B2a | each pins its command's `declaredFields`, which gain `role` (C-1) |
| `T/ui/references/ReferencesSectionViewModelTest.kt:264-271` | B2a | `theRowStateCarriesNothingThatBelongsToBytes` pins `ReferenceRowState`'s fields, which gain `role` (C15, C-1) |
| `T/ui/references/MaterializeViewModelTest.kt:221-225`, `:232-245`; the three `SourceSnapshot(` test sites | B2c | the prefill copies `snapshot.role`; `SourceSnapshot.role` has no default |
| `T/api/MaterializeRoutesTest.kt:249-256` | B2c | re-read, not edited: its source has no role, so "no role" still holds — listed so a reviewer knows it was checked |
| `M/tests/test_reference_tools.py:1-12`, `:73`; `M/tests/test_attachment_tools.py:147-149` | B2b | `update_reference` and `materialize_reference` gain `clear_fields` (R91-3, R91-2) |
| `M/tests/test_tools.py:801-815` | B2b | `import_merge`'s docstring and README range read 1–17 (C-2, the `05dc90b0` precedent) |
| `T/share/ShareIntakeViewModelTest.kt:410-428`; `AT/share/ShareIntakeScreenTest.kt:174-193` | B3a | R67-9 is amended for web links (R91-4) |
| `AT/ui/references/ReferencesSectionTest.kt:132`, `:223-246` | B3b | the edit sheet gains a Role section on a web row; the row gains a role line (R91-13) |
| `AT/ui/references/ReferencesSectionTest.kt:281-303` | B3b | `AddLinkSheet` gains `roleOffered` and a four-argument `onSave` (C23, C-1); its string-set assertion still holds (empty text, no chips) |
| `T/api/CommandShapesGoldenTest.kt:132-172`; `T/api/ReferenceRoutesTest.kt:556-566`, `:597-604` | B4 | the import range reads 1–17; the `IDENTICAL` row gains the reference sentence; the document's code rows gain `| 422 | REFERENCE_ROLE_NOT_ALLOWED |` (C-3a) |

**Device rows: none new** (R91-13). #91 adds no platform-only fact: the column, the codec, the merge, the use cases,
the routes, the MCP and every rule a phone surface applies (C21's `roleOffered`, C23's form, C22/C24's state) are
JVM-proven. What remains on a device is Compose rendering of an already-proven state, which three **shipped** cases
already exercise; they grow (rows 41, 44) rather than a class or case being added. The sheets' reshape (C22) is
observed by `performScrollTo()` on Save in row 44, the idiom `ShareIntakeScreenTest` ships (`:183-186`). No row needs
the network, NFC, a picker or a second UID. **Known cost (N-10):** the grown cases first run at the merged-tip gate,
so a red there costs one fix round after the merge — accepted under the device-boundary rule.

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `C/model/{DocumentRole (new),Attachment,AssetReference}.kt`; `C/references/ReferenceKinds.kt`; `A/data/room/{entities/AssetReferenceEntity,ReferenceMappers,Migrations,AppDatabase}.kt`; `A/di/AppGraph.kt`; `app/schemas/…AppDatabase/17.json` (generated); `CT/references/ReferenceRolesTest.kt` (new); `T/data/room/{Migration16To17Test (new),MigrationTestSupport,Migration8To9Test,ReferenceDaoConstraintTest}.kt`; `T/VersionAgreementTest.kt`, `T/api/MaintenanceRoutesTest.kt` (schema lines only) | `C/backup`, `C/merge`, `C/usecase`, `A/api`, `A/ui`, `A/share`, `docs`, `tools`, `app/schemas/…/16.json` |
| B1b | `C/backup/{BackupFormat,BackupCodec}.kt`; `CT/backup/BackupFormat17Test.kt` (new); C6's thirteen format pins and its two structural pins (`BackupFormat7Test`, `BackupFormat8Test:277/:280`); `T/VersionAgreementTest.kt`, `T/api/MaintenanceRoutesTest.kt` (format lines only) | `C/merge`, `C/usecase`, `A/**` main, `docs`, `tools` |
| B1c | `C/merge/MergePlanner.kt`; `CT/merge/MergePlannerReferenceTest.kt`; `CT/transfer/ImportTransferPackTest.kt` (+1) | `C/backup`, `C/transfer` main, `A/**`, `docs`, `tools` |
| B2a | `C/usecase/{ReferenceCommands,AddReference,UpdateReference}.kt`; `A/api/{ReferenceDtos,ReferenceHandlers,ApiJson}.kt`; `A/ui/references/{ReferencesSectionViewModel,ReferenceSheets}.kt` (C12 arm 3, C15's field, C9's one site — no picker); `A/share/ShareIntakeViewModel.kt` (C12 arm 4 only); `CT/usecase/{AddReferenceTest,UpdateReferenceTest}.kt`; `CT/transfer/HeldWriteGuardTest.kt` (`:412`); `T/api/ReferenceRoutesTest.kt`; `T/ui/references/ReferencesSectionViewModelTest.kt`; `AT/ui/references/ReferencesSectionTest.kt` (`:245` only) | `C/backup`, `C/merge`, `MaterializeReference.kt`, `AttachmentHandlers.kt`, `A/share/ShareIntakeScreen.kt`, `docs`, `tools` |
| B2c | `C/usecase/MaterializeReference.kt` (`SourceSnapshot`, `prepare`'s one line); `A/ui/references/MaterializeViewModel.kt` (`reviewPrefill`); `A/api/AttachmentHandlers.kt` (C17 only); `CT/usecase/MaterializeReferenceTest.kt`; `T/ui/references/MaterializeViewModelTest.kt`; `T/api/MaterializeRoutesTest.kt`; the three `SourceSnapshot(` test sites | `MaterializeSheet.kt`, `C/fetch/*`, `A/fetch/*`, the lock and hand-off in `AttachmentHandlers.kt`, `docs`, `tools` |
| B2b | `M/src/servicetag_mcp/server.py` (the three tools, the gate, `import_merge`'s range); `M/README.md` (`:112`, `:356`); `M/tests/{test_reference_tools,test_attachment_tools,test_tools}.py` | `app/**`, `core/**`, `S/**`, `M/src/servicetag_mcp/command_shapes.py`, `docs` |
| B3a | `A/share/{ShareIntakeViewModel,ShareIntakeScreen}.kt`; `T/share/ShareIntakeViewModelTest.kt`; `AT/share/ShareIntakeScreenTest.kt` | `A/ui/**`, `C/**`, `A/api`, `tools`, `docs` |
| B3b | `A/ui/references/{ReferenceSheets,ReferencesSection,ReferencesSectionViewModel,AddLinkForm (new)}.kt`; `T/ui/references/{AddLinkFormTest (new),ReferencesSectionViewModelTest}.kt`; `AT/ui/references/ReferencesSectionTest.kt` | `A/share/**`, `A/ui/attachments/**`, `MaterializeSheet.kt`, `C/**`, `A/api`, `tools`, `docs` |
| B4 | `docs/api/v1.md`; `docs/release-proofs.md`; `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md`; `docs/versioning.md` (R91-11); `T/api/{CommandShapesGoldenTest,ReferenceRoutesTest}.kt` (the document pins only) | any `.kt` under `src/main`, `tools`, `docs/api/command-shapes.json` |

**Order:** B1a → B1b → B1c → B2a → B2c → { B2b ∥ (B3a → B3b) } → B4. B1a creates the enum's home, the predicate and
the column; B1b needs the domain field; B1c needs `FIRST_REFERENCE_ROLE_FORMAT`; B2a needs the domain field and the
predicate; B2c needs only `AssetReference.role` but follows B2a so that both wire changes the MCP speaks (the reference
routes and the materialize tri-state) exist before B2b; B2b needs the routes of B2a and B2c; B3a and B3b need B2a's command and problem. **B2b and B3a/B3b share no file** (`tools/servicetag-mcp/**` against
`app/src/**/share/**` and `app/src/**/ui/references/**`): under the owner's two-lane rule B2b may run in a second
worktree off B2c's tip while B3a → B3b run on the branch; the controller lands B2b's one commit on the branch before
B4, and B4's `<base>` is the tip holding both. Sequential execution is equally valid.

**Gate budget** at the merged tip (estimates; B1a records the base's exact counts): core 1659 + ~32 (predicate 3,
format 9, merge 11, pack 1, use cases 12, materialize 1); app 1702 + ~33 (migration 3, DAO 1, routes 12, materialize
routes 4, materialize VM 3, share VM 3, `AddLinkForm` 4, section VM 3); MCP 460 + ~12; loader 154 unchanged; device
classes **55, unchanged**, no new case, three grown (rows 41, 44). The whole gate timed against #92's merged-tip
record, reporting only, no rerun-until-green.

## 5. Strings

**No new user-visible sentence** (R91-5, R91-8, R91-14 decided). Every phone word #91 draws is shipped and ratified,
reused **verbatim** from its one home, and **approved unchanged on the new surfaces** (owner, 2026-09-30):

| string | resource (one home) | new surfaces |
|---|---|---|
| "Role" (the chips' header; drawn upper-case by `SectionHeader`) | `ROLE_HEADER`, `A/ui/attachments/AttachmentsSectionViewModel.kt:74-75`; `IntakeStrings.ROLE` (`A/share/ShareIntakeViewModel.kt:65`) | share intake on a web link (C21); Edit reference and Add link (C22, C23) |
| "No role", "Purchase invoice or receipt", "User manual", "Service manual" | `DocumentRole?.label()`, `AttachmentsSectionViewModel.kt:62-72` | the same chips |
| the chip order, "No role" first | `ROLE_CHOICES`, `AttachmentsSectionViewModel.kt:77-78` | the same chips |
| "Purchase invoice or receipt" / "User manual" / "Service manual" as a row line | `DocumentRole?.label()` (never its `null` arm) | the References row, one `QuietLine` (C24, R91-8) |
| "Edit reference", "Add link", "Link", "Name", "Description", "Save", "Cancel" | `A/ui/references/ReferenceSheets.kt:49`, `:82`, `:86`, the shared fields and `SheetButtons` (audit §5: `:183-184`) | unchanged |

**Gaps (audit §5):**
- **G1** — a refusal sentence for a role picked on a link that cannot take one. **Not needed** (R91-5 decided: the
  chips follow the live text, C23), nor for share intake (C21 decides once, from the shared URI), nor for C12's
  arms 3–4 (R91-14). The plan invents none.
- **G2 — awaiting ratification (the only open string).** The wire code `REFERENCE_ROLE_NOT_ALLOWED`, 422, `field`
  `role` (decided with R91-1), and its developer-facing `message`, literally:
  `a document role belongs on an http or https link` — in the shape of `attachmentRoleNotAllowed`
  (`A/api/ApiJson.kt:773-777`). B2a implements it as written; a different ratified wording is a one-line change in
  `ApiJson.kt` and its route assertion, closed by controller inspection.
- **G3** — an accessibility phrase naming the role on the row's overflow. **Not taken** (R91-8): the overflow stays
  `"More"` (`ReferencesSection.kt:237`); the role line is ordinary text TalkBack reads with the row.

**Developer-facing texts** (listed for the plan review, not phone strings): C2's `message`; C6's two `BackupCorrupt`
messages (in the shape of the attachment's, `C/backup/BackupFormat.kt:1025`, and #67's format-gate message,
`BackupCodec.kt:355-357`); C19's `APP_SCHEMA_TOO_OLD` text (the shipped template, with "the reference document role");
the MCP docstrings and README lines (C20). Of these, only C2's `message` (G2) is awaiting ratification.

## 6. Owner rulings (owner, 2026-09-30 — R91-1…14 DECIDED, recorded on issue #91)

The owner ruled "the five as stated" and ratified R91-11…14; every ruling is DECIDED at the plan's recommendation and
is binding as written. The HARD SCOPE sentence (§1) governs all of them.

| ruling | decision, binding as written | where it lands |
|---|---|---|
| **R91-1** (Q1; owner's 1) | **Roles are legal on web references only** — `WEB_URL`, http and https. A role elsewhere is `ReferenceProblem.RoleNotAllowed` → 422 `REFERENCE_ROLE_NOT_ALLOWED`, `field` `role` (its `message` is G2, §5) | C1, C2, C6, C10–C12, C21–C23; rows 1, 8, 20, 25, 26 |
| **R91-2** (Q2; owner's 3) | **Materialize inherits unless overridden or cleared**: absent → the source role exactly; a value → that value; `"role": null` → no role, read as a raw key; MCP `materialize_reference(clear_fields=["role"])` sends the null | C16, C17, C18, C26; limit 7; rows 31, 32, 37 |
| **R91-3** (Q3; owner's 2) | **Editing is nullable, with explicit set and clear**: on `PATCH`, absent = unchanged, `null` = clear, a name = set; name and description keep "null = unchanged"; MCP `update_reference(role, clear_fields=["role"])`, reversing `test_reference_tools.py:73` for that tool | C3, C14, C18, C19; rows 27, 36, 39 |
| **R91-4** (Q4; owner's 5) | **Intake chips on web links**: a `LINK` share whose URI classifies `WEB_URL` draws the Role chips after Description, "No role" preselected; bytes and notes unchanged. Amends R67-9 for web links | C21, C26 (the intake spec); rows 40, 41 |
| **R91-5** (Q5; owner's 2) | Add link draws the chips **only while the typed text classifies `WEB_URL`**; a pick is cleared when it stops doing so; no new sentence (G1 not needed) | C23, C12; row 42 |
| **R91-6** (Q6; owner's 4) | **Old rows have no role**: a NULL column only, never backfilled or inferred | C5; row 2 |
| **R91-7** (Q7; owner's 4) | **The merge handles pre-17 deliberately**: R67-12 option B mirrored on both the id and the pair arm; in format 17 a role difference is `CONFLICT` (same id) / `SKIPPED` (same pair, D-18 C); no update path | C7, C26; rows 12–17 |
| **R91-8** (Q8, G3; owner's 5) | **A quiet label**: the role's label verbatim as one `QuietLine`; never "No role"; the overflow stays "More". **A link is not a Key Document until materialized**: no Key documents or Details "Purchase document" treatment | C24; row 44 |
| **R91-9** (Q9; owner's 3) | **Kind untouched**: the Review keeps #85's proven-type kind; no role-to-kind derivation | C16; row 33 |
| **R91-10** (Q10, corrected; owner's 4) | **Old clients keep working**: `add_reference` / `update_reference` with a role, or clearing it, refuse below schema 17 with `APP_SCHEMA_TOO_OLD`, nothing sent; without a role, unchanged; `materialize_reference` keeps its schema-16 gate and gains none | C19; rows 34, 35, 37 |
| **R91-11** (Q11) | **RATIFIED:** schema 17 / format 17 may land on master with **no version bump or release**, validated on the emulator; the **release vehicle and phone promotion remain separate decisions**. The schema-17 release-proofs paragraph and the one `versioning.md` sentence are written with the change | C26, B4; §7 |
| **R91-12** (plan) | **RATIFIED: no** `command-shapes.json` entry for references | C26; B2b runs without B4 |
| **R91-13** (plan) | **RATIFIED:** "prove the field's propagation through appropriate existing coverage" — no new device class or case; the three shipped Compose cases grow (rows 41, 44); every rule is JVM-proven first | §3, B3a, B3b |
| **R91-14** (plan) | **RATIFIED:** the unreachable `RoleNotAllowed` arm draws nothing new — the References section says nothing; share intake joins `OwnerMissing / NoSuchReference / Unchanged → ownerGone()` | C12 |

**Still open:** G2's developer-facing `message` only (§5).

**The plan review's conditions (2026-09-30, `plan-review.md`) — where each landed.** C-1 → §3's pins table (seven
added), row 10 (thirteen format sites), B1b/B2a/B3b pin lists and widened confirm greps. C-2 → C20, B2b's touch and
pin lists, row 39, R6. C-3 → (a) C2/C26, row 45, R6 and B4's table grep; (b) B2a's scoped grep; (c) B3b's
"unchanged" grep. C-4 → C21 (a constructor parameter defaulting to `LinkLaunchPolicy()`), C23 (`AddLinkSheet` takes
`roleOffered`). N-1 → C14; N-2 → B2a and B3a; N-3 → row 25; N-4 → §12; N-5 → C21 (another way, header); N-6 → C24;
N-7 → B1c; N-8 → C26; N-9 → limit 7; N-10 → §3's device paragraph and §7.

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin` (every brief: B1b, B2a, B2c, B3a and B3b edit `AT/` sources); B2b `uv run
  --frozen pytest` in `M/` (and `S/`'s suite, unchanged, because the loader imports the MCP in process,
  `S/src/servicetag_schedules/phone.py:1-3`); B4 the app JVM suite (the document pins). No device run in any brief.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite on the emulator: **55 classes,
  unchanged**, zero skips; rows 41 and 44 run here for the first time — a red there is one post-merge fix round,
  N-10), R3 (the three Python suites), R5
  (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`), R6 greps. `ReleaseProofPolicyTest`
  unchanged and green. Timed; the 14- and 15-minute lines are reporting only.
- **R6 greps (anchored; `git grep -nE`), expected counts at the tip:**
  - `'^    const val FORMAT_VERSION = 17$'` in `C/backup/BackupCodec.kt` → 1;
    `'^    internal const val FIRST_REFERENCE_ROLE_FORMAT = 17$'` there → 1.
  - `'^    version = 17,$'` in `A/data/room/AppDatabase.kt` → 1; `'^        const val SCHEMA_VERSION = 17$'` in
    `A/di/AppGraph.kt` → 1.
  - ``'ALTER TABLE `asset_reference` ADD COLUMN `document_role` TEXT'`` in `A/data/room/Migrations.kt` → 1 (never a
    bare `document_role`, which `MIGRATION_9_10` already contains at `:585`); `'MIGRATION_16_17'` in `AppGraph.kt` and in
    `T/data/room/MigrationTestSupport.kt` → each equal to `'MIGRATION_15_16'`'s count there (2 and 1 at `66aa9987`:
    the import and the chain; the chain); `app/schemas/…/17.json` present, `16.json` unchanged
    (`git diff <base> -- app/schemas/**/16.json` → empty).
  - `'^enum class DocumentRole '` over `core/src/main` → 1, in `C/model/DocumentRole.kt`;
    `'^val ReferenceKind\.takesRole'` → 1 and `'^fun ReferenceKind\.accepts\('` → 1, both in `ReferenceKinds.kt`.
  - `'"REFERENCE_ROLE_NOT_ALLOWED"'` in `A/api` → 1 (the code arm); `'^\| 422 \| `REFERENCE_ROLE_NOT_ALLOWED` \|'` in
    `docs/api/v1.md` → 1 (the table is `| status | code | when |`, C-3a); `'REFERENCE_ROLE_NOT_ALLOWED'` in
    `app/src/test` → ≥ 1.
  - `'data object RoleNotAllowed'` in `C/usecase/ReferenceCommands.kt` → 1; `'else ->'` count in
    `ApiJson.kt`'s two reference `when`s unchanged (0 inside them).
  - `'^@mcp\.tool\('` in `M/src/servicetag_mcp/server.py` → 76; `'^_MIN_REFERENCE_ROLE_SCHEMA_VERSION = 17$'` → 1;
    `'_require_reference_role_schema\('` → 3 (the definition and the two callers), and **0** inside
    `materialize_reference`; `'format 1–17'` in `server.py` → 1 and `'format 1–16'` → 0; `'format \*\*1–17\*\*'` in
    `M/README.md` → 1 and `'1–16'` → 0 (C-2).
  - The tombstone check → 0; C25's baseline-equal greps; gitlink `7e0377a`; `versionName`/`versionCode` unchanged.
- **The emulator and the phones.** The merged-tip gate runs a debug build at schema 17 on the emulator only. The
  **schema 16 → 17 in-place upgrade** is **not** proven by this plan's gate: it is the next signed release's gate,
  written by B4 into `release-proofs.md` (C26) — the direct upgrade from each installed release, references with and
  without roles, roles given after the upgrade, the pre-upgrade export re-planning every row `IDENTICAL`, and the
  format-17 round trip. **Not proven on the emulator by #91:** a person picking a chip on the share screen from a real
  sharing app (the boundary tests prove delivery only, and #91 adds no boundary); the development and production
  phones (untouched; no install).

## 8. Relationships

- **#67 — the shipped attachment role.** `DocumentRole` moves to its own file unchanged (C1). **R67-9** (share intake:
  bytes only) is **amended** by R91-4 for web links, recorded on issue #91, in this header and in the intake spec
  (C26); the #67 plan is not edited. **R67-12 option B** is **mirrored** by R91-7 (C7); **R67-10** is **followed** by
  R91-11; **R67-11** (asset-owned attachments only) and **R67-7** (role independent of kind) are untouched and carried
  over to references (C1).
- **#43 — references and share intake.** I-1 (the uri never edited), I-6 (never re-owned) and D-21 C (Add link and a
  share write identical rows) all hold: both now pass the same `role` through the same `AddReference`.
- **#85 — Save as document.** `reviewPrefill`'s designation invariant (`MaterializeViewModel.kt:203-209`) is fulfilled
  (C16); the #85 plan's "`role` = none" row (`:633`) is superseded by R91-2 for a source with a role.
- **#92 — the materialize route and the MCP.** The #92 plan's §8 said the route "inherits it with no change"; that holds
  for an absent `role`, but an explicit `null` needs C17's raw-key read (R91-2). `materialize_reference` keeps #92's
  schema-16 gate (R91-10). Nothing in #92's lock, hand-off or operation key moves.
- **#69 — generalised resource owners.** The enum sits in `C/model/DocumentRole.kt` with no owner in its values; each
  owner states its own `accepts` (attachments by owner, references by kind, later owners by theirs); the column
  `document_role` and the DTO key `role` keep their names; hoisting the UI labels out of `ui/attachments` waits for
  #69 (§1).
- **#76 — the roadmap of record** places #91; **#90** (the gate-time trigger) is untouched: no harness, gate script or
  `tools/emulator/*` change, and the gate budget adds no device time beyond three grown cases.
- **#62 — the testing hierarchy:** no black-box UI driving; row 41/44's grown assertions are tier-2 Compose semantics.

## Briefs — common to all nine

Read §1–§8, the audit, issue #91 and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`, over
`app/src/main` and `core/src/main` unless named); the untouched diff (`git diff <base> --stat` against the brief's
"never touches"); the tombstone check → 0; C25's baseline-equal counts; `git ls-tree HEAD libs/nfc-tag-core` →
`7e0377a…`; each → 1: `'^\s*versionName = "1\.5\.0"$'`, `'^\s*versionCode = 18$'` in `app/build.gradle.kts`;
`git diff <base> -- app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml tools/*/uv.lock
app/src/main/AndroidManifest.xml` → empty; `git status` clean. B2b adds `uv run --frozen pytest` in `M/` and `S/`.

**Pin rule.** A shipped assertion moves only where §3's moving-pins table names it for this brief. Confirm the set
first with `git grep -nE 'UpdateReferenceCommand\(|SourceSnapshot\(|FORMAT_VERSION|formatVersion\)|schemaVersion\)|SCHEMA_VERSION|1–16|16 since|clear_fields|R67-9' -- app/src/test app/src/androidTest core/src/test tools/servicetag-mcp/tests`,
and report any hit the table does not name before editing it.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each grep
with its count; the test counts before and after; the wall time against the box; what is done, proven and not proven.

**Stop and report, always:** an edit to a never-touched file; an assertion that must move outside the pin list; a JVM
or build failure the brief did not cause (reported, not retried); any network access from a test; a phone string not
in §5; a schema, format, table or count change outside B1a/B1b; an owner question the brief finds undecided; the cap
reached, the 1-hour target passed with under half the rows green, or the 2-hour hard stop.

**Must NOT, always:** step outside the **HARD SCOPE** (owner, binding): "#91 = add `DocumentRole?` to HTTP(S)
References and propagate it faithfully through existing paths. Nothing more. No new role vocabulary, no inference, no
new document model, no new API tools, no new device class, no redesign of References, no Key Documents treatment for
links, no generalized resource work (#69)." Also never: delete a shipped assertion; commit outside the brief's files;
add a device class or case or run a device; bump a version; write a repository from a handler or a view model; re-check in the API or UI a rule the use
case owns; infer a role from anything (C25); compare a kind to `WEB_URL` for a role anywhere but C1; touch
`external_link`, `ExternalLinkEntity`, `LinkKind`, `ExternalLinkDto` or `externalLinks`; edit `16.json`; log a URI;
name a screen-driving tool under `tools/`; touch `tools/emulator/*`, a harness helper or the gate script; add a
dependency; write a ledger; write a real name, host, serial or e-mail address (fixtures: `example.invalid`).

## 9. B1a — the enum's home, the reference predicate and Room schema 17 (C1, C4, C5; core and app JVM)

**Read:** audit §0, §1, §2, §3 (Room), §9; `C/model/{Attachment,AssetReference}.kt`; `C/references/ReferenceKinds.kt`;
`A/data/room/{Migrations,AppDatabase,ReferenceMappers,Mappers}.kt`; `A/data/room/entities/{AssetReferenceEntity,
AttachmentEntity}.kt`; `A/di/AppGraph.kt:253-256`, `:1027`; `T/data/room/{Migration15To16Test,Migration8To9Test,
MigrationTestSupport,ReferenceDaoConstraintTest}.kt`; `T/VersionAgreementTest.kt`. **`<base>`** = master at dispatch
(today `66aa9987` plus this plan's commit). **Rows:** 1–5. **Rulings:** R91-1 (the predicate's rule), R91-6.
**Interfaces produced:** `C/model/DocumentRole.kt`; `ReferenceKind.takesRole` / `accepts(role)`;
`AssetReference.role`; the `document_role` column at schema 17 — consumed by every later brief.

**Connected:** none. **Greps:** `'^enum class DocumentRole '` over `core/src/main` → 1 (in `DocumentRole.kt`), 0 in
`Attachment.kt`; `'^val ReferenceKind\.takesRole'` → 1; `'^fun ReferenceKind\.accepts\('` → 1; ``'ALTER TABLE
`asset_reference` ADD COLUMN `document_role` TEXT'`` → 1; no `DEFAULT`, `UPDATE` or `CREATE INDEX` inside
`MIGRATION_16_17` (read it); `'^    version = 17,$'` in `AppDatabase.kt` → 1; `'^        const val SCHEMA_VERSION =
17$'` → 1; `'MIGRATION_16_17'` →
each equal to `'MIGRATION_15_16'`'s count in `AppGraph.kt` (2) and `MigrationTestSupport.kt` (1); `app/schemas/**/17.json` added, `16.json` unchanged;
`'FORMAT_VERSION = 16'` in `BackupCodec.kt` → 1 (the format is B1b's). **Pin list:** `VersionAgreementTest.kt:84`,
`:153`; `MaintenanceRoutesTest.kt:1505`; `Migration8To9Test.kt:37`. Confirm first with `git grep -nE
'16\.json|SCHEMA_VERSION|schemaVersion\)|version = 16' -- app/src/test`. **Untouched:** `C/backup`, `C/merge`,
`C/usecase`, `A/api`, `A/ui`, `A/share`, `docs`, `tools`. **Must NOT:** change a `DocumentRole` value, its order or its
package; add a default, backfill, index or CHECK to the column; move the backup format; construct an `AssetReference`
without passing the role where a row is read from storage. **Counted RED (3):** rows 1, 2, 4. **Caps:** 5 JVM mutation
runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 70 production, 190 test lines (plus the
generated `17.json`). **Estimate:** 50 min.

## 10. B1b — backup format 17 (C6; core JVM)

**Read:** audit §3 (format), §1's tombstone paragraph; `C/backup/BackupFormat.kt:367-417`, `:1000-1030`, `:1186-1210`;
`C/backup/BackupCodec.kt:25-35`, `:140-185`, `:315-375`, `:690-702`; `CT/backup/BackupFormat15Test.kt` (the shape to
follow); B1a's report. **`<base>`** = B1a's accepted tip. **Rows:** 6–11. **Rulings:** R91-1.
**Interfaces consumed:** `AssetReference.role`, `ReferenceKind.accepts`. **Produced:** `AssetReferenceDto.role`,
`FIRST_REFERENCE_ROLE_FORMAT` (for B1c), format 17.

**Connected:** none (`AT/backup/Format7RestoreContractTest.kt:164` is edited and compiled, run at the merged gate).
**Greps:** `'^    const val FORMAT_VERSION = 17$'` → 1; `'^    internal const val FIRST_REFERENCE_ROLE_FORMAT = 17$'` → 1;
`'val role: String\? = null'` inside `AssetReferenceDto` → 1 and it is the last field; `'ignoreUnknownKeys'` in
`BackupCodec.kt` unchanged from `<base>`; `'LAST_LEGACY_FORMAT = 7'` unchanged; the stale `v15` header line gone; the
tombstone check → 0. **Pin list:** C6's thirteen format literals (`ExportBackupSetTest:47` included);
`VersionAgreementTest.kt:85`, `:154`; `BackupFormat8Test.kt:277`, `:280` (17 → 18, with its KDoc);
`BackupFormat7Test.kt:195-212` (ten names, `"role"` last). Confirm first with `git grep -nE 'assertEquals\(1[67],
|formatVersion = 17|elementNames|elementsCount' -- core/src/test app/src/test app/src/androidTest` (C-1). **Untouched:**
`C/merge`, `C/usecase`, `C/transfer` main, `A/**` main, `docs`, `tools`. **Must NOT:** loosen strict decoding; reorder
`AssetReferenceDto`'s fields or `data.json`'s keys; re-derive `kind` from the uri at decode; accept a role on a
non-web row at decode. **Counted RED (3):** rows 6, 7, 8. **Caps:** 5 JVM mutation runs; **1 h target, 2 h hard
stop**; fix round 3 runs, 45 min. **Size:** about 45 production, 260 test lines. **Estimate:** 50 min.

## 11. B1c — the merge and the Transfer Pack (C7, C8; core JVM)

**Read:** audit §4; `C/merge/MergePlanner.kt:125-160`, `:765-790`, `:826-876`; `CT/merge/MergePlannerReferenceTest.kt`
(its eleven cases and fixtures); `CT/merge/MergePlannerTest.kt:1246-1350` (the #67 template);
`CT/transfer/ImportTransferPackTest.kt:120-160`; B1b's report. **`<base>`** = B1b's accepted tip. **Rows:** 12–18.
**Rulings:** R91-7. **Consumed:** `FIRST_REFERENCE_ROLE_FORMAT`, `AssetReferenceDto.role`.

**Connected:** none. **Greps:** `'FIRST_REFERENCE_ROLE_FORMAT'` in `MergePlanner.kt` → 1; `'sameReference\('` in
`MergePlanner.kt` → 3 (the definition and the two arms); `'dto == local\.toDto\(\)'` inside the reference pass → 0;
`'MergeReason\.|MergeVerdict\.'` counts in the reference pass unchanged from `<base>`; no new `MergeTable`,
`MergeReason` or tally member (`git diff <base> -- C/merge/MergePlan.kt` → empty, N-7).
**Pin list:** none (new cases only; the eleven shipped `MergePlannerReferenceTest` cases stay unchanged and green).
**Untouched:** `C/backup`, `C/transfer` main, `C/usecase`, `A/**`, `docs`, `tools`. **Must NOT:** add an UPDATE verdict
or any update path; apply the exception to format 17; touch arms 5–7 or the attachment pass; generalise `sameAttachment`
into a shared helper. **Counted RED (5):** rows 12, 13, 14, 15, 16 (row 18 fails under row 6's mutation, recorded).
**Caps:** 7 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 30
production, 240 test lines. **Estimate:** 45 min.

## 12. B2a — the use cases and the reference routes (C9–C15, C25's core half; core and app JVM)

**Read:** audit §1, §5 (the commands), §7, §8; `C/usecase/{ReferenceCommands,AddReference,UpdateReference}.kt`;
`A/api/{ReferenceDtos,ReferenceHandlers}.kt`; `A/api/ApiJson.kt:405-450`, `:676-716`, `:765-780`;
`A/api/ScheduleForms.kt:75-120`, `:150-158` (the raw-key idiom); `A/ui/references/{ReferencesSectionViewModel,
ReferenceSheets}.kt`; `A/share/ShareIntakeViewModel.kt:293-346`; `T/api/ReferenceRoutesTest.kt` (`:193`, `:263`,
`:351`, `:428`); B1c's report. **`<base>`** = B1c's accepted tip. **Rows:** 19–29. **Rulings:** R91-1, R91-3,
R91-14. **Produced:** the two commands, `RoleNotAllowed` and its code, the routes' `role`, `ReferenceRowState.role`
— consumed by B2c, B2b, B3a, B3b.

**Connected:** none (`AT/ui/references/ReferencesSectionTest.kt:245` edited and compiled only). **Greps:** row 24's
anchored `UpdateReferenceCommand` → 1; `'val role: DocumentRole\? = null'` in `AddReferenceCommand` → 1;
`'data object RoleNotAllowed'` → 1; `'"REFERENCE_ROLE_NOT_ALLOWED"'` in `A/api` → 1; `'JsonObject\.serializer\(\)'` in
`ReferenceHandlers.kt` → 1 (the PATCH only; the POST decodes typed); `'addReference\.run\('` → 1 and
`'updateReference\.run\('` → 1 in `ReferenceHandlers.kt`; `'\.upsert\('` in `A/api/ReferenceHandlers.kt` → 0;
`'takesRole|accepts\('` in `A/api/{ReferenceHandlers,ReferenceDtos}.kt` → 0 (the handler re-checks nothing) and over
`A/api` unchanged from `<base>` (1: `AttachmentHandlers.kt:136`, C-3b); `'ReferenceKind\.WEB_URL'` in
`AddReference.kt` and `UpdateReference.kt` → 0 (they ask C1); C25's baseline-equal counts; the count comments of N-2 moved (`ApiJson.kt:410` "ten arms"; `say`'s "five of the ten").
**Pin list:** the C9 sites; `ReferenceRoutesTest.kt:428`, `:342-388`; `AddReferenceTest.kt:279-282`,
`UpdateReferenceTest.kt:115-118`, `ReferencesSectionViewModelTest.kt:264-271` (§3's table). Confirm first with
`git grep -nE 'declaredFields|UpdateReferenceCommand\(|five of' -- core/src/test app/src/test app/src/androidTest` (C-1). **Untouched:** `C/backup`, `C/merge`, `MaterializeReference.kt`,
`AttachmentHandlers.kt`, `ShareIntakeScreen.kt`, the sheets' layout (only `ReferenceSheets.kt:55`'s command),
`docs`, `tools`. **Must NOT:** give `UpdateReferenceCommand.role` a default; let a `null` name or description clear
anything; draw anything new on a phone surface; add a sentence for `RoleNotAllowed` on the phone. **Counted RED (8):**
rows 19, 20, 21, 22, 23, 25, 26, 27 (row 29 is a one-line mapper; recorded without a separate run if row 27's
fixtures already cover it). **Caps:** 10 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min.
**Size:** about 95 production, 390 test lines. **Estimate:** 60 min — the plan's tightest brief; if the 1-hour mark
passes with under half the rows green, stop and report. **The split's limit (N-4):** adding `RoleNotAllowed` makes all
four `when`s non-exhaustive at once, so C9–C12 (rows 19–25) are compile-coupled and stay in B2a; a B2a′ can take only
C13/C14's handler changes and rows 26–28 (row 25 enumerates explicitly, so it does not break on the new member).

## 13. B2c — materialize carries the source role (C16, C17; core and app JVM)

**Read:** audit §6; `C/usecase/MaterializeReference.kt:60-120`, `:198-215`; `A/ui/references/MaterializeViewModel.kt:
120-215`; `A/api/AttachmentHandlers.kt:88-100`, `:262-330`, `:430-441`; `T/ui/references/MaterializeViewModelTest.kt:
200-250`; `T/api/MaterializeRoutesTest.kt:230-270`; `docs/api/v1.md:1340-1356`; B2a's report. **`<base>`** = B2a's
accepted tip. **Rows:** 30–33. **Rulings:** R91-2, R91-9.

**Connected:** none. **Greps:** `'role = snapshot\.role'` in `MaterializeViewModel.kt` → 1; `'role = null'` in
`reviewPrefill` → 0; `'editorKindFor'` in `A/ui/references` → 0; `'given\.role \?: prefilled\.role'` → 0;
`'"role" in raw'` (or the brief's named equivalent) in `AttachmentHandlers.kt` → 1; `'NonCancellable'`,
`'apiLongWrites\.withLock'`, `'downloads\.register'` counts in `AttachmentHandlers.kt` unchanged from `<base>`;
`'override fun toString\(\) = "SourceSnapshot\(host=\$host\)"'` → 1. **Pin list:** `MaterializeViewModelTest.kt:221-225`,
`:232-245`; the three `SourceSnapshot(` test sites (§3's table); `MaterializeRoutesTest.kt:249-256` re-read, not
edited. **Untouched:** `MaterializeSheet.kt`, `C/fetch/*`, `A/fetch/*`, the lock, the hand-off and the `finally`
discard, `docs`, `tools`. **Must NOT:** change the kind prefill; read a role from the name, description, title or bytes;
change what an absent `displayName`, `kind` or `notes` means; move #92's C33 lock boundaries. **Counted RED (4):** rows
30, 31, 32, 33. **Caps:** 6 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about
30 production, 170 test lines. **Estimate:** 40 min.

## 14. B2b — the MCP (C18–C20; pytest)

**Read:** audit §7 (MCP); `M/src/servicetag_mcp/server.py:170-292`, `:356-400`, `:1962-2056`, `:2800-2815`,
`:2985-3040`, `:3183-3260`, `:1163-1185`; `M/tests/{test_reference_tools,test_attachment_tools,test_argument_guard}.py`;
`M/tests/test_tools.py:790-815`; `M/tests/test_season_health_tools.py:360-380`; `M/README.md:100-130`, `:350-360`;
`git show --stat 05dc90b0` (the format-16 precedent); B2a's and B2c's reports (the wire).
**`<base>`** = B2c's accepted tip (a second worktree if run beside B3a/B3b; §4). **Rows:** 34–39. **Rulings:** R91-2,
R91-3, R91-10.

**Connected:** none; no app build. **Greps:** `'^@mcp\.tool\('` → 76; `'^_MIN_REFERENCE_ROLE_SCHEMA_VERSION = 17$'` →
1; `'_require_reference_role_schema\('` → 3, and 0 between `def materialize_reference` and the next `@mcp.tool`;
`'^_REFERENCE_CLEARABLE_FIELDS'` → 1; `"role"` not among `_REFERENCE_FIELDS`' nine; `'screen|uiautomator|adb shell
input'` under `tools/servicetag-mcp` unchanged from `<base>` (the tripwire's terms); `'format 1–17'` in `server.py` →
1, `'format 1–16'` → 0; `'format \*\*1–17\*\*'` in `M/README.md` → 1, `'1–16'` → 0 (C-2). **Pin list:**
`test_reference_tools.py:1-12`, `:73`; `test_attachment_tools.py:147-149`; `test_tools.py:801-815` (§3's table);
confirm first with `git grep -nE 'clear_fields|TOOL_NAMES|== 76|names\(server_module\.|1–16' -- tools/servicetag-mcp`. **Untouched:** `app/**`,
`core/**`, `S/**`, `command_shapes.py`, `docs`. **Must NOT:** add or remove a tool; add `kind` to either reference write
tool; gate `materialize_reference` or `list_references` at 17; require `role` on a read row; retry a materialize.
**Counted RED (5):** rows 34, 35, 36, 37, 38. **Caps:** 7 pytest mutation runs; **1 h target, 2 h hard stop**; fix
round 3 runs, 45 min. **Size:** about 75 production (code and docstrings), 205 test lines. **Estimate:** 50 min.

## 15. B3a — share intake offers the Role chips on a web link (C21; app JVM, one grown Compose case)

**Read:** audit §5 (intake); `A/share/ShareIntakeViewModel.kt:55-130`, `:160-180`, `:250-346`, `:455-485`;
`A/share/ShareIntakeScreen.kt:100-205`; `T/share/ShareIntakeViewModelTest.kt:380-430`;
`AT/share/ShareIntakeScreenTest.kt:1-60`, `:170-200` (the `form(…)` helper); `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md:459-466`;
B2a's report. **`<base>`** = B2c's accepted tip (B2b may be in flight beside it). **Rows:** 40, 41. **Rulings:**
R91-4, R91-14.

**Connected:** none run (row 41 is edited and compiled; it runs at the merged gate). **Greps:**
`'linkTakesRole'` in `ShareIntakeViewModel.kt` ≥ 3 (the field, its decision, the getter) and `'roleOffered'` ≥ 3 (the
getter, `role()`, `saveLink`); `'path == IntakePath\.BYTES'` in `role()` → 0;
`'private val linkPolicy: LinkLaunchPolicy = LinkLaunchPolicy\(\)'` → 1 (C-4); `ShareIntakeActivity.kt` untouched; `'if \(state\.roleOffered\)'` in `ShareIntakeScreen.kt` → 1; `'startsWith\("http'` over `A/share` →
0; `'suggestedName'` never on a line assigning `role` (read it); C25's baseline-equal count for
`ShareIntakeViewModel.kt`; the KDocs naming R67-9 (`:116`, `:266-269`; the screen's `:175-176`) name R91-4 (N-2).
**Pin list:** `ShareIntakeViewModelTest.kt:410-428`, `ShareIntakeScreenTest.kt:174-193`.
**Untouched:** `A/ui/**`, `C/**`, `A/api`, `tools`, `docs` (the spec amendment is B4's). **Must NOT:** draw the Type
chips on a link; offer a role on a note link, an `OTHER` link, a note share or a pack; change the byte path's order or
behaviour; add a string. **Counted RED (2):** row 40's two mutations. **Caps:** 4 JVM mutation runs; **1 h target, 2 h
hard stop**; fix round 3 runs, 45 min. **Size:** about 30 production, 130 test lines. **Estimate:** 35 min.

## 16. B3b — the reference sheets and the row (C22–C24; app JVM, two grown Compose cases)

**Read:** audit §5 (sheets, the component to reuse, strings); `A/ui/references/{ReferenceSheets,ReferencesSection,
ReferencesSectionViewModel}.kt`; `A/ui/attachments/AttachmentEditSheet.kt:55-125`;
`A/ui/attachments/AttachmentsSectionViewModel.kt:60-79`; `C/references/LinkLaunchPolicy.kt:28-45`;
`AT/ui/references/ReferencesSectionTest.kt:50-160`, `:223-310`; B3a's report. **`<base>`** = B3a's accepted tip.
**Rows:** 42, 43, 44. **Rulings:** R91-5, R91-8, R91-13.

**Connected:** none run (row 44 edited and compiled; it runs at the merged gate). **Greps:**
`'skipPartiallyExpanded = true'` in `ReferenceSheets.kt` → 2 (both sheets); `'verticalScroll\('` there → 1 (in
`SheetColumn`); `'takesRole'` over `A/ui/references` ≥ 2 (the edit sheet's `row.kind.takesRole`, the view model's
`roleOffered`); `'ReferenceKind\.WEB_URL'` in `ReferenceSheets.kt` and `AddLinkForm.kt` → 0, and unchanged from `<base>`
in `ReferencesSection.kt` (1, the kind label `:259`) and `ReferencesSectionViewModel.kt` (1, #85's `materializable`
`:247`) (C-3c); `'roleOffered: \(String\) -> Boolean'` in `ReferenceSheets.kt` → 1 (C-4);
`'startsWith\("http'` over `A/ui/references` → 0; `'ROLE_CHOICES'` ≥ 2 and `'"(No role|User manual|Service manual|Purchase
invoice or receipt|Role)"'` over `A/ui/references` → 0 (reused, never re-typed); `contentDescription = "More"` in
`ReferencesSection.kt` unchanged. **Pin list:** `ReferencesSectionTest.kt:132`, `:223-246`, `:281-303` (the
four-argument `onSave` and `roleOffered`, C-1); `ReferencesSectionViewModelTest.kt` command sites (already moved in
B2a; the `addLink` signature moves here). Confirm first with `git grep -nE 'AddLinkSheet\(|addLink\(' -- app/src/test
app/src/androidTest` (C-1).
**Untouched:** `A/share/**`, `A/ui/attachments/**`, `MaterializeSheet.kt`, `C/**`, `A/api`, `tools`, `docs`. **Must
NOT:** draw "No role" on a row; compose the kind and role into one line; offer a role on a non-web row or typed text;
send a role for a non-web link; add a refusal sentence; name the role in the overflow's description. **Counted RED
(3):** row 42's two mutations, row 43. **Caps:** 5 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs,
45 min. **Size:** about 100 production, 220 test lines. **Estimate:** 55 min.

## 17. B4 — the wire documents and the release-proofs paragraph (C26; docs and app JVM pins)

**Read:** audit §3 (versioning), §4 (the `IDENTICAL` row), §7; `docs/api/v1.md:200-220`, `:589-616`, `:1340-1356`,
`:1700-1726`, `:1940-1975`; `docs/release-proofs.md:100-118`; `docs/versioning.md`;
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md:455-470`; `T/api/CommandShapesGoldenTest.kt:40-175`;
`T/api/ReferenceRoutesTest.kt:540-612`; every earlier report on the branch. **`<base>`** = the tip holding B2b and
B3b. **Row:** 45. **Rulings:** R91-11, R91-12.

**Connected:** none. **Greps:** `'format \*\*1–17\*\*'` and `'\*\*format 1–17\*\*'` in `v1.md` each ≥ 1, and `'1–16'`
→ 0; `'17 since #91'` → 2; `'^\| 422 \| `REFERENCE_ROLE_NOT_ALLOWED` \|'` → 1, its "when" cell naming `field` `role`
(C-3a); `'"role": null'` or the tri-state sentence
present in both the `PATCH` and the materialize sections (read them); `'Room schema 17 / backup format 17'` in
`release-proofs.md` → 1, placed after the #92 note and before `## Environment notes`; `'Amendment \(#91, owner ruling
R91-4'` in the intake spec → 1; `docs/api/command-shapes.json` unchanged (R91-12: no entry); the `release-proofs.md`
paragraphs for 1.5.0 and #92 unchanged (`git diff` touches only added lines there). **Pin list:**
`CommandShapesGoldenTest.kt:132-172`, `ReferenceRoutesTest.kt:556-566` and `:597-604` (`theApiDocumentAgreesWithTheRouter`
gains the 422 row pattern, C-3a). **Untouched:** any `.kt` under `src/main`,
`tools`, the #67 and #85 plans. **Must NOT:** edit the 1.5.0 erratum or the #92 note; claim a proof #91's gate does not
run (the upgrade is the release's); name a device serial or the owner's data. **Counted RED (1):** row 45. **Caps:** 2
JVM mutation runs; **1 h target, 2 h hard stop**; fix round 2 runs, 45 min. **Size:** about 90 document lines, 25 test
lines. **Estimate:** 40 min.
