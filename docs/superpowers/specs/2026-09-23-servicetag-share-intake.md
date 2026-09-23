# ServiceTag share intake (#43) — DESIGN SPEC (FINAL, owner-ruled)

Status: **FINAL SPECIFICATION**, 2026-09-23. Every decision the drafts posed has been ruled by the
owner in `owner-rulings-2026-09-23.md`; this revision states the ruled behaviour as **contract**, not
as options. All 48 user-visible strings are **RATIFIED** (§10). Not a plan and not an implementation:
next is the master plan and the per-component briefs.

Scope source: issue **#43**, with the owner's ruling of 2026-09-23 fixing the first release to the
smallest useful local-first slice. Governing documents: `docs/superpowers/planning-policy.md`,
`docs/versioning.md`, `docs/api/v1.md`, `docs/design/09-security-privacy.md`, and the shipped code in
`core/…/core/{model,ports,backup,merge}/`. **#7** (attachment/storage foundation, the `AttachmentStore`
port, SAF-managed storage, backup inclusion) and **#35** (untrusted-input policy) are DONE and are
extended here, never re-invented. **#45** replication, **#15/#47** parts and **#44**'s conflict UI are
not prerequisites. No private data; every example is fictional; public-repository material.

## Rulings (2026-09-23)

| Decision | Ruling |
|---|---|
| D-6 share-sheet MIME types | **A** — the explicit useful list (§4.1) |
| D-12 automation API and MCP for references | **A** — `GET`/`POST`/`PATCH`, no `DELETE`, no bytes, so the domain has an automation surface from day one (§6) |
| D-14 exported-component audit | **A** — the exact set grows from two names to three; #35 AC 3 amended (§4.1) |
| D-18 same URL on the same asset from two phones | **C** — `(asset, uri)` stays unique; identical → `IDENTICAL`, diverged → `SKIPPED`, never `CONFLICT` (§5) |
| D-19 a shared document's description | **A** — a second quiet line on the Documents row (§7) |
| D-20 a document shared before any attachment folder | **A** — one sentence, Save disabled, Close (§2, §7) |
| D-21 in-app "Add link" and `provenance` | **C** — "Add link" ships; there is **no** `provenance` column (§3.2) |

Strings: all 48 ratified as listed, including the two called out for a deliberate answer — the intake
title **"Save to ServiceTag"** (not the mock's "Share to ServiceTag") and the no-attachment-folder line
**"Choose an attachment folder in ServiceTag Settings, then share this again."**

Version: **1.3.0 / versionCode 15**, Room schema **7**, backup format **7** (§12).

---

## 1. Purpose and scope

### 1.1 What this release delivers

The share sheet sends one item into ServiceTag, the owner picks the asset, names and describes it, and
it is saved — **bytes as an ordinary #7 attachment, a URI as a new `asset_reference` row** — then shows
on the asset and opens with the system. The owner's eight items: (1) `ACTION_SEND` intake; (2) an asset
chooser; (3) a URL / web reference; (4) one document, PDF or image; (5) an editable display name and
description on both paths; (6) external note links such as Joplin, through a generic scheme mechanism;
(7) a place on the asset that shows and opens them; (8) hardened URI and grant handling, with the new
metadata in backup and merge. Plus, under D-12 and D-21, a reference surface on the automation API and
an in-app "Add link" action.

### 1.2 What this release does not deliver

| Out | Why |
|---|---|
| `ACTION_SEND_MULTIPLE` | #43 defers it. No multi-share filter is declared, so the sheet does not offer ServiceTag for one. |
| A `REFERENCE`-mode attachment from a share | A share grant is not persistable. `AttachmentMode.REFERENCE` and `StorageProvider.SAF_DOCUMENT` are declared in `Attachment.kt` and have **no production writer anywhere** — #7 (b) closed without one — so this closes off #43's "Reference original" row and revives nothing. |
| Replication | #45 owns it. No provider id, remote id or account reaches this model. |
| A `provenance` field distinguishing shared-in from added-in-app | D-21(C). Nothing reads it, and a format field is forever. |
| Sharesheet shortcuts, OCR/LLM classification, the #42 handoff | Later phases, named in #43. |
| Reviving `external_link` (§3.4); an NFC tag bound to a reference (#43 AC 10) | Forbidden. NoteTag remains the tag-to-note product. |
| Editing a saved URI, or moving a reference between assets | I-1, I-6. Delete and re-add. |
| Preferences for the caps or the scheme list | They are constants; #18 owns preferences. |

---

## 2. The user flow

**The happy path.** A browser on a fictional manual page `https://example-mower.invalid/xt1/manual.pdf`.
Share → **ServiceTag** → the intake screen opens over the sharing app with what arrived, an asset
chooser, a name prefilled from the page title, and an empty description. Choose "Cub Cadet XT1", type
"OEM parts lookup", **Save**. The screen finishes, the browser is back, and the reference is in the
asset's **References** section.

**A document or image.** The same screen; the bytes copy into the attachment folder through the shipped
#7 path that "Add file" already uses, becoming an ordinary attachment row in **Documents**, with the
description in `notes` and visible on the row (§7).

**A Joplin note.** Joplin shares `[Mower maintenance](joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978)`.
The parser takes the URI and offers **Mower maintenance** as the name; it saves as a `NOTE_LINK` and
later starts Joplin through `ACTION_VIEW`. With Joplin uninstalled: one sentence, and nothing changes.

**No assets yet.** One sentence and Close. Nothing is saved, and no asset-creation flow runs inside the
share task.

**No attachment folder picked.** `AddAttachment` refuses first with `AttachmentProblem.NoStore` when
`storage.state()` is `NotConfigured`, and `StoreUnavailable` on `AccessLost`. Intake cannot offer the
shell's "Open settings" affordance without breaking its finish-back-to-the-sharer contract, so a
**byte** share shows one sentence, disables Save and offers Close; nothing is staged and nothing is
copied. A **URI** share on the same phone is unaffected — a reference needs no folder — and the screen
says nothing about storage on that path.

**Running versus cold.** Its empty `taskAffinity` keeps the intake activity out of `MainActivity`'s
`singleTask` stack. Cold, it builds the graph as `MainActivity` does and finishes back to the sharer;
warm, the shell behind it is not navigated, recreated or scrolled. Cancel or back writes nothing. A
process recreation mid-intake either still reads the grant — it lives until the receiving **task**
finishes, not for one read — or refuses the copy cleanly and writes nothing.

---

## 3. Domain model

### 3.1 The split: bytes are attachments, URIs are references

**What a shared thing *is* decides where it lives, not how it arrived.** A stream becomes an
`attachment`; a URI-only share becomes a new `asset_reference`.

That is why `Attachment` is not widened. Every field it requires is a fact about bytes: `sizeBytes`,
`sha256`, `storageProvider` (`SAF_TREE` or `SAF_DOCUMENT` — there is no third member), `storageLocator`,
`mimeType`. A web URL has none of them, so an `EXTERNAL_URI` mode would write sentinels into five
columns, and three shipped invariants would each need an exception: `AttachmentLocator.matchesShape` is
asserted by the backup reader against every attachment row with no mode branch; the artifacts archive
is one entry per MANAGED row; and `MergePlanner` step 5 answers `ATTACHMENT_BYTES_ABSENT` / `SKIPPED`
for exactly the shape a URL row would always have, so references would arrive on every phone as skipped
rows. The mode such a row would sit beside has never been written by anything, so that path would stack
a second never-written mode on a first.

Conversely a shared PDF must **not** become a reference: #7 already stores documents correctly, backup
included, and a second file store is what #43's own sequencing forbids.

### 3.2 `asset_reference`

```
id            ReferenceId    new inline value class beside Ids.kt's nine
asset_id      AssetId        NOT NULL, FK → asset, ON DELETE CASCADE
kind          ReferenceKind  WEB_URL | NOTE_LINK | OTHER
uri           TEXT NOT NULL  stored verbatim as validated; never rewritten
display_name  TEXT NOT NULL  non-blank, ≤ 200 chars
description   TEXT NOT NULL  may be empty, ≤ 2,000 chars
scheme        TEXT NOT NULL  lowercased, derived on write: the provider hint
created_at, updated_at       INTEGER NOT NULL
UNIQUE(asset_id, uri)        INDEX(asset_id)
```

Nine columns. There is **no `provenance` column** (D-21 C): an in-app "Add link" and a share write
identical rows, because nothing in the product distinguishes them.

`kind` is three values, not #43's provisional seven: the byte-bearing kinds already exist as
`AttachmentKind`, and a second overlapping vocabulary is how two catalogs start. It is also
deliberately the opposite choice from the retired tombstone's `LinkKind`
(`{JOPLIN, OBSIDIAN, LOGSEQ, WEB, OTHER}`), which was a per-app vocabulary 2.6 retired. A reference's
kind is a fact about its scheme; its subject ("OEM parts lookup") belongs in the description.

`scheme` is derived, not identity: it is the provider hint #43 asks for, recomputed from `uri` on every
write so the two cannot disagree. It is **not** a provider account or remote id — #45's boundary holds.
Only an asset owns a reference; an event owner later would be one nullable column plus a `CHECK`.

`kind` is inferred from the scheme and is not a picker: `WEB_URL` for `http`/`https`, `NOTE_LINK` for an
allow-listed app scheme, `OTHER` otherwise. The byte path is the opposite — there the Type control is
shown, prefilled with `AttachmentKinds.inferFrom`, because it yields only `PHOTO`, `DOCUMENT` and
`OTHER` and only the owner knows a receipt from a manual.

### 3.3 Invariants

- **I-1** `uri` is stored exactly as validated and is never edited after creation. Only `display_name`,
  `description` and `updated_at` are mutable.
- **I-2** No reference is created whose scheme is hard-blocked (§4.2). Refused **in the use case**, so
  no API or MCP path can smuggle one in.
- **I-3** A reference has no bytes: no locator, no sha256, no artifacts entry, and it is never handed
  to `AttachmentStore`.
- **I-4** A reference is never a tag target and never appears in `TagTargets` (#43 AC 10).
- **I-5** Nothing in this feature reads, writes or re-purposes `external_link`, `ExternalLink`,
  `LinkRepository` or the format-5 `externalLinks` field. §3.4.
- **I-6** A reference cannot change owner. Re-parenting is delete plus re-add.
- **I-7** `UNIQUE(asset_id, uri)`. The same URI on two different assets is ordinary and permitted.
- **I-8** Cancelling intake at any step writes nothing: no row, no bytes, no grant, no journal event
  (#43 AC 6).
- **I-9** A byte share's stream URI is **`content://` or it is refused**, and its authority is never one
  of ServiceTag's own. `ContentResolver.openInputStream` resolves `file:` with ServiceTag's own uid, and
  `${applicationId}.files` is a non-exported FileProvider only ServiceTag can read, so either would turn
  intake into a self-exfiltration primitive.
  **Where it is enforced:** not in `AddAttachment`, which cannot see a URI — `:core` holds no Android
  type anywhere, `ByteSource` is `fun interface ByteSource { fun open(): InputStream }`, and
  `AddAttachmentCommand` carries no URI, the `ByteSource` being constructed in `:app`. The predicate is
  therefore **Android-free `:core` code taking the scheme and authority as plain strings**, with
  ServiceTag's own authorities injected as configuration — the same shape and module as
  `LinkLaunchPolicy` — and the `:app` stream reader calls it **before the `ByteSource` is constructed**,
  so a refused URI is never opened. I-2 differs because a *reference's* scheme is a plain string on a
  `:core` command, so its refusal genuinely does live in the use case and closes the API and MCP paths
  too; a stream has no API path at all, the loopback API carrying no bytes.

### 3.4 The 2.6 tombstone rule

`external_link` and the format-5 `externalLinks` field were retired with the note-link feature at 2.6
and survive only as tombstones: exported and restored byte-for-byte, carried through merge, never
displayed, never acted on. **They must not be bumped, re-purposed or discarded**, and this spec touches
none of them. `asset_reference` is a new table with a new name, id type, merge entry and shape; that
both hold a URI is not a reason to reuse a tombstone. `v1.md`'s refusal of anything at all for the
`externalLinks` tombstones is unchanged.

---

## 4. Security — the #35 extension

### 4.1 The exported share target

`com.loosecannon.servicetag.share.ShareIntakeActivity` — this exact fully-qualified name is what
`ManifestContractTest`'s expected set asserts — with `exported="true"`, `taskAffinity=""`,
`excludeFromRecents="true"`, `launchMode="standard"`, and filters declaring `ACTION_SEND` and
`category.DEFAULT` over exactly these types:

`text/plain`, `text/uri-list`, `image/*`, `application/pdf`, `text/markdown`, `text/csv`,
`application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`,
`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/zip`.

No `BROWSABLE`, `ACTION_VIEW`, `ACTION_SEND_MULTIPLE` or `PROCESS_TEXT`. No allow-list gate runs *after*
the filter (§4.3). `MimeTypes.EXTENSIONS` does not carry `text/markdown`, `text/csv` or
`application/msword`, so `AttachmentLocator.extension` takes their extension from the shared filename
and falls back to `bin` — correct existing behaviour, not a gap to close by growing that table.

The activity reads **only** `EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT` and `EXTRA_TITLE`, all as
data; every other extra is ignored. `EXTRA_STREAM` uses the API-33+ typed
`getParcelableExtra(name, Uri::class.java)`, never the deprecated overload, which returns whatever a
hostile parcel names. The two text extras are capped to the 200-character name cap and sanitised before
becoming a display name, as the filename is.

**Three exported components where #35 AC 3 asserts two.** #35 AC 3 is amended to name three, and the
assertion stays exact. The change is to
`ManifestContractTest.theExportedComponentSetIsExactlyTheTwoShippedActivities`, which parses the
**source** manifest and asserts an exact **set** — `com.loosecannon.servicetag.MainActivity`,
`com.loosecannon.servicetag.nfc.NfcDispatchActivity` — plus "no other component kind carries
`exported="true"`": add the share activity's name to that set and rename the test off
"TheTwoShippedActivities". It is **not** made a count of three, because that file's own KDoc records
that the merged manifest carries library-injected components which "would make an exact-count assertion
flaky". `everyReceiverIsNonExportedAndThereAreSix` stays at six, and
`MergedManifestContractTest.theMergedManifestPermissionSetIsExactly` is **unchanged** — `ACTION_SEND`
intake needs no new permission, which is itself worth asserting.

### 4.2 Outbound launching

ServiceTag has no `LinkLaunchPolicy` today; it went with the note-link feature. This release
reintroduces it as new `:core` code, no Android types, in three tiers — both lists #35's shipped ones,
verbatim:

- **Allowed:** `http`, `https`, `joplin`, `obsidian`, `logseq`.
- **Hard blocked, at save and at launch:** `javascript`, `file`, `content`, `intent`, `android-app`,
  `tel`, `sms`, `mailto`, and no scheme at all.
- **Everything else:** saved after one explicit confirmation, then launched without a second.

Both save and launch apply it, so a URI that was legal when saved and is not now is shown and refused,
never launched. `<queries>` today holds one `VIEW` + `content` + `*/*` entry and gains one `ACTION_VIEW`
entry per allowed scheme for API 30+ package visibility; `ActivityNotFoundException` is always caught.
`content://` is blocked as a *reference* and always was the attachment path's business.

### 4.3 The stream, grants and bytes

**Scheme and authority (I-9) are checked first**, by the `:core` predicate the `:app` stream reader
calls before it constructs the `ByteSource`, so a refused URI is never opened at all.

`ACTION_SEND`'s `FLAG_GRANT_READ_URI_PERMISSION` lives until the receiving **task** finishes — it is
not one-shot — but `takePersistableUriPermission` throws on it regardless, not having come from
`ACTION_OPEN_DOCUMENT` or `ACTION_OPEN_DOCUMENT_TREE`. So **a share always copies**, through the shipped
`AttachmentCommands` path, retaining nothing that expires. That is what #43 AC 7 demands, and it keeps
ServiceTag clear of the 512 persisted-grant cap #7 warns about.

Limits: one item; `MAX_ATTACHMENT_BYTES` of 256 MiB, checked against the declared size before the copy
and against the bytes actually read; the URI capped at 2,048 characters, the display name at 200 and
the description at 2,000; the filename sanitised to a path-free basename; and a zero-length stream
refused **in the intake layer** — `AttachmentProblem`'s KDoc says "the list is closed", and a new member
would change the shipped camera and picker paths and the exhaustive `when` in
`AttachmentsSectionViewModel.say`, so intake owns that refusal and its own string, with the accepted
consequence that other callers still accept an empty file.

**The declared MIME type is trusted, and "MIME sniffed on import" is retired.**
`docs/design/09-security-privacy.md` states sniffing as a control; today `AttachmentKinds.inferFrom` and
`AttachmentLocator.extension` both read the declared type and nothing sniffs. That is defensible now
that bytes are never rendered or executed, but a stated control is being dropped, so that row is amended
in the same commit rather than quietly diverging. (Its "size cap configurable" likewise becomes a
constant; #18 owns preferences.)

### 4.4 Text, logging, rendering

`text/plain` and `text/uri-list` are scanned for their first URI token; **raw text is never stored as a
URI**, #35's own rule. Text that is not a URI is offered as a `NOTE` journal event and never guessed at
(#43 AC 5); `EventKind.NOTE` already exists, so no new enum member. Nothing shared is executed,
interpreted as a command or rendered in a WebView. No URI, filename or shared text is logged at INFO or
above, and the visible refusal strings name no URI, scheme, authority or path either.

---

## 5. Schema, backup and merge

**Room schema 6 → 7**: one new table, one new index, a pure addition. No existing column or row changes.

**Backup format 6 → 7.** `BackupCodec.FORMAT_VERSION` 6 → 7. `BackupData` gains one list with an empty
default — the pattern its seven existing defaulted lists use (three are non-defaulted):
`val assetReferences: List<AssetReferenceDto> = emptyList()`. The DTO is the table's nine columns:
`{id, assetId, kind, uri, displayName, description, scheme, createdAt, updatedAt}` — **no `provenance`**
(D-21 C). The encoder sorts by `id`; `counts` gains `assetReferences`.

**The decoder does three things, not one**: the `formatVersion > FORMAT_VERSION` refusal
(`BackupNewerFormat`) is unchanged; `uniqueIds("assetReferences", …)` runs as it does per list; every row
is validated by `toDomain()` in the same eager pass; and a row whose `assetId` is absent from the
archive's own assets is refused, exactly as `externalLinks` and `attachments` are. In-archive
`(assetId, uri)` uniqueness is deliberately **not** checked at decode — that is what keeps
`REFERENCE_DUPLICATED_IN_ARCHIVE` reachable, as `CLOSURE_DUPLICATED_IN_ARCHIVE` is.

**Forward-only:** 1.3 reads formats 1–7; a 1.2.x app refuses a format-7 archive loudly through the
existing `BackupNewerFormat` path rather than dropping rows. A reference has no bytes, so the artifacts
archive and its tallies do not move; the URI is metadata in `data.json`, retained after a restore even
when the target app is absent, which is how the association survives phone replacement.

**`MergeTable` gains `REFERENCES`, last.** Its KDoc's rule is that every reference a row makes points at
a table declared earlier, and a reference points only at an asset, so any position after `ASSETS` is
correct and last is the smaller diff. The KDoc's "ten canonical tables" becomes eleven and
`MergeWrites`' write-order comment follows; `MergeWrites`, `MergeSnapshot` and `MergeReport` each gain
`references`, making eleven tallies.

| Table | Identity | Unique constraints | Reasons (**new in bold**) |
|---|---|---|---|
| `REFERENCES` | row `id`, **plus `(asset_id, uri)` independently of the id** | that pair, against the destination *and* the archive's own accepted rows | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (shipped, reused); **`REFERENCE_DUPLICATED_IN_ARCHIVE`**; **`REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`** on an `IDENTICAL`; **`REFERENCE_HELD_BY_A_LOCAL_ROW`** on a `SKIPPED` |

Three new `MergeReason` members, parallel to the shipped closure trio.

**The rule (D-18 C).** Id absent → `INSERT`. Id present and every backup-format field equal →
`IDENTICAL`. Id present and different → `CONFLICT / CONTENT_DIFFERS`, the shipped shape for every table.
A **different** id holding the same `(asset_id, uri)`: field-for-field equal →
`IDENTICAL / REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`; otherwise **`SKIPPED` /
`REFERENCE_HELD_BY_A_LOCAL_ROW`** — never `CONFLICT`. There is still no `UPDATE` verdict, and one
conflict anywhere empties `MergeWrites`.

**Why a skip and not a conflict.** `MergePlanner`'s contract is that "canonical content is every
backup-format field, `createdAt` and the last-modified stamp included, compared as
`incoming == local.toDto()`", and the equivalent arm is `dto.copy(id = samePair.id) == samePair.toDto()`.
The closure pass can rely on that only because "a closure has no `updated_at` and no mutable field"; an
`asset_reference` has two human-typed mutable fields and two timestamps, on which two phones agree about
never. Decisively, **there is no `UPDATE` verdict anywhere in the planner**, so the incoming
`display_name` and `description` could never be adopted under any rule — a `CONFLICT` on a diverged pair
therefore cannot produce a better merged state than a skip, it can only refuse the entire archive, and
there is no #44 conflict UI to resolve it with. `SKIPPED` already means "the plan declining to write a
row it could otherwise have written", and `applicable` is `conflicts.isEmpty()`, so a skip cannot block
an apply. No new verdict and no new comparison mechanism is introduced.

**This is the codebase's first declining second identity**, and that is stated rather than glossed: the
two shipped ones raise a `CONFLICT` on divergence (`PAYLOAD_HELD_BY_A_DIVERGED_LOCAL_TAG` on tags,
`CLOSURE_DIVERGED` on closures), and the attachment pass is no precedent, its non-blocking declines being
about bytes while its identity collision `ATTACHMENT_LOCATOR_TAKEN` is a `CONFLICT`. Departing is right
on the merits: those two guard an NFC payload and an immutable closed round, each of which a human must
arbitrate, whereas two descriptions of one URL are not a disagreement about anything.

**Two costs of the rule, accepted.** A skipped reference is visible only as a count — `MergeReport` lists
conflicts per row but `MergeTally` carries `skipped` as an integer — so the owner sees
`references: {… skipped: 1}` without learning which URI was passed over. And references are **not**
incapable of blocking an archive: the same-id/different-content arm is still `CONFLICT / CONTENT_DIFFERS`.

---

## 6. API and MCP surface

API version stays **1**, extended additively; `/v1/status` counts gain `assetReferences`.

| method | path | body | success | notes |
|---|---|---|---|---|
| `GET` | `/v1/assets/{id}/references` | — | 200 | `{references: [AssetReferenceDto]}`, by `displayName` |
| `POST` | `/v1/references` | `{assetId, kind, uri, displayName, description}` | 201 | `{reference}` |
| `PATCH` | `/v1/references/{id}` | `{displayName?, description?}` | 200 | `{reference}`; `uri`, `assetId` and `kind` are **unknown fields** here (I-1, I-6) |

`AssetReferenceDto` is the backup DTO's nine fields, so the request shape is a subset of the response
shape, as the shipped commands are.

**No `DELETE`, and no bytes, ever.** Deleting a reference joins "What has no endpoint, deliberately"
beside attachments and closures: the API adds and amends, the phone removes. No endpoint accepts or
returns a file, so the loopback API still carries no attachment bytes, and there is no share-by-API.

New codes, `UPPER_SNAKE` like 1.2's, in their own "The reference codes (1.3.0)" subsection rather than
folded into 1.1.0's `lower_snake` list: 404 `NO_SUCH_REFERENCE`; 422 `REFERENCE_URI_INVALID`;
422 `REFERENCE_SCHEME_BLOCKED` — a refusal, never a confirmation, over the API; 409 `REFERENCE_URI_TAKEN`.

MCP (`tools/servicetag-mcp/`) gains `list_references`, `add_reference` and `update_reference` under the
shipped conventions: `null` means unchanged, unknown arguments are rejected, and every error path is a
real `ToolError` carrying the code.

Four `docs/api/v1.md` edits: the import-merge rows' "a data archive of format **1–6**" → **1–7**; the
405 row's "eight `/v1/assets/{id}/…` sub-resources" → **nine**, with `/v1/assets/{id}/references`
answering **404** for a verb it does not take while `/v1/references` and `/v1/references/{id}` are
**405** path shapes; and the merge-report sentence's "ten tables" → eleven, that same sentence's field
list gaining `references` in write-order position.

---

## 7. UI surfaces

**Asset detail gains a References section** below Documents, built from the same section primitives. Two
sections and not one, because Documents is bytes on this phone and fails by losing its folder while
References is a pointer elsewhere and fails by having no handler. Each row shows the display name, a
quiet kind label, and the description when there is one. Actions: **Open** through `LinkLaunchPolicy`,
**Edit** (Name and Description only), **Remove** (a plain confirm and a hard delete — one metadata row
with nothing to orphan; note that merge's insert-never-delete rule means re-importing an older archive
re-inserts it, as it does for events).

**"Add link"** is the section's own action (D-21 C), calling the same use case a share does and writing
an identical row.

**The intake screen** is one scrolling column: what arrived, the asset chooser, Name, Description, and
for a byte share a Type control prefilled with `AttachmentKinds.inferFrom`. Save is disabled until an
asset is chosen and the name is non-blank — and, for a byte share with no folder, until there is one.
Disabling Save *is* the intake behaviour, so the person never sees a blank-name line here; the two
blank-name refusal strings in §10 belong to the use-case layer, which still answers the shipped picker
and camera paths and, for references, the API and MCP paths.

**The description on the byte path.** Name → `AddAttachmentCommand.displayName` and Description →
`AddAttachmentCommand.notes`, which is stored today but never rendered: `DocumentsSection`'s row is a
56 dp leading thumbnail or kind glyph, a one-line ellipsised name, a quiet `kind · size · captured-on`
line, and a trailing overflow. **D-19(A): a second quiet line carries the description when `notes` is
non-empty.** `DocumentRow`'s `Column` holds exactly one `QuietLine` today and it is mutually exclusive —
`QuietLine(if (row.present) row.quietLine() else "Not on this device")` — so this is a genuinely new
line, with two rules: when `present` is false the description line is **suppressed**, because a row whose
whole message is that the bytes are gone should not also carry prose; and the line is `maxLines = 1` with
`Ellipsis`, since the description is capped at 2,000 characters and this is a compact row. Without it a
shared PDF's description would be collected, stored and invisible forever, failing #43 AC 9.

---

## 8. Hazards and test matrix (hazard classes, not permutations)

One test per hazard class. No acceptance procedure waits on a real-world delay.

| Area | Hazard classes needing tests |
|---|---|
| Parsing | a bare URL; a Markdown link yields URI + label; non-URI text is not a link; a URI mid-sentence; an over-long URI; query and fragment survive verbatim; `text/uri-list`; a filename with `../`, a null byte or a newline is sanitised; the two text extras capped and sanitised |
| Stream URI | a `file://` stream is refused; a `content://` naming ServiceTag's own `${applicationId}.files` is refused; any other scheme is refused; **each refusal happens before the `ByteSource` is constructed**, so nothing is opened — a JVM test over the `:core` scheme/authority predicate plus one `:app` test that the reader consults it first |
| Policy | each allowed scheme launches; **each of the eight hard-blocked schemes** is refused at save and at launch; an unknown scheme takes one confirmation then saves; a missing handler is caught; a URI blocked after it was saved is shown, not launched |
| Grants | the stream is copied and no persistable grant taken; over the cap refused before the copy; zero length refused in the intake layer; a failure mid-copy leaves no row and no partial file; a process recreation mid-intake either still reads the grant or refuses cleanly and writes nothing |
| Intake | cancel at each step writes nothing; the no-assets state offers no save; **the no-store state on a byte share offers no save and stages nothing, while a URI share on the same phone saves normally**; a blank name refused at the use case; the sharing app is returned to; the running shell is undisturbed; cold and warm starts save the same row |
| Model | `UNIQUE(asset_id, uri)` rejects a duplicate and permits the same URI on two assets; deleting an asset cascades; a reference on an archived asset still lists and opens; a reference is never a tag target; no code path reaches `external_link`; **`kind` is inferred from the scheme for all three values** |
| Description | Name and Description round-trip through the byte path into `displayName`/`notes`, survive a backup round trip, and render as the second quiet line; **the line is suppressed when `present` is false**; a 2,000-character description ellipsises rather than wrapping the row |
| Backup | every field round-trips byte-identically; a format-≤6 archive decodes with an empty list; a format-7 archive is refused by the version gate; `uniqueIds` rejects a duplicate id; a row whose `assetId` is absent from the archive is refused; the new `counts` key is correct; the artifacts archive is unchanged; **no `provenance` field appears anywhere in the DTO** |
| Merge | insert; identical; `REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` on an `IDENTICAL`; **a diverged pair is `SKIPPED / REFERENCE_HELD_BY_A_LOCAL_ROW` and `applicable` stays true**; the same-id/different-content arm is still a blocking `CONFLICT`; `REFERENCE_DUPLICATED_IN_ARCHIVE`; owner-not-available; deterministic order; no partial write |
| Migration | schema 6 → 7 adds the table and index and changes no existing row |
| API/MCP | one case per status class over the three rows; each new code by name; unknown-field rejection on both commands, `uri` on the PATCH included; no route deletes a reference; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` extended to the new paths; `POST /v1/import-merge/plan` with a format-7 archive; the three MCP tools, `null`-means-unchanged and unknown-argument rejection |
| Manifest | the exported **set** is exactly the three named activities and no other component kind is exported; `everyReceiverIsNonExportedAndThereAreSix` still passes; `MergedManifestContractTest.theMergedManifestPermissionSetIsExactly` unchanged; the share filter declares the ten types, no `ACTION_SEND_MULTIPLE` and no `BROWSABLE` |

---

## 9. Decisions, as ruled

All twenty-one bind. D-6, D-12, D-14, D-18, D-19, D-20 and D-21 were ruled by the owner on 2026-09-23;
the rest were recommended in the drafts and stand unopposed.

| # | Ruled | What binds, and the cost accepted |
|---|---|---|
| D-1 | **A** | A new `asset_reference` table, not a mode on `Attachment`. Costs one migration and one merge entry. |
| D-2 | **A** | A shared stream always copies into managed storage. Closes off #43's "Reference original" row permanently. |
| D-3 | **A** | Kinds are `WEB_URL`, `NOTE_LINK`, `OTHER`. "OEM parts lookup" lives in the description, not an enum; widening later is additive. |
| D-4 | **B/A** | A Type control for byte shares, inference for references. One extra tap per document share. |
| D-5 | **A** | Non-URI text becomes a `NOTE` journal event. No new storage, no new enum member. |
| D-6 | **A** | The ten MIME types in §4.1. An unlisted type shows no ServiceTag entry until the list grows — a one-line manifest change. |
| D-7 | **A** | No `ACTION_SEND_MULTIPLE`. Sharing three photos is three shares. |
| D-8 | **A** | `UNIQUE(asset_id, uri)`. A second copy of one URL on one asset is refused; edit the first instead. |
| D-9 | **A** | Remove is a hard delete behind a confirm. An older archive re-inserts it on merge, as with events. |
| D-10 | **A** | A separate References section. One more header on asset detail. |
| D-11 | **A** | `REFERENCES` last in `MergeTable`, on dependency grounds. |
| D-12 | **A** | Three routes, four codes, three MCP tools, four `v1.md` edits. Removal stays a phone-only action. |
| D-13 | **A** | No assets: one sentence and Close. That first share is wasted; the owner re-shares. |
| D-14 | **A** | #35 AC 3 amended to three names; the assertion stays an exact set. |
| D-15 | **A** | #35's allow and block lists verbatim, both tiers. An unlisted note app costs one confirmation per reference. |
| D-16 | **A** | 1.3.0 / versionCode 15 (§12). |
| D-17 | **A** | Name ≤ 200, description ≤ 2,000, URI ≤ 2,048. A pathological URI is refused with one sentence. |
| D-18 | **C** | Diverged second identity → `SKIPPED`, never `CONFLICT`. Two costs accepted, both in §5: a skip shows only as a count, and the same-id arm still blocks. |
| D-19 | **A** | A second quiet line on the Documents row, suppressed when the bytes are absent, ellipsised at one line. |
| D-20 | **A** | No folder: one sentence, Save disabled, Close. That share is wasted; the URI path is unaffected. |
| D-21 | **C** | "Add link" ships; no `provenance` column. Shared and in-app references are indistinguishable afterwards, deliberately. |

---

## 10. Ratified strings

**All 48 RATIFIED by the owner, 2026-09-23**, as listed. Ten are already shipped and are reused
verbatim, marked *(shipped)*. Both call-outs were confirmed explicitly.

**Share-sheet entry** — `android:label`: **"ServiceTag"** *(shipped, `@string/app_name`)*.

**Intake screen** — "Save to ServiceTag" · "Received" · "Attach to" · "Choose asset" · "Name" ·
"Description (optional)" · "Type" · "Save" · "Cancel" · "Saved to <asset>".
**Confirmed explicitly:** the title is "Save to ServiceTag", *not* #43's mock wording "Share to
ServiceTag" — the screen's verb is saving.

**Intake refusals** — "Give the file a name" *(shipped)* on the byte path · "Give the reference a name"
on the URI path · "That file is larger than 256 MB" *(shipped)* · "That file is empty" ·
"Could not read what was shared" · "That link is already on this asset".

**Refused stream (I-9)** — "That file cannot be accepted from the app that shared it." Distinct from
"Could not read what was shared", which is a read *failure*; this one is a refusal. It names no URI,
scheme, authority or path.

**Over-long URI** — "That link is too long to save." The name and description caps need no string: the
input fields stop at their cap rather than refusing after the fact.

**No assets** — "Add an asset in ServiceTag first, then share this again." · "Close".

**No attachment folder** — "Choose an attachment folder in ServiceTag Settings, then share this again."
· "Close". **Confirmed explicitly:** this deliberately departs from the shipped "Choose an attachment
folder in Settings first", which implies an in-app affordance the share screen does not offer.

**Type control labels** — "Photo" · "Label photo" · "Receipt" · "Manual" · "Warranty" · "Document" ·
"Other" — all *(shipped, `DocumentsSection`)*.

**Unknown-scheme confirmation** — "Save this link?" · "ServiceTag does not recognise \"<scheme>\" links.
It will be saved as written and opened with whatever app claims it." · "Save" · "Cancel".

**Blocked scheme** — "ServiceTag will not save that kind of link." **Not a link** — "That is not a
link." · "Save as a note" · "Cancel".

**Asset detail** — "References" · "References · <n>" · "No references yet" · "Add link" · "Open" ·
"Edit" · "Remove" · "No app can open this link" · kinds "Web link", "Note", "Other".

**Remove confirmation** — "Remove this reference?" · "The link is removed from this asset. Nothing in
the other app is changed." · "Remove" · "Cancel". **Edit sheet** — "Edit reference" · "Name" ·
"Description" · "Save" · "Cancel".

---

## 11. Release gate

The JVM unit gate green with the new suites counted; the connected suite green on the emulator;
`ManifestContractTest`'s exported **set** passing with the three named activities while
`MergedManifestContractTest.theMergedManifestPermissionSetIsExactly` is unchanged; a backup exported on
one install and imported into a second with the references present and the report `applicable`; **a
two-phone merge in which each side saved the same URL on one asset under different names, proven to
report a skip and still apply**; a format-7 archive refused by a 1.2.x build through `BackupNewerFormat`;
a format-6 archive imported by the new build with an empty reference list; MCP pytest green with the
three new tools; a release-workflow dry run.

**Device work.** Emulator first, per the standing rule. Three shares need no hardware and run on the
emulator — a browser URL, a PDF from Files, a gallery image, all share sheet and `ACTION_VIEW` only — as
does the cancelled-share proof and the no-folder proof. **Only the Joplin round trip runs on the phone**,
needing Joplin installed with real notes to prove the `joplin://` handoff end to end. Then tag, owner
release approval, and verify the APK's signer, version and checksum.

### 11.1 Boundaries, ordering, untouched, size

Seven components in dependency order: **C1** `:core` model, `ReferenceKind`, `ReferenceId`, the
repository port and use cases · **C2** `:core` pure policy — `LinkLaunchPolicy`, the share parser, and
I-9's scheme/authority predicate · **C3** Room entity, DAO, migration 6 → 7 · **C4** format 7 (DTO,
codec, `uniqueIds`, owner checks) and `MergeTable`/`MergePlanner` · **C5**
`com.loosecannon.servicetag.share.ShareIntakeActivity` — this exact fully-qualified name is the string
`ManifestContractTest` asserts and §4.1 quotes — plus its manifest entry, the stream reader that calls
C2's predicate before constructing the `ByteSource`, and the intake ViewModel · **C6** the References
section, "Add link", the edit sheet and the D-19 Documents-row change · **C7** the API routes, codes and
MCP tools.

Untouched: `external_link` and everything around it; `AttachmentProblem` and its exhaustive `when`;
`AttachmentKind.label()` and `AttachmentKinds.inferFrom`, on which D-4 and §10 both depend;
`AttachmentStore` and both implementations; the artifacts archive; `MergeVerdict`; every existing
`MergeTable` member's position; `MergedManifestContractTest`; the six receivers.

Size, against the planning policy's guardrails: a master plan of 500–700 lines and briefs of 150–300
each for C1–C7 (C4 largest, C2 smallest) — roughly 1,900–2,800 lines across eight documents, each inside
the "normal detailed plan" band, with no production code beyond interface-pinning fragments.

---

## 12. Versioning

**`versionName` 1.3.0, `versionCode` 15.** A new user-facing capability is a MINOR under
`docs/versioning.md`'s classification table, so 1.2.0's MINOR becomes 1.3.0 and PATCH resets to 0.
`versionCode` is independent and monotonic, +1 on every released APK: 1.2.0 shipped code 13, the 1.2.x
hardening release takes 14, and this release takes **15**. No code is reserved in advance.

**Room schema 7.** One new table and one new index; a pure addition, so the migration creates and
changes nothing existing.

**Backup format 7, forward-only, and that is why this stays a MINOR.** `docs/versioning.md` states the
rule directly: "A **forward-only** backup-format bump — where the new app reads every older archive and
an older app safely refuses a newer one rather than dropping rows — is a **MINOR**. A change that makes
the app unable to read data it previously could is a **MAJOR**." Both halves hold here. 1.3.0 reads
formats 1–7, a format-≤6 archive decoding with an empty reference list. A **1.2.x app handed a format-7
archive refuses it loudly through the existing `BackupNewerFormat` path** — the `formatVersion >
FORMAT_VERSION` gate that has shipped since format 5 and needs no change — so the failure is a clear
refusal, never a silent partial restore. The direction that matters, old data into the new app, is
preserved.

`docs/versioning.md`'s supported-release table gains a 1.3.0 / 15 row naming the share intake, the
`asset_reference` table, schema 7, format 7 and the reference API, with `docs/api/v1.md` and this
specification as its contracts. The seasonal and operational model moves to 1.4.0.
