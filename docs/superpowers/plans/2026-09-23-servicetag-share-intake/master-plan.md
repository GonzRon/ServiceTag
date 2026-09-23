# ServiceTag 1.3.0 — share intake (#43): MASTER PLAN

> **For agentic workers.** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` — execute the
> authorized wave brief by brief, one implementer and one independent reviewer each, scoped re-review
> of every fix, controller merge. This plan follows `docs/superpowers/planning-policy.md`: **it
> specifies contracts, invariants and test matrices; the implementers author the code and the tests.**
> Nothing here is meant to be transcribed; a fragment appears only where it pins an interface.
> **Execution is not authorized until the owner releases the gate**; when it is, waves open one at a
> time under §14. No production phone, and no release before §15.

**Goal.** Android's share sheet sends one item into ServiceTag; the owner picks an asset, names and
describes it, and it is saved — **bytes as an ordinary #7 attachment, a URI as a new
`asset_reference` row** — then shows on the asset and opens with the system. Plus, under D-12 and
D-21, a reference surface on the loopback API and MCP, and an in-app "Add link".

**Spec — the authority.** `docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` (FINAL,
2026-09-23; all 48 strings ratified). Cite that committed path, never the workspace draft. Contracts
below trace to it as `spec §n`. Where this plan decides something the spec left open it says `Plan decision:` and repeats it in §18. **An implementer who finds this plan disagreeing with the spec
follows the spec and reports the disagreement**, except where §18 names the deviation; one who finds
the spec silent asks the controller rather than inventing a user-visible behaviour.

**Architecture.** The shipped three-layer shape is unchanged. `:core` gains the reference model, the
`ReferenceRepository` port, three use cases and a new Android-free `core/references/` policy
package. `:app` gains one Room table with its DAO, mapper and repository, one exported activity in a
new `share/` package, a References section in `ui/references/`, and three `/v1` routes.
`tools/servicetag-mcp/` gains three tools. **`:core` stays free of Android types**, which is why
I-9's stream predicate takes a scheme and an authority as plain strings (spec §3.3).

---

## 1. Global constraints

Binding on every brief. Each brief restates only what it owns.

| # | constraint |
|---|---|
| 1.1 | **Commits:** identity **GonzRon**, one per task, a **single casual subject**, no body, no trailers, **no attribution**. Implementers do not push. No implementer runs adb, an emulator, a device command or `gradlew` outside the sandbox tool the controller names. Never work in `/tmp` |
| 1.2 | **Privacy and hygiene**, every tracked file, report and review: no private inventory, no serials or device models, no e-mail, **no `/home/<user>` paths — write `~`**, no pairing codes, no real `backupSetId`. Fixtures use fictional nouns (`example-mower.invalid`, the spec's own "Cub Cadet XT1") |
| 1.3 | **The `libs/nfc-tag-core` gitlink stays `7e0377a`**; `bash tools/check-submodule-pin.sh` is part of the release hygiene proof |
| 1.4 | **Strict serialization:** `ApiJson` keeps `ignoreUnknownKeys = false` and `encodeDefaults = true` (`api/ApiJson.kt:62`–`65`); `BackupCodec`'s `Json` is unchanged. A misspelled field is a 400 naming it, never a silent default |
| 1.5 | **The 2.6 tombstone rule (spec §3.4, I-5).** `external_link`, `ExternalLink`, `LinkKind`, `LinkRepository`, `ExternalLinkDao`, `ExternalLinkEntity` and the format-5 `externalLinks` field are exported and restored byte-for-byte, carried through merge, never displayed, never acted on. **Never bumped, re-purposed or discarded.** `asset_reference` is a **new** table with a new name, id type, DTO, merge entry and shape. `v1.md`'s refusal of anything at all for the tombstones is unchanged |
| 1.6 | **Schema 7 / format 7, forward-only.** `AppDatabase.version` = `AppGraph.SCHEMA_VERSION` = `BackupCodec.FORMAT_VERSION` = **7**. 1.3.0 reads formats 1–7; a format-≤6 archive decodes with an empty reference list; **a 1.2.x app refuses a format-7 archive through the shipped `BackupNewerFormat` gate** (`BackupCodec.kt:173`–`175`), unchanged. That is why this is a MINOR (spec §12) |
| 1.7 | **Exactly three exported components, by name** (D-14): `com.loosecannon.servicetag.MainActivity`, `com.loosecannon.servicetag.nfc.NfcDispatchActivity`, `com.loosecannon.servicetag.share.ShareIntakeActivity`, asserted as an exact **set** by **`ManifestContractTest`** — the **first** of the two classes in `app/src/test/kotlin/com/loosecannon/servicetag/reminders/ManifestContractTest.kt` (`:23`) — plus its "no other component kind carries `exported="true"`" arm (`:98`–`:100`). **Never made a count of three**: that class's own KDoc (`:16`,`:19`) records that the merged manifest carries library-injected components, which would make an exact count flaky. `everyReceiverIsNonExportedAndThereAreSix` stays at six |
| 1.8 | **No new permission.** **`MergedManifestContractTest.theMergedManifestPermissionSetIsExactly`** — the **second** class in that same file (`:197`, the `@Test` at `:237`) — is **unchanged and untouched**; `ACTION_SEND` intake needs no permission, and asserting that is part of the gate. Two classes, one file: B03 edits the first and leaves the second alone (§18.5) |
| 1.9 | **Strings.** Every user-visible string comes from spec §10 and is used **verbatim**; no brief may paraphrase one. **A string a brief needs that §10 does not list is a finding for the controller, never a brief's to invent.** §10 is read as including the two the owner ratified on 2026-09-23 in the spec's Amendments block — the Add-link sheet's "Link" and "Add link" (§18.4); §18.14 flags one wording question that blocks nothing |
| 1.10 | **Proportionality.** One test per hazard class, not per permutation. **Every `@Test` asserts a hazard; a test that cannot fail is a defect.** No acceptance procedure waits on a real-world delay |
| 1.11 | **Lanes.** At most **two implementers at once** (`feedback-two-lanes`), one worktree per lane, one implementer and one independent reviewer per brief. **Only one lane holds `emulator-5554` at a time**, requested from and released to the controller with `ANDROID_SERIAL` pinned; instrumented runs wipe app data. Single-class connected runs use `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` (a package with `.package=<pkg>`); `connectedDebugAndroidTest --tests` is rejected on this AGP |
| 1.12 | **Grep expectations are anchored patterns from the start** (`planning-policy.md:40`), so a comment naming a grep can never match it — **and a pattern that cannot fail is a defect** (§1.10). Where the file's formatting defeats a regex, the check is a parser, not a cleverer pattern: `app/src/main/AndroidManifest.xml` puts **one attribute per line**, so no `<element …attr>` single-line pattern can ever match it, and every structural manifest gate in this plan is an XML-aware check (§16) or the `@Test` that already parses. **Every gate command in this plan and its briefs is written on one physical line, however long**: prose here is wrapped at 100 columns, commands are not, because a command broken across a newline inside an inline code span cannot be pasted — it hangs, or splits at a pipe |
| 1.13 | **Out of scope, do not build:** `ACTION_SEND_MULTIPLE`; a `REFERENCE`-mode attachment or any writer for `AttachmentMode.REFERENCE` / `StorageProvider.SAF_DOCUMENT`; replication (#45); a `provenance` column; share-sheet shortcuts, OCR or LLM classification, the #42 handoff; reviving `external_link`; an NFC tag bound to a reference (#43 AC 10); editing a saved `uri` or re-parenting a reference; preferences for the caps or the scheme lists (#18 owns preferences) |
| 1.14 | **Untouched** unless a brief's Files section names it: `libs/`, `tools/servicetag-bundle/`, `tools/servicetag-schedules/`, `core/.../core/nfc/`, `core/.../core/schedule/`, `core/.../core/reminders/`, `app/.../reminders/`, `app/.../ui/maintenance/`, `app/.../ui/theme/`, `app/.../ui/components/`; `AttachmentProblem` and its exhaustive `when` in `AttachmentsSectionViewModel.say`; `AttachmentKind.label()`; `AttachmentKinds.inferFrom`; `AttachmentStore` and both implementations; the artifacts archive and its tallies; **`MergeVerdict`'s members** — but **not** its KDoc, see §18.9; every existing `MergeTable` member's position; **`MergedManifestContractTest`**; the six receivers; `app/schemas/{1..6}.json` |
| 1.15 | **Two diff tokens, and they are not interchangeable.** `$BASE` is **the commit the lane's worktree was cut from** — the release tip at that wave's opening, which **the controller records at dispatch** and the brief reads as `$BASE`. **Every per-brief `git diff` gate uses `$BASE`**, because a later wave's tree legitimately carries earlier waves' merged work, and a gate that demanded otherwise would invite a lane to revert a predecessor — which §14 forbids. `<base>` is the **release branch's base commit** and appears only in §16/R8, where the question is what the whole release changed |

---

## 2. Shared contract A — Room schema 6 → 7

One new table, two new indices, a pure addition; **no existing column, index or row changes** (spec
§5, §12). Owned by **B01**. `asset_reference`, nine columns (spec §3.2):

| column | type | rule |
|---|---|---|
| `id` | TEXT NOT NULL | PRIMARY KEY; domain type `ReferenceId` |
| `asset_id` | TEXT NOT NULL | FK → `asset(id)` `ON DELETE CASCADE` |
| `kind` | TEXT NOT NULL | `ReferenceKind` name: `WEB_URL`, `NOTE_LINK`, `OTHER` |
| `uri` | TEXT NOT NULL | stored exactly as validated; never rewritten (I-1) |
| `display_name` | TEXT NOT NULL | non-blank, ≤ 200 chars |
| `description` | TEXT NOT NULL | may be empty, ≤ 2,000 chars |
| `scheme` | TEXT NOT NULL | lowercased, **derived from `uri` on every write** |
| `created_at` / `updated_at` | INTEGER NOT NULL | |

Indices: `UNIQUE(asset_id, uri)` and `INDEX(asset_id)`, both as §3.2 declares them (§18.6). **No
`provenance` column** (D-21 C) and no SQL `CHECK`. `MIGRATION_6_7` is a plain `CREATE TABLE` plus
two `CREATE INDEX`es, in the shape of `MIGRATION_4_5` — no recreate, no copy, so "every pre-existing
row is untouched" holds by construction, and the SQL is copied **verbatim from the exported
`7.json`**. B01 holds the detail.

---

## 3. Shared contract B — backup format 6 → 7

Owned by **B01**. `BackupCodec.FORMAT_VERSION` 6 → 7. `BackupData` gains **one defaulted list**, the
pattern its seven defaulted lists already use (`BackupFormat.kt:330`–`344`):

```kotlin
/** Format 7; their own rows, never nested. Empty on every format ≤6 archive. */
val assetReferences: List<AssetReferenceDto> = emptyList(),
```

`AssetReferenceDto` is the table's nine columns in this order — `id, assetId, kind, uri, displayName, description, scheme, createdAt, updatedAt` — all `String` but the two `Long`
timestamps, **no `provenance`**.

| concern | contract |
|---|---|
| encoder | `assetReferences = data.assetReferences.sortedBy { it.id }` in `encode`'s `sorted` block |
| `counts` | one new key, `"assetReferences"`, taking the manifest to **17** keys |
| eager validation | `data.assetReferences.forEach { it.toDomain() }` in the same pass as the other ten lists |
| id uniqueness | `uniqueIds("assetReferences", …)` in `validateGraph` |
| owner check | a row whose `assetId` is absent from the archive's own `assets` is a `BackupCorrupt`, exactly as `externalLinks` is (`BackupCodec.kt:273`–`280`) |
| `(assetId, uri)` | **deliberately NOT checked at decode** — that is what keeps `REFERENCE_DUPLICATED_IN_ARCHIVE` reachable, as `CLOSURE_DUPLICATED_IN_ARCHIVE` is (spec §5) |
| version gate | `formatVersion > FORMAT_VERSION` → `BackupNewerFormat`, unchanged |
| artifacts | **unchanged**: a reference has no bytes, so the artifacts archive, its `managed` filter and its tallies do not move |

---

## 4. Shared contract C — the merge (D-18 C)

Owned by **B01**. `MergeTable` gains **`REFERENCES`, last**: `ASSETS, GROUPS, DEFINITIONS, PROFILES, SCHEDULES, CLOSURES, LINKS, TAGS, EVENTS, ATTACHMENTS, REFERENCES`. Its KDoc's "ten canonical
tables" becomes **eleven** and `MergeWrites`' write-order comment follows. `MergeWrites`,
`MergeSnapshot` and `MergeReport` each gain `references` — **eleven tallies**. Three new
`MergeReason` members, parallel to the shipped closure trio.

**Where `references` goes in each carrier**, pinned because the three differ and R8's review reads
the member lists against this section. `MergeWrites`: **last**, because that class's field order
*is* the write order. `MergeReport`: **last among the tallies**, after `attachments` and before
`conflicts`. `MergeSnapshot`: **after `attachments` and before `storedBytes`**, with `= emptyList()`
— that class's field order is **not** the write order (`MergePlan.kt:237`–`250` reads assets,
groups, tags, links, definitions, profiles, schedules, closures, events, attachments) and its final
`attachmentStoreConfigured` deliberately carries **no default**, so it must stay last.

The rule, with `pair = (assetId, uri)`, in the arm order the closure pass uses
(`MergePlanner.kt:376`–`408`):

| arm | verdict / reason |
|---|---|
| id present, `dto == local.toDto()` | `IDENTICAL` |
| id present, any field differs | `CONFLICT / CONTENT_DIFFERS` |
| a **different** id holds the pair locally, `dto.copy(id = samePair.id) == samePair.toDto()` | `IDENTICAL / REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` |
| a **different** id holds the pair locally and differs | **`SKIPPED / REFERENCE_HELD_BY_A_LOCAL_ROW`** — never `CONFLICT` |
| no local row holds the pair, another archive row claims it | `CONFLICT / REFERENCE_DUPLICATED_IN_ARCHIVE` |
| `assetId` resolves to nothing local and nothing this plan inserts | `CONFLICT / OWNER_NOT_AVAILABLE` |
| otherwise | `INSERT` |

A `SKIPPED` row **claims no pair and writes nothing**.

**Why a skip and not a conflict** (spec §5; every brief and review must be able to restate it):
there is **no `UPDATE` verdict anywhere in the planner**, so an incoming `display_name` or
`description` could never be adopted under any rule. A `CONFLICT` on a diverged pair therefore
cannot produce a better merged state than a skip — it can only refuse the whole archive, and there
is no #44 conflict UI to resolve it with. `applicable` is `conflicts.isEmpty()`, so a skip cannot
block an apply. **This is the codebase's first declining second identity**; tags and closures raise
a `CONFLICT` on divergence because each guards something a human must arbitrate, whereas two
descriptions of one URL are not a disagreement about anything. **Two costs, accepted:** a skip is
visible only as a count, and the same-id/different-content arm still blocks.

---

## 5. Shared contract D — the reference domain and the three policies

Owned by **B02**, in a new Android-free `core/references/`. **B01 owns the data shapes**
(`ReferenceId`, `ReferenceKind`, `AssetReference`, `ReferenceRepository`) because the entity and the
DTO need them; B02 owns every **rule** over them (§18.1).

| policy | shape | rule |
|---|---|---|
| `LinkLaunchPolicy` | `fun classify(uri: String): LinkDecision`, `LinkDecision = Allowed \| Blocked \| Unknown(scheme)` | **Allowed:** `http`, `https`, `joplin`, `obsidian`, `logseq`. **Blocked:** `javascript`, `file`, `content`, `intent`, `android-app`, `tel`, `sms`, `mailto`, **and no scheme at all**. Everything else `Unknown`. Both #35 lists verbatim (D-15). Applied **at save and at launch**, so a URI legal when saved and not now is shown and refused, never launched |
| `ShareTextParser` | `fun firstUri(text: String): ParsedShare?`, `ParsedShare(uri, label?)` | the **first URI token** in `text/plain` or `text/uri-list`; a Markdown `[label](uri)` yields both; query and fragment survive verbatim. **Raw text is never stored as a URI** (#35) — no URI means `null` |
| `StreamSourcePolicy` | `class StreamSourcePolicy(ownAuthorities: Set<String>) { fun accepts(scheme: String?, authority: String?): Boolean }` | I-9: **`content://` or refused**, and never one of ServiceTag's own authorities. Android-free, strings only, authorities injected as configuration — the same shape and module as `LinkLaunchPolicy` |

`ReferenceKinds.inferFrom(scheme)` → `WEB_URL` for `http`/`https`, `NOTE_LINK` for an allow-listed
app scheme, `OTHER` otherwise. **`kind` is inferred and never a picker** (spec §3.2); the byte path
keeps its Type control, because `AttachmentKinds.inferFrom` yields only three of seven values and
only the owner knows a receipt from a manual (D-4). Limits, constants with no preference (D-17): URI
≤ **2,048**, name ≤ **200**, description ≤ **2,000**; `MAX_ATTACHMENT_BYTES` reused unchanged at 256
MiB.

Three use cases in `core/usecase/` — `AddReference`, `UpdateReference`, `RemoveReference` — with
`AddReferenceCommand(uri, displayName, description, confirmedUnknownScheme = false)` and
`UpdateReferenceCommand(displayName, description)`, the latter carrying **no `uri`, no `assetId`, no
`kind`** (I-1, I-6). `ReferenceProblem` is a **new** sealed interface; `AttachmentProblem`'s list is
closed and stays so. **Every refusal lives in the use case** (I-2), so the share screen, the "Add
link" sheet, the API and MCP all inherit it and none can smuggle a blocked scheme past it. The API
and MCP **never set `confirmedUnknownScheme`**, so over the wire an unknown scheme is a refusal and
never a confirmation (spec §6, §18.2). B02 holds the signatures and the step order.

---

## 6. Shared contract E — the share intake

Owned by **B03** (spec §2, §4). The exported target is
`com.loosecannon.servicetag.share.ShareIntakeActivity` — **this exact fully-qualified name is the
string `ManifestContractTest` asserts**, and no later brief may rename or repackage it.

| attribute | value |
|---|---|
| `exported` / `taskAffinity` / `excludeFromRecents` / `launchMode` | `true` / `""` / `true` / `standard` |
| filter | `ACTION_SEND` + `category.DEFAULT` over exactly ten types |
| types (D-6) | `text/plain`, `text/uri-list`, `image/*`, `application/pdf`, `text/markdown`, `text/csv`, `application/msword`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/zip` |
| never declared | `BROWSABLE`, `ACTION_VIEW`, `ACTION_SEND_MULTIPLE`, `PROCESS_TEXT` |

`taskAffinity=""` keeps the activity out of `MainActivity`'s `singleTask` stack, so a warm shell is
neither navigated, recreated nor scrolled. No allow-list gate runs after the filter, and
`MimeTypes.EXTENSIONS` is **not** grown for the three types it does not carry — `AttachmentLocator`
falls back to the shared filename's extension and then `bin`, correct existing behaviour.

The activity reads **only** `EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT`, `EXTRA_TITLE`, all as
data; every other extra is ignored. `EXTRA_STREAM` uses the API-33+ typed `getParcelableExtra(name, Uri::class.java)`, **never the deprecated overload**. The two text extras are capped to 200
characters and sanitised before becoming a display name, as the filename is.

**Order of operations on a byte share, and it is load-bearing:** the `:app` stream reader asks
`StreamSourcePolicy.accepts(uri.scheme, uri.authority)` **before it constructs the `ByteSource`**,
so a refused URI is never opened; then the declared size; then a zero length, refused **in the
intake layer** because `AttachmentProblem`'s list is closed; then the shipped `AddAttachment` path.
**A share always copies** — `takePersistableUriPermission` throws on an `ACTION_SEND` grant
regardless — so nothing that expires is retained and the 512 persisted-grant cap is untouched (#43
AC 7). The grant lives until the receiving **task** finishes, so a process recreation mid-intake
either still reads it or refuses cleanly and writes nothing.

`<queries>` gains **one `ACTION_VIEW` entry per allowed scheme** beside its shipped one, for API-30+
package visibility; `ActivityNotFoundException` is always caught. Non-URI text becomes a `NOTE`
journal event through the shipped `EventKind.NOTE` — **no new enum member** (D-5). Nothing shared is
executed, interpreted as a command or rendered in a WebView; **no URI, filename or shared text is
logged at INFO or above**, and no visible refusal names a URI, scheme, authority or path.

---

## 7. Shared contract F — the `/v1` additions and the MCP tools

Owned by **B05**. API version stays **1**, extended additively; `/v1/status` counts gain
`assetReferences`.

| method | path | body | success | notes |
|---|---|---|---|---|
| `GET` | `/v1/assets/{id}/references` | — | 200 | `{references: [AssetReferenceDto]}`, ordered by `displayName`; the **ninth** `/v1/assets/{id}/…` sub-resource, so a wrong verb is **404** |
| `POST` | `/v1/references` | `{assetId, uri, displayName, description}` | 201 | `{reference}`; a wrong verb is **405**. **No `kind`** — derived from the scheme, read-only on the way out, an unknown field here (§18.18) |
| `PATCH` | `/v1/references/{id}` | `{displayName?, description?}` | 200 | `{reference}`; `uri`, `assetId` and `kind` are **unknown fields** here (I-1, I-6, §18.18) |

Responses reuse the backup DTO — one schema, not two; requests are declared in `:app` with plain
`String` fields. **No `DELETE`, and no bytes, ever**: deleting a reference joins "What has no
endpoint, deliberately" beside attachments and closures, and no endpoint accepts or returns a file,
so there is no share-by-API.

**Five** new codes, `UPPER_SNAKE` like 1.2's, in their own **"The reference codes (1.3.0)"**
subsection: 404 `NO_SUCH_REFERENCE`; 422 `REFERENCE_URI_INVALID`; 422 `REFERENCE_SCHEME_BLOCKED`;
409 `REFERENCE_URI_TAKEN`; and **422 `REFERENCE_NAME_REQUIRED`** ("a reference needs a name"),
**ruled by the controller in the spec's Amendments block** (`spec:578`) for a `displayName` blank
after trimming — `REFERENCE_URI_INVALID` would be wrong on its face, the URI being valid. MCP
surfaces it as any other error. MCP gains `list_references`, `add_reference`, `update_reference`
under the shipped conventions — `None` means unchanged, unknown arguments rejected, every error path
a real `ToolError` carrying the code — and `TOOL_NAMES` goes **38 → 41**. Five `docs/api/v1.md`
edits: format **1–6** → **1–7**; "eight `/v1/assets/{id}/…` sub-resources" → **nine**; "ten tables"
→ **eleven** with `references` last; the codes subsection; and the create body without `kind`. B05 holds the wire shapes and the
error map.

---

## 8. Shared contract G — the UI surfaces

**Asset detail gains a References section below Documents** (B04), built from the same section
primitives. Two sections and not one, because Documents is bytes on this phone and fails by losing
its folder, while References is a pointer elsewhere and fails by having no handler. **Open** goes
through `LinkLaunchPolicy`, **Edit** takes Name and Description only, **Remove** is a plain confirm
and a hard delete, and **"Add link"** is the section's own action (D-21 C), calling the same use
case a share does and writing an identical row.

**The intake screen** (B03) is one scrolling column: what arrived, the asset chooser, Name,
Description, and — for a byte share only — a Type control prefilled with
`AttachmentKinds.inferFrom`. **Save is disabled** until an asset is chosen and the name is
non-blank, and, for a byte share with no folder, until there is one. Disabling Save *is* the intake
behaviour, so the person never sees a blank-name line here; §10's two blank-name refusals belong to
the use-case layer, which still answers the shipped picker and camera paths and, for references, the
API and MCP paths.

**D-19 — the Documents row's second quiet line** (B04). `AddAttachmentCommand.notes` is stored today
and never rendered; `DocumentRow`'s `Column` holds exactly one `QuietLine`
(`DocumentsSection.kt:208`), mutually exclusive with "Not on this device". The new line is genuinely
additional, **suppressed when `present` is false** and `maxLines = 1` with `Ellipsis`. Without it a
shared PDF's description would be collected, stored and invisible forever, failing #43 AC 9.

---

## 9. Cross-cutting invariants

Spec §3.3's numbering. Every brief names the ones it holds.

| # | invariant | held by |
|---|---|---|
| I-1 | `uri` is stored exactly as validated and never edited after creation; only `display_name`, `description` and `updated_at` are mutable | B02, B04, B05 |
| I-2 | no reference is created whose scheme is hard-blocked — refused **in the use case** | B02 |
| I-3 | a reference has no bytes: no locator, no sha256, no artifacts entry, never handed to `AttachmentStore` | B01, B02, B04, B05 |
| I-4 | a reference is never a tag target and never appears in `TagTargets` | B01 (no change is the proof) |
| I-5 | nothing reads, writes or re-purposes `external_link`, `ExternalLink`, `LinkRepository` or format-5 `externalLinks` | every brief |
| I-6 | a reference cannot change owner; re-parenting is delete plus re-add | B02, B04, B05 |
| I-7 | `UNIQUE(asset_id, uri)`; the same URI on two different assets is permitted | B01, B02 |
| I-8 | cancelling intake at any step writes nothing — no row, no bytes, no grant, no journal event | B03 |
| I-9 | a byte share's stream URI is `content://` or refused, and never one of ServiceTag's own authorities; **checked before the `ByteSource` is constructed** | B02 (predicate), B03 (call site) |

---

## 10. File map

| area | created by | modified by |
|---|---|---|
| `core/.../core/model/AssetReference.kt`; `Ids.kt` | B01 | B01 |
| `core/.../core/references/**` | B02 | — |
| `core/.../core/usecase/{AddReference,UpdateReference,RemoveReference,ReferenceCommands}.kt` | B02 | — |
| `core/.../core/ports/Repositories.kt` | — | B01 |
| `core/.../core/backup/{BackupFormat,BackupCodec}.kt`; `core/.../core/merge/{MergePlan,MergePlanner}.kt` | — | B01 |
| `core/.../core/usecase/{ApplyBackupMergePlan,BuildBackupMergePlan,ImportBackupReplace,ExportBackupSet}.kt` | — | B01 |
| `app/.../data/room/**` (entity, DAO, mapper, repository, `Migrations.kt`, `AppDatabase.kt`); `app/schemas/7.json` | B01 | B01 |
| `app/.../di/AppGraph.kt` | — | B01 (schema, migration, repository), B02 (policies, use cases), B03, B04, B05 |
| `app/.../share/**`; `AndroidManifest.xml` | B03 | B03 |
| `app/src/test/.../reminders/ManifestContractTest.kt` | — | B03 |
| `app/.../ui/references/**` | B04 | — |
| `app/.../ui/asset/AssetDetailScreen.kt`; `ui/attachments/DocumentsSection.kt`; `ui/attachments/AttachmentsSectionViewModel.kt`; `links/LinkLauncher.kt` | — | B04 |
| `app/.../api/{ReferenceDtos,ReferenceHandlers}.kt` | B05 | — |
| `app/.../api/{ApiRouter,ApiJson,ApiHandlers,ApiDtos}.kt` | — | B05 (B01 owns `ApiDtos`' merge-report field and its `:142` comment only — §18.3) |
| `app/src/test/.../api/ApiRouterTest.kt` | — | B05 |
| `tools/servicetag-mcp/src/servicetag_mcp/server.py`; `tests/test_reference_tools.py` | B05 | B05 |
| `docs/api/v1.md`; `tools/servicetag-mcp/README.md` | — | B05 |
| `app/build.gradle.kts`; `docs/versioning.md`; `README.md`; `docs/design/09-security-privacy.md`; `docs/design/04-domain-data-model.md` | — | B06 |
| `app/src/test/.../VersionAgreementTest.kt` | — | **B01** (the schema and format assertions only) and **B06** (the version and release-document assertions) — §18.16 |
| the shipped tests whose pinned numbers move with schema 7 / format 7 / the eleventh table: `core/src/test/.../backup/{BackupFormat6Test,BackupCodecTest,StageABundleConformanceTest}.kt`, `core/src/test/.../usecase/BackupUseCasesTest.kt`, `core/src/test/.../merge/MergePlannerMaintenanceTest.kt`, `app/src/test/.../api/MaintenanceRoutesTest.kt` | — | **B01**, under §18.21's rule |
| `app/src/test/.../testing/FakeGraph.kt` | — | **B01** (its private `SCHEMA_VERSION`, §18.21) |
| `app/src/test/kotlin/com/loosecannon/servicetag/api/MaintenanceCommandShapeTest.kt` | B01 | its `schemaVersion = 6` injection now reads `AppGraph.SCHEMA_VERSION` (added to the map at the B01 merge; disclosed by the implementer, accepted by the review). |

| this plan's own `master-plan.md` | — | **B06, conditionally**: only to record, dated, a §16 proof found wrong while running it |

**Every file any brief mutates appears above** — including the last row, which is the plan
correcting itself; the whole-branch release review uses this table to spot an unowned mutation, so a
file missing here is a gap in that review.

`AppGraph.kt` is the one file several briefs touch. Waves 1, 2, 4 and 5 are single-lane, so the only
concurrent case is wave 3: **B05 merges first** (it edits the `ApiHandlers` construction region),
then **B03 rebases once** onto the merged tip and **re-runs its whole gate** before hand-back.
Neither changes a signature the other compiles against, so the rebase is textual. A lane that needs
a line in the other lane's file asks the controller rather than edits.

---

## 11. Briefs — what each owns, produces and consumes

| brief | owns | produces (the names later briefs call) | consumes |
|---|---|---|---|
| **B01** `briefs/B01-schema-format-and-merge.md` | the Room table, DAO, mapper, repository, `MIGRATION_6_7`, `7.json`; format 7; the merge entry and the three reasons; **`VersionAgreementTest`'s schema and format assertions**. **No policy, no use case, no UI, no API** | `ReferenceId`, `ReferenceKind{WEB_URL,NOTE_LINK,OTHER}`, `AssetReference`, `ReferenceRepository` (`upsert`/`get`/`forAsset`/`findByUri`/`all`/`delete`/`deleteAll`/`observeForAsset`), `AssetReferenceDto` (nine fields), `BackupData.assetReferences`, `MergeTable.REFERENCES`, `REFERENCE_DUPLICATED_IN_ARCHIVE`, `REFERENCE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`, `REFERENCE_HELD_BY_A_LOCAL_ROW`, `MergeWrites/MergeSnapshot/MergeReport.references` | shipped code only |
| **B02** `briefs/B02-reference-domain-and-policy.md` | `core/references/` and the three use cases. **No Android** | `LinkLaunchPolicy`, `LinkDecision`, `ShareTextParser`, `ParsedShare`, `StreamSourcePolicy`, `ReferenceLimits` (`MAX_REFERENCE_URI_CHARS`/`_NAME_CHARS`/`_DESCRIPTION_CHARS`), `ReferenceText.sanitiseName`/`sanitiseFilename`, `ReferenceKinds.inferFrom`, `AddReference`, `UpdateReference`, `RemoveReference`, `AddReferenceCommand`, `UpdateReferenceCommand`, `ReferenceResult`, `ReferenceProblem` | B01 |
| **B03** `briefs/B03-share-target-and-intake.md` | `ShareIntakeActivity`, the intake screen and view model, the stream reader, the manifest entry and `<queries>`, `ManifestContractTest`'s set of three | `SharedItem{Link,Bytes,PlainText,Refused}`, `IntakeRefusal`, `readSharedItem(intent, resolver, streamPolicy, linkPolicy)` | B01, B02 |
| **B04** `briefs/B04-references-section-and-documents-line.md` | the References section, "Add link", the edit and remove sheets, open-with-system, the D-19 Documents line, `LinkLauncher`'s refusal string | `ReferenceRowState`, `ReferencesSectionState`, `ReferencesSection(assetId, graph, snackbars, onOpen)` | B01, B02 |
| **B05** `briefs/B05-api-and-mcp-surface.md` | the three routes, the DTOs, the codes, the `/v1/status` count, the three MCP tools, the four `v1.md` edits | `CreateReferenceRequest`, `UpdateReferenceRequest`, `referenceHandlersFor(graph)`, `referenceProblemCode`, the three MCP tools | B01, B02 |
| **B06** `briefs/B06-version-docs-and-release-proofs.md` | `1.3.0` / `15`, `docs/versioning.md`, `README.md`, the security-doc amendment, `VersionAgreementTest`, the release-proof runbook R1–R10 | the runbook §16 points at | all |

---

## 12. Dependency graph

```
B01 ──► B02 ──┬─► B03 ──┐
              ├─► B05 ──┼─► B06 (last: needs all)
              └─► B04 ──┘
```

**B01 gates everything**: every other brief compiles against its model, DTO and repository port.
**B02 gates B03, B04 and B05**, which each call its use cases and its policies. B03, B04 and B05 are
mutually independent — they share no file but `AppGraph.kt`. B06 is last because it asserts the
final test counts and the final documents.

---

## 13. Waves and lanes

Two lanes is a **ceiling, not a target**; a wave whose lane B is not ready runs as a single lane.

| wave | lane A | lane B | emulator | file-set separation |
|---|---|---|---|---|
| 1 | **B01** | — | none | single lane by design; everything compiles against it |
| 2 | **B02** | — | none | single lane by design; pure `:core`, JVM only |
| 3 | **B03** | **B05** | **B03 only** | `app/.../share/**` + the manifest + `ManifestContractTest` vs `app/.../api/**` + `tools/servicetag-mcp/**` + `docs/api/`. Disjoint but for `AppGraph.kt` |
| 4 | **B04** | — | **B04** | `ui/references/**`, `ui/asset/`, `ui/attachments/`, `links/` |
| 5 | **B06** | — | **B06** | single lane by design |

B05's work is JVM and pytest only, so it never queues for the device; B03 holds `emulator-5554` for
the whole of wave 3. A lane waiting for the device works on its unit gate meanwhile, which is where
most of every matrix lives.

## 14. Wave precondition

No wave opens until the controller confirms: the predecessor merged `--no-ff` into the release
branch and **green — the whole `:core` and `:app` unit suite at that tip, with no assertion carried
red into a later wave** (§18.16); the reviewer's scoped re-review of every fix round closed; the worktree cut from
the release tip; `ANDROID_SERIAL` free if the wave needs it. **No lane rebases the other's merged
work away.**

---

## 15. The release gate

Owner-run and owner-gated; the controller stops where the owner's click is required.

1. Every brief merged and independently reviewed, **each handing back a green unit suite at its own
   tip**; every fix round's scoped re-review closed;
   whole-branch **release** review clean — scope, unreviewed mutations, document consistency,
   ratified strings only, hygiene, evidence correspondence, release mechanics.
2. **Every user-visible string is spec §10's, used verbatim** — including the two the owner ratified
   in the spec's Amendments block, "Link" and "Add link" (§18.4). The release review checks each
   diff against §17 rather than re-ratifying; a string §17 does not list is a **release blocker**.
3. `versionName` **1.3.0**, `versionCode` **15**; `AppDatabase.version` and
   `AppGraph.SCHEMA_VERSION` **7**; `BackupCodec.FORMAT_VERSION` **7**; `/v1/status` echoing 7 and
   7; `docs/versioning.md` carrying the 1.3.0 / 15 row.
4. §16's controller proofs all PASS at the exact final tip.
5. Tree clean; push the exact final tip; **CI green on that exact commit** (jobs `build`, `mcp`,
   `bundle`, `schedules`; zero annotations).
6. Annotated tag **`servicetag-v1.3.0`** on that commit. The release workflow reaches the protected
   `release` environment and **waits for the owner's approval in GitHub** — the controller reports
   the run as waiting and stops.
7. After approval: verify the published artifact — `sha256sum -c` on a fresh download, `apksigner`
   verifying with **one** signer whose certificate equals `RELEASE_CERT_SHA256`, badging **1.3.0 /
   15**, not debuggable, **the permission set unchanged from 1.2.1**.
8. **Install on the development phone** over 1.2.1 **in place**, code 14 → 15, the estate intact;
   then one URL share, one document share, and **the `joplin://` round trip — the only step in this
   release that needs hardware**. Never a serial in the report. **The production phone is
   untouched**; its install is the owner's separate decision. B06's R10 carries the steps.
9. Tracker: close **#43** with the evidence; comment on **#35** recording the AC-3 amendment to
   three exported names; file whatever the release surfaced.
10. Evidence committed in `.superpowers/sdd/2026-09-23-servicetag-share-intake/` and the owner's
    copies refreshed in the owner's canonical folder, under a `1.3.0` directory.

---

## 16. Controller proofs

Run by the controller at the final tip, never by an implementer. **B06 carries the runbook**
(R1–R10) with each step's command, expected sentence and expected row delta; this table is the
contract those steps must satisfy.

| # | proof | PASS is |
|---|---|---|
| R1 | unit gate from scratch: `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest --rerun-tasks --console=plain` | BUILD SUCCESSFUL, zero failures, **zero skips**, and the final counts equalling the recorded baseline plus the per-brief counts each review recorded. `Plan decision:` no total is hardcoded — the plan author may not run Gradle, so the controller's recorded baseline is the number of record |
| R2 | the whole `androidTest` suite on `emulator-5554`, `ANDROID_SERIAL` pinned, never a phone | green, zero skips, the new classes present |
| R3 | `tools/servicetag-mcp` pytest with the three new tools; `servicetag-bundle` and `servicetag-schedules` pytest | all green, the last two **unchanged** |
| R4 | **the share flows driven by intent on the emulator.** No second app is needed and none is used: `adb shell am start` constructs an `ACTION_SEND` exactly as a sharing app would, `--es`/`--eu` carry the extras and `--grant-read-uri-permission` reproduces the grant. Two commands per flow — `adb shell cmd package query-activities` with **no** `-n` to prove the manifest filter, then `am start -n com.loosecannon.servicetag/com.loosecannon.servicetag.share.ShareIntakeActivity` to drive the screen | **ten flows**, and B06's runbook must list all ten by name: **(1)** URL, **(2)** document, **(3)** image, **(4)** note-link, **(5)** blocked scheme, **(6)** unknown scheme, **(7) not-a-link** — the prose → "That is not a link." → "Save as a note" → `EventKind.NOTE` path, which is #43 AC 5 and D-5 and the only end-to-end proof of the NOTE path — **(8)** refused `file://` stream, **(9)** no assets, **(10)** no attachment folder (byte refused **and** URL still saving). Each shows its §10 sentence and its expected row delta; `SEND_MULTIPLE` resolves to **no** ServiceTag entry; a cancelled run of each leaves every count unchanged |
| R5 | the archives: the format-5 **preserved-set** restore (the **controller** stages the archive — no implementer handles owner data); a format-6 archive importing with `assetReferences: []`; a format-7 archive handed to a **1.2.1** build; a format-7 export re-planned against the phone that produced it | counts unchanged and no reference invented; empty list; **refused through `BackupNewerFormat`**; **all `IDENTICAL`**, references included |
| R6 | **the two-phone D-18 proof.** Two installs — a second AVD, or two `pm clear` generations with an archive kept between them — each saving **the same URL on the same asset under a different display name**; then one same-id/different-content case | `references` reporting `skipped: 1`, **`applicable: true`**, the apply succeeding and the local row unchanged; **and** the same-id case still a blocking `CONFLICT`. Both halves are required |
| R7 | API/MCP e2e through a connected MCP client with the Developer API screen open | `add_reference` 201 → `list_references` shows it → `update_reference` changes the description and **leaves the URI** → duplicate 409 `REFERENCE_URI_TAKEN` → blocked scheme 422 `REFERENCE_SCHEME_BLOCKED` → **unknown scheme 422 `REFERENCE_SCHEME_BLOCKED`, never a confirmation** → 2,049-character URI 422 `REFERENCE_URI_INVALID` → **a blank `display_name` 422 `REFERENCE_NAME_REQUIRED`** → `uri=` rejected as an unknown argument → `DELETE /v1/references/{id}` 405 → format-7 `import-merge/plan` 200 with the references tally. `pm clear` after |
| R8 | the structural greps, **every one anchored**, listed in B06's runbook | each returning its stated count |
| R9 | hygiene over the whole range | no private nouns, no e-mail, no home path, no serial, no pairing code; single-subject commits; one author identity; `bash tools/check-submodule-pin.sh` clean |
| R10 | the development-phone install (§15.8) | in place over 1.2.1, code 14 → 15, the estate intact, the three phone flows PASS |

**The four checks §15 will not accept a waiver on**, stated here so they cannot be lost in a
runbook. `<base>` is the release branch's base commit (§1.15).

1. **The exported-component set, parsed and not grepped.** `app/src/main/AndroidManifest.xml` puts
   one attribute per line, so **no `<activity …android:exported="true"` single-line pattern can ever
   match it** — such a pattern returns 0 today and would keep returning 0 if a fourth exported
   activity were added, which §1.10 forbids outright. The gate is an XML-aware check that **fails
   when a fourth appears**:

   ```
   python3 -c 'import sys,xml.etree.ElementTree as E; \
   A="{http://schemas.android.com/apk/res/android}"; \
   r=E.parse("app/src/main/AndroidManifest.xml").getroot(); \
   f=lambda t:sorted(e.get(A+"name") for e in r.iter(t) if e.get(A+"exported")=="true"); \
   act=f("activity")+f("activity-alias"); oth=f("receiver")+f("service")+f("provider"); \
   print("activities",len(act),sorted(act)); print("other",len(oth),oth); \
   sys.exit(0 if sorted(act)==["com.loosecannon.servicetag.MainActivity",
   "com.loosecannon.servicetag.nfc.NfcDispatchActivity",
   "com.loosecannon.servicetag.share.ShareIntakeActivity"] and not oth else 1)'
   ```

   → exit 0, printing the **three names sorted** and `other 0 []`. **`activity-alias` counts as an
   activity** — an exported alias is a fourth exported component, and a check that ignored the tag
   would exit 0 on one (none exists today, and §1.13 builds none, but the whole point of this check
   is that a fourth fails it). It is the same set `ManifestContractTest` asserts (§1.7), which gains
   the same tag; both must pass, the parser at the gate and the `@Test` in CI.

2. **No new permission:** `git diff <base>..HEAD -- app/src/main/AndroidManifest.xml | grep -c '^+.*uses-permission'` → **0**, with
   `MergedManifestContractTest.theMergedManifestPermissionSetIsExactly` green and its expected set
   unedited (§1.8).
3. **The tombstones:** `git diff --stat <base>..HEAD --` over the six tombstone files → empty.
4. **Schema, format and release identity:** `FORMAT_VERSION = 7`, `SCHEMA_VERSION = 7`, `versionName = "1.3.0"` and `versionCode = 15`, one occurrence each (B06's R8 carries the exact lines).

---

## 17. Ratified strings

**Fifty-two RATIFIED by the owner, 2026-09-23 (fifty for #43; two for B07, §18.23)** — spec §10's 48, plus the Add-link field label **"Link"**
and the launch-time refusal **"ServiceTag will not open that kind of link."**, both ruled on the same
day and both read as part of §10 (§18.4, §18.14). A brief **quotes them verbatim and may not paraphrase
one**. Ten are already shipped and reused unchanged, marked *(shipped)*.

| group | strings | owner |
|---|---|---|
| share-sheet entry | "ServiceTag" *(shipped, `@string/app_name`)* | B03 |
| intake screen | "Save to ServiceTag" · "Received" · "Attach to" · "Choose asset" · "Name" · "Description (optional)" · "Type" · "Save" · "Cancel" · "Saved to \<asset\>" | B03 |
| intake refusals | "Give the file a name" *(shipped)* · "Give the reference a name" · "That file is larger than 256 MB" *(shipped)* · "That file is empty" · "Could not read what was shared" · "That link is already on this asset" | B03 (intake layer); the two blank-name lines are the use-case layer's, drawn by B03 and B04 |
| refused stream (I-9) | "That file cannot be accepted from the app that shared it." | B03 |
| over-long URI | "That link is too long to save." | B03, B04 |
| no assets | "Add an asset in ServiceTag first, then share this again." · "Close" | B03 |
| no attachment folder | "Choose an attachment folder in ServiceTag Settings, then share this again." · "Close" | B03 |
| Type control | "Photo" · "Label photo" · "Receipt" · "Manual" · "Warranty" · "Document" · "Other" — all *(shipped, `DocumentsSection`)* | B03 |
| unknown scheme | "Save this link?" · "ServiceTag does not recognise \"\<scheme\>\" links. It will be saved as written and opened with whatever app claims it." · "Save" · "Cancel" | B03, B04 |
| blocked scheme at **save** / not a link | "ServiceTag will not save that kind of link." · "That is not a link." · "Save as a note" · "Cancel" | B03, B04 |
| blocked scheme at **open** *(RATIFIED 2026-09-23)* | **"ServiceTag will not open that kind of link."** — a stored link the policy now refuses, shown and not launched. Distinct from the save-time line, which stays as it is | B04 |
| asset detail | "References" · "References · \<n\>" · "No references yet" · "Add link" · "Open" · "Edit" · "Remove" · "No app can open this link" · kinds "Web link", "Note", "Other" | B04 |
| remove confirmation | "Remove this reference?" · "The link is removed from this asset. Nothing in the other app is changed." · "Remove" · "Cancel" | B04 |
| edit sheet | "Edit reference" · "Name" · "Description" · "Save" · "Cancel" | B04 |
| **add-link sheet** *(RATIFIED 2026-09-23, spec Amendments `spec:579`)* | title **"Add link"** (the action label, reused) · field label **"Link"** · "Name" · "Description" · "Save" · "Cancel" | B04 |
| Dashboard hint (B07, §18.23) | "Components are listed on the asset they belong to." *(replaces the 2.7 sentence; ratified 2026-09-23)* | B07 |
| Assets archived-only hint (B07, §18.23) | "Matching assets are archived. Turn on Show archived to see them." *(new; ratified 2026-09-23)* | B07 |

**Two call-outs the owner confirmed explicitly, neither of which may be softened back:** the intake
title is **"Save to ServiceTag"**, not #43's mock wording "Share to ServiceTag"; and the no-folder
line departs deliberately from the shipped "Choose an attachment folder in Settings first", which
implies an in-app affordance the share screen does not offer.

---

## 18. Plan decisions and amendments

| # | decision or amendment |
|---|---|
| **18.1** | `Plan decision:` **the data shapes ride with the schema, not with the policy.** Spec §11.1 puts the model in C1 and the Room table in C3; this plan has six briefs and puts `ReferenceId`, `ReferenceKind`, `AssetReference` and `ReferenceRepository` in **B01** beside the entity and the DTO, which cannot be written without them — the split 1.2's B01/B02 used. **Nothing the owner ruled moves**; only the brief boundary does. Consequence: `ReferenceKind`'s three values are declared in B01 and `ReferenceKinds.inferFrom`, the rule that picks one, is B02's |
| **18.2** | `Plan decision:` **the unknown-scheme confirmation is a command flag, not a second use case.** `AddReferenceCommand.confirmedUnknownScheme` defaults to `false`; the UI sets it only after the owner answers "Save this link?"; the API and MCP never set it. That makes spec §6's "a refusal, never a confirmation, over the API" true by construction rather than by remembering |
| **18.3** | `Plan decision:` **`ApiDtos.kt`'s merge-report field belongs to B01.** `MergeReportResponse` (`api/ApiDtos.kt:136`–`180`) is a hand-written 1:1 mirror of `MergeReport`; a field added to the domain type and not to the mirror is **silently dropped from the wire** rather than a compile error. B01 adds `references: MergeTallyDto` last, in the same commit as the tally; B05 owns the rest of that file. Different waves, so no lane conflict |
| **18.4** | **CLOSED — the Add-link sheet's strings are ratified.** This entry recorded a gap: §7 and D-21 ship "Add link" as the section's own action, but §10 ratified no label for the field the URI is typed into and no sheet title beyond the action label. **The owner ratified both on 2026-09-23** (spec Amendments, `spec:579`): the field label is **"Link"** and the sheet title is **"Add link"**, reused. §10 is read as including them, §17 carries them, and **B04's blocker is cleared** — the sheet is written with those two strings and no others |
| **18.5** | **SUPERSEDED, and the plan was the one that was wrong.** An earlier revision of this entry said `MergedManifestContractTest` does not exist. It does. `app/src/test/kotlin/com/loosecannon/servicetag/reminders/ManifestContractTest.kt` holds **two top-level classes**: `ManifestContractTest` (`:23`), which parses the **source** manifest and carries `everyReceiverIsNonExportedAndThereAreSix` (`:57`) and the exported-set assertion (`:84`); and `MergedManifestContractTest` (`:197`), which parses the **merged** manifest and carries `neitherExactAlarmPermissionInTheMergedManifestEither` (`:208`) and `theMergedManifestPermissionSetIsExactly` (`:237`). The file's own comment at `:267` names both. The spec's citations were right all along and its Amendments block now records the file path (`spec:576`). **B03 edits the first class and leaves the second untouched** (§1.7, §1.8, §1.14) |
| **18.6** | **Spec arithmetic — "one new index" is two.** §5 and §12 describe the migration as "one new table and one new index" while §3.2 declares both `UNIQUE(asset_id, uri)` and `INDEX(asset_id)`. §3.2 is the contract and this plan follows it: **two indices**. The second is a left-prefix of the first and so redundant to the query planner; it is declared anyway because §3.2 declares it, and because a Room entity whose `indices` disagree with the exported schema will not open |
| **18.7** | **Amendment carried by this release — `docs/design/09-security-privacy.md:28`** claims "MIME sniffed on import; size cap configurable" as shipped controls. Neither is true, and spec §4.3 retires both. **B06 amends that row in this release** rather than letting a stated control quietly diverge, records why the declared type is defensible (bytes are never rendered or executed) and adds the I-9 control. B06 carries the wording |
| **18.8** | **Amendment carried by this release — `LinkLauncher`'s refusal names the URI.** `links/LinkLauncher.kt:22` toasts `"No app can open this link:\n$uri"`; §4.4 forbids a visible refusal from naming a URI and §10 ratifies exactly **"No app can open this link"**. **B04 changes it.** Its only shipped caller is the Settings project link (`ui/settings/SettingsScreen.kt:264`), unaffected in behaviour |
| **18.9** | `Plan decision:` **the two `MergePlan.kt` KDoc sentences that explain `SKIPPED` are carved out of §1.14's `MergeVerdict` rule.** `MergeVerdict`'s KDoc (`:40`) says "`SKIPPED` is the plan declining to write a row it could otherwise have written — **in 1.1.0**, an attachment row whose bytes are not on this phone…" and `MergeReport`'s (`:257`) says "neither does a `SKIPPED` attachment row." Both become false the moment D-18 C ships, and an enum documenting a rule the planner no longer follows is how the next reader gets it wrong. **B01 updates both sentences — comment text only.** No `MergeVerdict` **member** moves, is added or is removed, which is what §1.14 protects |
| **18.10** | `Plan decision:` **`OwnerMissing` answers the shipped 1.1.0 code.** `POST /v1/references` naming an absent `assetId` is **404 `no_such_asset`** — `lower_snake`, reused verbatim from 1.1.0 — rather than a new `UPPER_SNAKE` code inside the 1.3.0 subsection. It is the same fact the shipped code already names, and inventing `NO_SUCH_ASSET` beside it would give one condition two codes. B05's `v1.md` subsection says so in one clause |
| **18.11** | `Plan decision:` **`kind` on `POST /v1/references` is accepted but advisory.** Spec §6 lists it in the body, so the request shape stays a subset of the response shape; but `kind` is **derived from the scheme** (§3.2), so the handler passes none to `AddReference`. A `kind` that disagrees with the derivation is **not honoured and not a 422**: nothing in the domain can hold the caller's answer, and refusing it would make a harmless field fatal. The handler's KDoc states it |
| **18.12** | `Plan decision:` **`UnknownSchemeNeedsConfirmation` maps to 422 `REFERENCE_SCHEME_BLOCKED`.** There is nobody on the wire to confirm and the API never sets `confirmedUnknownScheme` (§18.2), so spec §6's "a refusal, never a confirmation, over the API" is satisfied by mapping the unconfirmed case to the same code as a hard block. It is a wire-contract decision, recorded here rather than only in B05 |
| **18.13** | `Plan decision:` **`ReferenceProblem.Unchanged`, and a no-op update writes nothing.** An `UpdateReference` whose sanitised name and capped description both equal the stored ones returns `Unchanged` and **does not write**, so `updated_at` does not move. That is not a convenience: `IDENTICAL` compares every backup-format field including `updatedAt`, so a write on every call would make a re-imported archive `CONTENT_DIFFERS` on the next merge. B01's re-import matrix row depends on it from the data side, and B02's owns it from the command side. Over the API the result is **200 with the stored row**, not an error |
| **18.14** | **RULED 2026-09-23 — the launch-time refusal gets its own sentence.** An earlier revision had B04 reuse the save-time "ServiceTag will not save that kind of link." when a *stored* URI classifies `Blocked` at open time, and flagged the oddity (nothing is being saved) to the owner. The owner ruled: the open-time line is **"ServiceTag will not open that kind of link."**, newly RATIFIED, and the save-time line is unchanged. Two sentences for two moments, both in §17; B04 uses each where it belongs and paraphrases neither |
| **18.15** | `Plan decision:` **only `text/uri-list` is read as text when it arrives as a stream; a `text/plain` stream is a document.** Spec §4.4 says `text/plain` and `text/uri-list` "are scanned for their first URI token", which is right for the `EXTRA_TEXT` case both types are in D-6's ten for. It does not follow that a **stream** of either is prose: `MimeTypes.EXTENSIONS` maps `text/plain` to `txt` (`core/.../core/model/Attachment.kt:92`) for exactly the case of a `text/plain` attachment needing a locator extension, and spec §4.1's remark that the table "does not carry `text/markdown`, `text/csv` or `application/msword`" only means anything if the other seven of the ten **do** reach `AttachmentLocator.extension`. A shared `.txt` maintenance log decoded as prose would find no URI, be offered as a note and **never be stored** — the owner's document lost silently. So B03's precedence rule names **`text/uri-list` alone**; `text/plain` with a stream is bytes, `text/plain` without one is text. The uri-list read cap is `MAX_REFERENCE_URI_CHARS` (2,048), a stated number and not a slack. **If the owner wants a `text/plain` stream treated as text, that is a ruling, not a brief's** |
| **18.16** | **Owner ruling, 2026-09-23 — no brief hands back a red suite, and `VersionAgreementTest` is split.** An earlier revision let B01 and B02 leave that test red on its schema and format cases "until B06 lands the version row". **Withdrawn.** **B01 owns `theSchemaAndTheFormatAreBothSix` and the two schema/format assertions inside `statusEchoesTheVersionsTheBuildCarries`**, updating them in the same commit as the schema, so its tip is green with `versionName` still **1.2.1**, `versionCode` still **14** and **no 1.3.0 row** in `docs/versioning.md`. **B06 keeps the version and release-document assertions** — `theReleaseIdentityIs121AndCode14`, the `appVersion` half of `statusEchoes…`, `versioningRecordsThisReleaseAndNoLongerReservesItsCode`, and the three new document cases. `theRoomDatabaseCarriesTheSameVersionAsTheGraphConstant` and `theProductionHandlersAreWiredToTheBuildsOwnConstants` need no edit from either brief. The two are four waves apart, so sharing the file costs nothing; what it buys is that **a red assertion is never an expected state**, and §14's "merged and green" means what it says |
| **18.17** | **The FINAL spec is being reconciled in place by the controller**, and this plan is written against the reconciled text. Six items: the **two** indexes §3.2 declares (§18.6); **five** reference codes, `REFERENCE_NAME_REQUIRED` included (§7); **no `kind`** on the create or patch command, the DTO carrying the derived kind read-only (§18.18); **structural URI validity** as a `:core` rule (§18.19); the **`text/uri-list` read caps** (§18.20); and the string ledger at **50** — the 48 plus "Link" and the launch-time refusal (§17, §18.14). The rule at the top of this plan still holds everywhere else: **an implementer who finds this plan disagreeing with the spec follows the spec and reports the disagreement** — except on these six, where the plan is ahead of the text and the controller's reconciliation is the authority |
| **18.18** | **Owner ruling, 2026-09-23 — `kind` is not on the command.** Spec §6's body listed `{assetId, kind, uri, displayName, description}`, and an earlier revision accepted `kind` as advisory-and-ignored. **Withdrawn**: a field the server accepts and then discards reads as settable, never takes effect, and nothing would catch the divergence. `kind` is **derived from the scheme** (spec §3.2), so it is an **unknown field on both the create and the patch**, refused **400** by the strict decoder; `AssetReferenceDto` carries it **read-only on the way out**; and the MCP create and update tools carry no such argument. The response shape stays a superset of the request shape, which is the shipped convention |
| **18.19** | **Owner ruling, 2026-09-23 — structural URI validity is a `:core` rule, not the parser's.** `ShareTextParser` only ever sees a *share*; **"Add link" and the API both bypass it entirely**, so a URI's shape cannot be guaranteed there and must be checked where every path meets — `AddReference`. The rule: the URI must parse with a scheme, and for a **hierarchical** scheme (`http`, `https`, and any scheme written with `//`) it must carry a **non-empty host**. Otherwise it is refused — `REFERENCE_URI_INVALID` on the wire, the ratified "That is not a link." in app. **No new string**: the arm folds into `ReferenceProblem.NotALink`, which B05 already maps to that code |
| **18.20** | **Owner ruling, 2026-09-23 — the `text/uri-list` stream has two separate caps.** An earlier revision applied the 2,048-character URI cap to the whole stream. A uri-list is a *list*: it may legitimately be longer than any one URI in it. So **at most 64 KiB of the stream is read**, the first line that is neither blank nor a `#` comment is taken, and **`MAX_REFERENCE_URI_CHARS` (2,048) is applied to that extracted URI alone**. A stream over 64 KiB, or undecodable as UTF-8, is `Refused(UNREADABLE)`; an extracted URI over 2,048 is the over-long-URI refusal |
| **18.21** | **A pinned number may move; an assertion may not weaken.** Schema 7, format 7 and the eleventh `MergeTable` member necessarily move numbers that shipped tests pin — a table list and count, a `FORMAT_VERSION`, two `counts` maps and their size, a `MergeTable.entries` list, a route's `formatVersion`. B01 names every one of those files with line anchors and updates them under one rule: **a pinned format number, table count, `counts`-map size or table list moves to the new value; no assertion is weakened or deleted, no exact comparison becomes a `contains`, nothing is `@Ignore`d, and every case still fails if the value is wrong.** Two consequences stated so they are not discovered: **`BackupFormat6Test` keeps its name** — it proves a *format-6* archive decodes and restores, which stays true and stays wanted — and `StageABundleConformanceTest` gains a **named `FORMAT_7_TABLES` beside `FORMAT_6_TABLES`** rather than widening the existing set, so its key-set guard still subtracts an exact named set. Also here because it is the same failure mode: `FakeGraph.kt:298`'s private `const val SCHEMA_VERSION = 6` is **decoupled from the constant the gate asserts** and would sit at 6 all release with nothing saying so — B01 either references `AppGraph.SCHEMA_VERSION` directly or bumps it and adds a one-line case that the two are equal |

### 18.22 Owner-directed addition: the search box moves from the Dashboard to the Assets screen (B07)

**Owner instruction 2026-09-23.** With the category and maintenance-status dropdowns now serving as the Dashboard's navigation aids, the "Search assets and components" field leaves the Dashboard and lives on the Assets screen, with the same six searched fields and the same component-names-its-system behaviour. Not part of #43's contract; rides in 1.3.0 as `briefs/B07-search-moves-to-assets.md`. Runs in Wave 2 (beside B02's review — B02 is `:core`, B07 is `ui/dashboard` + `ui/asset`; disjoint) and holds `emulator-5554`; it **must merge before Wave 3** because B04 edits `ui/asset/` later. It was written to add no string; the owner then ratified two sentences for it (§18.23: the shortened Dashboard hint and the Assets archived-only hint), so the ledger is fifty-two. File map: `ui/dashboard/DashboardScreen.kt`, `ui/dashboard/DashboardViewModel.kt`, new `ui/asset/AssetSearch.kt`, `ui/asset/AssetsScreen.kt`, `ui/asset/AssetViewModels.kt`, their JVM tests, `androidTest/.../ui/dashboard/DashboardSearchTest.kt` (deleted) → `androidTest/.../ui/asset/AssetsSearchTest.kt`, one added case in an existing dashboard connected class.

### 18.23 Owner rulings on B07's two sentences (2026-09-23)

- The Dashboard's component hint is **RATIFIED** as **"Components are listed on the asset they belong to."** — the 2.7 sentence with its tail "Search to find one." removed (the instruction no longer points at anything on that screen). §17 gains this row; it replaces the 2.7 sentence, which the 2.7 ledger entry now marks superseded.
- The Assets screen gets a new **RATIFIED** hint for the archived-only case: when the query is non-blank, "Show archived" is off, no active row matches and at least one archived row does, the screen shows **"Matching assets are archived. Turn on Show archived to see them."** instead of "Nothing matches that." §17 gains this row (the 1.3 ledger is now fifty-two entries). B07 implements both; no other string changes.

