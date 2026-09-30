# #85 — save a document link as a local attachment, keeping its source: plan and briefs (rev 1.1, 2026-09-29)

> **Rev 1.1** applies the plan review (`.superpowers/sdd/2026-09-29-issue-85/brief-review.md`: APPROVE with conditions):
> C-1…C-8 (the authority allowlist, the `Migration9To10Test:67/:69` pin, all three "no outbound" claims, B2b split into
> B2b sniff and B2c fetch, m1–m11), NOTEs 1, 2, 4 and 7, and the owner's rulings R85-13…15 (User-Agent, path
> parameters, ASCII hosts) as DECIDED (§7), with C9's exact address set stated once and matched by the tests.

> **Amendment (§19, owner, 2026-09-29, after 413c8fed):** R85-5 widened to nineteen document types (PDF, PNG, JPEG,
> GIF, WebP, RTF, DOC, XLS, PPT, DOCX, XLSX, PPTX, ODT, ODS, ODP, TXT, Markdown, CSV, TSV); P85-13 re-ratified; three new
> briefs B2d, B2e, B2f after B3 and before B4 (§20–§22); one bounded read-only container inspection (C26).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-29-issue-85/progress.md`.
> Thirteen briefs (§8–§17, §20–§22) on one branch `issue-85`, strictly sequential — B1a, B1b, B1c, B2a, B2b, B2c, B3,
> B2d, B2e, B2f, B4, B5a, B5b — each
> `<base>` the previous accepted tip; one task review each, at most one bounded fix round each; one whole-branch review;
> the merge; one merged-tip gate. Planned read-only from issue #85 (`.superpowers/sdd/2026-09-29-issue-85/issue-85.json`)
> and the audit (`.superpowers/sdd/2026-09-29-issue-85/audit.md`, the inventory of record), every citation re-verified on
> master `8057c561` (schema/format 15 unreleased under 1.4.1, MCP 69 tools, twenty merge tables, 55 gate classes).
> **The owner ruled R85-1…12 on 2026-09-29 (§7): the audit's recommendations, with R85-2, R85-5 and R85-7 amended. They
> are DECIDED, and the menu order is ratified.** The strings (§6: P85-1…19 and the 18 reused) were
> **RATIFIED** at the owner gate on 2026-09-29, with P85-13, P85-18 and P85-19 amended. **GO B1a.** **R85-13…15 were ruled the same day and are DECIDED** (§7).
> The audit's eight briefs become ten: the owner's R85-5 and R85-7 amendments grew the fetch policy past one hour, so B2
> is split into B2a (URL and address policy), B2b (the pure sniff) and B2c (the fetch), sniff first (review M4).

**Goal:** one explicit phone action, **Save as document**, on an `https` web reference. It downloads the linked file
through a bounded, hardened fetch into app-private staging; proves from the bytes that it is one of the nineteen
document types of §19 (PDF, PNG and JPEG first; the widened set by the amendment); lets
the owner review the name, kind, #67 role and notes; and stores it through the shipped `AddAttachment` as an ordinary
**managed** attachment on the same asset, carrying a **write-once provenance snapshot** (the original URI verbatim, the
redirect destination without its query or fragment, when it was retrieved, and the reference's title). The reference
row is never touched. Backup, restore, merge and Transfer Packs carry the provenance. No API route or MCP tool reads
or records provenance; an import-merge (`POST /v1/import-merge/apply`) carries an archive's sourced rows as they are.

**Inputs:** issue #85; the audit; the #86 plan (the structural model, its B1 row-7 pin list and §12 time-box rule); the
#77 plan's staging precedent; `docs/superpowers/planning-policy.md`. Paths: `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`,
`A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`,
`AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`, `M/` = `tools/servicetag-mcp/`.

## Global constraints

- **The owner's product intent, binding on every brief:**
  - **R85-C1.** Turn an existing reference link into a locally managed attachment, keeping explicit provenance about
    where it came from. Materialization is **copy/import, never destructive movement**: no write of #85 changes or
    deletes a reference row, and deleting either side never deletes the other (R85-1).
  - **R85-C2.** The provenance survives backup, restore, merge and Transfer Packs, as structured data, never as notes.
  - **R85-C3.** Network fetching stays out of every path that assumes purely local attachment operations. It sits
    behind ports in `:core` (`DocumentTransport`, `HostResolver`, `StagingArea`), so every JVM test stays local.
    `AttachmentStore` and `AddAttachment` only ever see a complete local file.
- **Roadmap (owner, 2026-09-29):** #85 → release 1.5.0 → #90. **No version bump:** `versionName = "1.4.1"`,
  `versionCode = 17` (`app/build.gradle.kts:51-52`). **Schema 16 / format 16** ride master unreleased:
  `MIGRATION_15_16`, `Migration15To16Test`, `SCHEMA_VERSION = 16`, `FORMAT_VERSION = 16`, `FIRST_SOURCE_FORMAT = 16`,
  **`LAST_LEGACY_FORMAT` stays 7** (`C/backup/LegacyArchive.kt:34`), the "≤ 15 carrying one is refused" pattern. **No
  table, count key, `MergeTable`, `MergeReason` or tally moves**: four nullable columns on `attachment`, the #67
  `document_role` shape (R85-2).
- **One backup format, one merge engine.** No DTO fork, no UPDATE verdict, no new merge arm. The 2.6 tombstones
  `external_link` / `externalLinks` are never touched. The #77 transfer records and the #86 successions stay
  append-only and unchanged in shape.
- **The manifest's permission set does not change** (R85-11): no new permission, component, intent filter,
  cleartext setting or network-security-config; `ManifestContractTest` unchanged. Only the INTERNET comment
  (`AndroidManifest.xml:4-12`) is rewritten. **No new dependency** (`app/build.gradle.kts`, `core/build.gradle.kts`
  unchanged): the JDK's `HttpURLConnection` and `InetAddress` are enough.
- **Security and privacy, binding:**
  - https on every hop (R85-4); every hop's authority passes an **ASCII allowlist** before anything else reads it
    (C9, review M1), so the host that is checked is the host the platform connects to; every hop's host is
    **resolved and refused if any address is local** (R85-7, C9); DNS rebinding between the check and the connect is
    a recorded limit, never a reason to weaken the check.
  - No cookie, credential, `Authorization` header, `Authenticator` or other auth state is introduced or forwarded; no
    global HTTP default (redirects, cookies, authenticator, TLS trust or hostname verification) is set or weakened.
  - **R85-13:** a fixed `User-Agent: ServiceTag`, never the platform default; no app version, Android version,
    device model, build id, cookie, credential or authentication state is sent.
  - No log line carries a URI, header, filename or byte; the new files log nothing.
  - The full URI is never rendered (R85-9); the host is shown at download and review, and in the provenance line.
  - The stored redirect destination carries no query or fragment (R85-2), and no `;…` path parameter in any
    segment (R85-14): presigned or session tokens never reach a backup or a Transfer Pack.
  - The file is judged by its first **and last** bytes and, for a ZIP or OLE2 container, by one bounded read-only
    inspection of its structure (§19, C26), never by its declared type; only the text formats also need a compatible
    declared type or extension, and a declared HTML type is refused at once (R85-5 widened).
- **Tests.**
  - JVM first, with the existing fakes (`FakeUnitOfWork.commits` / `rollbacks`; `FakeGraph` is Room-backed). **No new
    device class and no new device case** (§3 row 33). Two connected runs of shipped classes whose pins or composables
    move (B1b, B5b), once each.
  - **No #90 harness work:** no gate script, runner, `tools/emulator/*` or androidTest helper change; no Robolectric,
    no JVM Compose.
  - **No rerun until green.** A device failure is reported with its log and stops the brief.
  - Every counted RED is a real mutation run with `--no-build-cache --rerun-tasks` (a `--tests` filter allowed), its
    failing assertion quoted, reverted before the commit. A row with no natural RED says why.
  - The network is never touched by a test: the core fakes serve bytes; B4's adapter test drives a loopback JDK
    `com.sun.net.httpserver.HttpServer`; the resolver adapter's test injects its lookup. Fixtures use `example.invalid`
    names and the documentation addresses `203.0.113.0/24` and `2001:db8::/32` as "public" stand-ins.
- **The merged-tip gate runs once**, after the merge (§4). Its 14- and 15-minute lines are **reporting only**; #85's
  delta is recorded against the #86 baseline: **11.18 min whole, 10.19 min device, 55 classes**.
- **Review budget:** one task review per brief; at most one bounded fix round per brief; one scoped re-review only for
  a substantive correctness finding; mechanical fixes (comments, ratified wording, renames, test tidies, under about 50
  lines) close by controller inspection plus the automated gates; one whole-branch review; the merge immediately after
  it; then the gate.
- **Time boxes (owner, 2026-09-29):** every brief has a **1 h target and a 2 h hard stop**; every fix round has **45
  minutes**, its own mutation cap (§4) and no device mutation, touches only its findings' files, and stops on the first
  finding it cannot close. A brief that will not fit stops and reports; the controller splits it.
- **Standing rules.**
  - Every user-visible string is ratified before it ships (§6: RATIFIED 2026-09-29). A reused string goes
    through its existing home, one home per literal; a reused inline literal is hoisted to a named constant in its home
    file, text byte-identical, its shipped call site switched (§6).
  - The gitlink `libs/nfc-tag-core` stays `7e0377a`.
  - Commits: one casual lowercase subject line; no body, no trailers, no AI attribution. **No personal data:**
    fictional fixtures only ("Example Pool Pump", "Sample Water Heater", `https://manuals.example.invalid/…`).
  - Device runs on `emulator-5554` only. Implementers never start, stop or restart the emulator or adb
    (`tools/emulator/prepare-emulator.sh` only waits for the device and settles one setting).
  - The tombstone check is `git diff <base> -- . ':!app/schemas' | grep -cE '^[-+].*(external_link|externalLinks)'`
    → 0 (the generated schema JSON restates the table).

## 1. Goal and scope

**Requirements.** The audit's 35 rows stand as tagged and the owner approved the dispositions: **KEEP 21, NARROW 9,
DEFER 3, DROP 2**. The two DROPs (G2, C8: the issue's delete-the-reference) stay dropped by R85-1. The three DEFERs stay
deferred: API/MCP materialization (A2), #42's keep-or-materialize (A3, AC12), and — inside R85-8 — background work.

| requirements | contract |
|---|---|
| G1, E2, U1, A1 | C20, C24 (the one explicit action; https only) |
| P1–P5 | C1, C2, C12, C14 (four write-once columns; the command never carries them on update) |
| P6 | C4, C5 (format 16; restore, export, merge, packs) |
| C1, C3, S1–S5, S7–S8 | C8, C9, C10 (https, userinfo, the resolver, redirects, timeouts, cap, no auth) |
| C2, C4, S6, S9 | C10, C16, C18 (bounded streaming, no logging, no background fetch) |
| C5, E1, E3, E4, S10 | C11 (head and tail signatures; the declared type is advisory) |
| C6, C7 | C12, C14 (through `AddAttachment`; the same asset; the snapshot) |
| C9, C10, C11 | C13, C14, C15, C18 (JVM re-reads; staging discarded; one write; start sweep) |
| U2, U3, U4 | C21, C22, C23, C25 |
| AC1–AC10, X1, X3 | §3 rows 18–23 |
| AC11, X2, X4 (NARROW) | C10 is owner-agnostic; row 21's two-asset case |

**Planner findings beside the audit** (each re-verified on `8057c561`; the briefs carry them; each is inside the
rulings, and the plan review may reject any of them as a contained edit):

1. **The migration-test family pins attachment rows (B1a).** `openMigrated` runs the chain to the newest schema
   (`T/data/room/MigrationTestSupport.kt:61-70`), so every test that compares a whole `attachment` row or its column set
   after the chain sees the four new columns and fails. The #79 `V11_ASSET_COLUMNS` precedent applies: B1a adds
   `V16_ATTACHMENT_COLUMNS` and filters it at `Migration4To5Test.kt:81`, `Migration9To10Test.kt:29`,
   `Migration10To11Test.kt:29`, `Migration11To12Test.kt:37`, `Migration12To13Test.kt:32`, `Migration13To14Test.kt:28`,
   `Migration14To15Test.kt:27` and `ReferenceMigrationTest.kt:78`; and (rev 1.1, review M2)
   `Migration9To10Test.kt:67` (`.last()` → `.dropLast(V16_ATTACHMENT_COLUMNS.size).last()`) and `:69` (16 → 20), where
   `theMigratedSchemaEqualsAFreshVersion10` reads the migrated `attachment` columns. `MaintenanceMigrationTest.kt:74`
   filters by the before-columns and does not move. The audit listed none of these.
2. **The MCP tests pin the docstring and README range (B1c).** `M/tests/test_tools.py:792` (the case name,
   `…formats_1_to_15…`), `:798` (`"format 1–15" in doc`) and `:807` (`"format **1–15**" in flat`) move with the text.
   The audit fenced `M/tests/**` out of every brief; B1c owns these three lines. `:782`'s report fixture
   (`"formatVersion": 15`) is a stub reply, not a pin, and stays.
3. **Three more "no outbound networking" claims go false** (rev 1.1, review M3): the root `README.md:74` INTERNET
   bullet ("used only by the Developer API", "ServiceTag makes no outbound connections"); the manifest comment's `:8`
   ("its only user") beside `:12`; and `docs/api/v1.md:31-41` ("introduces no outbound networking", "There is no
   `HttpURLConnection` … anywhere in the app"). B4 rewrites all three (C19); R85-11's scope (the manifest's permission
   set) is unchanged.
4. **Cancel must reach a blocked body read (B2c).** The adapter's `invokeOnCancellation { disconnect() }` covers only
   `get`; the body is read by `FetchDocument` after `get` returns, and a blocking socket read ignores thread
   interruption. So `FetchDocument` reads the body in a child and closes the response when its own scope is cancelled
   or times out (C10's fragment); any failure raised after cancellation rethrows the cancellation, never a problem.
5. **A sourced add takes its locator extension from the sniffed type (B3).** `AttachmentLocator.extension` lets the
   display name's own extension win (`C/model/Attachment.kt:86-90`), and `SafTreeAttachmentStore` creates the document
   with the MIME that extension implies (`A/attachments/SafTreeAttachmentStore.kt:47`, `:135-136`). A reference's name
   is a title, not a filename: "manuals.example.invalid" would store a PDF as `<id>.invalid`, created as a generic
   binary. For a command with a `source`, the extension is `MimeTypes.extensionFor(sniffed)` alone (C12).
6. **`materializable` is row state; read-only is the composable's (B5a/B5b).** The view model does not know
   `readOnly`; the row hides Edit and Remove itself (`A/ui/references/ReferencesSection.kt:212`). So the state is
   `WEB_URL ∧ launchable ∧ HopPolicy.staticProblem(uri) == null` (https, no userinfo, not a localhost name), and the
   composable hides the item when read-only, beside Edit and Remove. A first hop that fails the static rule therefore
   never reaches a refusal line.
7. **`ReferencesSectionTest` is outside the merged-tip gate.** Since #90's split it is an R2-only class (not in the
   55-class `BASE_CLASSES` of `.superpowers/sdd/2026-09-29-issue-86/gate/run.sh:31`). The audit's optional row 26 (one
   case appended to it) would never run at the gate. **No device case is added.** B5b runs the shipped
   `ReferencesSectionTest` once, because its overflow gains an item.
8. **Progress needs a callback.** P85-3 "{done} of {total}" needs `FetchDocument.run(url, onProgress)`; the audit's
   `run(uri)` had none.
9. **`AddAttachment`'s tests live in `CT/usecase/AttachmentUseCasesTest.kt`**; there is no `AddAttachmentTest`.
10. **Two more files for B5b.** `A/ui/attachments/DocumentsSection.kt` hosts `AttachmentEditSheet` (`:197`) and must pass
    the open-source-link callback; `A/ui/api/DeveloperApiScreen.kt:150` holds "Open app settings" inline and is
    hoisted to a constant.
11. **`:core` gains exactly one `java.net` import: `java.net.URI`,** for resolving a relative `Location` in B2c. It is
    a `Location` parser only, not I/O, corrected for its RFC 2396 quirks so the result is RFC 3986 §5.2's (C10 step
    3); every host decision still goes through the shared `hostOf` behind the authority allowlist (the underscore-host
    reason at `C/usecase/AddReference.kt:97-99`), and no socket, connection or `InetAddress` type enters `:core`.
12. **R85-7's resolver creates one new refusal.** A public name that resolves to a LAN address on the first hop is not
    caught by the static rule (finding 6), so the owner taps and needs a line: **P85-19** is proposed (§6). A redirect
    into a local address answers P85-19 too; P85-16 keeps the other redirect refusals.
13. **The codec checks the stripped shape; the write checks "differs".** The format rule (B1b) refuses a
    `sourceResolvedUri` carrying a query, a fragment or userinfo, or equal to `sourceUri`. "Only when it differs from
    the original destination" (R85-2) needs `ReferenceUris.destinationOf`, which lands in B2a after B1a, so it is
    enforced where the value is made (B3, C14) and tested there.
14. **(rev 1.1, review M1) The checked host must be the connected host.** `hostOf` ends the authority only at `/`, `?`
    or `#`, while the platform's HTTP stack also treats `\` as a delimiter, percent-decodes the host and IDN-maps it
    (fullwidth digits and dots fold to ASCII). A stored reference can carry any of them (`AddReference` refuses only
    whitespace and control characters; references also arrive by the API, share intake, merge and packs). So C9's
    `staticProblem` applies an **authority allowlist** before anything reads the host, and B4's transport refuses a URL
    whose platform-parsed host differs from `hostOf`'s. IDN hosts must be stored in punycode (R85-15).

**In scope:** C1–C25. **Out of scope** (follow-up candidates): an API or MCP materialization route and an attachment
read route (A2; a remote fetcher needs its own threat review); #42's materialize choice; #69 SupplyItem owners;
background, WorkManager or resumable downloads; DOCX, XLSX and ZIP; adopting an existing hand-added attachment;
note-link materialization; automatic IDNA conversion of a host (R85-15); a folder orphan sweep ("4B"); an address pinned from check to connect (a custom socket
factory or DNS layer); the release (1.5.0 / code 18 and its signed schema-16 upgrade smoke).

**Recorded limits (slice 1).**
- **DNS rebinding:** `HttpURLConnection` resolves the host again at connect, so a name that answers public at the check
  and local at the connect is not caught. Mitigated by https (a LAN device rarely holds a valid certificate for a public
  name), the explicit tap and the per-hop check; not removed.
- **A system HTTP proxy:** when Android routes the connection through a configured proxy, the proxy resolves and
  connects; the check still runs on the target name.
- **A process death mid-`put`** can leave an unnamed document in the folder (shipped, M10); mid-download or at review it
  leaves a `.part` file the next start sweeps.
- **The platform may clear the cache** while the review is open; Save then answers "Could not save {name}" and the owner
  taps again.
- **A JPEG whose trailer after EOI exceeds 1,024 bytes** is refused as not a document (C11): safe side. Motion-photo
  JPEGs will be refused.
- **A network-specific DNS64 prefix** (not `64:ff9b::/96` or `64:ff9b:1::/48`) is not unwrapped to its embedded IPv4.
- **An internationalised host** must be stored in its punycode (`xn--…`) form to be saved as a document; there is no
  automatic IDNA conversion (R85-15); the reference stays valid.
- **Secrets in ordinary path segments:** R85-14 strips the query, the fragment and every segment's `;` parameters,
  but a server can encode a secret in an ordinary-looking path segment, which cannot safely be told apart from a
  legitimate path; the stored destination keeps the ordinary path so the redirect provenance stays useful.

## 2. Contracts

### B1a — the provenance columns (C1–C3)

```kotlin
// C/model/Attachment.kt (B1a). Write-once: only AddAttachment, a restore and a merge insert ever write it.
data class AttachmentSource(
    val uri: String,            // the reference's URI, verbatim (R85-2)
    val resolvedUri: String?,   // the redirect destination, scheme + authority + path; null when it did not move
    val retrievedAt: Long,      // epoch millis the fetch finished
    val name: String?,          // the reference's display name at that moment
)
// Attachment gains, appended after `role`:   val source: AttachmentSource? = null
/** The one home of the shape rule: null when the four fields are well formed (or all null), else the breach. */
fun attachmentSourceProblem(uri: String?, resolvedUri: String?, retrievedAt: Long?, name: String?): String?
```

- **C1, the model and its shape (R85-2, R85-3).** `AttachmentSource` sits beside `Attachment`; `Attachment.source`
  defaults to null, so every shipped constructor call compiles unchanged. **Shape rule** (one home, used by the codec
  and by `AddAttachment`'s `require`): all four null → well formed; `uri` null with any other non-null → breach;
  `uri` non-null requires `retrievedAt` non-null and `> 0`; `uri` starts with `https://` (case-insensitive), has no
  whitespace or control character, and is ≤ `MAX_REFERENCE_URI_CHARS` (2,048); `resolvedUri`, when non-null, starts
  with `https://`, is ≤ 2,048, contains no `?`, no `#` and no `;` (R85-14), carries no userinfo (no `@` in its
  authority), and differs from `uri`; `name`, when non-null, is non-blank and ≤ `MAX_REFERENCE_NAME_CHARS` (200). The rule applies to
  any owner (asset or event), unlike R67-11. **No `reference_id`** and no key (R85-3).
- **C2, schema 16 (R85-2).** `AttachmentEntity` appends `source_uri` TEXT, `source_resolved_uri` TEXT,
  `source_retrieved_at` INTEGER, `source_name` TEXT — nullable, no default, no index. `MIGRATION_15_16` is four
  `ALTER TABLE \`attachment\` ADD COLUMN …` statements copied from the exported `16.json`, nothing else, on the
  `MIGRATION_9_10` shape (`A/data/room/Migrations.kt:583-587`). Wired: `AppDatabase` `version = 16` (`:90`), the
  `AppGraph` migration list (`:244`), `SCHEMA_VERSION = 16` (`:980`), `MigrationTestSupport` (`openMigrated`'s chain at
  `:68`; `internal val V16_ATTACHMENT_COLUMNS` beside `V10_ATTACHMENT_COLUMNS` at `:216`). The mappers carry the four
  both ways: the domain `source` is non-null iff `source_uri` is non-null. `UpdateAttachment`'s `row.copy(...)`
  (`C/usecase/UpdateAttachment.kt:34`) keeps the source untouched by construction; `UpdateAttachmentCommand` gains
  nothing (write-once, P5).
- **C3, the schema-half pins.** Every shipped assertion pinning the schema number moves with B1a, and the eight
  migration-test sites of planner finding 1 filter `V16_ATTACHMENT_COLUMNS`; every other assertion is kept (§8).

### B1b — format 16, merge, restore and packs (C4–C6)

- **C4, format 16 (R85-2).** `AttachmentDto` appends `sourceUri`, `sourceResolvedUri`, `sourceRetrievedAt`,
  `sourceName`, each `= null`, after `role` (`C/backup/BackupFormat.kt:373-390`), written as explicit nulls like
  `role`. `toDto` (`:977`) and `toDomain` (`:1001`) map them; `toDomain` calls `attachmentSourceProblem` beside the
  role check (`:1008`) and throws `BackupCorrupt` naming the attachment id. `FORMAT_VERSION = 16` (`BackupCodec.kt:135`);
  `private const val FIRST_SOURCE_FORMAT = 16` after `FIRST_SUCCESSION_FORMAT` (`:164`); beside the role guard
  (`:339-351`), a format < 16 archive with **any** of the four non-null on any attachment is `BackupCorrupt` naming the
  id and "format N" (a hand-built file). A KDoc paragraph "Format 16 (#85) adds four attachment fields and no upgrade"
  beside `:74-80`. `LAST_LEGACY_FORMAT` stays 7. Encode stays non-validating.
- **C5, merge, restore, export, packs: no logic change.** `sameAttachment` (`C/merge/MergePlanner.kt:775-779`)
  compares whole DTOs at format ≥ 10, so the four fields join the comparison by construction. Provenance is only ever
  set on a **new** id (C12, C14) and never added to an existing row (C2), so **no R67-12-style exception is needed**:
  an older archive against a local sourced row with the same id is `CONTENT_DIFFERS`, and that is pinned (row 5). On
  the format ≤ 9 path the local row's source still takes part (`incoming.copy(role = null) == here`), so it also
  differs, and that is pinned too. `ImportBackupReplace`, `ExportBackupSet`, `ApplyBackupMergePlan`,
  `BackupContentCheck`, `TransferGraph`, `TransferOwnership`, the guard, the API tally and the MCP counts are
  untouched: a Transfer Pack's data is a `BackupCodec` archive (`C/transfer/TransferPackReader.kt:129`), so the fields
  travel in packs with no pack-format change. `MergePlanner.kt` may gain one KDoc sentence and nothing else.
- **C6, the format-half pins.** Every shipped assertion pinning the format number moves with B1b (§9), every other
  kept; one connected run of `Format7RestoreContractTest` (its pin moves).

### B1c — the documents at 16 (C7)

- **C7, docs and their text pins (R85-10).** `docs/api/v1.md`: format **1–16** at `:184` and `:1539`; the status
  paragraphs (`:187-191`) gain "16 since #85 (attachment provenance)" after "15 since #86 (asset successions)"; "What
  has no endpoint, deliberately" (`:1162`) gains one sentence: since 1.5, **saving a reference as an attachment** and
  **reading or recording an attachment's source provenance** have no endpoint; an import-merge carries an archive's
  sourced rows as they are (review m9, the #86 errata lesson). The per-format paragraph (`:1539-1550`, where #67's
  role is described) gains: "a format-16 archive also carries each attachment's source provenance (#85); an older one
  whose attachments carry any is corrupt". The `:31-41` networking paragraph is B4's (C19), not B1c's. MCP: `import_merge`'s docstring (`M/src/servicetag_mcp/server.py:1131`) and `M/README.md:299` read format 1–16; the
  three `test_tools.py` pins move (planner finding 2); 69 tools; `_MIN_SUCCESSION_SCHEMA_VERSION` stays 15.
  `docs/release-proofs.md:107`: every schema/format 15 becomes 16 ("Room schema 16 / backup format 16", "(schema 8) →
  schema 16", `schemaVersion` 16, `backupFormatVersion` 16, "the format-16 round trip", "Master builds carry schema
  16"), and "#85's attachment provenance at schema 16" joins the list after "#86's asset successions at schema 15";
  the post-upgrade export's attachments carry the four `source…` keys as null. `CommandShapesGoldenTest`
  (`:115-122`, the new "16 since #85 (attachment provenance)" assertion) and `ReferenceRoutesTest` (`:544`, `:552-562`:
  "1–15" joins the refused ranges, "1–16" required) move with the document. **The twenty tables stay twenty.**

### B2a — the URL and address policy (C8–C9)

```kotlin
// C/references/ReferenceUris.kt (B2a): moved out of AddReference, behaviour byte-identical for AddReference.
object ReferenceUris {
    fun hostOf(uri: String): String?          // the authority's host; userinfo and port dropped; IPv6 unbracketed
    fun hasUserInfo(uri: String): Boolean     // an '@' inside the authority
    fun destinationOf(uri: String): String?   // scheme://authority/path, scheme and host lowercased; no ?, no #,
}                                             // no ;params in any segment (R85-14)
// C/fetch/AddressPolicy.kt (B2a). No java.net: an address is its raw bytes, 4 or 16.
fun interface HostResolver {
    /** Every address [host] resolves to now. Throws TransportFailure(UNREACHABLE) when it resolves to none. */
    suspend fun resolve(host: String): List<ByteArray>
}
fun isForbiddenAddress(address: ByteArray): Boolean          // C9's set
class HopPolicy(private val resolver: HostResolver) {
    fun staticProblem(url: String): FetchProblem?             // no resolution: the row state asks this (C20)
    suspend fun check(url: String): FetchProblem?             // static, then resolve: refused if ANY address is forbidden
}
```

- **C8, the shared URI helpers and the closed problem set.** `hostOf` moves from `AddReference` (`:110-123`) by
  extraction only; `AddReference.isStructurallyValid` calls the shared one, and every shipped `AddReference` test stays
  green and unchanged. `destinationOf` answers `scheme://authority/path` (the authority without userinfo, the path as
  sent, an empty path as `/`), or null when the URI has no authority; **(R85-14)** it also drops the path parameters
  **per segment** (from each segment's first `;` to that segment's end, never a cut at the first `;` of the path:
  `/a;x=1/b;jsessionid=AB12/m.pdf` → `/a/b/m.pdf`), so what remains is scheme + authority + the ordinary path. `C/fetch/FetchProblem.kt` holds the closed set
  `NotHttps`, `HasCredentials`, `LocalAddress`, `Unreachable`, `Interrupted`, `TimedOut`, `TooLarge`, `Empty`,
  `NotADocument`, `NeedsSignIn`, `ServerError(code)`, `RedirectRefused`, `NetworkDenied`, and
  `class TransportFailure(val kind: Kind) : IOException()` with `Kind { UNREACHABLE, TIMED_OUT, INTERRUPTED, DENIED }`
  (no message, no URI). Cancellation is never a problem: it propagates as `CancellationException`
  (`C/usecase/AttachmentSweep.kt:14-21`).
- **C9, the hop rule (R85-4, R85-7 amended, R85-15).** **The invariant (review M1, R85-15): the host ServiceTag
  validates is unambiguously the host the networking layer connects to.** No browser-compatible reading of an odd
  authority is attempted; a host that could be read two ways is refused before resolution or any request.
  `staticProblem(url)`, in order:
  1. scheme (`LinkLaunchPolicy.schemeOf`) not `https` → `NotHttps`;
  2. `hasUserInfo` → `HasCredentials`;
  3. **the authority allowlist** → `NotHttps` on any breach. The authority (from `//` to the first `/`, `?` or `#`) is
     a host plus an optional port (`:` and 1–5 digits, value 1–65535). The host is exactly one of:
     - **a DNS name** in ordinary ASCII label syntax: dot-separated labels of ASCII letters, digits, `-` and `_` (the
       shipped underscore reason, `AddReference.kt:97-99`), each 1–63 characters, not starting or ending with `-`, the
       whole ≤ 253, one trailing dot tolerated; already-encoded `xn--…` punycode labels are ordinary labels;
     - **an IPv4 literal**, accepted only as a canonical dotted quad (four decimal parts, each 0–255, no leading zero).
       A host whose last label is all digits, or which has any label starting `0x`, is an IPv4 candidate and must be
       canonical, so `127.1`, `2130706433`, `0177.0.0.1` and `0x7f.0.0.1` are refused;
     - **an IPv6 literal** in brackets that parses as RFC 4291 text (hex groups, at most one `::`, an optional
       canonical trailing dotted quad) and equals its own RFC 5952 canonical text (lowercase, longest zero run
       compressed, no leading zeros); no zone id.

     Anything else is refused: a Unicode character (so an IDN host not stored as punycode, R85-15 — no automatic IDNA
     conversion; the reference stays valid), a `%` escape, a `\`, a control or whitespace character, an empty label, an
     empty host, a malformed port;
  4. host (lowercased, one trailing dot dropped) `localhost` or ending `.localhost` → `LocalAddress`.

  `check(url)`: `staticProblem`, then `resolver.resolve(host)` — a `TransportFailure` or an empty list →
  `Unreachable`, `DENIED` → `NetworkDenied` — then `LocalAddress` if **any** address is in C9's set. **C9's address
  set is exactly this, stated once; the prose, the contracts and row 10 use it and nothing broader:**
  - **IPv4:** `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `172.16.0.0/12`,
    `192.168.0.0/16`, `224.0.0.0/4`, `240.0.0.0/4`;
  - **IPv6:** `::/128`, `::1/128`, `fe80::/10`, `fec0::/10`, `fc00::/7`, `ff00::/8`;
  - **embedded IPv4** (the IPv4 list applied to the embedded address): the last four bytes of `::ffff:0:0/96`,
    `64:ff9b::/96`, `64:ff9b:1::/48` (RFC 8215) and `::/96`; bytes 2–5 of `2002::/16` (6to4) (review m5);
  - an address of any length other than 4 or 16 bytes.

  Every other address is allowed, **including the documentation ranges** `192.0.2.0/24`, `198.51.100.0/24`,
  `203.0.113.0/24` and `2001:db8::/32`, which the tests use as deterministic public stand-ins (owner, 2026-09-29).
  A literal host is resolved like a name (the platform parses the canonical form), so literals and names meet one
  rule. The check runs on **every hop, the first included** (C10), immediately before that hop's GET. A forbidden
  address answers `LocalAddress` (P85-19) on the first hop and on every redirect hop, never `Unreachable` or
  `RedirectRefused`.

### B2b — the sniff (C11)

```kotlin
// C/fetch/DocumentSniff.kt (B2b): pure; no I/O, no declared type.
object DocumentSniff {
    const val WINDOW = 1_024
    /** The MIME the bytes prove (application/pdf, image/png, image/jpeg), or null. [head] = the first
     *  min(size, WINDOW) bytes, [tail] = the last min(size, WINDOW); for a small file they overlap. */
    fun classify(head: ByteArray, tail: ByteArray): String?
}
```

- **C11, the sniff (R85-5 amended).** A pure function over two byte windows: no I/O, no declared type, no stub —
  B2c calls the real one. **Widened by §19:** GIF, WebP, RTF and ODF join these two windows (C27, C30); the containers
  (C28, C29) and the text formats (C30) are decided after it (C31). Judged from the bytes only; the declared type is
  advisory (C10 step 5 is its only use). Head
  = the first `min(size, 1,024)` bytes, tail = the last `min(size, 1,024)`:
  - **PDF** (`application/pdf`): the head contains `%PDF-` followed by a digit, `.`, a digit, **and** the tail contains
    `%%EOF`.
  - **PNG** (`image/png`): the file starts with `89 50 4E 47 0D 0A 1A 0A` **and ends with** the IEND chunk
    `00 00 00 00 49 45 4E 44 AE 42 60 82`.
  - **JPEG** (`image/jpeg`): the file starts with `FF D8 FF` **and** the tail contains the EOI marker `FF D9`.
  - Anything else → `NotADocument`. It is sniffing, not parsing: nothing between the windows is read for meaning.
  **Required fixtures (R85-5):** in B2b on bytes alone (row 17), **HTML that begins `%PDF-1.7` and has no `%%EOF`** →
  null, which only the tail check refuses (its own mutation). In B2c end to end (row 17b), through the real sniff:
  **HTML served as `application/pdf`** → `NotADocument`; **a genuine PDF served as `application/octet-stream`** →
  `Fetched` as `application/pdf` (its own mutation, review m6); and the fake-header HTML again, **served as
  `application/pdf`**, so the declared-type check (step 5) cannot be what refuses it.

### B2c — the fetch (C10)

```kotlin
// C/fetch/FetchPorts.kt (B2c)
interface DocumentTransport {
    /** One GET. Never follows a redirect, never sends a cookie or credential. Throws TransportFailure. */
    suspend fun get(url: String): TransportResponse
}
class TransportResponse(
    val status: Int, val location: String?, val contentType: String?, val contentLength: Long?,
    val body: InputStream,              // read failures surface as TransportFailure(INTERRUPTED | TIMED_OUT)
    private val abort: () -> Unit,      // closes the connection; idempotent; unblocks a pending read
) : Closeable { override fun close() = abort() }
interface StagingArea { fun create(): StagingFile }
interface StagingFile { fun output(): OutputStream; fun source(): ByteSource; fun discard() }   // discard idempotent
data class FetchLimits(val maxBytes: Long = MAX_ATTACHMENT_BYTES, val maxRedirects: Int = 5,
    val overallMillis: Long = 600_000, val chunkBytes: Int = 65_536, val windowBytes: Int = 1_024)
sealed interface FetchOutcome {
    data class Fetched(val staged: StagingFile, val finalUrl: String, val mimeType: String,
                       val sizeBytes: Long, val sha256: String) : FetchOutcome
    data class Refused(val problem: FetchProblem) : FetchOutcome
}
class FetchDocument(transport: DocumentTransport, hops: HopPolicy, staging: StagingArea,
                    limits: FetchLimits = FetchLimits(), io: CoroutineContext = Dispatchers.IO) {
    suspend fun run(url: String, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }): FetchOutcome
}
```

- **C10, the fetch (R85-4, R85-7, R85-8, R85-13).** In order, the whole run inside the overall deadline (10 min → `TimedOut`):
  1. `hops.check(url)`; a problem is returned as is (the first hop).
  2. `transport.get(url)`; `TransportFailure` maps `UNREACHABLE` → `Unreachable`, `TIMED_OUT` → `TimedOut`,
     `INTERRUPTED` → `Interrupted`, `DENIED` → `NetworkDenied`.
  3. On 301, 302, 303, 307 or 308: close the response; a missing or unparseable `Location` → `RedirectRefused`;
     resolve it against the current URL by RFC 3986 §5.2 — `java.net.URI.resolve` (planner finding 11) with its two
     RFC 2396 quirks corrected: a base with an empty path is normalised to `/` first (else `https://h.example` +
     `x.pdf` → `https://h.examplex.pdf`), and a query-only reference (`?t=1`) keeps the base's whole path, handled by
     hand (review m4); the result goes through `hops.check` like any hop; a sixth redirect →
     `RedirectRefused`; `hops.check(next)` — `LocalAddress` stays `LocalAddress` (P85-19), every other static or
     resolution problem on a redirect hop becomes `RedirectRefused`, except `Unreachable` and `NetworkDenied`, which
     keep their own meaning; then loop to 2 with the next URL.
  4. 401, 403, 407 → `NeedsSignIn`; only 200 and 203 are a whole body (review NOTE 4); any other status, 204 and 206
     included, → `ServerError(status)`.
  5. A declared type that `MimeTypes.normalise`s to `text/html`, `application/xhtml+xml` or `text/plain` →
     `NotADocument`, **before the body is read**. **Amended by §19 (C31, B2f's edit):** only `text/html` and
     `application/xhtml+xml` fail fast; `text/plain` and the other text types proceed to `TextSniff`; and step 8's
     sniff is followed, for a ZIP or OLE2 head, by one bounded inspection (C26, C28, C29). A declared `Content-Length` > `maxBytes` → `TooLarge`, before the body.
  6. Stream in `chunkBytes` chunks to one `StagingFile`: `ensureActive()` per chunk; a running count (the count, never
     the header, is authoritative); `sha256` (lowercase hex, 64 characters, the `Attachment.sha256` format); the first
     `windowBytes` kept as the head window and the last `windowBytes` as a rolling tail window (no second pass over
     the file); `onProgress(done, contentLength)` at most once per chunk. At `maxBytes + 1` bytes → `TooLarge`.
  7. 0 bytes → `Empty`.
  8. `DocumentSniff.classify(head, tail)` (C11, B2b's, the real one) → the MIME, or `NotADocument`. The declared
     type is never recorded (review m6).
  9. `Fetched(staged, finalUrl, mime, size, sha256)`.
  **Cleanup:** every outcome but `Fetched`, every exception and every cancellation discards the staging file (in
  `finally`), and closes every response. **Cancellation reaches a blocked read** (planner finding 4):
  ```kotlin
  // inside the deadline, once a 2xx response is in hand
  val body = coroutineScope {
      val reader = async(io) { readInto(staged, response) }       // the blocking chunk loop
      try { reader.await() } catch (e: CancellationException) { response.close(); throw e }
  }
  // readInto: catch (e: IOException) { currentCoroutineContext().ensureActive(); map e to a problem }
  ```
  The deadline is `withTimeoutOrNull(limits.overallMillis) { … } ?: Refused(TimedOut)` with a block that never answers
  null, so an outer cancellation still propagates. No cookie, header or credential exists in this class (the
  adapter's three request properties are C16's); it neither logs nor keeps the URL anywhere but the returned
  `finalUrl`, and `FetchOutcome.Fetched.toString()` (like `Prepared.Ready`'s, C13) never prints a URL (review NOTE 1).
  **Tests and dispatchers (review m1):** under `runTest`, `io` is the test's own dispatcher (the fakes' bodies are in
  memory), so virtual time never races a real thread; the two blocking cases (`aStalledBodyIsClosedAtTheDeadline`,
  `cancellingMidBodyClosesTheResponseAndDiscards`) run under `runBlocking` with a real `Dispatchers.IO` and a
  wall-clock bound; no test mixes virtual time with a real `io`.

### B3 — `MaterializeReference` (C12–C15)

```kotlin
// C/usecase/MaterializeReference.kt (B3). Built once in AppGraph; used only by the Save-as-document sheet.
class MaterializeReference(
    references: ReferenceRepository, attachments: AttachmentRepository /* guarded */, storage: AttachmentStorage,
    policy: LinkLaunchPolicy, hops: HopPolicy, fetch: FetchDocument, addAttachment: AddAttachment,
    networkPermissionGranted: () -> Boolean, clock: Clock,
) {
    suspend fun prepare(assetId: AssetId, referenceId: ReferenceId,
                        onProgress: (Long, Long?) -> Unit = { _, _ -> }): Prepared
    suspend fun commit(ready: Prepared.Ready, review: MaterializeReview): AttachmentResult<Attachment>
    fun discard(ready: Prepared.Ready)                                  // idempotent
}
sealed interface Prepared {
    data class Ready(val assetId: AssetId, val snapshot: SourceSnapshot, val fetched: FetchOutcome.Fetched,
                     val retrievedAt: Long) : Prepared
    data class Refused(val why: MaterializeRefusal) : Prepared
}
data class SourceSnapshot(val uri: String, val displayName: String, val description: String, val host: String)
data class MaterializeReview(val displayName: String, val kind: AttachmentKind, val role: DocumentRole?,
                             val notes: String)
sealed interface MaterializeRefusal {
    data object NoSuchReference : MaterializeRefusal; data object NotEligible : MaterializeRefusal
    data class Store(val problem: AttachmentProblem) : MaterializeRefusal     // NoStore | StoreUnavailable
    data object NetworkDenied : MaterializeRefusal
    data class Fetch(val problem: FetchProblem) : MaterializeRefusal
    data class AlreadyHave(val name: String) : MaterializeRefusal             // R85-6
}
```

- **C12, `AddAttachment` learns one field (R85-2).** `AddAttachmentCommand` appends `val source: AttachmentSource? =
  null`; the row copies it. `require(attachmentSourceProblem(...) == null)` beside the role `require`: provenance can
  never enter malformed. **For a command with a source, the locator is `AttachmentLocator.forOwner(owner, id, "",
  mimeType)`** (review m8): an empty name has no extension, so `MimeTypes` decides (`Attachment.kt:86-90`) — never the
  name's (planner finding 5), with no `C/model` edit and no second home for the locator shape; without a source, the
  shipped call is untouched. Every shipped caller passes no source, so its rows are byte-identical
  (row 18). Refusals, their order, the bytes-first rule, the row-failure cleanup (`:89-100`) and the commit count stay
  as shipped.
- **C13, `prepare` (R85-4, R85-6, R85-7, R85-8).** Writes nothing. In order, **the transport and the resolver are
  never called before step 5**: (1) the reference exists and belongs to `assetId`, else `NoSuchReference`; (2) its
  kind is `WEB_URL`, `policy.classify(uri)` is `Allowed` and `hops.staticProblem(uri)` is null, else `NotEligible`;
  (3) `storage.state()`: `NotConfigured` → `Store(NoStore)`, `AccessLost` → `Store(StoreUnavailable)`; (4)
  `networkPermissionGranted()` false → `NetworkDenied`; (5) `fetch.run(uri, onProgress)`; a `Refused` becomes
  `Fetch(problem)` (`NetworkDenied` from the transport becomes `NetworkDenied`); (6) `retrievedAt = clock.nowMillis()`
  after the fetch; (7) **the duplicate check, per asset only**: any row of `attachments.forAsset(assetId)` (any mode)
  with `sha256` and `sizeBytes` equal to the download's → staging discarded, `AlreadyHave(name)` naming the earliest
  such row by `createdAt`, then id. **From the moment `Fetched` is in hand until `Ready` is returned, any exception or
  cancellation discards the staging, then rethrows** (review m2: step 7's read suspends). `Prepared.Ready.toString()`
  prints no URI (review NOTE 1). The snapshot is taken from the reference at step 1: `uri` verbatim, `displayName`,
  `description`, and `host = ReferenceUris.hostOf(uri)`. Two materializations on one asset at once cannot come from
  the phone (the sheet is modal and holds one job); the check is not repeated inside the write, and that is a stated
  limit, not a lock.
- **C14, `commit` and `discard` (R85-1, R85-2, R85-3).** `commit` trims the review's name; a blank one →
  `Refused(BlankName)` with **the staging kept** and nothing else done, so the owner can fix it. Otherwise it builds
  `AttachmentSource(uri = snapshot.uri, resolvedUri = r, retrievedAt, name = snapshot.displayName)` where `r =
  ReferenceUris.destinationOf(finalUrl)` **iff** it differs from `destinationOf(snapshot.uri)` and is ≤ 2,048
  characters, else null — never the query, the fragment or a segment's `;` parameters (R85-2 amended, R85-14). Then exactly one call:
  `addAttachment.run(OfAsset(assetId), AddAttachmentCommand(displayName, mimeType = fetched.mimeType, sizeBytes =
  fetched.sizeBytes, kind, notes, role, source), fetched.staged.source())`, and in `finally` the staging is discarded
  whatever the outcome (the bytes are in the store now, or nowhere). `commit` opens no `uow.write` of its own:
  exactly `AddAttachment`'s one write. `AssetTransferredOut` (the guard, inside the write) and any store failure
  propagate after the cleanup. `discard` discards the staging and nothing else.
- **C15, the invariants.** (a) **The reference is never written:** `MaterializeReference.kt` names no
  `references.upsert` or `references.delete`, and every row's reference is byte-equal before and after. (b) **One
  write or none:** success is `commits == 1`, every refusal and failure `commits == 0` with the store empty and staging
  discarded (AC4, AC6, C9). (c) **Deleting either side never deletes the other** (R85-1): the shipped
  `RemoveReference` leaves a sourced attachment and its source intact; the shipped `DeleteAttachment` leaves the
  reference intact. (d) **Only this use case reaches the network:** `DocumentTransport`, `HostResolver`, `HopPolicy`,
  `FetchDocument` and `MaterializeReference` are named nowhere in `C/{backup,merge,transfer}` or in any other use case.

### B4 — the app's adapters (C16–C19)

- **C16, `UrlConnectionTransport` (R85-7, R85-8, R85-13, R85-15).** `A/fetch/UrlConnectionTransport.kt`, the only class in the app
  with `HttpURLConnection`. Per call: `instanceFollowRedirects = false`, `useCaches = false`, `connectTimeout = 15_000`,
  `readTimeout = 30_000`; exactly three request properties, `Accept: application/pdf, image/png, image/jpeg;q=0.9,
  */*;q=0.1`, `Accept-Encoding: identity` (so the platform's transparent gzip never hides `Content-Length`) and
  **`User-Agent: ServiceTag`** (R85-13: no app version, Android version, device model or build id); no
  `Authorization`, no `Cookie`, no `Authenticator`, no `CookieHandler`, no global default of any kind. **Host
  agreement (review M1, defence in depth):** before connecting it refuses, as `UNREACHABLE`, a URL whose
  `java.net.URL(url).host` (brackets removed) differs (ASCII case-insensitively) from `ReferenceUris.hostOf(url)`. The blocking connect
  runs on `io`, and cancellation of `get` calls `disconnect()`; **a response that arrives after cancellation is closed,
  never dropped** (review m3: on the JDK `disconnect()` is a no-op during a TCP connect). The response carries `status`, `Location`,
  `Content-Type` and a `Content-Length` (−1 → null); for a 2xx the body is the connection's input stream wrapped so a
  `SocketTimeoutException` surfaces as `TransportFailure(TIMED_OUT)` and any other `IOException` as
  `TransportFailure(INTERRUPTED)`; for any other status the body is empty and the error stream is never read; `close()`
  is `disconnect()`, idempotent.
  ```kotlin
  override suspend fun get(url: String): TransportResponse = withContext(io) {
      val c = open(url).apply { /* C16's settings, and nothing else */ }
      suspendCancellableCoroutine { cont ->
          cont.invokeOnCancellation { c.disconnect() }
          val r = runCatching { respond(c) }                       // classified per C17 on failure
          r.onSuccess { cont.resume(it) { _ -> it.close() } }      // resumed after cancel → closed
           .onFailure { cont.resumeWithException(classify(it, CONNECT)) }
      }
  }
  ```
  (The fragment pins the shape — settings, then a cancellable connect that disconnects, and a late response closed —
  not the code.)
- **C17, the failure classification (the #66 M21 shape).** One pure function, JVM-tested, the rules in order:
  `SocketTimeoutException` → `TIMED_OUT`; a `SecurityException`, or `EACCES`/`EPERM` in the chain (reduced to an enum
  by an Android-only helper, the `BindErrno` precedent at `A/api/LoopbackApiServer.kt:67-88`) → `DENIED` **only when
  the permission reads as not granted**, otherwise `UNREACHABLE`; `UnknownHostException`, `ConnectException`,
  `NoRouteToHostException`, `SSLException` and any other connect-phase `IOException` → `UNREACHABLE`; a read-phase
  `IOException` → `INTERRUPTED`; **any other `Throwable`** (an `IllegalArgumentException` for a host the platform
  rejects, an `IllegalStateException`) other than a `CancellationException` or an `Error` → `UNREACHABLE` at connect,
  `INTERRUPTED` in the body — never rethrown with its message, which may carry the URL (review m7).
- **C18, `InetHostResolver` and `CacheStagingArea` (R85-7, R85-8).** `A/fetch/InetHostResolver.kt`:
  `lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName` on `io`, answering **every** address's bytes;
  an `UnknownHostException` or an empty answer → `TransportFailure(UNREACHABLE)`; a `SecurityException` → C17's
  permission rule. `A/fetch/CacheStagingArea.kt` over `File(cacheDir, "materialize")`: `create()` makes
  `<id>.part`; `output()` / `source()` over that file; `discard()` deletes it, idempotent; `sweepAtStart(startedAt)`
  deletes every file whose `lastModified < startedAt` (the `TransferPackWriter.kt:81-86` shape). **(§19, C26)** Its
  staging file answers `reader()` with the core `FileStagedReader(partFile)`: B4 wires it and adds nothing else. `ServiceTagApp`
  launches it beside the transfer sweep (`:51-60`), off the main thread, guarded the same way (its one log line names
  the sweep, never a file).
- **C19, wiring, permission and the claims (R85-10, R85-11).** `AppGraph` builds `networkPermissionGranted` (the
  `ContextCompat.checkSelfPermission(…, INTERNET)` lambda `DeveloperApiScreen.kt:82-86` builds; that screen is not
  refactored), one `InetHostResolver`, one `HopPolicy`, one `UrlConnectionTransport`, one `CacheStagingArea`, one
  `FetchDocument` and **one `MaterializeReference`** over the **guarded** attachment port — the only network-reaching
  graph object, consumed only under `A/ui/references/`. `FakeGraph` wires the same over app-side fakes
  (`T/testing/FakeDocumentTransport.kt`, a fake resolver answering `203.0.113.10`, staging in a temp directory). The
  three "no outbound networking" claims are corrected (review M3, owner-approved), and nothing else around them:
  - **the manifest comment** (`AndroidManifest.xml:4-12`) keeps its #66 explanation; `:8`'s "its only user" and
    `:12`'s "introduces no outbound networking" sentence give way to the one intentional outbound use (an owner-tapped
    https download of a reference's document) and the Developer API still listening only on localhost;
  - **`README.md:74`**, the INTERNET bullet: its "used only by the Developer API" opener and its "ServiceTag makes no
    outbound connections" sentence are corrected; nothing else in the README changes;
  - **`docs/api/v1.md:31-41`**: "introduces no outbound networking" and "There is no `HttpURLConnection` … anywhere
    in the app" give way to the one outbound use, with `A/fetch/UrlConnectionTransport.kt` the only
    `HttpURLConnection`, which B4's grep proves.

  No API route, no MCP tool (R85-10).

### B5a — the state (C20–C22)

```kotlin
// A/ui/references/MaterializeViewModel.kt (B5a): one sheet, one job.
sealed interface MaterializeState {
    data class Downloading(val host: String, val done: Long, val total: Long?) : MaterializeState
    data class Review(val host: String, val typeLine: String, val name: String, val kind: AttachmentKind,
                      val role: DocumentRole?, val notes: String, val nameError: String?) : MaterializeState
    data object Saving : MaterializeState
    data class Refused(val line: String, val offersAppSettings: Boolean) : MaterializeState
    data object Done : MaterializeState          // state-backed; the host consumes it once (#84)
    data object Closed : MaterializeState        // a silent close: NoSuchReference or NotEligible
}
```

- **C20, the reference rows (R85-1, R85-12, R85-15).** `ReferenceRowState` appends `materializable: Boolean = false`
  and `savedAsDocument: Boolean = false` (defaults, so the shipped androidTest builders compile). `materializable` =
  `kind == WEB_URL ∧ launchable ∧ hops.staticProblem(uri) == null` (planner finding 6), so an `http` link, a
  userinfo link, a `localhost` link and a host outside C9's allowlist (a Unicode IDN host, R85-15) get no item and
  the reference itself is unchanged. `savedAsDocument` is derived,
  never stored: the view model combines `references.observeForAsset` with
  `attachments.observeForOwner(OfAsset(assetId))` and marks a row when any attachment's `source?.uri == row.uri` (the
  `(assetId, uri)` second identity; M1, M12). Deleting that attachment clears the line. The constructor gains
  `attachments: AttachmentRepository` and `hops: HopPolicy` (the one test builder and the graph constructor ripple).
- **C21, `MaterializeViewModel` (R85-8, R85-9).** Keyed by `(assetId, referenceId)`; its `viewModelScope` owns one
  `Job`. It starts `prepare` at once (download first, R85-9) in `Downloading(host, 0, null)`, updating `done`/`total`
  from `onProgress`. `Ready` → `Review`: `host`; `typeLine` P85-5; `name` = the reference's display name; `kind` =
  `AttachmentKinds.inferFrom(mime, false)`; `role` = none; `notes` = the reference's description; all editable.
  **Save** sets `Saving` **before its first suspension** (a double tap commits once), then `commit`: `Ok` → `Done`;
  `Refused(BlankName)` → back to `Review` with `nameError` = the reused "Give the file a name"; anything else →
  `Refused(line)`. **Cancel** (in Downloading or Review) and `onCleared` (leaving the screen) cancel the job and
  `discard` the staging; they write nothing and draw no line. Saving has no Cancel; leaving the screen during Saving
  cancels `commit`, and the shipped store deletes the partial document (M10), consistent with R85-8 (review NOTE 7).
  `onProgress` runs on the `io` thread, so the model updates its state with `MutableStateFlow.update` (review NOTE 2). **The refusal lines:**
  `NetworkDenied` → P85-10 with `offersAppSettings = true`; `Store(p)` → `AttachmentFailure.Refused(p).sentence()`
  (the reused NoStore / StoreUnavailable lines); `Fetch`: `Unreachable` → P85-11(host), `TimedOut` → P85-12,
  `NotADocument` → P85-13, `NeedsSignIn` → P85-14, `ServerError(c)` → P85-15(c), `RedirectRefused` → P85-16,
  `Interrupted` → P85-18, `LocalAddress` → P85-19, `TooLarge` → the reused "That file is larger than 256 MB" (through
  `AttachmentFailure.Refused(TooLarge(MAX_ATTACHMENT_BYTES)).sentence()`), `Empty` → `IntakeStrings.EMPTY_FILE`,
  `NetworkDenied` → as above; `NotHttps`/`HasCredentials` cannot reach here (C13 step 2) and close silently;
  `AlreadyHave(name)` → P85-17(name); at commit, `AssetTransferredOut` → `transferredOutOr(...)`'s reused line and any
  other failure → the reused "Could not save {name}". `NoSuchReference` / `NotEligible` → `Closed`, no line (no
  sentence is ratified for a vanished or ineligible row, the `ReferencesSectionViewModel` rule).
- **C22, the attachment rows and the strings' home.** `AttachmentRowState` appends `provenanceLine: String? = null`
  (P85-8: "Downloaded from {host} on {date}", `{host}` = `hostOf(source.uri)`, `{date}` = the shipped `displayDate`
  (`A/ui/condition/ConditionWords.kt:96`) of `retrievedAt` in the view model's zone, injected with a
  `ZoneId.systemDefault()` default) and `sourceUri: String? = null` = **`source.uri`, never the resolved destination**
  (R85-2 amended). Every P85 literal is one constant or template function in `A/ui/references/MaterializeStrings.kt`.
  B5a hoists "Could not save {name}" (`A/ui/attachments/AttachmentsSectionViewModel.kt:300`) into `internal fun
  couldNotSave(name: String)` in that file, the call site switched, text byte-identical.

### B5b — the rendered UI (C23–C25)

- **C23, `MaterializeSheet`.** `A/ui/references/MaterializeSheet.kt`, one modal bottom sheet hosted by
  `ReferencesSection`, drawing C21's states: Downloading — P85-2 and P85-3 (indeterminate without a total), `Cancel`;
  Review — title P85-1, P85-4, P85-5, `Name` (a text field), `Kind` (the seven chips), `Role` (the three plus "No
  role"), `Notes`, `Cancel`, `Save`; Saving — an indeterminate indicator and no button; Refused — the line, `Close`,
  and for P85-10 "Open app settings" (`appDetails(context)`, `A/ui/components/SystemSettings.kt:16`). `Done` → the
  snackbar P85-7 once, and the sheet closes. The full URI is never drawn (R85-9); the host is.
- **C24, the reference row (R85-12, ratified).** The ⋮ order is **Open, Save as document, Edit, Remove**; Save as
  document shows iff `row.materializable && !readOnly`, beside Edit and Remove under the shipped `if (!readOnly)`
  (`ReferencesSection.kt:212`). When `row.savedAsDocument`, P85-6 is a `QuietLine` after the kind line. Every new
  composable parameter has a default, so the shipped androidTest callers compile.
- **C25, the edit sheet and the homes (R85-9, R85-2).** `AttachmentEditSheet`, only for a row with a `provenanceLine`:
  a read-only block under Notes with the line and a `TextButton` P85-9 that opens **`row.sourceUri`** through the same
  `LinkLaunchPolicy` decision and launcher a reference's Open uses; a URI the policy blocks draws the shipped
  "ServiceTag will not open that kind of link." (`BLOCKED_AT_OPEN`, `ReferencesSection.kt:49`, made `internal`).
  `DocumentsSection` (`:197`) passes the launcher callback (default: none). The DOCUMENTS row and its description line
  are unchanged. Hoists (text byte-identical, call sites switched): `Name`, `Kind`, `Notes`, `Cancel`, `Save` into
  named constants in `AttachmentEditSheet.kt`; "Open app settings" into a named constant in `DeveloperApiScreen.kt`.

## 3. Test matrix

| row | hazard | test (class · case) | RED mutation |
|---|---|---|---|
| **B1a** 1 | C1: the shape rule | core `CT/model/AttachmentSourceTest` · `allNullIsNoSource`; `aUriWithoutADateIsABreach` and the converse; `httpIsRefused`; `aResolvedUriWithAQueryFragmentPathParameterOrUserinfoIsRefused`; `aResolvedUriEqualToTheSourceIsRefused`; `lengthsAt2048And200PassAnd2049And201Fail`; `aBlankNameIsRefused` | accept a resolved URI carrying `?` |
| 2 | C2: schema 16 | app `T/data/room/Migration15To16Test` · `everyV15RowSurvivesWithFourNulls` (no `updated_at` moves; the #67 shape); `theMigratedSchemaEqualsAFresh16`; `AttachmentDaoTest.aSourceRoundTripsAndNullStaysNull` | (1) the mapper drops `source_retrieved_at`; (2) the migration omits one column |
| 3 | C3: schema pins | §8's list and the eight filters of planner finding 1 | none: a pin moves with the number |
| **B1b** 4 | C4: format 16 | core `BackupCodecTest` · `aSourceRoundTrips` (explicit nulls when unset); `aFormat15ArchiveWithAnySourceFieldIsCorrupt` (each of the four, naming the id and "format 15"); `eachShapeBreachIsCorrupt` (URI without date, date without URI, `ftp:`, 2,049 characters, a resolved URI with a query); `anEventAttachmentMayCarryASource` | (1) skip the ≤ 15 guard; (2) skip the shape check in `toDomain`; (3) `toDto` drops `sourceName` |
| 5 | C5: merge | core `MergePlannerTest` · `aFormat16IdenticalSourcedRowIsIdentical`; `aDifferentSourceNameIsContentDiffers`; `aFormat15ArchiveAgainstALocalSourcedRowIsContentDiffers`; `aFormat9ArchiveAgainstALocalSourcedRowIsContentDiffers`; `ImportBackupMergeTest.anInsertCarriesTheSource` | compare without the source (`copy` the four to null on both sides): the format-15 case turns IDENTICAL |
| 6 | C5: restore, export, packs | core `ExportBackupSetTest.theSourceIsExported`; `ImportBackupReplaceTest.theSourceIsRestored`; `ImportTransferPackTest` · one shipped round-trip case extended: a sourced attachment keeps its source | none: no production line changes; row 4's mapping mutation reds these too |
| 7 | C6: format pins | §9's list; `Format7RestoreContractTest` one connected run | none |
| **B1c** 8 | C7: documents | app `CommandShapesGoldenTest` (the range and "16 since #85 (attachment provenance)"); `ReferenceRoutesTest` (1–15 refused, 1–16 required); pytest `test_tools.py` (1–16 in the docstring and README) | the new "16 since #85" assertion, run before the document moves (test-first) |
| **B2a** 9 | C8, C9: static rules | core `CT/fetch/HopPolicyTest` · `httpIsNotHttps`; `userinfoIsHasCredentials`; `localhostAndDotLocalhostAreLocal` (case, trailing dot); `noHostIsRefused`; `theStaticRuleNeverResolves`; **`aHostOutsideTheAllowlistIsRefused`** (a backslash `https://10.0.0.5\.x.example.invalid/`, a percent escape `10%2e0%2e0%2e5.example.invalid`, fullwidth digits and dots, a Unicode IDN host, an empty label, a label over 63, a leading hyphen, a bad port); **`nonCanonicalIpv4IsRefused`** (`127.1`, `2130706433`, `0177.0.0.1`, `0x7f.0.0.1`); **`nonCanonicalIpv6IsRefused`** (uppercase, uncompressed, a zone id); `punycodeUnderscoreCanonicalLiteralsAndAPortPass` (`xn--bcher-kva.example.invalid`, `dl_cdn.example.invalid`, `203.0.113.10`, `[2001:db8::10]:8443`); `AddReference`'s shipped suites green and unchanged | (1) allow userinfo; (2) **drop the allowlist** (review M1) |
| 10 | C9: the address set (exactly C9's list) | core `CT/fetch/AddressPolicyTest` · a table, **forbidden:** each IPv4 range's first and last address (`0.0.0.0`–`0.255.255.255`, `10.x`, `100.64.0.0`–`100.127.255.255`, `127.x`, `169.254.x`, `172.16.0.0`–`172.31.255.255`, `192.168.x`, `224.0.0.0`, `255.255.255.255`), `::`, `::1`, `fe80::1`, `fec0::1`, `fc00::1`, `fd12::1`, `ff02::1`, `::ffff:192.168.1.5`, `::a00:1`, `64:ff9b::a00:1`, `64:ff9b:1::a00:1`, `2002:a00:1::1`, a 5-byte address; **allowed:** `203.0.113.10`, `192.0.2.1`, `198.51.100.7`, `2001:db8::10`, `::ffff:203.0.113.10`, `100.63.255.255`, `100.128.0.0`, `172.15.255.255`, `172.32.0.0` (the documentation ranges stay allowed: owner, 2026-09-29) | (1) drop `100.64.0.0/10`; (2) do not unwrap `::ffff:0:0/96` |
| 11 | C9: resolution | · `anyForbiddenAddressAmongManyIsLocal` (public first, private last); `aResolverFailureIsUnreachable`; `anEmptyAnswerIsUnreachable`; `deniedIsNetworkDenied` | check only the first address |
| 12 | C8: the destination (R85-2, R85-14) | core `CT/references/ReferenceUrisTest` · `destinationDropsQueryAndFragment`; **`destinationDropsPathParametersPerSegment`** (`/a;x=1/b;jsessionid=AB12/m.pdf` → `/a/b/m.pdf`, never cut at the first `;`); `destinationKeepsTheOrdinaryPath`; `destinationDropsUserinfoAndLowercasesSchemeAndHost`; `anEmptyPathIsSlash`; `hostOfIsByteIdenticalForTheShippedCases` (IPv6, port, userinfo, underscore) | (1) keep the query; (2) keep the fragment; (3) cut at the first `;` of the path instead of per segment |
| **B2b** 17 | C11: the sniff on bytes (R85-5) | core `CT/fetch/DocumentSniffTest` · **`htmlBeginningWithAFakePdfHeaderIsNotADocument`** (`%PDF-1.7\n<!doctype html>…`, no `%%EOF`); `aMinimalPdfIsAPdf`; `aPdfWithJunkBeforeItsHeaderUnder1KiBIsAPdf`; `aPdfWithoutEofIsNotADocument`; `aPngEndingInIendIsAPng`; `aPngWithBytesAfterIendIsNotADocument`; `aTruncatedPngIsNotADocument`; `aJpegWithEoiIsAJpeg`; `aTruncatedJpegIsNotADocument`; `aZipIsNotADocument`; `aFileSmallerThanOneWindowIsJudgedOnBoth` | (1) **skip the PDF tail check** (the fake-header case turns PDF); (2) skip the PNG IEND check; (3) skip the JPEG EOI check |
| **B2c** 13 | C10: redirects | core `CT/fetch/FetchDocumentTest` over `FakeDocumentTransport`, `FakeHostResolver`, `FakeStaging` · `fiveRedirectsAreFollowedAndTheSixthIsRefused`; `anHttpsToHttpHopIsRefusedBeforeItsGet`; `aRelativeLocationIsResolved` (`../`, `/abs`, `//host`; review m4: `https://h.example` + `x.pdf` → `https://h.example/x.pdf`, and `https://h.example/a/b.pdf` + `?t=1` → `https://h.example/a/b.pdf?t=1`); `aMissingLocationIsRefused`; `everyHopIsResolved` (the resolver is asked once per hop, the first included); `aRedirectToAPublicNameResolvingToALanAddressIsLocalAddress` (no GET to it; `LocalAddress`, never `RedirectRefused` or `Unreachable`); `theFirstHopResolvingLocalNeverReachesTheTransport` | (1) follow an https → http hop; (2) allow a sixth hop; (3) resolve the first hop only |
| 14 | C10: statuses | · 401, 403, 407 → `NeedsSignIn`; 200 and 203 are a body; 204, 206, 404, 500 → `ServerError(code)`; every response closed | map 403 to `ServerError` |
| 15 | C10: headers | · `declaredHtmlXhtmlAndPlainTextFailBeforeTheBody` (the fake body throws if read); `contentLengthOverTheLimitFailsBeforeTheBody` | read the body before the declared-type check |
| 16 | C10: streaming | · `theCapIsLimitPlusOne` (a small `FetchLimits.maxBytes`; exactly the limit passes); `theDefaultLimitIsMaxAttachmentBytes`; `emptyIsEmpty`; `aMidBodyFailureIsInterruptedAndDiscards`; `aStalledBodyIsClosedAtTheDeadline` (`runBlocking`, a real `Dispatchers.IO`, real time, `overallMillis = 100`, a body that blocks until closed: `TimedOut`, the response closed, staging discarded); `cancellingMidBodyClosesTheResponseAndDiscards` (`runBlocking`, real `io`; cancellation propagates, never `Interrupted`); `aSlowGetTimesOutInVirtualTime` (`runTest`, `io` = the test dispatcher; review m1); `progressReportsDoneAndTotal`; `theDigestIsTheBytesSha256` | (1) the cap at `maxBytes` instead of `+ 1`; (2) map an `IOException` after cancellation to `Interrupted` |
| **B2c** 17b | C10 + C11 end to end: the "served as" fixtures (R85-5) | · **`htmlServedAsPdfIsNotADocument`**; **`aPdfServedAsOctetStreamIsAPdf`** (recorded as `application/pdf`); **`theFakeHeaderHtmlServedAsPdfIsNotADocument`** (declared `application/pdf`, so step 5 cannot refuse it; review m6) | (1) trust a declared `application/pdf` (skip the sniff); (2) **record the declared type as the MIME** (the octet-stream PDF turns `application/octet-stream`; review m6) |
| **B3** 18 | C12: `AddAttachment` | core `AttachmentUseCasesTest` · `withoutASourceTheRowIsTheShippedRow`; `aSourceIsCopiedOntoTheRow`; `aSourcedAddTakesItsExtensionFromTheType` (a name "manuals.example.invalid" → `assets/<a>/<id>.pdf`); `aMalformedSourceIsAProgrammingError` | (1) drop the source from the row; (2) let the name's extension win for a sourced add |
| 19 | C13: pre-checks | core `CT/usecase/MaterializeReferenceTest` · in order: no such reference, another asset's reference, `NOTE_LINK`, `http`, userinfo, `localhost`, no store, lost store, network denied — **the transport and the resolver are never called** | (1) fetch before the permission check; (2) skip the store pre-check |
| 20 | C14: success | · `oneAttachmentOnTheSameAssetWithTheSnapshot` (sniffed MIME, inferred kind, the review's name, kind, role and notes, `source.name` the reference's name at prepare, `retrievedAt` from the clock); `theResolvedUriIsTheDestinationWithoutQueryOrFragment` (a redirect to `…/file.pdf?token=…#p2`); `anUnmovedDestinationStoresNoResolvedUri` (a redirect that changes only the query); `anOverlongDestinationIsDropped`; `stagingIsDiscarded`; `theReferenceIsByteEqual`; `commitsIsOne`; `aBlankNameKeepsTheStaging` | (1) store the final URL with its query; (2) take `source.name` from the review |
| 21 | C13: duplicates (R85-6) | · `identicalBytesOnTheAssetAreAlreadyHave` (staging discarded, the store untouched, the earliest row named); `changedBytesAreASecondAttachment`; `theSameBytesOnAnotherAssetAreAllowed` (X4: two assets, each its own); **`cancellingDuringTheDuplicateCheckDiscards`** (review m2) | check across every asset |
| 22 | C14, C15: failures | · a failure at `put`, at the row write, and the guard's `AssetTransferredOut`: the reference untouched, no row, the store empty, staging discarded, `commits == 0` | keep the staging on a `put` failure |
| 23 | C15: independence (R85-1) | · `removingTheReferenceKeepsTheSourcedAttachment`; `deletingTheAttachmentKeepsTheReference` (shipped use cases, unchanged) | none: shipped use cases are unchanged; the proof is the assertion |
| **B4** 24 | C16: the transport | app `T/fetch/UrlConnectionTransportTest` over a loopback JDK `HttpServer` · status, `Location`, type and length; `aRedirectIsNotFollowed`; `sendsExactlyAcceptIdentityEncodingAndTheFixedUserAgent` (`User-Agent: ServiceTag`, R85-13; no `Cookie`/`Authorization`; no version, model or build); `aNon2xxBodyIsEmpty`; `aReadTimeoutIsTimedOut`; `closeDisconnects`; **`cancellingAPendingGetDisconnects`** (a loopback server that never answers; cancel; `get` returns within 1 s of real time; the server sees EOF; review m3); `aHostThePlatformReadsDifferentlyIsRefused` (review M1) | (1) `instanceFollowRedirects = true`; (2) drop `Accept-Encoding: identity` |
| 25 | C17: classification | app `T/fetch/TransportFailureTest` · each rule; `aDenialWithThePermissionGrantedIsUnreachable`; **`aNonIoThrowableIsClassifiedWithoutItsMessage`** (an `IllegalArgumentException` at connect → `UNREACHABLE`, in the body → `INTERRUPTED`; `CancellationException` and `Error` propagate) | answer `DENIED` whatever the permission says |
| 26 | C18: the resolver | app `T/fetch/InetHostResolverTest` · `everyAddressIsAnswered` (an injected lookup with two); `aLiteralResolvesToItself` (`127.0.0.1`, no DNS); `unknownHostIsUnreachable`; `emptyIsUnreachable` | answer only the first address |
| 27 | C18: staging | app `T/fetch/CacheStagingAreaTest` · create, write, `source()` reads it back, discard twice; `sweepAtStartKeepsThisProcesssFiles` | the sweep deletes files made after `startedAt` |
| 28 | C15, C16, C19: greps | §15's anchored greps (the three claims → 0, no TLS weakening or global default, three request properties) | none: structure |
| **B5a** 29 | C20: row state | app `ReferencesSectionViewModelTest` (+5) · `materializableForAnHttpsWebLink`; `notForHttpNoteBlockedUserinfoOrANonAsciiHost` (R85-15: the reference is unchanged); `savedAsDocumentWhenASourcedAttachmentNamesTheUri`; `clearedWhenThatAttachmentIsDeleted`; `anotherAssetsSourceDoesNotCount` | (1) materializable on `http`; (2) derive by display name, not URI |
| 30 | C21: the sheet model | app `T/ui/references/MaterializeViewModelTest` · `downloadsFirstThenReviews` (prefill: name, description, inferred kind, no role); `progressFlowsIntoDownloading`; `everyRefusalMapsToItsLine` (a table over C21's list, P85-10 with settings); `aDoubleTapCommitsOnce`; `saveGoesDoneOnce`; `aBlankNameStaysInReviewWithTheReusedLine`; `cancelInDownloadingAndReviewWritesNothingAndDiscards`; `clearingTheModelDiscards`; `aTransferredOutAssetSaysTheShippedLine` | (1) Cancel does not discard; (2) set `Saving` after the suspension |
| 31 | C22: provenance state | app `AttachmentsSectionViewModelTest` (+3) · `aSourcedRowCarriesItsLineAndTheOriginalUri`; `theLineNamesTheSourceHostNotTheRedirectHost`; `anUnsourcedRowCarriesNeither` | read `sourceUri` from the resolved destination |
| 32 | C22: strings as data | app `T/ui/references/MaterializeStringsTest` · every P85 literal verbatim; the templates' placeholders | none: a pin |
| **B5b** 33 | C23–C25: rendered | **no new device class or case.** `ReferencesSectionTest` (shipped, R2-only, planner finding 7) run once connected; the gate's `AssetDetailKeyDocumentsTest` covers the edit sheet's unchanged path | none: rows 29–31 carry the REDs |

**Planned counted REDs: 61** (46 before §19's amendment, which adds B2d 6, B2e 2 and B2f 7, rows 34–43) — B1a 3,
B1b 4, B1c 1, B2a 8, B2b 3, B2c 9, B3 8, B2d 6, B2e 2, B2f 7, B4 5, B5a 5, B5b 0 — including every
mutation the owner and the review asked for: the three end-marker checks (row 17, the fake-header case its own), the
octet-stream PDF (row 17b (2)), the authority allowlist (row 9 (2)), the resolver rule (rows 10, 11, 13 (3), 26), the
query stripping (rows 1, 12, 20 (1)) and the per-segment path parameters (row 12 (3)). **No device mutation.** **New JVM cases:**
about 125 in core, about 60 in app; pytest count unchanged (three assertions move).

## 4. Files, fences, order and the gate budget

One branch `issue-85` from the plan's ratified commit: **B1a → B1b → B1c → B2a → B2b → B2c → B3 → B2d → B2e → B2f → B4 → B5a → B5b**, each
`<base>` the previous accepted tip.

| brief | production | tests | cap (runs) | fix round | size (prod / test) |
|---|---|---|---|---|---|
| B1a | `C/model/Attachment.kt`; `A/data/room/{entities/AttachmentEntity,Mappers,Migrations,AppDatabase}.kt`; `A/di/AppGraph.kt` (`:150`, `:244`, `:980` only); `app/schemas/…/16.json` | new `AttachmentSourceTest`, `Migration15To16Test`; `AttachmentDaoTest`; `MigrationTestSupport`; §8's pins | 4 | 2 runs, 45 min | ~70 / ~220 |
| B1b | `C/backup/{BackupFormat,BackupCodec}.kt`; `C/merge/MergePlanner.kt` (KDoc only) | row 4–6 classes; §9's pins | 5 | 3 runs, 45 min | ~80 / ~260 |
| B1c | `docs/api/v1.md` (`:184`, `:187-191`, `:1162-1180`, `:1539-1550`); `M/src/servicetag_mcp/server.py` (docstring only); `M/README.md` (`:299` only); `docs/release-proofs.md` (`:107` only) | `CommandShapesGoldenTest`, `ReferenceRoutesTest`, `M/tests/test_tools.py` (`:792`, `:798`, `:807`) | 2 | 1 run, 45 min | ~35 docs / ~30 |
| B2a | new `C/references/ReferenceUris.kt`, `C/fetch/{AddressPolicy,HopPolicy,FetchProblem}.kt`; `C/usecase/AddReference.kt` (extraction only) | new `HopPolicyTest`, `AddressPolicyTest`, `ReferenceUrisTest`, `CT/testing/FakeHostResolver.kt` | 9 | 3 runs, 45 min | ~190 / ~280 |
| B2b | new `C/fetch/DocumentSniff.kt` | new `DocumentSniffTest` | 4 | 2 runs, 45 min | ~70 / ~150 |
| B2c | new `C/fetch/{FetchPorts,FetchDocument}.kt` | new `FetchDocumentTest`, `CT/testing/{FakeDocumentTransport,FakeStaging}.kt` | 10 | 3 runs, 45 min | ~170 / ~270 |
| B3 | new `C/usecase/MaterializeReference.kt`; `C/usecase/{AddAttachment,AttachmentCommands}.kt` | new `MaterializeReferenceTest`; `AttachmentUseCasesTest` | 9 | 3 runs, 45 min | ~160 / ~320 |
| B2d (§20) | `C/fetch/{FetchPorts,DocumentSniff,FetchDocument}.kt`; new `C/fetch/{ContainerInspect,FileStagedReader}.kt`; `C/model/Attachment.kt` (`EXTENSIONS` only) | new `BoundedInspectionTest`, `FileStagedReaderTest`, `ContainerInspectZipTest`; `DocumentSniffTest`, `FetchDocumentTest`, `CT/testing/FakeStaging.kt` | 7 | 3 runs, 45 min | ~150 / ~260 |
| B2e (§21) | `C/fetch/{ContainerInspect,FetchDocument}.kt`; `C/model/Attachment.kt` (`EXTENSIONS` only) | new `ContainerInspectOleTest`, `CT/testing/CfbFixtures.kt`; `FetchDocumentTest` | 3 | 2 runs, 45 min | ~110 / ~200 |
| B2f (§22) | `C/fetch/{DocumentSniff,FetchDocument}.kt`; new `C/fetch/TextSniff.kt`; `C/model/Attachment.kt` (`EXTENSIONS` only) | new `TextSniffTest`; `DocumentSniffTest`, `FetchDocumentTest`, `AttachmentRulesTest` | 8 | 3 runs, 45 min | ~120 / ~250 |
| B4 | new `A/fetch/{UrlConnectionTransport,TransportFailures,InetHostResolver,CacheStagingArea}.kt`; `A/di/AppGraph.kt`; `A/ServiceTagApp.kt` (the sweep block); `app/src/main/AndroidManifest.xml` (the comment at `:4-12` only); `README.md` (the INTERNET bullet at `:74` only); `docs/api/v1.md` (`:31-41` only) | new rows 24–27 classes; `T/testing/{FakeGraph,FakeDocumentTransport}.kt` | 6 | 3 runs, 45 min | ~230 / ~320 |
| B5a | new `A/ui/references/{MaterializeViewModel,MaterializeStrings}.kt`; `A/ui/references/ReferencesSectionViewModel.kt`; `A/ui/attachments/AttachmentsSectionViewModel.kt` (row state, the `couldNotSave` hoist) | new `MaterializeViewModelTest`, `MaterializeStringsTest`; `ReferencesSectionViewModelTest`; `AttachmentsSectionViewModelTest` | 6 | 3 runs, 45 min | ~220 / ~300 |
| B5b | new `A/ui/references/MaterializeSheet.kt`; `A/ui/references/ReferencesSection.kt`; `A/ui/attachments/{AttachmentEditSheet,DocumentsSection}.kt`; `A/ui/api/DeveloperApiScreen.kt` (the hoist only); `A/ui/asset/AssetDetailScreen.kt` (≤ 20 lines near `:461`, only if the host must pass a callback) | none new; the connected run | 2 | 1 run, 45 min | ~230 / ~0 |

**Untouched by every brief** (`git diff <base> --stat -- <paths>` → empty): the manifest's `uses-permission`
elements and every other manifest line but B4's comment, `ManifestContractTest`; `app/build.gradle.kts`,
`core/build.gradle.kts`; `libs/nfc-tag-core`; `tools/emulator/**`, the gate script and every androidTest helper;
`share-test-sender/**`; `A/share/**`, `A/api/**`, `A/transfer/**`; `C/transfer/**`;
`C/usecase/{ImportBackupReplace,ExportBackupSet,ApplyBackupMergePlan,UpdateAttachment,DeleteAttachment,RemoveReference,UpdateReference}.kt`;
`A/attachments/SafTreeAttachmentStore.kt`; `docs/versioning.md`; `ReleaseProofPolicyTest`; every `MergeTable`,
`MergeReason` and tally; `M/tests/**` except B1c's three lines. No changed line names `external_link` or
`externalLinks`. Each brief adds its own fence (§8–§17).

**Collisions.** B1a, B4 and B5a (the constructor ripple only) touch `A/di/AppGraph.kt`; B4 and B5a touch
`T/testing/FakeGraph.kt`; B1a and B1b move the two halves of `VersionAgreementTest` and `MaintenanceRoutesTest`, as #86
did; B1c and B4 touch different lines of `docs/api/v1.md`. All sequential; no live lane.

**Gate budget.** After the whole-branch review, merge `--no-ff issue-85` into master at once, then run the controller's
gate **once** at the merge commit: `.superpowers/sdd/2026-09-29-issue-86/gate/run.sh` copied unchanged into
`.superpowers/sdd/2026-09-29-issue-85/gate/` with `extra-classes.env` copied unchanged
(`EXTRA_CLASSES="com.loosecannon.servicetag.ui.replace.ReplaceAssetFlowTest"`: #86's class is now shipped and stays in
the run; #85 adds none). **Expected:** 55 device classes; JVM core 1398 + about 125, app 1530 + about 60; MCP 384 at 69
tools; the whole about 11.2–11.5 minutes. **Record in the ledger and the plan's errata** (reporting only; the 14- and
15-minute lines trigger nothing): the whole time from start to GATE DONE with its JVM, device and MCP portions; the class
count; **#85's delta against 11.18 minutes whole, 10.19 minutes device and 55 classes**. **A failure is recorded, never
rerun:** its class, case and log; a flake at an untouched site goes to #90 with the log; a fix is a new commit and a new
gate, by the controller's decision.

## 5. Owner-gate summary

**What changes.** Room 15 → 16 and backup format 15 → 16: four nullable, write-once columns on `attachment`
(`source_uri` verbatim, `source_resolved_uri` as scheme + authority + the path without its per-segment parameters,
only when the destination moved,
`source_retrieved_at`, `source_name`); no table, count, merge table, reason or tally moves; older archives decode
unchanged, and one carrying provenance is refused as hand-built. **Merge:** no new logic; a sourced row differs from
an unsourced one. **Transfer Packs** carry the fields as they are. **Network:** the app's first outbound use, one
owner-tapped https download, behind `:core` ports; every hop's authority in unambiguous ASCII form, resolved, and
refused if any address is in C9's explicit set; `User-Agent: ServiceTag` and nothing identifying; no cookies,
credentials or logging. **Phone:** one ⋮ item (Open, Save as document, Edit, Remove), one sheet (download, review,
save), one quiet line on the reference, one provenance block with "Open source link" (the original URI) in the
attachment's edit sheet. **API and MCP:** documents only; 69 tools; an import-merge carries sourced rows as they are.
**Manifest:** the INTERNET comment only. **README:** the INTERNET bullet's two false claims. **`docs/api/v1.md`:** the
networking paragraph's claims. **No `versionName` bump.**

**The gate at 16** (`docs/release-proofs.md:107`, written by B1c): the direct **1.4.1 (schema 8) → schema 16** proof,
its 15s moved to 16, "#85's attachment provenance at schema 16" added; the post-upgrade export's attachments carry the
four `source…` keys as null; the pre-upgrade export re-plans IDENTICAL. No reference is saved as a document in the
gate (the release brief's optional one-row smoke on the dev phone, with INTERNET granted, is the 1.5.0 release's).

**What the owner gate asks:** ratify §6's table — P85-1…19 with their treatments, home and wording flags — and the
18 reused strings with theirs. The rulings (§7), R85-13…15 included, are decided.

## 6. Strings — RATIFIED (owner, 2026-09-29; P85-13, P85-18 and P85-19 amended)

`{host}` is `ReferenceUris.hostOf(source or reference URI)` — never the redirect's; `{done}` and `{total}` are sizes by
the shipped `Long.asFileSize()` (`A/ui/attachments/DocumentsSection.kt:361`); `{TYPE}` ∈ the nineteen ratified labels of §19's table (PDF, PNG, JPEG, GIF, WebP, RTF, DOC, XLS, PPT, DOCX, XLSX, PPTX, ODT, ODS, ODP, TXT, Markdown, CSV, TSV);
`{size}` by `asFileSize()`; `{date}` is `d MMM uuuu` through `displayDate` (`A/ui/condition/ConditionWords.kt:96`);
`{code}` is the HTTP status; `{name}` is an attachment's display name. **Home:** every P85 literal is one constant or
one template function, on a line of its own, in `A/ui/references/MaterializeStrings.kt`; `ReferencesSection`,
`MaterializeSheet` and `AttachmentEditSheet` reference it from there.

| id | where | text | treatment | home | gate flag |
|---|---|---|---|---|---|
| P85-1 | ⋮ item; sheet title | `Save as document` | `DropdownMenuItem` · title | `MaterializeStrings` | — |
| P85-2 | Downloading | `Downloading from {host}…` | headline | `MaterializeStrings` | — |
| P85-3 | Downloading | `{done} of {total}` — or `{done}` when the size is unknown | progress line | `MaterializeStrings` | — |
| P85-4 | Review | `From {host}` | line | `MaterializeStrings` | — |
| P85-5 | Review | `{TYPE} · {size}` | line | `MaterializeStrings` | all nineteen `{TYPE}` labels RATIFIED 2026-09-29 (§19's table): PDF, PNG, JPEG, GIF, WebP, RTF, DOC, XLS, PPT, DOCX, XLSX, PPTX, ODT, ODS, ODP, TXT, Markdown, CSV, TSV |
| P85-6 | reference row | `Saved as document` | `QuietLine` | `MaterializeStrings` | — |
| P85-7 | after Save | `Saved to Documents` | snackbar | `MaterializeStrings` | — |
| P85-8 | attachment edit sheet | `Downloaded from {host} on {date}` | read-only line under Notes | `MaterializeStrings` | — |
| P85-9 | attachment edit sheet | `Open source link` | `TextButton` (opens the original URI) | `MaterializeStrings` | — |
| P85-10 | refusal: network denied | `ServiceTag is not allowed to use the network, so it cannot download this file. Allow network access in the app settings, then close ServiceTag and open it again.` | error line, plus "Open app settings" | `MaterializeStrings` | follows P1A-1 |
| P85-11 | refusal: unreachable | `Could not reach {host}. Check the connection and try again.` | error line | `MaterializeStrings` | also answers a certificate failure ("Check the connection" misleads there) |
| P85-12 | refusal: timeout | `The download took too long and was stopped.` | error line | `MaterializeStrings` | — |
| P85-13 | refusal: not a document | `That link did not lead to a supported document type. It stays a link.` | error line | `MaterializeStrings` | re-ratified with R85-5's widening (§19), 2026-09-29 |
| P85-14 | refusal: sign-in | `That file needs a sign-in, so ServiceTag cannot download it. It stays a link.` | error line | `MaterializeStrings` | — |
| P85-15 | refusal: server | `The server did not send the file (error {code}).` | error line | `MaterializeStrings` | — |
| P85-16 | refusal: redirect | `That link redirects somewhere ServiceTag will not follow. It stays a link.` | error line | `MaterializeStrings` | — |
| P85-17 | refusal: identical bytes | `This asset already has this file: {name}.` | error line | `MaterializeStrings` | — |
| P85-18 | refusal: interrupted | `The download could not be completed. Try again.` | error line | `MaterializeStrings` | also answers a full cache (a staging write failure) |
| P85-19 | refusal: local address, first hop or any redirect hop (R85-7 amended; planner finding 12; behaviour approved by the owner) | `That link resolves to a local-network address, so ServiceTag will not download it. It stays a link.` | error line | `MaterializeStrings` | also appears on captive-portal (hotel, airport) Wi-Fi and split-horizon networks whose DNS answers private addresses; follows the P85-13/14/16 "It stays a link." pattern |

**Reused, through their homes** (B5a/B5b hoist each † literal into a named constant beside its shipped use, the shipped
call site switched, text byte-identical; `MaterializeStrings.kt` and `MaterializeSheet.kt` quote none of these):

| # | text | where #85 uses it | home |
|---|---|---|---|
| 1 | `Cancel` | Downloading, Review | `AttachmentEditSheet.kt:130` † (B5b) |
| 2 | `Save` | Review | `AttachmentEditSheet.kt:145` † (B5b) |
| 3 | `Name` | Review field label | `AttachmentEditSheet.kt:76` † (B5b) |
| 4 | `Notes` | Review field label | `AttachmentEditSheet.kt:118` † (B5b) |
| 5 | `Kind` | Review section header | `AttachmentEditSheet.kt:80` † (B5b) |
| 6 | the seven kind labels | Review chips | `AttachmentKind.label()`, `DocumentsSection.kt:337` |
| 7 | `Role` | Review section header | `ROLE_HEADER`, `AttachmentsSectionViewModel.kt:68` |
| 8 | the three role labels and `No role` | Review chips | `DocumentRole?.label()`, `AttachmentsSectionViewModel.kt:60-65`; `ROLE_CHOICES` |
| 9 | `Close` | Refused | `IntakeStrings.CLOSE`, `A/share/ShareIntakeViewModel.kt:68` |
| 10 | `Open app settings` | P85-10's button | `DeveloperApiScreen.kt:150` † (B5b) |
| 11 | `That file is larger than 256 MB` | `TooLarge` | `AttachmentFailure.sentence()`, `AttachmentsSectionViewModel.kt:449` |
| 12 | `That file is empty` | `Empty` | `IntakeStrings.EMPTY_FILE`, `ShareIntakeViewModel.kt:78` |
| 13 | `Choose an attachment folder in Settings first` | `Store(NoStore)` | `AttachmentFailure.sentence()`, `:447` |
| 14 | `The attachment folder is not available` | `Store(StoreUnavailable)` | `AttachmentFailure.sentence()`, `:448` |
| 15 | `Give the file a name` | a blank name at Save | `AttachmentFailure.sentence()`, `:446` |
| 16 | `Could not save {name}` | any other failure at Save | `AttachmentsSectionViewModel.kt:300` † → `couldNotSave(name)` (B5a) |
| 17 | `This asset was transferred out.` | the guard at Save | `TransferImportStrings.ASSET_TRANSFERRED_OUT` via `transferredOutOr` (`A/ui/transfer/TransferRefusals.kt:11`) |
| 18 | `ServiceTag will not open that kind of link.` | "Open source link" on a blocked URI | `BLOCKED_AT_OPEN`, `ReferencesSection.kt:49` (private → internal, B5b) |

**Count (final):** 19 new ids (P85-1…19, all RATIFIED; P85-13, P85-18 and P85-19 as amended), no
accessibility-only string; 18 reused (7 hoisted: five in `AttachmentEditSheet.kt`, one in `DeveloperApiScreen.kt`,
one in `AttachmentsSectionViewModel.kt`; `BLOCKED_AT_OPEN` only becomes `internal`). The wording flags (P85-10, 11, 13,
18, 19) are flags for the string gate, not rulings. **Not a UI string:** the wire header `User-Agent: ServiceTag`
(R85-13) is a request property owned by C16, decided, and outside the string gate.

## 7. Rulings (owner, 2026-09-29 — DECIDED)

**Decided constraints:** R85-C1, R85-C2, R85-C3 (Global constraints). **R85-1…11 APPROVED as the audit recommended,
with R85-2, R85-5 and R85-7 amended; R85-12's menu order and strings RATIFIED (§6). R85-13, R85-14 (which
amends R85-2 again) and R85-15 DECIDED the same day, after the plan review.** The audit's dispositions (two DROPs, three
DEFERs) are approved. Each ruling names the contracts and rows it governs.

| ruling | decision | contracts · rows |
|---|---|---|
| R85-1 | **Keep the reference.** The attachment is a copy; the reference shows a derived "Saved as document". Deleting either side never deletes the other. | C14, C15, C20, C24 · rows 20, 22, 23, 29 |
| R85-2 **(amended: privacy)** | Four nullable, write-once columns on `attachment`, schema/format 16. `source_uri` is the original reference URI, verbatim. **`source_resolved_uri` keeps no query, no fragment and (R85-14) no per-segment path parameter**: the final https URL stripped to scheme + authority + the ordinary path, stored only when it differs from the original destination (`destinationOf` both sides) and fits 2,048 characters; presigned or session tokens never travel into backups or Transfer Packs. **"Open source link" uses `source_uri`, never the resolved one.** The shape rule applies on any owner. | C1, C2, C4, C5, C8, C12, C14, C22, C25 · rows 1, 2, 4, 5, 6, 12, 18, 20, 31 |
| R85-3 | A pure snapshot: no `reference_id`, no key; "Saved as document" is derived by `(assetId, uri)`. | C1, C14, C20 · rows 20, 29 |
| R85-4 | https only, on every redirect hop; no cleartext exception. `http` references get no ⋮ item. | C9, C10, C20 · rows 9, 13, 29 |
| R85-5 **(amended: validation; WIDENED by the owner, 2026-09-29, §19)** | **Save as document** accepts exactly PDF, PNG, JPEG, GIF, WebP, RTF, DOC, XLS, PPT, DOCX, XLSX, PPTX, ODT, ODS, ODP, TXT, Markdown, CSV and TSV; ordinary attachments (picker, share) stay unrestricted. Binary and container formats are judged from the bytes or the container, never a filename or MIME type alone; **a header alone is not enough** (PDF header plus `%%EOF`; PNG signature plus a terminal IEND; JPEG SOI plus EOI; GIF trailer; WebP RIFF size; RTF closing brace). OOXML needs its package structure (`[Content_Types].xml`, `_rels/.rels` and one main part) — an arbitrary ZIP renamed `.docx` is refused; legacy Office needs the CFB container **and** a Word, Excel or PowerPoint stream; ODF needs its stored `mimetype` identity. TXT, Markdown, CSV and TSV need text-like bytes, a compatible declared type or extension, and no web-page pattern (the residual risk accepted: explicit action, inert data). A declared HTML type fails at once. Required fixtures: HTML served as `application/pdf` fails; a genuine PDF served as `application/octet-stream` succeeds; HTML with a fake `%PDF-` fails through the end check; and §19's nine must-fail and sixteen must-pass fixtures. Sniffing and bounded inspection, never parsing or decompressing. | C10, C11, C21, C26–C31 · rows 15, 17, 17b, 30, 34–43 (B2b, B2c, B2d, B2e, B2f) |
| R85-6 | Always fetch. The same bytes (digest and size) on the same asset are refused (P85-17); changed bytes become a new attachment; a hand-added row is never given provenance. | C13 · row 21 |
| R85-7 **(amended: network)** | Connect 15 s, idle 30 s, overall 10 min; at most 5 redirects; 256 MiB; empty refused. **Every hop's host is resolved before its request and refused if ANY address is loopback, link-local, RFC 1918, 100.64.0.0/10, fc00::/7** (C9 states the exact set once; the documentation ranges stay allowed, owner 2026-09-29); the resolver is a `:core` port so JVM tests stay local. DNS rebinding between the check and the connect is a recorded limit, never a reason to fall back to literals. No cookie, credential, `Authorization` header or auth state is introduced or forwarded. | C9, C10, C16, C17, C18 · rows 9–11, 13, 16, 24–26; P85-19 on the first and every redirect hop |
| R85-8 | Screen-bound: the sheet's view model owns the job; Cancel or leaving stops it; staging is swept at start; no WorkManager, no foreground service. | C10, C18, C21 · rows 16, 27, 30 |
| R85-9 | Download, then review, then Save. The review shows the host, type and size; the full URL is never rendered. The provenance line and "Open source link" sit on the attachment's edit sheet. | C21, C22, C23, C25 · rows 30, 31 |
| R85-10 | No API route, no MCP tool; 69 tools. The documents name both as deliberately absent. A remotely callable fetcher is a separate issue. | C7 · row 8 |
| R85-11 | A manifest comment only: the permission set, cleartext setting and dependencies are unchanged. | C19 · row 28 |
| R85-12 | **Menu order RATIFIED:** Open → Save as document → Edit → Remove. **Strings RATIFIED** (§6; P85-13, P85-18, P85-19 amended; the 18 reused as listed). | C24, §6 · rows 29, 32 |
| R85-13 **(DECIDED, owner 2026-09-29)** | A fixed `User-Agent: ServiceTag`; no app version, Android version, device model, build id, cookie, credential or authentication state is sent. It is the third and last request property. | Global constraints, C10, C16 · row 24 (`sendsExactlyAcceptIdentityEncodingAndTheFixedUserAgent`), row 28 (three `setRequestProperty`) |
| R85-14 **(DECIDED, amends R85-2)** | `source_resolved_uri` also strips path parameters, **per path segment** (`;` to that segment's end), never a cut at the first `;`; the ordinary path is kept so the redirect provenance stays useful. Recorded limit: a server can encode a secret in an ordinary-looking path segment, which cannot safely be told apart from a legitimate path. | C1 (the codec refuses a `;` in the resolved URI), C8, C14 · rows 1, 12 (3), 20 |
| R85-15 **(DECIDED, makes review M1 explicit)** | A host must already be in unambiguous ASCII form: a DNS name in ordinary ASCII label syntax (`xn--…` punycode labels included), or an IPv4 or IPv6 literal that parses canonically and then passes the address policy. Unicode host characters, percent escapes, backslashes, control or whitespace characters, malformed labels or authorities and every other alternate spelling are refused before resolution or any request. No automatic IDNA conversion: the reference stays valid and simply gets no Save as document until its host is in the accepted form. **The invariant:** the host ServiceTag validates is unambiguously the host the networking layer connects to; the downloader allows no browser-compatible reading of an odd authority. | C9, C16 (host agreement), C20 · rows 9 (2), 24, 29 |

**Where the plan refines the audit's text** (planner findings 1–13, §1), each inside the rulings above: the
migration-test filters (1) and the MCP pins (2) are pin work under R85-2; the README sentence (3) sits beside R85-11's
comment; the cancellation shape (4), the progress callback (8) and the one `java.net.URI` import (11) are inside
R85-8 and R85-7; the sniffed extension (5) is inside R85-5; the row-state split (6) and P85-19 (12) follow R85-4 and
R85-7; the stripped-shape split between codec and write (13) implements R85-2's amendment; the authority allowlist
(14) is R85-15. The plan review (`brief-review.md`) approved all eleven departures and all five consequences; rev 1.1
applies its conditions C-1…C-8.

## Briefs — common to all thirteen

Read §1–§7, the audit, issue #85 and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun` with zero failures and skips;
`:app:compileDebugAndroidTestKotlin` in every brief. Before a connected class, `tools/emulator/prepare-emulator.sh`;
then one class per run, **once**: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Then the brief's anchored greps (`git grep -nE '<pattern>'
-- <paths>`, over `app/src/main` and `core/src/main` unless named); the untouched diff; the tombstone check
(`git diff <base> -- . ':!app/schemas' | grep -cE '^[-+].*(external_link|externalLinks)'` → 0);
`git ls-tree HEAD libs/nfc-tag-core` → `7e0377a…`; each → 1: `'^\s*versionName = "1\.4\.1"$'` and
`'^\s*versionCode = 17$'` in `app/build.gradle.kts`; `git diff <base> -- app/build.gradle.kts core/build.gradle.kts`
→ empty; `git status` clean.

**Pin rule.** A shipped assertion pinning a version, column set, row snapshot, document wording, tool count or menu
order moves only where the brief's pin list names it; every other assertion stays. Confirm the set first with
`git grep -nE '\b15\b|Fifteen|1–15|Format15|V15_TABLES|_ATTACHMENT_COLUMNS|rowOf\(' -- core/src/test app/src/test
app/src/androidTest tools/servicetag-mcp/tests`; the report lists every move and every mechanical constructor ripple.
**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row with no natural RED, and why;
each connected run with its class, cases and time; each grep with its count; the brief's wall time against its box;
what is done, proven and not proven.

**Stop and report, always:**
- an edit to an untouched file;
- an assertion that must move outside the brief's pin list;
- any device failure, with its log (never rerun);
- a JVM or build failure the brief did not cause (reported, not retried);
- the emulator unavailable for 30 minutes;
- a user-visible string not in §6, or a §6 literal worded differently;
- any network access from a test (a test that needs a real host is wrong by construction);
- the cap reached, the 1-hour target passed with less than half the rows green, or the 2-hour hard stop — with what is
  done, proven and not.

**Must NOT, always:** delete a shipped assertion; commit outside the brief's files; add a device class or case; run a
device mutation; use a device other than `emulator-5554`; start, stop or restart the emulator or adb; touch a harness
helper, `tools/emulator/*` or the gate script; add Robolectric, a JVM Compose dependency or any library; bump a
version; move a table, count key, `MergeTable`, `MergeReason` or tally; write a reference row; log a URI, header,
filename or byte; send or store a cookie, credential or `Authorization` header; render a full URI; write a real name,
address, host or serial (fixtures use `example.invalid`, `203.0.113.0/24` and `2001:db8::/32`).

## 8. B1a — the provenance columns (C1–C3, JVM)

**Read:** audit §3 (M7–M11, M14–M18), §4.2, §5; `C/model/Attachment.kt`; `A/data/room/entities/AttachmentEntity.kt`;
`A/data/room/Mappers.kt` (the attachment pair); `A/data/room/Migrations.kt` (`:583-587`, `:709-720`);
`AppDatabase.kt:85-95`; `AppGraph.kt` (`:150`, `:244`, `:980`); `T/data/room/MigrationTestSupport.kt` (`:55-75`,
`:190-235`); `Migration9To10Test.kt` (the #67 shape to copy); `Migration14To15Test.kt`; the eight sites of planner
finding 1. **`<base>`** = the ratified plan commit. **Rows:** 1–3. **Rulings:** R85-2, R85-3.

**Pins (schema half):**
- `VersionAgreementTest.kt:81-83`: the name → `theSchemaIsSixteenAndTheFormatIsFifteen`, `:82` 15 → 16 (`:83` stays
  15); `:149` 15 → 16 (`:150` stays 15).
- `MaintenanceRoutesTest.kt:1503` 15 → 16; the name at `:1486` → `statusReports16And15AndTheNewCounts` (a name).
- `MigrationTestSupport.kt:68` gains `MIGRATION_15_16`; `V16_ATTACHMENT_COLUMNS` = the four names, beside `:216`.
- The eight filters (planner finding 1): `Migration4To5Test.kt:81` (`- V16_ATTACHMENT_COLUMNS`), and at
  `Migration9To10Test.kt:29`, `Migration10To11Test.kt:29`, `Migration11To12Test.kt:37`, `Migration12To13Test.kt:32`,
  `Migration13To14Test.kt:28`, `Migration14To15Test.kt:27` and `ReferenceMigrationTest.kt:78` the attachment row
  compared without `V16_ATTACHMENT_COLUMNS` — the before-snapshot never moves, only the columns the chain added.
- (rev 1.1, review M2) `Migration9To10Test.kt:67`: `.last()` → `.dropLast(V16_ATTACHMENT_COLUMNS.size).last()` (the
  #86 `dropLast` precedent), and `:69`: `16` → `20` — `theMigratedSchemaEqualsAFreshVersion10` reads the migrated
  `attachment` columns, which the chain now carries to 16.

**Connected:** none. **Greps** (each → 1 unless named): `'^\s+version = 16,$'` in `AppDatabase.kt`;
`'^\s*const val SCHEMA_VERSION = 16$'` in `AppGraph.kt`; `'\bMIGRATION_15_16\b'` in `Migrations.kt`, `AppGraph.kt` and
`MigrationTestSupport.kt` (each ≥ 1); ``'ADD COLUMN `source_(uri|resolved_uri|retrieved_at|name)`'`` in `Migrations.kt`
→ 4; `'^\s+val source: AttachmentSource\? = null,?$'` in `Attachment.kt`; `'fun attachmentSourceProblem\('` over
`C/model`; `16.json` present and `git diff <base> -- 'app/schemas/**/15.json'` empty; `'source'` in
`C/usecase/{AttachmentCommands,UpdateAttachment}.kt` → 0.

**Untouched:** `C/backup/**`, `C/merge/**`, `C/usecase/**`, `A/ui/**`, `A/api/**`, `docs`, `tools`,
`app/src/androidTest`. **Must NOT:** add an index, default, backfill or `updated_at` move; add a column beyond C2's;
give `UpdateAttachmentCommand` a source; add a `reference_id`. **Counted RED (3):** rows 1 (1), 2 (2). **Caps:** 4 JVM
mutation runs, 0 device; **1 h target, 2 h hard stop**; fix round 2 runs, 45 min. **Stop also** if a migration test
outside the nine sites needs a filter (report it; it is a pin the plan missed). **Size:** about 70 production, 220 test
lines.

## 9. B1b — format 16, merge, restore and packs (C4–C6, JVM plus one connected run of a moved pin)

**Read:** audit §4.2, §5; `C/backup/BackupCodec.kt` (`:60-170`, `:330-360`); `C/backup/BackupFormat.kt` (`:360-395`,
`:970-1035`); `C/merge/MergePlanner.kt` (`:755-830`); `C/transfer/TransferPackReader.kt:120-135`;
`BackupCodecTest.kt` (`:1040-1140`, the #67 role cases to mirror); `ImportTransferPackTest.kt`. **`<base>`** = B1a's
tip. **Rows:** 4–7. **Rulings:** R85-2, R85-3.

**Pins (format half; re-located on `8057c561`):**
- core backup: `BackupCodecTest.kt:1060-1061` (the name → `theFormatIsSixteenAndTheLegacyBoundaryStaysSeven`, 15 →
  16); `BackupFormat6Test.kt:358`; `BackupFormat8Test.kt:276-281` (the one-past format 16 → 17, `found` 16 → 17,
  `supported` 15 → 16) and its KDoc (`:269-270`); `BackupFormat9Test.kt:70`; `BackupFormat13Test.kt:54`,
  `BackupFormat14Test.kt:48`, `BackupFormat15Test.kt:48` (15 → 16).
- core use cases: `ExportBackupSetTest.kt:45` (and its comment); `Format7ImportIdentityTest.kt:245`.
- app: `VersionAgreementTest.kt:81-83` (the name → `theSchemaIsSixteenAndTheFormatIsSixteen`, `:83` → 16) and `:150`;
  `MaintenanceRoutesTest.kt:1157`, `:1504`, `:1551` (→ 16), the names at `:1486` (→ `statusReports16And16…`) and
  `:1539` (→ `importMergeReadsFormat16AndReportsTwentyTables`).
- androidTest: `Format7RestoreContractTest.kt:164` (15 → 16), the name at `:157` (→ `reExportIsFormat16AndPlansIdentical`)
  and the KDoc at `:41-42` and `:151`, the precedented fenced retarget.
- **Unchanged** (contrast with #86): the table counts in `BackupFormat6Test`/`7Test`; `BackupFormat9Test:101-102`,
  `BackupFormat12Test:70`, `BackupFormat13Test:58`/`:68`, `BackupFormat14Test:52`/`:65`; `StageABundleConformanceTest`;
  every `MergePlanner*Test` table list; `ApiRouterTest` (symbolic); `_MIN_SUCCESSION_SCHEMA_VERSION == 15`.

**Connected:** `Format7RestoreContractTest`, once (its pin moved). **Greps** (each → 1 unless named):
`'^\s*const val FORMAT_VERSION = 16$'` and `'^\s*private const val FIRST_SOURCE_FORMAT = 16$'` in `BackupCodec.kt`;
`'^\s*const val LAST_LEGACY_FORMAT: Int = 7$'` in `LegacyArchive.kt`;
`'^\s+val source(Uri|ResolvedUri|RetrievedAt|Name): (String|Long)\? = null,?$'` in `BackupFormat.kt` → 4;
`'attachmentSourceProblem\('` in `BackupFormat.kt` ≥ 1; `git diff <base> -- C/merge/MergePlan.kt` → empty;
`git diff <base> -U0 -- C/merge/MergePlanner.kt | grep -E '^[-+][^-+]' | grep -cvE '^[-+]\s*(\*|//|/\*\*)'` → 0 (KDoc
only); `git diff <base> -- C/usecase C/transfer` → empty.

**Untouched:** `C/usecase/**` (tests aside), `C/transfer/**`, `A/**` (tests aside), `app/schemas`, `docs`, `tools`,
every androidTest file but `Format7RestoreContractTest.kt`. **Must NOT:** add an R67-12-style comparison exception;
add a merge arm, reason or tally; validate on encode; upgrade older archives. **Counted RED (4):** rows 4 (3), 5 (1).
**Caps:** 5 JVM mutation runs, 0 device mutations, 1 connected run; **1 h target, 2 h hard stop**; fix round 3 runs,
45 min. **Stop also** if a shipped archive fixture or golden fails for a reason other than the moved number (the
explicit-null keys must not break a shipped byte comparison; if one does, report it). **Size:** about 80 production,
260 test lines.

## 10. B1c — the documents at 16 (C7)

**Read:** `docs/api/v1.md` (`:180-192`, `:1160-1180`, `:1535-1550`); the review's m9; `M/src/servicetag_mcp/server.py:1125-1140`;
`M/README.md:295-305`; `M/tests/test_tools.py:776-810`; `docs/release-proofs.md:107`;
`CommandShapesGoldenTest.kt:108-150`; `ReferenceRoutesTest.kt:536-565`. **`<base>`** = B1b's tip. **Rows:** 8.
**Rulings:** R85-2, R85-10.

**Pins:** `CommandShapesGoldenTest.kt:115-122` (the name → `theContractDocumentNamesFormat16AndTwentyTables`, 1–15 →
1–16) plus one new assertion beside `:144` ("16 since #85 (attachment provenance)"); `ReferenceRoutesTest.kt:544`
(the history comment gains "#85's format 16 moved the range to 1–16"), `:552-562` ("1–15" joins the refused list,
"1–16" required at both sites); `M/tests/test_tools.py:792` (the name → `…formats_1_to_16…`), `:798`, `:807` (1–15 →
1–16).

**Connected:** none. `(cd tools/servicetag-mcp && uv run --frozen pytest)` once: green, 384 tests, 69 tools.
**Greps:** in `v1.md` `'format \*\*1–16\*\*'` and `'\*\*format 1–16\*\*'` → 1 each; `'1–15\*\*|format 1–15'` → 0;
`'16 since #85 \(attachment provenance\)'` ≥ 1; `'twenty tables'` ≥ 1 (unchanged); `'saving a reference as an
attachment'` → 1; in `server.py` `'format 1–16'` → 1; in `M/README.md` `'format \*\*1–16\*\*'` → 1; in
`docs/release-proofs.md` `'schema 8\) → schema 16'` → 1, ``'Room schema 15 / backup format 15|`schemaVersion` 15|`backupFormatVersion` 15|format-15 round trip|carry schema 15 under'`` → 0,
`'asset successions at schema 15'` → 1, `'attachment provenance at schema 16'` → 1; in `v1.md`
`'reading or recording an attachment.s source provenance'` → 1 and `'carries each attachment.s source provenance \(#85\)'`
→ 1 (review m9); `'neither reads nor writes attachments'` in any line B1c adds → 0.

**Untouched:** `core/src/main`, `app/src/main`, `app/schemas`, `app/src/androidTest`, `M/src` beyond the one
docstring, `M/tests` beyond the three lines, R4 and `ReleaseProofPolicyTest`. **Must NOT:** document a route or tool;
name the resolved URI's query anywhere as stored; change the tool count; touch `v1.md:31-41` (B4's); claim that no
route writes provenance (an import-merge carries it). **Counted RED (1):** row 8.
**Caps:** 2 JVM mutation runs; **1 h target, 2 h hard stop** (expected well under); fix round 1 run, 45 min.
**Size:** about 35 docs, 30 test lines.

## 11. B2a — the URL and address policy (C8–C9, core, JVM only)

**Read:** audit §4.8, §6, §13 (3); the review's M1 and m5; `C/usecase/AddReference.kt` (`:55-125`);
`C/references/{LinkLaunchPolicy,ReferenceLimits,ReferenceKinds}.kt`; `C/usecase/AttachmentSweep.kt:10-25`
(cancellation); the shipped `AddReference` tests. **`<base>`** = B1c's tip. **Rows:** 9–12. **Rulings:** R85-2, R85-4,
R85-7, R85-14, R85-15.

**Connected:** none. **Greps:** `'private fun hostOf'` in `AddReference.kt` → 0 and `'ReferenceUris\.hostOf'` there
≥ 1; `'fun interface HostResolver'` → 1; `'class TransportFailure'` → 1; `'java\.net\.'` over `core/src/main` → 0
(review m11: a fully qualified use is caught too); `'InetAddress|Socket|URLConnection|IDN\.'` over `core/src/main` → 0
(no IDNA conversion, R85-15); `git diff <base> --diff-filter=M --name-only -- core/src/test` → empty (B2a adds test
files only); `'Log\.|println'` over `C/fetch` → 0; row 10's table lists exactly C9's set and the four documentation
ranges as allowed (by inspection, quoted in the report).

**Untouched:** every `C/usecase` file but `AddReference.kt` (extraction only: its behaviour, refusals and order
identical — the allowlist is `HopPolicy`'s, never `AddReference`'s, so a reference with an odd host still saves);
`C/{backup,merge,transfer,model}`; `app/**`; `docs`; `tools`. **Must NOT:** change what `AddReference` accepts; use
`java.net.URI` for a host decision; decode, IDN-map or otherwise normalise a host beyond ASCII lowercasing and one
trailing dot; treat a resolver failure as allowed; check fewer than all resolved addresses; resolve inside
`staticProblem`; forbid an address outside C9's set (the documentation ranges stay allowed). **Counted RED (8):**
rows 9 (2), 10 (2), 11 (1), 12 (3). **Caps:** 9 JVM mutation runs, 0 device; **1 h target, 2 h hard stop**; fix
round 3 runs, 45 min. **Stop also** if the extraction would change any shipped `AddReference` assertion, or if the
canonical-IPv6 rule needs more than about 40 lines (report; the controller decides). **Size:** about 190 production,
280 test lines.

## 12. B2b — the sniff (C11, core, JVM only)

**Read:** audit §4.5, §13 (1); the review's M4 and m6; `C/model/Attachment.kt` (`MimeTypes`); B2a's report.
**`<base>`** = B2a's tip. **Rows:** 17. **Rulings:** R85-5.

**Fixtures (the magic-byte risk, R85-5):** built in code as byte arrays, fictional, each a few hundred bytes: a minimal
PDF (`%PDF-1.4` … `%%EOF`); a PDF with 200 junk bytes before its header; a PDF without `%%EOF`; an HTML page that
begins `%PDF-1.7\n` and has no `%%EOF`; a minimal PNG (signature, IHDR, IDAT, IEND), one cut before IEND and one with
bytes after IEND; a minimal JPEG (`FF D8 FF E0` … `FF D9`) and one cut before EOI; a ZIP (`PK\x03\x04`); a 40-byte
file (head and tail overlap). The sniff sees no declared type; the "served as" fixtures are B2c's.

**Connected:** none. **Greps:** `'^object DocumentSniff'` → 1; `'import '` lines in `DocumentSniff.kt` → none outside
`kotlin.*` (a pure function); `'java\.net\.'` over `core/src/main` → 0; `git diff <base> --diff-filter=M --name-only --
core/src` → empty (B2b adds files only).

**Untouched:** every existing file. **Must NOT:** read anything but the two windows; take a declared type; parse
beyond the signatures and end markers; accept a PNG with bytes after IEND. **Counted RED (3):** row 17 (1) the PDF
tail check (the fake-header case its own), (2) the PNG IEND check, (3) the JPEG EOI check. **Caps:** 4 JVM mutation
runs, 0 device; **1 h target, 2 h hard stop** (expected well under); fix round 2 runs, 45 min. **Size:** about 70
production, 150 test lines.

## 13. B2c — the fetch (C10, core, JVM only)

**Read:** audit §4.5, §4.6, §6, §11 rows 8–13; the review's M4, m1, m4, m6 and NOTEs 1 and 4;
`C/ports/AttachmentStore.kt`; B2a's and B2b's reports. **`<base>`** = B2b's tip. **Rows:** 13–16, 17b. **Rulings:**
R85-4, R85-5, R85-7, R85-8.

**Fixtures:** B2b's byte arrays, served end to end by the fake transport under a declared type: HTML (a fictional
login page) **served as `application/pdf`**; the minimal PDF **served as `application/octet-stream`**; the fake-header
HTML **served as `application/pdf`** (so step 5 cannot be what refuses it); every other case as its row says. The
fake resolver answers `203.0.113.10` unless a case says otherwise. **Dispatchers (review m1):** under `runTest`, `io`
is the test's own dispatcher; the two blocking cases run under `runBlocking` with a real `Dispatchers.IO` and a
wall-clock bound; never virtual time with a real `io`.

**Connected:** none. **Greps:** `'java\.net\.'` over `core/src/main` → exactly 1, `import java.net.URI` in
`C/fetch/FetchDocument.kt` (planner finding 11); `'InetAddress|Socket|URLConnection|CookieHandler'` over
`core/src/main` → 0; `'withTimeoutOrNull'` in `FetchDocument.kt` → 1; `'DocumentSniff\.classify\('` in
`FetchDocument.kt` → 1; `'hops\.check\('` in `FetchDocument.kt` ≥ 1 and reached for every hop (by row 13, not by
grep); `'@InternalCoroutinesApi'` over `core/src/main` → 0; `'Log\.|println'` over `C/fetch` → 0; `git diff <base>
--diff-filter=M --name-only -- core/src` → empty (B2c adds files only).

**Untouched:** every existing file, `DocumentSniff.kt` included. **Must NOT:** read the body before the declared-type
and length checks; trust `Content-Length` over the count; judge or record by the declared type; read the staged file a
second time (amended by §19 for the code after B2d: at most one bounded, read-only container inspection through
`BoundedInspection`, only after the stream completes and only for a ZIP or OLE2 head); buffer the body beyond the two 1,024-byte windows and one chunk; map a failure after cancellation to a
problem; follow a redirect without `hops.check`; lump `LocalAddress` into `RedirectRefused` or `Unreachable`; print a
URL from a `toString`. **Counted RED (9):** rows 13 (3), 14 (1), 15 (1), 16 (2), 17b (2). **Caps:** 10 JVM mutation
runs, 0 device; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Stop also** if a real-time case needs more
than 2 s of wall time, or if C10's cancellation shape cannot be met with public coroutine API (report; do not use
`@InternalCoroutinesApi`). **Size:** about 170 production, 270 test lines.

## 14. B3 — `MaterializeReference` (C12–C15, core, JVM only)

**Read:** audit §4.1, §4.3–§4.7; `C/usecase/{AddAttachment,AttachmentCommands,UpdateAttachment,DeleteAttachment,
RemoveReference}.kt`; `C/model/Attachment.kt` (`:65-95`, `:155-161`); `C/transfer/HeldWriteGuard.kt:130-140`;
`CT/usecase/AttachmentUseCasesTest.kt`; `CT/testing/InMemoryAttachmentStore.kt`; the review's m2, m8 and NOTE 1;
B2a's, B2b's and B2c's reports. **`<base>`** = B2c's tip. **Rows:** 18–23. **Rulings:** R85-1, R85-2, R85-3, R85-6,
R85-8, R85-14.

**Connected:** none. **Greps:** `'^class MaterializeReference\('` over `C/usecase` → 1; `'references\.(upsert|delete)'`
in `MaterializeReference.kt` → 0; `'uow\.(write|read)'` in `MaterializeReference.kt` → 0;
`'addAttachment\.run\('` there → 1; `'DocumentTransport|HostResolver|HopPolicy|FetchDocument|MaterializeReference'`
over `C/{backup,merge,transfer}` and every other `C/usecase` file → 0; `'val source: AttachmentSource\? = null'` in
`AttachmentCommands.kt` → 1, inside `AddAttachmentCommand` (quoted in the report); `git diff <base> --
C/usecase/{UpdateAttachment,DeleteAttachment,RemoveReference,UpdateReference}.kt` → empty; every shipped
`AttachmentUseCasesTest` case green and unchanged; `'AttachmentLocator\.forOwner\(owner, id, "", '` in `AddAttachment.kt`
→ 1 (review m8); `git diff <base> -- C/model` → empty.

**Untouched:** `C/{backup,merge,transfer,model,fetch,references}`; every use case but the three named; `app/**`;
`docs`; `tools`. **Must NOT:** write or delete a reference; open a transaction; call the transport or the resolver
before the permission check; retrofit provenance onto an existing row; check duplicates across assets; store a query or
fragment or a segment's `;` parameters; leave staging behind when `prepare` is cancelled or throws after the fetch;
print a URI from `Prepared.Ready.toString()`; take `source.name` from the review; change `AddAttachment`'s refusals, their order or its commit count for
an unsourced command. **Counted RED (8):** rows 18 (2), 19 (2), 20 (2), 21 (1), 22 (1). **Caps:** 9 JVM mutation runs,
0 device; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Stop also** if `AddAttachment` needs a second new
field. **Size:** about 160 production, 320 test lines.

## 15. B4 — the app's adapters (C16–C19)

**Read:** audit §4.8, §6; `A/api/LoopbackApiServer.kt:60-95` (the classification precedent);
`A/ui/api/DeveloperApiScreen.kt:78-90`; `A/transfer/TransferPackWriter.kt:70-90`; `A/ServiceTagApp.kt:40-70`;
`A/di/AppGraph.kt` (`:500-530`, `:620-640`); `T/testing/FakeGraph.kt`; `AndroidManifest.xml:1-16`; `README.md:70-76`;
`docs/api/v1.md:31-41`; the review's M1 (host agreement), M3, m3, m7 and m11; §19's C26. **`<base>`** = B2f's tip
(§19). **Rows:**
24–28. **Rulings:** R85-7, R85-8, R85-10, R85-11, R85-13, R85-15.

**Connected:** none (the transport test is a JVM test against a loopback JDK `HttpServer`; the https rule is B2's, so it
needs no TLS). **Greps:** `git grep -lE 'HttpURLConnection|openConnection\(' -- app/src/main core/src/main` → exactly
`A/fetch/UrlConnectionTransport.kt`; `'setRequestProperty\('` there → 3 (`Accept`, `Accept-Encoding`, `User-Agent`;
R85-13); `'"User-Agent", "ServiceTag"'` there → 1; `'Build\.|BuildConfig|VERSION_NAME|http\.agent'` in `A/fetch` → 0;
`'CookieHandler|CookieManager|Authenticator|Authorization|"Cookie"'` over `app/src/main/kotlin` → 0;
`'setFollowRedirects|HostnameVerifier|TrustManager|SSLSocketFactory|CookieHandler\.setDefault|Authenticator\.setDefault'`
over `app/src/main/kotlin` → 0 (review m11: TLS is never weakened, no global default is set); `'Log\.|println'` over `A/fetch` → 0;
`git grep -lE '\bInetAddress\b' -- app/src/main` → `A/fetch/InetHostResolver.kt` and the shipped
`A/api/LoopbackApiServer.kt` only; `'MaterializeReference\('` over `app/src/main` → 1 (`AppGraph.kt`);
`'materializeReference\b'` over `app/src/main` outside `A/di` and `A/ui/references` → 0;
`git diff <base> -- app/src/main/AndroidManifest.xml | grep -cE '^[-+].*(uses-permission|<application|<activity|<provider|<service|<receiver|usesCleartextTraffic|networkSecurityConfig)'`
→ 0; **the three claims (review M3):** `'its only user|used only by the Developer API|introduces no outbound networking|makes no outbound connections|There is no .HttpURLConnection.'`
over `app/src/main/AndroidManifest.xml`, `README.md` and `docs/api/v1.md` → 0; `git diff <base> -- README.md` touches
the INTERNET bullet only and `git diff <base> -- docs/api/v1.md` lines `:31-41` only (by inspection, quoted);
`git diff <base> -- T/reminders/ManifestContractTest.kt` → empty.

**Untouched:** `core/**`; `A/{api,share,transfer,attachments,ui}`; `app/schemas`; `app/src/androidTest`; `docs` but
`v1.md:31-41`; `tools`; every manifest line outside the INTERNET comment; `README.md` outside the INTERNET bullet.
**Must NOT:** follow a redirect in the adapter; read an error stream; set any request property beyond the three; send
the platform User-Agent or any version, model or build; drop a response that arrives after cancellation; rethrow a
platform exception with its message; register a cookie handler or
authenticator; add a permission, cleartext setting or dependency; refactor `DeveloperApiScreen`; resolve through
anything but the injected lookup in tests. **Counted RED (5):** rows 24 (2), 25 (1), 26 (1), 27 (1). **Caps:** 6 JVM
mutation runs, 0 device; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Stop also** if the loopback
`HttpServer` cannot be bound in the JVM test sandbox (report; do not skip). **Size:** about 230 production, 320 test
lines.

## 16. B5a — the state (C20–C22)

**Read:** audit §7, §9; `A/ui/references/ReferencesSectionViewModel.kt`; `A/ui/attachments/AttachmentsSectionViewModel.kt`
(`:50-130`, `:290-330`, `:436-458`); `A/ui/attachments/DocumentsSection.kt:330-370`; `A/ui/condition/ConditionWords.kt:90-100`;
`A/ui/transfer/TransferRefusals.kt`; `A/share/ShareIntakeViewModel.kt:55-80`; the #84 plan's state-backed one-shot
rule; `ReferencesSectionViewModelTest.kt`; `AttachmentsSectionViewModelTest.kt`. **`<base>`** = B4's tip. **Rows:**
29–32. **Rulings:** R85-1, R85-2, R85-6, R85-8, R85-9, R85-12 (strings RATIFIED: the literals are §6's exactly).

**Connected:** none. **Greps:** every P85 literal → 1, anchored, in `A/ui/references/MaterializeStrings.kt`, and 0
elsewhere in `app/src/main`; `'fun couldNotSave\('` → 1 and `'"Could not save '` over `app/src/main` → 1 (the hoist);
`'resolvedUri'` over `A/ui` → 0 (R85-2: the UI never reads the redirect destination); `'"(Give the file a name|That
file is empty|That file is larger than 256 MB|Close|This asset was transferred out\.)"'` in `A/ui/references` → 0.

**Untouched:** `core/**`; `A/{api,share,transfer,fetch,data,di}` (but the constructor ripple in `A/di`, listed);
every composable file (B5b's); `app/src/androidTest`; `docs`; `tools`. **Must NOT:** store `savedAsDocument`; key it
by anything but the URI; read the resolved destination; draw a line for `NoSuchReference` or `NotEligible`; offer
Cancel in Saving; invent a string. **Counted RED (5):** rows 29 (2), 30 (2), 31 (1). **Caps:** 6 JVM mutation runs,
0 device; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Stop also** if a §6 literal needs a change to fit
(the gate decides wording). **Size:** about 220 production, 300 test lines.

## 17. B5b — the rendered UI (C23–C25)

**Read:** audit §7; `A/ui/references/ReferencesSection.kt`; `A/ui/attachments/{AttachmentEditSheet,DocumentsSection}.kt`;
`A/ui/api/DeveloperApiScreen.kt:130-155`; `A/ui/components/SystemSettings.kt`; `A/links/`; `A/ui/asset/AssetDetailScreen.kt:455-470`;
`AT/ui/references/ReferencesSectionTest.kt`; B5a's report. **`<base>`** = B5a's tip. **Rows:** 33. **Rulings:** R85-9,
R85-12 (the menu order ratified).

**Connected, one run, no rerun, with its time:** `com.loosecannon.servicetag.ui.references.ReferencesSectionTest`
(shipped, R2-only; its overflow gains an item, planner finding 7). **Greps:** every P85 literal 0 outside
`MaterializeStrings.kt`; `'"(Name|Kind|Notes|Cancel|Save|Close|Open app settings)"'` in `MaterializeSheet.kt` → 0; each
of the six hoisted constants' definitions → 1 (`Name`, `Kind`, `Notes`, `Cancel`, `Save` in `AttachmentEditSheet.kt`;
"Open app settings" in `DeveloperApiScreen.kt`); `'internal const val BLOCKED_AT_OPEN'` → 1; `'resolvedUri'` over
`A/ui` → 0; `git diff <base> --stat -- A/ui/asset/AssetDetailScreen.kt` ≤ 20 lines; the menu order Open, Save as
document, Edit, Remove by inspection, quoted in the report; the manifest and `ManifestContractTest` diffs → empty.

**Untouched:** `core/**`; `A/{api,share,transfer,fetch,data,di}`; every view model (B5a's); shipped androidTest
files; `docs`; `tools`. `DeveloperApiScreen.kt` changes by its one hoist only. **Must NOT:** render a full URI; draw
the resolved destination; open anything but `row.sourceUri`; offer Save as document on a read-only detail; add a
device case; change a DOCUMENTS row or its description line. **Counted RED:** none (rows 29–31 carry them).
**Caps:** 2 JVM mutation runs for any glue, 0 device mutations, 1 connected run; **1 h target, 2 h hard stop**; fix
round 1 run, 45 min. **Stop also** past about 80 changed lines in `ReferencesSection.kt`, or on any failure of the
connected run (report with its log; never rerun). **Size:** about 230 production lines, no test lines.

## 18. Time boxes (owner rule, 2026-09-29)

| brief | target | hard stop | fix round | counted REDs / cap | size (prod / test) |
|---|---|---|---|---|---|
| B1a | 1 h | 2 h | 45 min, 2 runs | 3 / 4 | ~70 / ~220 |
| B1b | 1 h | 2 h | 45 min, 3 runs | 4 / 5 | ~80 / ~260 |
| B1c | 1 h | 2 h | 45 min, 1 run | 1 / 2 | ~35 docs / ~30 |
| B2a | 1 h | 2 h | 45 min, 3 runs | 8 / 9 | ~190 / ~280 |
| B2b | 1 h | 2 h | 45 min, 2 runs | 3 / 4 | ~70 / ~150 |
| B2c | 1 h | 2 h | 45 min, 3 runs | 9 / 10 | ~170 / ~270 |
| B3 | 1 h | 2 h | 45 min, 3 runs | 8 / 9 | ~160 / ~320 |
| B2d | 1 h | 2 h | 45 min, 3 runs | 6 / 7 | ~150 / ~260 |
| B2e | 1 h | 2 h | 45 min, 2 runs | 2 / 3 | ~110 / ~200 |
| B2f | 1 h | 2 h | 45 min, 3 runs | 7 / 8 | ~120 / ~250 |
| B4 | 1 h | 2 h | 45 min, 3 runs | 5 / 6 | ~230 / ~320 |
| B5a | 1 h | 2 h | 45 min, 3 runs | 5 / 6 | ~220 / ~300 |
| B5b | 1 h | 2 h | 45 min, 1 run | 0 / 2 | ~230 / ~0 |

No brief or agent runs past 2 hours. A brief that passes its 1-hour target with less than half its rows green stops
and reports; the controller splits the rest. B2 was split three ways in rev 1.1 (review M4), sniff first, so the fetch
brief calls the real sniff and no brief carries a stand-in. B2a's cap is 9, not the review's 8: the review's M1 adds one
RED (7 → cap 8), and R85-14, decided after the review, adds the per-segment path-parameter RED (8 → cap 9).

## 19. Amendment — widened document types (owner, 2026-09-29)

**The ruling (binding, recorded on #85; §7 R85-5 as widened).** Ordinary attachments (picker, share) stay
unrestricted. **Save as document** accepts exactly: **PDF, PNG, JPEG, GIF, WebP, RTF, DOC, XLS, PPT, DOCX, XLSX, PPTX,
ODT, ODS, ODP, TXT, Markdown, CSV, TSV.** A binary or container format is accepted only when its bytes or container
match the family; a filename or MIME type alone is never enough. OOXML and ODF need a recognisable internal package
structure (an arbitrary ZIP renamed `.docx` is not enough). TXT, Markdown, CSV and TSV use the weaker model the owner
accepted: text-like content, a compatible declared type or extension, and explicit web-page rejection; the residual
risk is accepted because the action is explicit and the result is stored as inert data. **HTML MIME is still refused
at once.** P85-13 is re-ratified: `That link did not lead to a supported document type. It stays a link.`

**Execution (owner).** B2b and B2c are complete and are not reopened as briefs. The widened validators are **three new
bounded briefs**, run after B3 and before B4, each with a 1 h target, a 2 h hard stop and a 45-minute fix round. The
owner's split was two (container, text); at about 620 lines the container half would not convincingly fit an hour
(the OLE2 directory walk and its hand-built fixture are the plan's second-hardest code after C10's cancellation), so
it is split now, not at its first hour (review M4's lesson). **The dependency points one way: B3 → B2d → B2e → B2f → B4
→ B5a → B5b.** No new brief waits for B4; B4 only wires what B2d defines.

| brief | owns | rows |
|---|---|---|
| **B2d** container: the inspection port, ODF, OOXML | C26, C27, C28, C31 (the ZIP wiring) | 34–37 |
| **B2e** legacy Office (OLE2 / CFB) | C29, C31 (the OLE2 wiring) | 38–39 |
| **B2f** text and the simple binaries (GIF, WebP, RTF, TXT, Markdown, CSV, TSV) | C30, C31 (the text wiring and the fail-fast change) | 40–43 |

### Decision 1 — container structure beyond the two windows: **(a), one bounded read-only inspection**

**Chosen: (a).** After the stream completes, a ZIP or OLE2 head triggers **at most one bounded, read-only, random-access
inspection** of the staged file, through a platform-neutral port that B2d defines in `:core`. Not (b): a streaming
scan would have to trust ZIP local headers (the central directory, not the local headers, is what every reader
believes, and the two can disagree) and buffer an OLE2 file's FAT chain, whose directory sectors can sit anywhere; the
central directory is authoritative and only reachable from the end record. `DocumentSniff`'s two-window contract is
kept for every format that fits in it (PDF, PNG, JPEG, GIF, WebP, RTF, ODF); deeper checks live in one separate
validator.

```kotlin
// C/fetch/FetchPorts.kt (B2d): platform-neutral, read-only, random access over the staged file.
fun interface StagedReader {
    /** Exactly min(length, size − position) bytes from [position] (empty at or past the end). Throws IOException. */
    fun readAt(position: Long, length: Int): ByteArray
}
interface StagingFile { /* output(), source(), discard() as shipped */ fun reader(): StagedReader }
/** The one budget for an inspection. A read past either cap throws InspectionOverBudget: the file is refused. */
class BoundedInspection(private val reader: StagedReader, val maxReads: Int = 160, val maxBytes: Int = 524_288) {
    fun readAt(position: Long, length: Int): ByteArray       // counts reads and bytes; refuses a negative position
}
// C/fetch/FileStagedReader.kt (B2d): java.io.RandomAccessFile, opened read-only per inspection, closed after it.
class FileStagedReader(private val file: File) : StagedReader
```

- **C26, the inspection port (B2d).** `StagingFile` gains `reader()`. `StagedReader` is read-only by construction (no
  write method); `FileStagedReader` opens its file `"r"` and never writes; `BoundedInspection` is the only way a
  validator reads, and every validator takes a `BoundedInspection`, never a raw reader. **Caps:** at most 160 reads and
  524,288 bytes per inspection; one inspection per fetch; a read past a cap → the file is refused (`NotADocument`),
  never truncated into an answer. JVM tests use a byte-array `StagedReader` in `CT/testing/` and `FileStagedReader` over
  a temp file; the shipped `CT/testing/FakeStaging.kt` (B2c's) gains `reader()` over its bytes. **B4's obligation, not
  B2d's prerequisite:** B4's `CacheStagingArea` staging file answers `reader()` with `FileStagedReader(partFile)`.
- **C27, ODF in the two windows (B2d, `DocumentSniff.kt`).** Head: a ZIP local file header at 0 (`50 4B 03 04`) whose
  entry name (offset 30, length at 26) is exactly `mimetype`, compression method 0 (stored, offset 8), and whose data
  (at 30 + name + extra, all inside the head window) is exactly `application/vnd.oasis.opendocument.text`,
  `…spreadsheet` or `…presentation` → ODT, ODS or ODP. Tail: the end-of-central-directory signature `50 4B 05 06` lies in
  the tail window with its comment-length field equal to the bytes after the record (the ZIP ends there). Anything
  else — `mimetype` missing, not first, compressed, another value (templates, drawings) — is not ODF. **The declared
  `mimetype` identity is proven; nothing else in the package is parsed.**
- **C28, ZIP → OOXML (B2d, `ContainerInspect.kt`).** Only for a head starting `50 4B 03 04` that C27 did not accept.
  (1) One read of the last min(size, 65,557) bytes; the **last** EOCD signature whose comment length equals the bytes
  after its 22-byte record; none → refused. (2) ZIP64 markers (`0xFFFF` entry counts, `0xFFFFFFFF` size or offset) →
  refused (recorded limit). (3) The central directory's offset + size must end at or before the EOCD, its size ≤
  262,144 bytes, its entries ≤ 4,096; one read. (4) Each record must start `50 4B 01 02` and fit; names are read as
  bytes (UTF-8), nothing is decompressed. (5) **The package proof:** the names include `[Content_Types].xml` and
  `_rels/.rels`, and **exactly one** main part: `word/document.xml` → DOCX, `xl/workbook.xml` → XLSX,
  `ppt/presentation.xml` → PPTX. (6) Any name ending `vbaProject.bin` → refused: a macro-enabled package (DOCM, XLSM,
  PPTM) is not on the list. **An arbitrary ZIP, whatever its declared type or URL extension, is refused.**
- **C29, OLE2 / CFB → DOC, XLS, PPT (B2e, `ContainerInspect.kt`).** Only for a head starting
  `D0 CF 11 E0 A1 B1 1A E1`. **The compound-file signature alone is never enough.** (1) The header (the first 512 bytes,
  in the head window): byte order `FE FF`; major version 3 with sector shift 9 (512-byte sectors) or 4 with shift 12
  (4,096); mini-sector shift 6. (2) The directory chain from the header's first-directory-sector field, followed through
  the FAT: FAT sector ids from the header's 109 DIFAT slots, then DIFAT sectors (at most 8); each sector position must
  lie inside the file; a sector seen twice ends the walk as refused (a cycle); at most 64 directory sectors. (3) Each
  128-byte entry: name length ≤ 64, UTF-16LE name, object type 2 (stream). (4) **The content proof, exactly one of:**
  a stream `WordDocument` → DOC; `Workbook` or `Book` → XLS; `PowerPoint Document` → PPT. None (an MSI, an Outlook
  message, a bare signature) or more than one → refused. Exact names, never prefixes.
- **C30, the text formats and the simple binaries (B2f).** `DocumentSniff.kt` (two windows) gains: **GIF** — head
  `GIF87a` or `GIF89a`, and the file's last byte is the trailer `3B`; **WebP** — head `RIFF`, a little-endian size, then
  `WEBP` and a chunk `VP8 `, `VP8L` or `VP8X`, and RIFF size + 8 == the file size exactly; **RTF** — head starts
  `{\rtf1`, and the last byte that is not a space, tab, CR, LF or NUL is `}`. New `C/fetch/TextSniff.kt`, asked **only
  when no binary family matched**:
  - **text-like:** after an optional UTF-8 BOM, both windows decode as UTF-8 (ASCII included) with no NUL byte; a
    multi-byte sequence cut at a window's inner edge is tolerated (at most 3 bytes; none when the windows overlap);
  - **compatible:** the declared type (normalised) is `text/plain`, `text/markdown`, `text/csv` or
    `text/tab-separated-values`, **or** the extension of the final URL's last path segment (after C8's stripping,
    lowercased) is `txt`, `md`, `csv` or `tsv`. Neither → refused: **arbitrary `text/plain`-looking bytes are never
    classified on their own;**
  - **web-page rejection:** the head window, case-insensitively, contains none of `<!doctype html`, `<html`, `<head`,
    `<script`, `<body`, `<meta`, `<form`, `<iframe`, and not `<?xml` followed anywhere in the head window by
    `http://www.w3.org/1999/xhtml`.
- **C31, where each format is decided, and the fetch wiring.** The family is proven by the bytes; the flavour within a
  family is fixed by package content (C27's `mimetype`, C28's main part, C29's stream name); **only the text formats
  use the declared type or the extension.** The text flavour: a declared `text/markdown`, `text/csv` or
  `text/tab-separated-values` wins; else the extension's (`md`, `csv`, `tsv`, `txt`); else `text/plain` (declared
  `text/plain` with no text extension). `FetchDocument` after step 7, in order: `DocumentSniff.classify(size, head,
  tail)`; else, for a ZIP or OLE2 head, `ContainerInspect.classify(size, head, tail, BoundedInspection(staged.reader()))`
  (B2d wires the ZIP arm, B2e the OLE2 arm); else `TextSniff.classify(size, head, tail, declared, extension)` (B2f);
  else `NotADocument`. An `IOException` from the inspection → `Interrupted` (P85-18), staging discarded;
  `InspectionOverBudget` → `NotADocument`. **C10 step 5 changes (B2f owns the `FetchDocument.kt` edit):** only a
  declared `text/html` or `application/xhtml+xml` fails fast; `text/plain` and the other text types proceed to the body
  and `TextSniff`. **The stored MIME and extension follow the proven flavour:** `Fetched.mimeType` is the flavour's
  MIME (never the declared one), and `MimeTypes.EXTENSIONS` (`C/model/Attachment.kt:47-55`, the one home) gains the
  missing pairs so C12's `AttachmentLocator.forOwner(owner, id, "", mime)` names the file by its type.

| format | proven by | stored MIME | ext. | `{TYPE}` (P85-5) | brief |
|---|---|---|---|---|---|
| PDF, PNG, JPEG | C11 (shipped) | as shipped | `pdf`, `png`, `jpg` | PDF, PNG, JPEG (ratified) | B2b |
| GIF | C30 head + trailer | `image/gif` | `gif` | **GIF** | B2f |
| WebP | C30 RIFF size | `image/webp` | `webp` | **WebP** | B2f |
| RTF | C30 head + closing `}` | `application/rtf` | `rtf` | **RTF** | B2f |
| DOC, XLS, PPT | C29 stream name | `application/msword`, `application/vnd.ms-excel`, `application/vnd.ms-powerpoint` | `doc`, `xls`, `ppt` | **DOC**, **XLS**, **PPT** | B2e |
| DOCX, XLSX, PPTX | C28 main part | the three `…openxmlformats-officedocument…` types (DOCX and XLSX already in `MimeTypes`) | `docx`, `xlsx`, `pptx` | **DOCX**, **XLSX**, **PPTX** | B2d |
| ODT, ODS, ODP | C27 `mimetype` | the three `…oasis.opendocument…` types | `odt`, `ods`, `odp` | **ODT**, **ODS**, **ODP** | B2d |
| TXT, Markdown, CSV, TSV | C30 + C31 | `text/plain`, `text/markdown`, `text/csv`, `text/tab-separated-values` | `txt`, `md`, `csv`, `tsv` | **TXT**, **Markdown**, **CSV**, **TSV** | B2f |

**P85-5 `{TYPE}` labels RATIFIED by the owner (2026-09-29), exactly as above:** GIF · WebP · RTF · DOC · XLS · PPT ·
DOCX · XLSX · PPTX · ODT · ODS · ODP · TXT · Markdown · CSV · TSV (the detected file format's familiar name, not a
content category). B5a's row 32 pins them.

**Kind at review** stays the shipped `AttachmentKinds.inferFrom` (`Attachment.kt:155-161`): GIF and WebP default to
Photo, PDF to Document, every other new type to Other; the owner changes it on the review. No kind rule changes.

**Recorded limits (the widened slice).** A ZIP comment longer than about 1,000 bytes pushes an ODF's end record out of
the tail window (refused); ZIP64 packages are refused; an OOXML package whose main part is not at its conventional name
is refused; a legacy file needing more than 8 DIFAT sectors or 64 directory sectors is refused; a macro-enabled OOXML
package is refused, but a legacy DOC/XLS/PPT with macros is stored as inert data like any other; UTF-16 text is
refused (it contains NULs); a text file that mentions `<script` or `<html` in its first KiB (a Markdown code sample)
is refused; a GIF or PNG with bytes after its trailer is refused; GIF and WebP reach the shipped bounds-first
thumbnail path like a shared image.

**Locked rule (owner, 2026-09-29):** macro-enabled OOXML formats (DOCM, XLSM, PPTM) are outside the 1.5 materialization
allowlist; legacy Office files are validated as genuine Office documents but are not inspected for embedded macros.
ServiceTag never executes downloaded attachment content. Ordinary attachment intake still accepts all of them.

### Test rows 34–43 (appended to §3)

| row | hazard | test (class · case) | RED mutation |
|---|---|---|---|
| **B2d** 34 | C26: the port | core `CT/fetch/BoundedInspectionTest` · `readsAreExactAndShortAtTheEnd`; `theReadCapRefuses` (the 161st read); `theByteCapRefuses`; `aNegativePositionIsRefused`; `FileStagedReaderTest.readsATempFileReadOnly` (the file's bytes and mtime unchanged) | count reads but not bytes (a 600 KiB central directory in two reads passes) |
| 35 | C27: ODF | core `DocumentSniffTest` (+) · **pass:** a minimal ODT, ODS and ODP (built in code with `java.util.zip`, `mimetype` stored first); **fail:** `mimetype` missing, wrong (`application/zip`), compressed, not first, a template value; no end record in the tail | accept any `mimetype` value that starts `application/vnd.oasis.opendocument` |
| 36 | C28: OOXML | core `CT/fetch/ContainerInspectZipTest` · **pass:** a minimal DOCX, XLSX and PPTX (`[Content_Types].xml`, `_rels/.rels`, the main part); **fail:** **an arbitrary ZIP** (a `readme.txt` inside); `[Content_Types].xml` missing; `_rels/.rels` missing; two main parts; a `word/vbaProject.bin`; ZIP64 markers; a central directory outside the file; one over 256 KiB; a comment length that does not end the file | (1) accept a `word/` entry without `[Content_Types].xml`; (2) take the flavour from the declared type; (3) drop the macro refusal |
| 37 | C31: ZIP wiring | core `FetchDocumentTest` (+) · **an arbitrary ZIP served as DOCX** (`application/vnd…wordprocessingml.document`, a `.docx` URL) → `NotADocument`; a DOCX served as `application/octet-stream` → `Fetched` as DOCX; a PDF never opens the reader (reads == 0); an inspection `IOException` → `Interrupted`, staging discarded; `MimeTypes.extensionFor` of every new container type | accept a ZIP head as the declared OOXML type without inspecting |
| **B2e** 38 | C29: OLE2 | core `CT/fetch/ContainerInspectOleTest` over a minimal CFB builder in `CT/testing/` (512-byte sectors; header, one FAT sector, one directory sector) · **pass:** a minimal DOC, XLS (`Workbook`, and `Book`) and PPT; a 4,096-byte-sector file; a directory chain across two sectors; **fail:** **no Word, Excel or PowerPoint stream** (only `\u0005SummaryInformation`); two content streams; `WordDocumentX` (a prefix); the signature and junk; a bad sector shift; a directory sector past the end; a FAT cycle | (1) accept the signature with a valid header alone; (2) match a stream name by prefix |
| 39 | C31: OLE2 wiring | core `FetchDocumentTest` (+) · a DOC served as `application/octet-stream` → `Fetched` as `application/msword`; the no-content-stream file served as `application/msword` → `NotADocument` | none: rows 38 and 37 carry the REDs |
| **B2f** 40 | C30: simple binaries | core `DocumentSniffTest` (+) · **pass:** minimal GIF87a, GIF89a, WebP (`VP8 `, `VP8L`, `VP8X`), RTF (with a trailing CRLF); **fail:** **a GIF cut before its trailer**; **a WebP whose RIFF size is not the file size**; **an RTF without its closing `}`**; `{\rtf` without `1` | (1) skip the GIF trailer; (2) skip the RIFF size check; (3) skip the RTF closing brace |
| 41 | C30: text | core `CT/fetch/TextSniffTest` · **pass:** TXT, MD, CSV, TSV by declared type, and each by extension with `application/octet-stream`; a BOM; a multi-byte character cut at a window edge; **fail:** **HTML served as `text/plain`**; **HTML at a `.txt` URL**; **HTML served as `text/csv`**; each rejection pattern (case-varied) and the XHTML `<?xml`; **a text file with a NUL**; invalid UTF-8; plain text declared `application/octet-stream` at a URL with no extension (never auto-classified) | (1) skip the web-page patterns; (2) skip the NUL check; (3) classify text with no compatible declaration or extension |
| 42 | C31: text wiring, fail-fast | core `FetchDocumentTest` (+) · a declared `text/plain` body now reaches `TextSniff` and is `Fetched` as `text/plain`; `text/html` and `application/xhtml+xml` still fail before the body (the fake body throws if read); a `.md` URL with `text/plain` is `text/markdown`; `text/csv` at a `.txt` URL is `text/csv` | keep `text/plain` in the fail-fast set (the TXT case turns `NotADocument`) |
| 43 | C31: extensions | core `AttachmentRulesTest` (+) · `extensionFor` for GIF, WebP, RTF, DOC, XLS, PPT, PPTX, ODT, ODS, ODP, Markdown, CSV, TSV; the shipped pairs unchanged | none: a table pin |

**Counted REDs added: 15** — B2d 6 (rows 34, 35, 37 one each; row 36 three), B2e 2 (row 38), B2f 7 (rows 40 and 41
three each; row 42 one). Every accepted format has a positive fixture; every family has its adversarial cases; the owner's nine
must-fail fixtures are bold above.

### Fixtures (all built in code, minimal, fictional)

OOXML and ODF packages are written in the test with `java.util.zip.ZipOutputStream` (ODF's `mimetype` entry `STORED`
with its CRC, first); OLE2 files by a small CFB builder in `CT/testing/CfbFixtures.kt` (B2e); GIF, WebP and RTF as byte
literals; text as strings. Part contents are one-line fictional XML or text (`Example Pool Pump manual`); no real
document, name or host.

### §19 errata after B2d's review (controller + owner, 2026-09-29)

The B2d review demonstrated two parser differentials that let an arbitrary ZIP pass as OOXML (a patched entry count hiding
records; a fake directory in front of an archive's real one) and one smaller one (a second end record). All three are
closed at `efe905c9` and pinned by fixtures; the text below supersedes the corresponding C27/C28 wording.

- **C28 (1) and C27's tail, tightened:** the chosen end record must be the **last** `50 4B 05 06` signature in the window at
  all; any later signature — in a comment, or a directory offset that happens to spell one inside the record's own 22 bytes —
  refuses the file. A genuine archive with an end signature inside its ZIP comment is refused (safe-side limit).
- **C28 (3), corrected:** the central directory's `offset + size` must end **exactly at** the end record (not "at or before").
- **C28 (4), tightened:** the records must fill the declared directory size exactly, and the end record's two entry-count
  fields must be equal.
- **C28 (7), new (owner):** the validator also refuses what it can positively identify as an OOXML type outside the list:
  any central-directory name ending `vbaProject.bin` or `vbaData.xml` (any case), and a ZIP-headed file whose declared
  type (normalised) or final-URL extension names a macro-enabled, template, slideshow, add-in or slide OOXML type
  (extensions `docm dotx dotm xlsm xltx xltm xlam pptm potx potm ppsx ppsm ppam sldx sldm`; the matching
  `application/vnd.ms-word.*`, `application/vnd.ms-excel.*`, `application/vnd.ms-powerpoint.*` (with the dot, so the exact
  legacy types stay XLS/PPT) and the `…openxmlformats-officedocument…` template/slideshow/slide types). The declared type
  and the extension are used here **only to refuse, never to accept**: a template or macro package the server labels as
  plain DOCX is stored as DOCX, because its distinguishing content type lives inside a deflated part.
- **The owner's framing (locked):** *#85 validates supported document/container families; it is not an active-content
  sanitizer or malware scanner. OOXML materialization does not guarantee that an accepted package is macro-free. ServiceTag
  stores downloaded content inertly and never executes it.* The validator rejects the canonical macro-enabled and
  template/slideshow forms it can identify (C28 (6)–(7)); it makes no "macro-free DOCX/XLSX/PPTX" claim, and it never
  decompresses a part to prove one.
- **Recorded limits, restated:** C27 and C11 are structural sniffing, not parsing (a body framed by a stored `mimetype`
  header and an end record is stored as ODT, as a `%PDF-`/`%%EOF`-framed body is stored as PDF); a VBA project under a
  non-canonical part name, or an unlabeled template/slideshow, is stored as the family's document; ODF with Basic macros is
  accepted; an ODF `mimetype` entry behind a data descriptor is refused; end-record disk-number fields are unchecked (a
  spanned archive fails the signature rule instead); an OOXML positive proof is case-sensitive while the macro refusal is
  not.

### §19 errata after B2e's review (controller + owner, 2026-09-29)

- **C29 (2)–(4), corrected (owner-pinned):** *Legacy Office family detection examines only streams directly owned by the
  compound file's root storage. Nested storages/embedded objects do not participate in outer-file classification.* Entry 0
  must be the root entry (type 5); the walk follows the root's child id and then left/right siblings only, never a
  storage's own child pointer; a seen-set bounds it and a cycle refuses. A DOC with an embedded sheet
  (`ObjectPool/…/Workbook`) is DOC; a message with an embedded Word object is refused.
- **C29 (4), "exactly one of" means exactly one FAMILY:** `Workbook` and `Book` together (the Excel 97/95 dual workbook) is
  XLS. Families are counted case-insensitively up to the first NUL, as MS-CFB compares names, so `WordDocument` beside
  `WORKBOOK` is two families (refused); the proof itself stays exact-case and NUL-terminated, so `WORKBOOK` alone is no
  proof (refused; safe-side limit).
- **Recorded limits (B2e):** the proof is structural — a truncated or internally inconsistent compound file whose root
  names the stream is still stored as its family; an odd name length, a missing NUL terminator, a sector read twice (a
  FAT sector that is also a directory sector), a non-storage/stream tree node, a second root, or a root with no children
  refuses; legacy templates, slideshows and add-ins (DOT, XLT, POT, PPS, XLA) are stored as DOC, XLS or PPT; the
  4,096-byte-sector worst case (64 directory + 8 DIFAT sectors) uses exactly the 524,288-byte cap, with no headroom.

### Owner ruling — the end of file-type hardening (2026-09-29)

B2f finishes under its existing cap; then B4 → B5a → B5b. **No more fuzzing campaigns; no mutation campaigns for file
parsers beyond a brief's own counted REDs; no full ZIP/CFB/PDF/ODF parsing; no decompression, macro scanning, malware
detection or recursive embedded-object inspection.** Residual edge cases are recorded as known limitations, not
eliminated. The acceptance bar: recognise the claimed family with a cheap, bounded structural sanity check; reject obvious
wrong-family or truncated content where that falls naturally out of the check; make sure an obvious HTML, login or error
page cannot masquerade as a document. *Downloaded attachments are untrusted inert bytes. ServiceTag is trying to avoid
accidentally saving an obvious login/error/web page as a manual — it is not certifying that the file is standards-perfect
or malware-free.* What is built stays; B2e is not tightened further.

### §19 errata after B2f's review (controller, 2026-09-29)

- **C30, the doctype pattern:** `<!doctype`, then one or more of space/tab/CR/LF/FF, then `html` (case-insensitive); the §22
  grep for it is `'<!doctype'` → 1.
- **Recorded limits (B2f):** web-page patterns are searched only in the head window (a page with more than about 1 KiB of
  leading whitespace or comments, or a bare fragment such as a lone `<title>` with none of the eight patterns, is stored as
  text under a text label); `<!DOCTYPEhtml>` with no whitespace passes the doctype pattern; the patterns match word
  prefixes, so text containing `<header>` or `<metadata>` is refused; an XML or JSON error body served 200 under a text
  label is stored as text; text is judged by the two windows only (a lead byte cut at the head's inner edge is tolerated as
  ruled); legacy aliases such as `text/x-markdown` need a text extension; RTF's closing `}` must lie in the tail window;
  GIF, RTF and WebP are checked structurally only (the WebP chunk's own size is never read); the six new `EXTENSIONS`
  pairs also give ordinary `.gif/.webp/.rtf/.md/.csv/.tsv` attachments their true type app-wide (intended).

## 20. B2d — container: the inspection port, ODF, OOXML (C26–C28, C31's ZIP arm; core, JVM only)

**Read:** §19; C10, C11; `C/fetch/{FetchPorts,DocumentSniff,FetchDocument}.kt` and `CT/testing/FakeStaging.kt` on the
branch; the ZIP format's end record and central directory (APPNOTE 4.3.12, 4.3.16); ECMA-376 Part 2 (OPC) §9 names;
ODF 1.2 Part 3 §3.3 (`mimetype`). **`<base>`** = B3's tip. **Rows:** 34–37. **Rulings:** R85-5 (widened).

**Connected:** none. **Greps:** `'fun interface StagedReader'` → 1; `'fun reader\(\): StagedReader'` in `FetchPorts.kt`
→ 1; `'class BoundedInspection'` → 1; `'RandomAccessFile\(.*"r"\)'` in `FileStagedReader.kt` → 1 and
`'"rw"|write\('` there → 0; `'Inflater|ZipFile|ZipInputStream|ZipEntry'` over `core/src/main` → 0 (names only, nothing
decompressed; `java.util.zip` stays in tests); `'java\.net\.'` over `core/src/main` → 1 (unchanged); `'BoundedInspection\('`
in `FetchDocument.kt` → 1; `git diff <base> -- C/model/Attachment.kt` touches only `EXTENSIONS`; `git diff <base> --
app` → empty.

**Untouched:** `app/**`; every `C/` file but `C/fetch/{FetchPorts,DocumentSniff,FetchDocument}.kt`, the new
`C/fetch/{ContainerInspect,FileStagedReader}.kt` and `C/model/Attachment.kt` (`EXTENSIONS` only); the shipped
PDF/PNG/JPEG rules; `docs`; `tools`. **Must NOT:** decompress anything; read the staged file outside one
`BoundedInspection`; inspect a head that is not a ZIP; accept a ZIP on a declared type or URL extension; take a flavour
from anything but part names; write to the staged file; wait on or touch any app code. **Counted RED (6):** rows 34
(1), 35 (1), 36 (3), 37 (1). **Caps:** 7 JVM mutation runs, 0 device; **1 h target, 2 h hard stop**; fix round 3 runs,
45 min. **Stop also** if a shipped `FetchDocumentTest` or `DocumentSniffTest` assertion must move. **Size:** about 150
production, 260 test lines.

## 21. B2e — legacy Office, OLE2 / CFB (C29, C31's OLE2 arm; core, JVM only)

**Read:** §19; B2d's report; `C/fetch/ContainerInspect.kt`; MS-CFB §2.2 (header), §2.3 (FAT), §2.5 (DIFAT), §2.6
(directory entries). **`<base>`** = B2d's tip. **Rows:** 38–39. **Rulings:** R85-5 (widened).

**Connected:** none. **Greps:** `'"WordDocument"'`, `'"Workbook"'`, `'"Book"'`, `'"PowerPoint Document"'` in
`ContainerInspect.kt` → 1 each; `'startsWith\('` applied to a stream name there → 0 (exact names); `'BoundedInspection'`
is the only reader type in the OLE2 arm (by inspection); `git diff <base> -- app` → empty.

**Untouched:** everything but `C/fetch/{ContainerInspect,FetchDocument}.kt` and `C/model/Attachment.kt`
(`EXTENSIONS` only, the three legacy pairs). **Must NOT:** accept the compound-file signature on its own; read a
stream's contents; follow more than 8 DIFAT or 64 directory sectors; loop on a cycle; read outside the budget.
**Counted RED (2):** row 38 (2). **Caps:** 3 JVM mutation runs, 0 device; **1 h target, 2 h hard stop**; fix round 2
runs, 45 min. **Stop also** if the minimal CFB builder passes about 80 lines (report; the controller decides).
**Size:** about 110 production, 200 test lines.

## 22. B2f — text and the simple binaries (C30, C31's text arm and the fail-fast change; core, JVM only)

**Read:** §19; B2d's and B2e's reports; `C/fetch/{DocumentSniff,FetchDocument}.kt`; `C/references/ReferenceUris.kt`
(`destinationOf`, for the extension). **`<base>`** = B2e's tip. **Rows:** 40–43. **Rulings:** R85-5 (widened).

**Connected:** none. **Greps:** in `FetchDocument.kt` the fail-fast set is exactly `text/html` and
`application/xhtml+xml` (`'"text/plain"'` → 0 there); `'object TextSniff'` → 1; each of the nine rejection patterns →
1 in `TextSniff.kt`; `'TextSniff\.classify\('` in `FetchDocument.kt` → 1, after the container arm; `git diff <base> --
app` → empty.

**Untouched:** everything but `C/fetch/{DocumentSniff,FetchDocument}.kt`, the new `C/fetch/TextSniff.kt` and
`C/model/Attachment.kt` (`EXTENSIONS` only). **Must NOT:** classify text without a compatible declared type or
extension; accept a NUL; let `text/html` or `application/xhtml+xml` reach the body; take a text flavour from anything
but C31's rule; change a binary rule. **Counted RED (7):** rows 40 (3), 41 (3), 42 (1). **Caps:** 8 JVM mutation runs, 0 device; **1 h target, 2 h hard stop**;
fix round 3 runs, 45 min. **Stop also** if a shipped fetch test that pinned `text/plain` as fail-fast must move beyond
that one expectation (report it as the pin). **Size:** about 120 production, 250 test lines.

### The rest of the plan, as amended

- **B2c** is not reopened. Its must-not "read the staged file a second time" now reads, for the code after B2d: "at most
  one bounded, read-only container inspection through `BoundedInspection`, only after the stream completes and only
  for a ZIP or OLE2 head". Its fail-fast set changes in B2f.
- **B4** (§15) gains one obligation: the `CacheStagingArea` staging file implements `reader()` as
  `FileStagedReader(partFile)`; row 27 gains `readerReadsThePartFile`. No other B4 change; B4's `Accept` header
  already ends in `*/*;q=0.1`.
- **B5a** (§16): C21's `typeLine` maps each proven MIME to its `{TYPE}` label from the table above, one home in
  `MaterializeStrings.kt`; row 32 pins the labels once ratified.
- **The brief count is thirteen:** B1a, B1b, B1c, B2a, B2b, B2c, B3, **B2d, B2e, B2f**, B4, B5a, B5b.
