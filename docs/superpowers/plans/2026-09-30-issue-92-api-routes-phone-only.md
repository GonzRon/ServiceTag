# #92 — API and MCP routes for the phone-only workflows: plan and briefs (rev 1.3, 2026-09-30)

> **Rev 1.3** applies the scoped re-review's C-15 (the MCP resolves and always sends `kind`) and C-16 (the
> installation id's first read), and its notes on rows 43–45 (`plan-review.md`, "Re-review: rev 1.2").

> **Rev 1.2** applies the independent plan review's C-1…C-14 and its mechanical notes
> (`.superpowers/sdd/2026-09-30-issue-92/plan-review.md`) and the owner's rulings **R92-7 (strict replay) and R92-8
> (a server-side installation id in the upload id namespace)**, both DECIDED 2026-09-30.

> **Rev 1.1** applied the owner's rulings R92-1…6 (recorded on the issue; §6, DECIDED — R92-3 conditional, R92-6
> revised to a request-level operation key) and two mechanical corrections (authentication before any upload byte;
> the eight operations reconciled with the seven tools), and adds the read-only threat-review brief **B2-pre**.

> **Status: PLANNING ONLY.** Not on the release train and not authorized for execution. **The sequence of record
> (owner, 2026-09-30): 1.5.0 SHIPPED → #92 → #90 → Phase 2 (#15 → #47 → #69).** Every brief stands on **today's**
> harness and gate; none assumes a #90 change. R92-1 supersedes R86-18, R92-3 supersedes R85-10 for an existing reference by id only, and R92-4 supersedes
> `docs/api/v1.md`'s "no provenance read". **B2 is not dispatched until B2-pre's threat review is signed off.**

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-30-issue-92/progress.md`. **Audit (inventory of record):**
> `.superpowers/sdd/2026-09-30-issue-92/audit.md`. Nine briefs on one branch **`issue-92`**, strictly sequential —
> B1a, B1b, B2-pre (read-only, no commit), B2, B3, B4, B5a, B5b, B6 — each `<base>` the previous accepted tip;
> **B1a's `<base>` is master at dispatch** — today `5b60788a` (1.5.0 / code 18, schema 16 / format 16, MCP 69
> tools, gitlink `7e0377a`) plus this plan's rev 1.2 commit; the audit's citations were read at `11080080`, and nothing
> between that and `5b60788a` touches code. One task review each, at most one
> bounded fix round each, one whole-branch review, the merge, one merged-tip gate.

**Goal:** the four owner workflows the phone alone can do today — **replace an asset**, **add a document with bytes
and give it a role**, **save a web reference as a document**, and **read an asset's documents** — become `/v1`
routes and MCP tools that call the **same use cases** the phone calls (`ReplaceAsset`, `AddAttachment`,
`UpdateAttachment`, `MaterializeReference`), with the same refusals as stable codes, and the schedules loader learns
to plan and apply a replacement idempotently (re-plan `IDENTICAL`). NFC writing and binding stay the phone's alone
(D3); `/v1/tags` stays read-only.

**Inputs:** issue #92 (`.superpowers/sdd/2026-09-30-issue-92/issue-92.md`); the audit; the #86 plan (§2 B2 C8–C15,
R86-18) and the #85 plan (§2 B3 C12–C15, §7 R85-6/8/9/10, §19 errata and recorded limits); `docs/api/v1.md`;
`docs/release-proofs.md`; `docs/superpowers/planning-policy.md`. Paths: `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, `CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`,
`A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`,
`M/` = `tools/servicetag-mcp/`, `S/` = `tools/servicetag-schedules/`.

## Global constraints

- **Reuse, never re-implement.** Every write is one call of the phone's use case: `AddAttachment.run`,
  `UpdateAttachment.run`, `MaterializeReference.prepare` → `commit`/`discard`, `ReplaceAsset.offer`/`plan`/`run`. No
  handler writes a repository. Three tiny core changes only, each behaviour-preserving for every shipped caller:
  `AddAttachment.run` takes an optional preset id checked before any byte moves (C13, the operation key),
  `MaterializeRefusal.AlreadyHave` names the row's id (C17), and `isIsoDate` is widened from `internal` to public (C7).
  Replace is #86's canonical atomic use case with its reviewed selections and the source-digest guard (R92-1). The
  review designation stays `reviewPrefill`'s (`A/ui/references/MaterializeViewModel.kt:203-215`; importing it from
  `A/ui/references` is legal within the fence, and moving it to a non-UI home is a follow-up).
- **One process-wide lock for the long writes (C33).** The listener is **not** one worker across a pause and resume:
  `stop()` never joins, and `start()` runs a new generation beside the old (`LoopbackApiServer.kt:199-202`,
  `:223-228`, `:261-268`). Every claim this plan once rested on "one worker" rests on C33 instead.
- **No schema or format change** — the operation key included: it lives in the attachment row's own id (C13), not in
  a table. Room 16 / backup 16 already carry the role (#67), the provenance (#85) and the succession (#86). No table,
  count key, `MergeTable`, `MergeReason` or tally moves. `/v1/status` gains one additive top-level key,
  `installationId` (C5a, R92-8), kept in a device-local file and never in any archive; `counts` is unchanged
  (`counts.attachments` exists).
- **API version stays 1, additive.** Every refusal is a stable `code`, plus `field` where one body (or header-JSON)
  key is at fault, in the shipped envelope (`A/api/ApiJson.kt:110-116`); 422 = change this body, 409 = change
  another row first (RN-8, `ApiJson.kt:445-449`), existence → state → problems (`:536-537`). Every new mapping is an
  exhaustive `when` over its sealed type. Unknown keys stay 400.
- **Manifest:** no new permission, component, intent filter or cleartext setting; `ManifestContractTest` unchanged.
  The INTERNET comment is reworded only (R85-11's shape). **No new dependency** anywhere (`java.util.Base64`,
  `MessageDigest`, `httpx` already present).
- **Security and privacy.** No log line carries a URI, header value, filename or byte. The phone never renders a full
  URI (R85-9 unchanged); the API's provenance answers are documented as **sensitive** (R92-4). The token is checked on
  the headers before any upload byte is read, and an unauthenticated body is never read past the drain bound (C10).
  The fetch keeps every #85 rule (https per hop, the ASCII allowlist, the local-address refusal, the size and time
  limits, the nineteen types, `User-Agent: ServiceTag`, no auth state), and it starts only from an existing
  reference by id — never a supplied URL (R92-3). NFC hardware is never written: a tag move is a binding re-target
  (R92-2).
- **Tests.** JVM first over the production router and the Room-backed `FakeGraph` (`T/api/MaintenanceFixtures.kt:133-173`).
  **No new device class and no new device case** (§7 explains the one platform fact). No test touches the network:
  `FakeDocumentTransport` serves bytes; socket tests bind 127.0.0.1 on the JVM. Fixtures are fictional
  ("Example Water Heater", `https://manuals.example.invalid/…`). Every counted RED is a real mutation with
  `--no-build-cache --rerun-tasks`, its failing assertion quoted, reverted before the commit.
- **Standing rules.** 2.6 tombstones: `git diff <base> -- . ':!app/schemas' | grep -cE
  '^[-+].*(external_link|externalLinks)'` → 0. Gitlink `libs/nfc-tag-core` stays `7e0377a`. **No version bump**
  (`versionName = "1.5.0"`, `versionCode = 18`; the release vehicle is the owner's). Commits: one casual lowercase
  subject line; no body, no trailers, no AI attribution. `tools/` never names a screen-driving tool (the
  `ReleaseProofPolicyTest` tripwire, `T/policy/ReleaseProofPolicyTest.kt:12-14`, `:85-88`) — docstrings included.
- **Time boxes:** every brief 1 h target, 2 h hard stop; fix rounds 45 min, findings' files only, stop on the first
  finding they cannot close. **Review budget:** one task review per brief, at most one bounded fix round, one scoped
  re-review only for a substantive correctness finding, mechanical fixes (< ~50 lines) by controller inspection, one
  whole-branch review, merge, one gate.

## 1. Scope

**In scope:** C1–C33 (C5a included) and B2-pre's threat review (TR1–TR13). Eight operations over seven path
shapes; seven MCP tools (69 → 76, C27's mapping); the loader's `replacements`.

**Out of scope** (follow-up candidates): deleting an attachment (the API adds and amends, the phone removes,
`v1.md:1175-1176`); reading or writing an attachment's bytes back out; an event-owned upload; attachments on supplies
or components' own owners (#69); recording provenance except through the materialize route; async/resumable
downloads; any tag write, bind or tag lifecycle action; a `/v1/status` capability list; a version bump; the phone UI.

**Recorded limits (stated, not fixed):**
1. The listener answers one request at a time per generation, and C33 serialises the long writes across generations:
   an upload of up to 256 MiB, or a download of up to ten minutes, holds every other long write, and every other call
   of that generation waits in the backlog (`LoopbackApiServer.kt:40`, `:122-130`).
2. A declared upload over 256 MiB is a pre-auth 413 whose unread body may reset the connection before the 413 arrives,
   as an over-cap import already may (`LoopbackApiServer.kt:115-120`); a `Content-Length` above `Int.MAX_VALUE` is the
   shipped pre-auth 400 "not a number" (`HttpWire.kt:102-103`). The MCP refuses such a file before sending.
3. A 401 on the upload path leaves its body unread (drained at most 4 MiB within 5 s, then closed, C10) and may reset
   likewise; the MCP always reads first, so a stale code is caught on a GET.
4. The upload stages the file in app cache and then copies it into the folder: free space for both is needed at once.
5. `sourceUri` is the reference's URI verbatim (#85 limit 1) and may carry a token from its own query; the list and
   row routes answer it as every export already carries it, documented as sensitive (R92-4).
6. A 404 `not_found` (route missing) is how the MCP recognises a pre-#92 app at schema 16; there is no capability list.
7. **A long job must finish inside the screen's timeout.** Screen-off pauses and stops the listener (`v1.md:25-28`):
   an upload in staging or a download is cut (nothing written); a commit or a `put` that began completes (C16, C33).
   Keeping the screen on during a request is a UI follow-up, fenced out; D1 runs with a long screen timeout.

## 2. Contracts

### Common (C1–C4)

- **C1, collaborators.** `AttachmentHandlers(graph)` (list, get, patch, upload, materialize) reached as
  `handlers.attachmentRoutes.*`, and `ReplaceHandlers(graph)` as `handlers.replace.*` — the `MaintenanceHandlers`
  pattern (`A/api/ApiHandlers.kt:131-173`), each with a `…HandlersFor(graph)` test fixture. **Not** named
  `attachments`: `ApiHandlers` already holds a private `attachments: AttachmentRepository` (`:98`). The constructor
  ripple is mechanical but wide: the graph constructor, plus **ten explicit primary-constructor calls in eight test
  files** — `T/api/ApiRouterTest.kt` ×2, `T/api/LoopbackApiServerTest.kt` ×2, `T/api/MaintenanceCommandShapeTest.kt`,
  `T/api/MaintenanceFixtures.kt` (`V1Client.router()`), `T/api/MaintenanceRoutesTest.kt`, `T/api/ReferenceRoutesTest.kt`,
  `T/VersionAgreementTest.kt` and `T/ui/api/DeveloperApiViewModelTest.kt` — each gaining `attachmentHandlersFor(graph)`
  in B1a and `replaceHandlersFor(graph)` in B3; both briefs list all eight as pins.
- **C2, the codes.** One table, every new code, its status, its `field`, and its wire `message` (developer-facing text,
  the precedent of every shipped `/v1` message; never shown on a phone screen):

| code | status | field | when | `message` |
|---|---|---|---|---|
| `NO_SUCH_ATTACHMENT` | 404 | — | no row with that id | `no such attachment` |
| `ATTACHMENT_NAME_REQUIRED` | 422 | `displayName` | blank after trim (`AttachmentProblem.BlankName`) | `an attachment needs a name` |
| `ATTACHMENT_BAD_DATE` | 422 | `capturedOn` | not an ISO day | `capturedOn is not a YYYY-MM-DD day` |
| `ATTACHMENT_ROLE_NOT_ALLOWED` | 422 | `role` | a role on an event's attachment (R67-11) | `a document role belongs on an asset's attachment` |
| `ATTACHMENT_EMPTY` | 422 | — | an upload of 0 bytes (share intake's rule) | `the file is empty` |
| `ATTACHMENT_SHA256_INVALID` | 422 | `sha256` | the declared digest is not 64 lowercase hex | `sha256 is not 64 lowercase hex characters` |
| `ATTACHMENT_SHA256_MISMATCH` | 422 | `sha256` | the declared digest is not what arrived | `the bytes that arrived do not match sha256` |
| `ATTACHMENT_TOO_LARGE` | 422 | — | `AttachmentProblem.TooLarge` (defence: framing's 413 comes first) | `the file is over 256 MiB` |
| `ATTACHMENT_STORE_NOT_CONFIGURED` | 409 | — | `NoStore` (the merge report's reason, same spelling) | `no attachment folder is picked on this phone` |
| `store_unavailable` | 409 | — | `StoreUnavailable`, or a `StoreIoException` (**shipped**, `ApiJson.kt:387-389`) | shipped |
| `ATTACHMENT_ALREADY_HELD` | 409 | — | **materialize only**: R85-6's same sha256 and size on the same asset; `problems`: `AlreadyHave(attachmentId=…)`. The upload never refuses identical bytes (R92-6) | `this asset already holds these bytes` |
| `OPERATION_KEY_INVALID` | 422 | `operationKey` | not 1–128 of `[A-Za-z0-9._~:-]` | `operationKey is not a valid key` |
| `OPERATION_KEY_REUSED` | 409 | `operationKey` | the key already produced a row on this asset and this request's payload or metadata differ from the row as it stands (C13, R92-7 strict); `problems`: `OperationKeyReused(attachmentId=…)` | `this operation key was used for a different upload; read or update that attachment` |
| `UPLOAD_NOT_STAGED` | 409 | — | a **staging-side** write failed (disk full, cache unwritable) — never a request-stream failure (C12) | `the upload could not be staged on this phone` |
| `REFERENCE_NOT_MATERIALIZABLE` | 409 | — | `MaterializeRefusal.NotEligible` | `that reference is not an https document link` |
| `NETWORK_DENIED` | 409 | — | `NetworkDenied` (defence: the listener cannot bind without INTERNET) | `this app may not use the network` |
| `FETCH_<PROBLEM>` | **502** | — | one per `FetchProblem` but `NetworkDenied`: `FETCH_NOT_HTTPS`, `FETCH_HAS_CREDENTIALS`, `FETCH_LOCAL_ADDRESS`, `FETCH_UNREACHABLE`, `FETCH_INTERRUPTED`, `FETCH_TIMED_OUT`, `FETCH_TOO_LARGE`, `FETCH_EMPTY`, `FETCH_NOT_A_DOCUMENT`, `FETCH_NEEDS_SIGN_IN`, `FETCH_SERVER_ERROR` (`problems`: `ServerError(code=…)`), `FETCH_REDIRECT_REFUSED`. Every one is a redirect-hop or upstream fact: a static first-hop problem is already `NotEligible` (`MaterializeReference.kt:78-82`). The transient ones are `FETCH_UNREACHABLE`, `FETCH_INTERRUPTED`, `FETCH_TIMED_OUT` and a 5xx `FETCH_SERVER_ERROR`; the MCP never auto-retries a materialize | `the download was refused` |
| `ASSET_ALREADY_REPLACED` | 409 | — | the predecessor has a successor; `problems`: `ReplacedBy(successorAssetId=…)` | `this asset has already been replaced` |
| `REPLACE_STALE` | 409 | `sourcesDigest` | the digest differs, or `ReplaceStale` from the write | `the asset or a row reviewed with it changed; nothing was replaced` |
| `REPLACE_NAME_REQUIRED` | 422 | `successor.name` | `ReplaceProblem.NameRequired` | `the new asset needs a name` |
| `REPLACE_BAD_DATE` | 422 | the key | `BadDate(field)`: `retiredOn`, `scheduleStartOn`, `successor.purchaseOn`, `successor.inServiceOn`, `successor.warrantyExpiresOn` | `that date is missing or not a YYYY-MM-DD day` |
| `REPLACE_NOT_OFFERED` | 422 | — | `NotOffered(id)`; `problems` names the id | `that schedule, group, tag or parent is not offered` |
| `REPLACE_NEEDS_SETUP` | 422 | `carrySetup` | `NeedsSetup` | `a ticked schedule needs its readings and actions` |
| `REPLACE_NEEDS_SEASON` | 422 | `carrySeason` | `NeedsSeason` | `a ticked pre-service schedule needs the season` |
| `REPLACE_PHASE_REQUIRED` | 422 | `manualPhase` | `PhaseRequired` | `say whether the new asset is in season` |
| `REPLACE_DATE_AFTER_TODAY` | 422 | `retiredOn` | `ReplacedOnAfterToday` (R86-13a) | `the replacement date is later than today` |
| `asset_validation` | 422 | `successor.<key>` | `ReplaceProblem.Successor(problem)` — the **shipped** 1.1.0 asset family via `assetRefusal`, its field prefixed | shipped |

  `field` gains its first dotted form (`successor.name`), additive. Header problems on the upload (C11) are **400**
  `bad_request` carrying the decoder's message, as a malformed body is today. Shipped codes reused unchanged:
  `no_such_asset`, `NO_SUCH_REFERENCE`, `asset_transferred_out`, `store_unavailable`, `bad_request`,
  `unsupported_media_type`. `problems` always carries every domain problem by its own name, first first.
- **C3, the rows.** Additive, at version 1:

| method | path | body | success | new shape? |
|---|---|---|---|---|
| `GET` | `/v1/assets/{id}/attachments` | — | 200 `{attachments, folder}` | 22nd asset sub-resource |
| `POST` | `/v1/assets/{id}/attachments` | the file's bytes (C9–C13) | 201 `{attachment}`; **200** the same row on an exact replay (C13) | (same) |
| `GET` | `/v1/attachments/{id}` | — | 200 `{attachment}` | new top-level shape |
| `PATCH` | `/v1/attachments/{id}` | full attachment command | 200 `{attachment}` | (same) |
| `POST` | `/v1/references/{id}/materialize` | review fields, all optional; **no URL** | 201 `{attachment}` | new shape |
| `GET` | `/v1/assets/{id}/replace-offer` | — | 200 the offer | 23rd sub-resource |
| `POST` | `/v1/assets/{id}/replace-plan` | the draft | 200 the plan — **writes nothing** | 24th |
| `POST` | `/v1/assets/{id}/replace` | the draft + `sourcesDigest` | 201 `{successor, succession}` | 25th |

  Asset sub-resources answer **404** for an untaken verb (the shipped convention, `ApiRouter.kt:134-169`); the two
  top-level shapes answer **405** (`DELETE /v1/attachments/{id}` is a 405 — nothing deletes an attachment here). Stay
  unrouted (404): `GET /v1/attachments`, `/v1/attachments/{id}/bytes`, any bytes read, any provenance write.
- **C4, gating.** No schema bump, so the MCP gates the seven tools at **schema 16** (a per-tool minimum,
  `APP_SCHEMA_TOO_OLD`, nothing sent) **and** translates a `404 not_found` from these routes into `APP_ROUTE_MISSING`
  (C28). `apiVersion` stays 1.
- **C33, one process-wide lock for #92's long writes (review C-1; numbered last so C1–C32 keep their ids).** The
  premise "one worker" is false across listener generations: the router lives for the Developer API visit and
  survives a pause (`A/ui/api/DeveloperApiViewModel.kt:52-60`), a screen-off pauses and stops the listener
  (`v1.md:25-28`), `stop()` never joins the worker (`LoopbackApiServer.kt:223-228`) and `start()` runs a new
  generation beside the old one (`:199-202`, review S1 `:261-268`). Without a lock, a retried upload on the new
  generation finds no row yet, stages, and reaches `AddAttachment(presetId)` at the **same locator**: `put` deletes the
  stale document there — the first request's file (`A/attachments/SafTreeAttachmentStore.kt:44-47`) — and both
  `upsert` the same id (`AddAttachment.kt:100`). The same overlap defeats R85-6's "not a lock" duplicate check
  (`MaterializeReference.kt:46-47`, `:105-110`).
  - **The lock:** one `kotlinx.coroutines.sync.Mutex`, `apiLongWrites`, held by `AppGraph` (one per process; `FakeGraph`
    mirrors it), never by the per-visit router or server.
  - **It covers:** upload steps 2–5 (C12) — the derived id is **re-checked under the lock**, so a second writer of one
    key finds the first's row and takes the replay path; and materialize from before `prepare` through `commit` (the
    duplicate check is inside `prepare`, `MaterializeReference.kt:105-110`, and the use case is not split), with C-9's
    hand-off decided under it (C16).
  - **It never covers** a read, a body drained by a refusal decided before step 2, or any other route; `withLock` is
    released on cancellation.
  - **The download slot is compare-and-clear** (S1's rule): `register` sets the slot to this request's `Job` (a stale
    one there is already cancelled); `finally` clears it only if it still holds this `Job`. So a stale generation's
    `finally` can never unregister the new generation's download, and a later `stop()` still cancels it. A request
    registers before it waits for the lock, so a `stop()` while it waits cancels it and it writes nothing.
  - **Counted RED:** the lock removed → row 43 fails (the first file is destroyed and two writes land on one id); the
    slot cleared unconditionally → row 45 fails.

### B1a — attachment reads and edits (C5–C8)

- **C5, the list.** `GET /v1/assets/{id}/attachments` → `{"attachments": [AttachmentDto], "folder": "READY" |
  "NOT_CONFIGURED" | "ACCESS_LOST"}`. Rows: `AttachmentRepository.forAsset` — the asset's own, never its events'
  (`C/ports/Repositories.kt:305-316`) — by `createdAt`, then `id`. `folder` is `AttachmentStorage.state()` reduced to
  its member name: **never** the folder's display name or authority. 404 `no_such_asset`. Writes nothing. A
  transferred-out asset's rows are readable (history is read, never changed).
- **C5a, the ServiceTag installation id (R92-8, DECIDED; created by B1a, before the upload brief).** Derived upload ids
  must not collide across installations that share asset ids — the development phone was seeded from the production
  export — so the collision is prevented **in the server-side id namespace** (C13), not by the MCP's key. Today
  `/v1/status` exposes no identity — `appVersion`, `apiVersion`, `schemaVersion`, `backupFormatVersion`, `counts` only
  (`A/api/ApiDtos.kt:46-50`) — and the app holds none: `AppPrefs.lastRestoredBackupSetId` (`A/prefs/AppPrefs.kt:35-38`)
  names the last **archive** restored, which a phone seeded from another's export shares, so it cannot serve.
  - **What it is:** an opaque, random **`installationId`**: 128 bits from `java.security.SecureRandom` (16 bytes, all
    random — no version bits forced), written in the canonical 8-4-4-4-12 lowercase-hex UUID form. **Not** an adb
    serial, Android id, model, hostname or any hardware identifier; **not authentication material** (the pairing code
    stays the only credential); never logged.
  - **Where it lives (a small additive contract):** one file, `installation-id`, under
    `Context.noBackupFilesDir`, read by `A/prefs/InstallationIdentity(dir: File)` beside `AppPrefs` — the home of the
    per-install, device-local facts (`AppPrefs.kt:21-22`, "Backed up? No"). **Generated on first read**, then stable
    across app restarts and upgrades; a data clear or reinstall mints a new one. **The first read (review C-16):** the
    id is resolved **once per process under a lock** and cached; a missing file is created **atomically** — the new id
    is written to a temporary file in the same directory and renamed onto `installation-id` — and **whichever file won
    is re-read**, so concurrent first reads yield one id; a file that does not hold one well-formed id is **replaced
    once**, as if the data had been cleared, and the replacement is then read like any other. `noBackupFilesDir` is excluded from
    Auto Backup and device-to-device transfer, which a `SharedPreferences` value would not be (the manifest sets no
    `allowBackup`, so its default `true` applies). **No Room schema and no backup-format bump.** `AppGraph` passes
    `context.noBackupFilesDir`; `FakeGraph` a temporary directory, so the JVM suite proves it.
  - **Never in** a backup, an export, a merge or a Transfer Pack: neither the backup codec (`C/backup/*`) nor the pack
    writer and codec (`A/transfer/TransferPackWriter.kt`, `C/transfer/*`) names it (B1a's grep, row 46).
  - **Exposed** additively as the top-level `installationId` of the authenticated `GET /v1/status`; used by C13's
    derivation on the phone and by the MCP's local derivation (C27), nothing else.
- **C6, one row.** `GET /v1/attachments/{id}` → `{"attachment": AttachmentDto}` for any owner (asset or event);
  404 `NO_SUCH_ATTACHMENT`.
- **C7, the edit.** `PATCH /v1/attachments/{id}`, a **full command** mirroring `UpdateAttachmentCommand`
  (`C/usecase/AttachmentCommands.kt:48-54`):

| key | type | absent | note |
|---|---|---|---|
| `displayName` | string | 400 | trimmed by the use case |
| `kind` | `AttachmentKind` name | 400 | |
| `capturedOn` | `YYYY-MM-DD` or null | null | checked with core's `isIsoDate` (widened to public, `C/usecase/AssetCommands.kt:123`) |
| `notes` | string | `""` | |
| `role` | `DocumentRole` name or null | **400** | no default, as #67 C2 rules: null clears |

  Order: decode (415/400) → the row exists (404 `NO_SUCH_ATTACHMENT`) → `capturedOn` (422) → **a non-null role on an
  event's row → 422 `ATTACHMENT_ROLE_NOT_ALLOWED`**, decided by `owner.accepts(role)` (`C/model/Attachment.kt:32-33`)
  before `UpdateAttachment`'s `require` could throw (`C/usecase/UpdateAttachment.kt:30`) → `UpdateAttachment.run`:
  `Ok` → 200 the row; `Unchanged` → **200 the stored row, nothing written** (the reference precedent,
  `A/api/ReferenceHandlers.kt:109-114`); `BlankName` → 422; `OwnerMissing` → 404; `AssetTransferredOut` (the guard) →
  409. The locator and bytes never move.
- **C8, the row on the wire is the archive's.** `AttachmentDto` via `Attachment.toDto()`
  (`C/backup/BackupFormat.kt:379-400`, `:987`), every field, **`sourceUri`, `sourceResolvedUri`, `sourceRetrievedAt`
  and `sourceName` included (R92-4, DECIDED)**. The API is the owner's own loopback and every one of these fields
  already leaves the phone in each export. **They are documented as sensitive** (`v1.md`, the MCP docstrings of
  `list_attachments` and `get_attachment`): `sourceUri` is verbatim and may carry a token from the reference's own
  query (#85 limit 1), so an answer is handled like an export — never logged by the phone, never pasted into an
  issue. The phone's screen rule "the full URI is never rendered" (R85-9, #85 C25) is separate and unchanged.

### B1b — the upload (C9–C13)

- **C9, framing.** A third cap tier chosen by **(method, path)** before any body byte, as the import tier is by path
  (`A/api/ApiRouter.kt:36-41`, `A/api/HttpWire.kt:114`; the parser already holds the method, `:84`): **`POST`** on the
  canonical path `/v1/assets/<one segment>/attachments` gets `MAX_UPLOAD_BYTES = MAX_ATTACHMENT_BYTES` (268,435,456,
  fits the parser's `Int`) and is left on the socket; **every other method on that shape keeps 64 KiB and is read as
  today** (a `PATCH` there is framed at 64 KiB and answers the sub-resource 404 with its body consumed), and every other
  path keeps its cap. `bodyCapFor(path)` becomes `bodyCapFor(method, path)`; the shipped pins
  `T/api/ApiRouterTest.kt:760-765` and `HttpWireTest`'s `caps` lambda (`:27`) gain the method argument (mechanical).
  On the POST shape only, `parseRequest` **does not read the body**: `ApiRequest` gains a defaulted
  `stream: InputStream?` — exactly `Content-Length` bytes of the socket, bounded — and `body` stays empty. Every other
  request's parse is byte-identical. `Transfer-Encoding` stays refused; a GET on the shape still carries no body.
  `DRAIN_BUDGET_BYTES` stays 4 MiB. **B1b rewrites the KDocs this makes false:** `LoopbackApiServer.kt:115-120` ("the
  largest body any route accepts") and `:125-126` ("no body spooled to disk"), `ApiRouter.kt:19-23` ("the body has
  already been read off the socket") and `:72-73` ("No row here accepts or returns a file"), and
  `A/api/ReferenceHandlers.kt:41` ("there is none anywhere"); I-3 itself, "a reference has no bytes", still holds.
- **C10, authenticate before streaming (mechanical correction 1).** The token is checked on the **headers alone**
  (`ApiRouter.kt:44-46`, unchanged) before a single byte of `stream` is read: the parser returns at the end of the
  header block on this shape, and a 401 never touches the stream. The server then answers 401 with its empty body and
  closes the connection through the shipped bounded drain — **at most `DRAIN_BUDGET_BYTES` (4 MiB) within the 5 s wall
  clock** (`LoopbackApiServer.kt:318-352`) — so an unauthenticated body is never staged, hashed, decoded or buffered,
  and cannot hold the worker past the bound (limit 3). Pre-auth refusals (400, 405, 413) keep their empty bodies.
- **C11, the request.**
  - `Content-Type`: the document's own media type, normalised by `MimeTypes.normalise` (absent →
    `application/octet-stream`). Never a 415 on this path.
  - `Content-Length`: required (the shipped rule); 0 is framed, then refused (C12).
  - **`X-ServiceTag-Attachment`**: base64url (RFC 4648 §5, unpadded) of a UTF-8 JSON object, decoded by the strict
    `ApiJson` (unknown key, bad enum, bad JSON, bad base64, header absent → 400 `bad_request`). It fits the 8 KiB
    header block (`HttpWire.kt:13-15`); a longer note goes through C7 afterwards.

```kotlin
@Serializable internal data class UploadMetadata(   // A/api, B1b; keys pinned in docs/api/command-shapes.json
    val operationKey: String,          // C13; required, 1-128 of [A-Za-z0-9._~:-]
    val displayName: String,
    val sha256: String,                // 64 lowercase hex of the body; checked while staging
    val kind: AttachmentKind? = null,  // null: AttachmentKinds.inferFrom(mime, fromCamera = false)
    val role: DocumentRole? = null,    // R92-5: a canonical enum name or absent; never inferred
    val capturedOn: String? = null,    // isIsoDate, else 422 ATTACHMENT_BAD_DATE
    val notes: String = "",
)
```

  **R92-5 (DECIDED):** a canonical `DocumentRole` may be set at creation, as share intake does
  (`A/share/ShareIntakeViewModel.kt:373-383`); the path is an asset, so R67-11 holds by construction. The role is
  **never inferred** — not from the file name, not from the MIME type, not from `kind`: absent means no role.

- **C12, the handler.** **Invariant: an authenticated answer on this path is written only after the declared body
  has been read to its end** — into staging on the way to `AddAttachment`, into a counting sink when a refusal was
  decided first — so a refusal is never a reset. In order:
  1. decode the header (400) → the asset exists (404 `no_such_asset`) → it is not transferred out (409
     `asset_transferred_out`) → the folder is `Ready` (409 `ATTACHMENT_STORE_NOT_CONFIGURED` or `store_unavailable`) →
     `operationKey` (422 `OPERATION_KEY_INVALID`) → name not blank (422) → `capturedOn` (422) → `sha256` well formed
     (422 `ATTACHMENT_SHA256_INVALID`). These cheap checks spare a 256 MiB stage; each refusal consumes the body into the
     counting sink first. `AddAttachment` and the guard still re-check at the write;
  2. **under C33's lock**, derive the row id from the key (C13) and look it up; **if a row with that id exists, this is
     a replay**: read the body to its end into a hashing sink (never staged), check the arrived digest against the
     declared one (422 on a mismatch), then C13's comparison → **200** `{"attachment": <the row as it stands>}` or 409
     `OPERATION_KEY_REUSED`; nothing is written either way;
  3. otherwise, still under the lock, stream the body into **`graph.materializeStaging`** (the shipped
     `CacheStagingArea`, `cache/materialize/`, swept at start — one staging area, one sweep), counting and
     SHA-256-hashing in 64 KiB chunks. **Two failure sources stay apart:** a **request-stream** failure — the 5 s
     `soTimeout`'s `SocketTimeoutException`, a reset, `stop()` closing the socket, or an early end of stream — writes
     nothing, discards staging and sends no answer (or the shipped 400 "shorter than Content-Length" for an early end),
     and is never mapped to 409 or 500; only a **staging-side** write failure is 409 `UPLOAD_NOT_STAGED`. The two are
     told apart by which stream threw, never by one broad `catch (IOException)`;
  4. 0 bytes → 422 `ATTACHMENT_EMPTY`; digest ≠ declared → 422 `ATTACHMENT_SHA256_MISMATCH`;
  5. `AddAttachment.run(OfAsset(id), AddAttachmentCommand(displayName, mimeType, sizeBytes = the count, kind,
     capturedOn, notes, fromCamera = false, role, source = null), staged.source(), presetId = <the derived id>)` — the
     store then sees a complete local file (R85-C3); `Ok` → **201** `{"attachment": AttachmentDto}`; `Refused` → C2;
     `StoreIoException` → 409 `store_unavailable`; `AssetTransferredOut` → 409 (the guard, inside the row write;
     `AddAttachment` deletes the bytes, `:99-111`);
  6. `finally`: the staging file is discarded on every path, and the lock is released.
  **The 10-minute wall-clock deadline covers every body read on this path** — staging, the step-2 hashing sink and the
  refusal counting sink alike; past it the connection is closed, staging discarded, nothing written.
  **Identical bytes are never refused as such (R92-6):** a new key with the same file is a new row. The upload can
  never set a `source` (no provenance key exists in C11). A client that disconnects, or a `stop()` that lands, during
  step 5 cannot cancel it — nothing ties the socket to the handler, and the body is already staged — so the outcome is
  one durable row or none; a retry on the same or a new generation waits for the lock and then answers 200 (C33).
- **C13, the operation key (R92-6, DECIDED as revised) — stored in the row's own id, no schema change.**
  - **Scope and identity (R92-8, DECIDED: the collision is prevented in the server-side namespace).** The key is scoped
    to the installation and the owner asset. The row's id is derived from **`installationId` (C5a) + `assetId` +
    `operationKey`**:
    `AttachmentId(uuid8(SHA-256(UTF-8("servicetag:attachment-upload:v2\n" + installationId + "\n" + assetId + "\n" +
    operationKey))))` — the first 16 bytes, RFC 9562 version-8 and variant bits set, lowercase canonical form; one
    Kotlin home (`A/api/AttachmentOperationIds.kt`), one golden vector (installation id, asset id, key → id) in
    `docs/api/attachment-operation-ids.json` (B1b writes it; B5a's Python twin reads it). It is deterministic on one
    installation, and the same asset and key on the development and production phones derive **different** ids, so a
    later dev→prod merge meets two distinct rows, never a `CONTENT_DIFFERS` on one id. Every shipped id is a random UUID
    (`C/ports/IdGenerator.kt:8`), so the derived form sits in the same space and travels in backups, merges and packs as
    an ordinary id (`AttachmentId` is a `String` value class; the codec checks only the locator's shape,
    `C/model/Attachment.kt:135-137`); the installation id itself never travels (C5a).
  - **`AddAttachment.run` gains `presetId: AttachmentId? = null`.** Null mints exactly as shipped (`AddAttachment.kt:66`).
    Given, it is used for the row and its locator, and **`require(attachments.get(presetId) == null)` runs before
    `store.put`** — a `put` at an occupied locator deletes that row's bytes (`SafTreeAttachmentStore.kt:44-47`) — and
    C33's lock spans the check, the `put` and the row's `upsert` (`AddAttachment.kt:100`), so the id stays free until
    the row lands. Unreachable from the wire (step 2 answers first, under the lock); no new `AttachmentProblem`
    member, so no phone string.
  - **Replay, strict (R92-7, DECIDED):** the derived row exists with owner `OfAsset(assetId)`, and the request's
    fingerprint — owner, `kind` (as resolved: given, else inferred from the normalised type), `role`, trimmed
    `displayName`, `sha256`, size — is compared with **the row's current values**. Equal → **200** with the row: an
    unchanged replay, or one whose meaningful metadata equals the row's **current** metadata with the same payload.
    **Any compared field different → 409 `OPERATION_KEY_REUSED`** naming the row, nothing written — in particular,
    after an upload-created attachment has been edited, a replay carrying the stale, original metadata under the same
    key is 409. **Metadata changes belong to `PATCH`** (C7), never to a replay. **A new key with identical bytes** → a
    new row, 201.
  - **Lifetime and scope:** exactly the row's, within one installation. A deleted row frees its key, and a replay then
    creates the file again; nothing expires on a clock. The namespace is (installation, asset, key), so a restore onto
    another installation starts a fresh key space there.
  - **Rejected alternative:** a device-local `attachment_operation` table (key, asset, fingerprint, attachment id,
    `createdAt`; CASCADE on the attachment; never in an archive) would compare against the original request across
    edits, at the cost of Room schema 17 (`MIGRATION_16_17`, `Migration16To17Test`, `17.json`, the `SCHEMA_VERSION` pins,
    the release runbook's schema paragraph) with backup format unchanged. Not chosen: more intrusive for a difference
    that appears only when a replay follows an edit, and it would still need C33.

### B2-pre — the API-boundary threat review (TR1–TR13; read-only, a precondition of B2)

R92-3 supersedes R85-10 **only on the condition** that the review #85 deferred ("a remote fetcher needs its own threat
review", #85 plan `:196-197`, `:871`) is written and signed off **before B2 is implemented**. Its output is one document,
`.superpowers/sdd/2026-09-30-issue-92/threat-review-materialize.md`, in the ledger directory; B2-pre changes no other
file and commits nothing. Each item states the threat, the shipped control with its `file:line`, whether #92 changes
it, and a verdict (holds / holds with a B2 condition / blocks):

| # | item |
|---|---|
| TR1 | **The new caller.** An authenticated loopback client replaces the owner's tap: who can hold the per-visit code (`v1.md:52-76`) and what an on-phone app on 127.0.0.1 can and cannot do without it. **The composition** `POST /v1/references` + materialize lets the paired caller make the phone fetch **any public https URL**: "never fetch a supplied URL" is true **per route, not per session** |
| TR2 | **The input is an existing `AssetReference` by id only** (C14): no URL, host or path in any request key; the fetched URI is the stored one, whose own write rules are the shipped reference rules |
| TR3 | **#85's restrictions intact:** https on every hop, the ASCII host allowlist and the local-address refusal on every hop (#85 C9), at most 5 redirects, the 256 MiB and 600 s limits (`C/fetch/FetchPorts.kt:105-110`), the nineteen proven document types and their exclusion set (#85 §19), the fixed `User-Agent`, no cookie or credential, **no URI in any log line or refusal body** (the 201's row carries `sourceUri` by R92-4). R85-6's duplicate check runs **after** the download and outside the write (`MaterializeReference.kt:105-110`); C33 now serialises it with the commit |
| TR4 | **The Developer API's own restrictions unchanged:** the token first (`ApiRouter.kt:44-46`), the loopback-only bind and the peer re-check (`LoopbackApiServer.kt:17-37`, `:299-302`), the screen-bound lifetime (`v1.md:25-28`), one worker per generation (C33 across generations) |
| TR5 | **Cancellation when the screen closes:** `stop()` cancels only the prepare (download) phase (C16); staging is discarded by the fetch's own cleanup; nothing is written; no answer is sent |
| TR6 | **#87's non-interruptible durable commit is preserved (R87-4, #87 plan `:439-449`):** only the commit phase is uncancellable; neither a client disconnect nor the screen closing during commit can cancel it or orphan bytes — the outcome is one durable attachment with its bytes, or no row and no bytes with the store's cleanup run, the reference untouched |
| TR7 | **Resource bounds:** one download at a time (C33's lock, not "one worker"), the staging directory and its start sweep, disk use of up to 2 × 256 MiB during commit, the MCP's 720 s budget |
| TR8 | **Residual risks and conditions for B2:** DNS rebinding and the system proxy (#85 recorded limits), the verbatim `sourceUri` on the wire (R92-4, sensitive), and any condition B2 must meet; a "blocks" verdict anywhere stops B2 and returns to the owner |
| TR9 | **The caller is often an agent.** The MCP's user may be a language model steered by fetched content (#42's research). The on-phone review — host and proven type shown before Save (R85-9) — is gone on this path. State the residual risk, and whether `materialize_reference`'s docstring must require the user's confirmation or echo the reference's host before calling |
| TR10 | **Overlapping listener generations** (C33): `stop()` never joins and `start()` runs a new generation beside the old (`LoopbackApiServer.kt:199-202`, `:223-228`); the lock, the compare-and-clear download slot and the stop-before-commit hand-off (C16) |
| TR11 | **Lifetime.** Screen-off pauses and stops the listener (`v1.md:25-28`), which cuts a long upload or download (limit 7). The pairing code lives for the view model's **visit**, across pause and resume (`A/ui/api/DeveloperApiViewModel.kt:52-60`), not only while the screen is resumed. Record the brute-force budget: 40 bits (eight of 32 symbols, `A/api/PairingCode.kt`) against one worker per generation |
| TR12 | **A lost answer and its replay.** Materialize has no operation key; after `stop()` no answer is sent. The MCP's `sourceUri` dedupe (C27) is stricter than the server's bytes rule (R85-6), so a refreshed manual at the same URI is never re-fetched by the tool: say whether that is intended, and what the owner does instead |
| TR13 | **Log and wire hygiene of the new handlers.** `StoreIoException` messages carry the locator and the tree authority (`SafTreeAttachmentStore.kt:48-49`) and must never reach a response or a log; the shipped 500 arm sends only a class name (`ApiJson.kt:551-553`) and the `store_unavailable` arm a fixed sentence (`:387-389`) |

**Sign-off:** the controller reviews the document against this table and records the verdict in the ledger; the owner's
acknowledgement of R92-3's condition is recorded beside it. **B2 is dispatched only after both lines exist.**

### B2 — save as document (C14–C18)

- **C14, the route (R92-3, DECIDED, conditional: supersedes R85-10 for an existing `AssetReference` by id only).**
  `POST /v1/references/{id}/materialize`, JSON, every key optional: `{"displayName": String?, "kind": AttachmentKind?,
  "role": DocumentRole?, "notes": String?}` (absent or null = the phone's prefill). **No request key carries a URL,
  host or path**: `uri`, `url` or anything else unknown is a 400 (the strict decoder), and the fetched URI is the stored
  reference's, read by `prepare`. Order:
  1. decode (415/400); a **given** `displayName` blank after trim → 422 `ATTACHMENT_NAME_REQUIRED` — before any fetch;
  2. `references.get(id)` → 404 `NO_SUCH_REFERENCE`; its `assetId`; that asset transferred out → 409
     `asset_transferred_out` **before any fetch** (the phone never offers Save as document there, #85 limit 7);
  3. register the request's `Job` in the download slot (C33, compare-and-clear), then take **C33's lock**; `prepare(assetId,
     id)` inside that `Job` (C16) → `Refused(why)` → C17;
  4. `review = reviewPrefill(ready.snapshot, ready.fetched.mimeType)` with each given key laid over it — the one home
     of the designation (`A/ui/references/MaterializeViewModel.kt:203-215`);
  5. **the hand-off, decided under the lock:** if the registered `Job` was cancelled after `prepare` returned `Ready`,
     the handler discards and writes nothing — **stop wins before the commit**; otherwise `commit(ready, review)` runs
     under `NonCancellable` — **the commit wins after it began** → `Ok` → **201** `{"attachment": AttachmentDto}` (its
     `source` keys filled); `Refused` → C2; `AssetTransferredOut` / `StoreIoException` → 409;
  6. `finally`: **`discard(ready)` for any `Ready` that `commit` did not spend** — a blank name returns from `commit`
     before spending or discarding (`MaterializeReference.kt:125-126`), and a throw while building the review never
     reaches `commit`; `discard` is idempotent (`:153-156`). Then the slot is cleared if it still holds this `Job`, and
     the lock is released.
  The reference row is never written (C15(a) of #85 holds: byte-equal before and after).
- **C15, synchronous (R92-3).** One request does prepare and commit; no job, no poll, no state between requests (the
  listener keeps none, `LoopbackApiServer.kt:125-130`). The answer can take the fetch's whole 600 s deadline plus its
  recorded overruns (#85 limit 2) plus the copy into the folder; the MCP tool's read budget is **720 s** (C29). The
  rejected alternative — `202` + `GET …/jobs/{id}` — needs a registry that outlives a request and owns a spent-once
  `Ready` with up to 256 MiB staged, leaking staging until the next start sweep whenever a poller vanishes.
- **C16, leaving stops the download (R85-8 on the wire).** `LoopbackApiServer.stop()` cancels a `prepare` in flight:
  the router owns one `DownloadInFlight` slot (C33 keeps it to one download at a time across generations); the
  materialize handler runs `prepare` in a child scope whose `Job` it registers there and clears in `finally` **only if
  the slot still holds it** (compare-and-clear); `stop()` cancels that `Job` only. `FetchDocument`'s
  shipped cancellation path discards staging; nothing is written; the connection is already closed, so no answer is
  sent. **The commit keeps #87's durable, non-interruptible rule (R87-4, #87 plan `:439-449`):** `commit` runs under
  `NonCancellable`, outside the registered `Job`, and nothing ties the socket to it — so neither `stop()` nor a client
  disconnect during the commit can cancel it or orphan bytes: exactly one durable attachment with its bytes, or no row
  and no bytes (the use case's `finally` discards staging and the store's cleanup runs), the reference untouched. A
  caller that lost the answer re-runs and meets C30's dedupe. **Every other route is untouched** — the shipped promise
  that a write in flight runs to completion as one transaction (`LoopbackApiServer.kt:216-232`) still holds for them.
- **C17, the refusals.** `NoSuchReference` → 404 `NO_SUCH_REFERENCE`; `NotEligible` → 409
  `REFERENCE_NOT_MATERIALIZABLE`; `Store(NoStore)` → 409 `ATTACHMENT_STORE_NOT_CONFIGURED`; `Store(StoreUnavailable)` →
  409 `store_unavailable`; `NetworkDenied` → 409 `NETWORK_DENIED`; `Fetch(p)` → **502** `FETCH_<P>` (C2);
  `AlreadyHave` → 409 `ATTACHMENT_ALREADY_HELD` (R85-6 unchanged: materialize keeps its same-bytes rule; the upload
  does not have one, R92-6). **`AlreadyHave` gains `val attachmentId: AttachmentId`** (the earliest same-bytes row's
  id, `MaterializeReference.kt:107-110`), so the refusal names the row and a retried call is recognisably already done; `MaterializeStrings` reads only
  `name` and is unchanged; the one core and four test constructions move. `materializeRefusalCode` and
  `fetchProblemCode` are exhaustive `when`s.
- **C18, every outbound sentence.** "Only when the owner taps" stops being true, and so does "never connects out".
  B4 rewords all five sentences to "when the owner taps it on the phone, or when the owner's paired workstation asks
  through the Developer API while its screen is open": the README's feature line (`README.md:41`, "downloads it only
  when you tap") and INTERNET bullet (`README.md:78`), the manifest comment (`app/src/main/AndroidManifest.xml:12`), and
  both halves of `v1.md:38` ("The Developer API listens only on localhost **and never connects out**" and "made only
  when the owner taps it on the phone, which no route here and no MCP tool reaches"). The pre-auth disclosure sentence
  `v1.md:72-76` gains the 256 MiB upload shape: a pre-auth 413 now also tells that path's `POST` apart.
  `VersionAgreementTest.kt:305-311`'s anchors (INTERNET, Save as document) keep passing.

### B3 — replace (C19–C24)

**R92-1 (DECIDED): API Replace explicitly supersedes R86-18** ("No API or MCP Replace command", #86 plan `:618`). The
routes reuse #86's canonical atomic replacement use case, `ReplaceAsset` — `offer`, `plan` and `run(draft, reviewed)`
— with its reviewed selections (every carry-forward item unticked unless the caller names it, R86-9) and its
source-digest guard (C23 over C10's stale rule). Nothing here re-implements a step of the replacement.

- **C19, the offer.** `GET /v1/assets/{id}/replace-offer` → `ReplaceAsset.offer` (`C/usecase/ReplaceAsset.kt:203-204`)
  as: `eligible`, `held`, `replacedBy` (the succession row or null), `predecessor` (`AssetDto`), `schedules` (the
  `/v1` schedule row, `A/api/ScheduleRowResponse.kt`), `groups` (`MaintenanceGroupDto`), `setupOffered`,
  `seasonOffered`, `notesOffered`, `tags` (`NfcTagDto`), `parentChoiceIds` (ids only, the parent rule's), `prefill`
  (`{name, category, location, parentAssetId}`), `childNames`, `openLoan`. 404 `no_such_asset`. Writes nothing. **No
  default for `scheduleStartOn`**: the phone's in-service-else-replacement default is a form default
  (`A/ui/replace/ReplaceAssetViewModel.kt:300`); core has none (R86-10), so the caller sends one explicitly.
- **C20, the draft.** The body of both POSTs mirrors `ReplaceDraft` (`ReplaceAsset.kt:51-63`); the predecessor is the
  path's id (a `predecessorId` key is a 400):

| key | type | default | note |
|---|---|---|---|
| `retiredOn` | `YYYY-MM-DD` or null | null | required iff the predecessor is not retired (R86-3) |
| `successor` | `ReplaceSuccessorRequest` | 400 | the asset command's keys **minus** `description`, `notes`, `templateKey`, `seasonStartMmdd`, `seasonEndMmdd` — the use case fills or ignores those (`:347`, `:389-407`), so sending one is a 400, never a silent drop |
| `carrySeason`, `carrySetup`, `carryNotes` | bool | false | every item unticked (R86-9) |
| `manualPhase` | `IN_SEASON` \| `OUT_OF_SEASON` \| null | null | required iff `carrySeason` on MANUAL |
| `scheduleIds`, `groupIds` | [id] | [] | |
| `scheduleStartOn` | `YYYY-MM-DD` or null | null | one reviewed anchor for every ticked time rule |
| `movedTagIds` | [id] | [] | **R92-2**: each binding selected individually by its id from the offer's `tags`; no "all" form, no label or pattern; an id not offered is `REPLACE_NOT_OFFERED` |
| `sourcesDigest` | hex | — | `replace` only; required there, a 400 on `replace-plan` |

- **C21, the plan.** `POST /v1/assets/{id}/replace-plan` → offer (404) → `ReplaceAsset.plan(draft)` → 200
  `{"eligible", "blockedBy": null | "asset_transferred_out" | "ASSET_ALREADY_REPLACED", "replacedOn", "problems":
  [{"code", "field", "problem"}], "sourcesDigest"}`. Problems are **data** here (the import-merge plan's precedent),
  each mapped by C24. **Writes nothing** (`readSnapshot` byte-equal, `commits == 0`).
- **C22, the apply.** `POST /v1/assets/{id}/replace`: decode → offer (404) → held → 409 `asset_transferred_out` →
  `replacedBy != null` → 409 `ASSET_ALREADY_REPLACED` → `plan(draft)` → digest ≠ `sourcesDigest` → 409
  `REPLACE_STALE` → a problem → 422, the first's code and field, every problem in `problems` → `run(draft, plan)`
  (which re-plans inside its one write, so the handler's own plan-then-run gap is covered by the use case) →
  `ReplaceStale` → 409 `REPLACE_STALE`; `AssetTransferredOut` → 409 → **201** `{"successor": AssetDto, "succession":
  <the archive's succession row>}`.
  **Tag moves (R92-2, DECIDED): only the individually selected bindings move, and a move is a metadata re-target —
  never an NFC hardware write.** Each id in `movedTagIds` is `BindTag`'s in-transaction retarget of its binding row
  (`ReplaceAsset.kt:366-369`): `target` becomes the successor and `updatedAt` now; id, payload format and key, label,
  `physicalUid`, `writtenAt`, `lastScannedAt` and `createdAt` are kept (#86 C14). No NDEF write, no `NfcAdapter`, no
  tag I/O exists on this path or anywhere in `A/api`; every binding not selected stays with the old asset;
  `/v1/tags` stays read-only.
- **C23, the digest — the screenless "changed while reviewing" and "only the reviewed draft".** `sourcesDigest` =
  lowercase-hex SHA-256 of `ApiJson` over one `@Serializable` value: **the canonical decoded draft** (the request DTO as
  decoded, re-encoded by `ApiJson`, minus `sourcesDigest`), then `replacedOn`, then `ReplaceSources`
  (`ReplaceAsset.kt:134-142`) as archive DTOs in its own order — the predecessor, its successor row, each ticked
  schedule, group and moved tag, and (iff `carrySetup`) every definition and profile (`sourcesOf` sorts each list by
  id, `:330-334`, so the order is deterministic). One function computes it for C21 and C22. It mirrors #86 exactly: a
  draft other than the planned one is 409 `REPLACE_STALE`, as `run` refuses any draft but the reviewed one (`:214`), and
  a source change is 409 when C10's stale rule (`fresh.sources != reviewed.sources || replacedOn differs`, `:220`) would
  fire, because the archive DTOs carry every canonical field of those rows (the DTOs carry `members` and `providers`,
  `C/backup/BackupFormat.kt:297-356`); row 32 proves it per source class by `x.toDto().toDomain() == x`.
- **C24, the mapping.** `replaceProblemRefusal(ReplaceProblem)` — exhaustive, C2's rows; `Successor(p)` delegates to
  the shipped `assetRefusal(p)` and prefixes its field with `successor.`. `mapDomainFailure` gains a `ReplaceStale` arm
  (409 `REPLACE_STALE`) as defence — today it would be a 500 (`ApiJson.kt:551-553`). The shipped pin
  `T/api/SuccessionRoutesTest.kt:136` (`POST /v1/assets/{id}/replace` → 404) loses exactly that entry; every other
  path in it stays 404.

### B4 — the wire documents (C25–C26)

- **C25, `docs/api/v1.md` and friends.** Three new sections — **Attachments (#92)**, **Save as document (#92)**,
  **Replacing an asset (#92)** — with C2's codes, C3's rows, C9's third tier in **Framing and limits** (and the
  auth-before-body note), 502 in the **Errors** table, twenty-five asset sub-resources in the 404/405 row, the two new
  405 shapes; **What has no endpoint** keeps deleting an attachment, reading or writing bytes back out, recording
  provenance except by materializing, and every tag write, and drops the three sentences #92 retires
  (`v1.md:1166-1169`, `:1181-1183`) plus **Asset successions**' "Recorded by the phone alone" (`:1134-1136`), now
  "recorded by the phone's Replace asset or by `POST /v1/assets/{id}/replace`". The upload section documents C10
  (authentication on the headers, the bounded drain), C13's operation key (its derivation with the golden vector, the
  200 replay, the 409 reuse including a stale replay after an edit, its lifetime, and C33's serialisation), and R92-5 (a role only as given).
  `/v1/status` documents `installationId` (C5a: opaque, device-local, never in an archive, not authentication
  material), and the upload section documents the derivation over it. The attachment row is documented with its provenance keys **marked
  sensitive** (R92-4). The new 502 row names the transient codes (`FETCH_UNREACHABLE`, `FETCH_INTERRUPTED`,
  `FETCH_TIMED_OUT`, a 5xx `FETCH_SERVER_ERROR`) and says the MCP never auto-retries a materialize. C18's five sentences
  and the 413 disclosure. `M/README.md`: the seven tools, C27's operation mapping, their schema-16 minimum and the
  default operation key. `docs/release-proofs.md`: a dated note after the 1.5.0 erratum
  (`:109-114`) that from #92 a gate may seed attachments of every kind and role, and make a Replace, through the API —
  the 1.5.0 paragraph itself is history and is not rewritten.
- **C26, `docs/api/command-shapes.json`.** Five entries — `attachmentUpdate`, `attachmentUpload` (C11's JSON keys,
  `operationKey` first),
  `materialize`, `replaceDraft`, `replaceSuccessor` — each asserted equal to its DTO's descriptor in
  `CommandShapesGoldenTest`, which also asserts `v1.md` names every C2 code.

### B5a and B5b — the MCP (C27–C30)

B5 is pre-split (review m14): **B5a** = the gates (C28), the client (C29), the five attachment and materialize tools
and the tool-count pins; **B5b** = `get_replace_offer` and `replace_asset`.

- **C27, eight operations, seven tools** (69 → 76; `TOOL_NAMES` and `M/tests/test_tools.py:22-110` move, 69 → 74 in B5a and → 76 in B5b) —
  **mechanical correction 2.** One tool per operation, except that **replace-plan and replace share `replace_asset`**:
  the apply's precondition is the plan's own `sourcesDigest`, so one tool plans and applies with the digest it was just
  handed and can never apply a digest from a different plan — the shipped `import_merge` (plan + apply) and
  `repair_schedule_providers` (plan + apply) precedent, `plan_only` as their switch. The loader's snapshot calls it
  with `plan_only=True`.

| operation | route | tool |
|---|---|---|
| list | `GET /v1/assets/{id}/attachments` | `list_attachments` |
| get | `GET /v1/attachments/{id}` | `get_attachment` |
| patch | `PATCH /v1/attachments/{id}` | `update_attachment` |
| upload | `POST /v1/assets/{id}/attachments` | `add_attachment` |
| materialize | `POST /v1/references/{id}/materialize` | `materialize_reference` |
| replace-offer | `GET /v1/assets/{id}/replace-offer` | `get_replace_offer` |
| replace-plan | `POST /v1/assets/{id}/replace-plan` | `replace_asset` (`plan_only=True`) |
| replace | `POST /v1/assets/{id}/replace` | `replace_asset` (`plan_only=False`) |

| tool | route(s) it calls | behaviour |
|---|---|---|
| `list_attachments(asset_id)` | GET list | as sent; the docstring marks the provenance keys sensitive (R92-4) |
| `get_attachment(attachment_id)` | GET one | as sent; same note |
| `add_attachment(asset_id, file_path, display_name=None, mime_type=None, kind=None, role=None, captured_on=None, notes=None, operation_key=None)` | GET status, GET list, GET one, then POST | refuses a file over 256 MiB **before opening it**; reads `GET …/attachments` first (the asset exists and `folder` is `READY`, else a local refusal naming the state, no bytes sent); hashes the file locally. **The default `operation_key` is payload-only (review C-5; R92-8 keeps it so):** lowercase hex SHA-256 of `asset_id`, the file's sha256 and its size — never the name, kind or role, and no per-phone discriminator (the server's namespace carries the installation, C13), so a re-run with a corrected role meets `OPERATION_KEY_REUSED` naming the row, and the message points to `update_attachment`; a deliberate second copy of identical bytes passes an explicit new key. **It resolves a missing `kind` exactly the server's way** before fingerprinting — `AttachmentKinds.inferFrom` over
`MimeTypes.normalise` (`image/*` → `PHOTO`, `application/pdf` → `DOCUMENT`, anything else → `OTHER`,
`C/model/Attachment.kt:171-172`, `:210-216`), as a Python copy held to golden cases in
`docs/api/attachment-operation-ids.json` — and **always sends the resolved `kind`** in `X-ServiceTag-Attachment`, so
the server never infers for an MCP upload and both sides compare one value by construction (review C-15); it sends
`display_name` already trimmed. It derives the row id with C13's function over the phone's `installationId` (read from `/v1/status` once per pairing; the Python twin, held to the golden vector) and `GET`s it. Its **local fingerprint check is C13's rule exactly** (the six fields against the row as it stands, R92-7 strict): equal → `{"decision": "REPLAYED", "attachment": row}` and **no POST**; different → `OPERATION_KEY_REUSED` refused locally, no POST; 404 → streams the file with an explicit `Content-Length` (never chunked) and the base64url header (201 `CREATED`; a server 200 → `REPLAYED`). **On the client's `ConnectError` retry the file is re-opened**, never a consumed iterator (`client.py:211-224`). `role` only as given — never guessed (R92-5). `display_name` defaults to the file's basename; `mime_type` to Python's `mimetypes` guess, else `application/octet-stream` |
| `update_attachment(attachment_id, display_name=None, kind=None, captured_on=None, notes=None, role=None, clear_fields=None)` | GET one, then PATCH | an overlay on the vendored `attachmentUpdate` keys; `clear_fields` ⊆ {`role`, `captured_on`, `notes`} |
| `materialize_reference(asset_id, reference_id, display_name=None, kind=None, role=None, notes=None)` | GET references, GET list, then POST | takes ids only — no URL argument (R92-3). No `GET /v1/references/{id}` exists (`/v1/references/{id}` is PATCH-only, `ApiRouter.kt:243-244`), so it reads `GET /v1/assets/{id}/references` for the reference and its URI (absent → `NO_SUCH_REFERENCE` locally, no POST), then `GET …/attachments`: a row whose `sourceUri` equals that URI → IDENTICAL, **no POST** (R92-4); else the POST; 409 `ATTACHMENT_ALREADY_HELD` → IDENTICAL; never retried automatically (a 502 is returned as the error). Its docstring carries TR9's outcome (B2-pre) |
| `get_replace_offer(asset_id)` | GET offer | as sent |
| `replace_asset(asset_id, successor…, retired_on=None, carry_season=False, manual_phase=None, carry_setup=False, carry_notes=False, schedule_ids=None, schedule_start_on=None, group_ids=None, moved_tag_ids=None, plan_only=True)` | POST plan, then POST replace | **plan-first, `plan_only=True` by default** (the provider-repair precedent); applies only a clean, eligible plan, sending its `sourcesDigest`; returns the plan otherwise; `moved_tag_ids` is a list of individual binding ids (R92-2), never "all"; a 409 `ASSET_ALREADY_REPLACED` whose successor carries the requested name → IDENTICAL |

- **C28, the gates.** `_MIN_ATTACHMENT_SCHEMA_VERSION = 16`, a per-tool minimum for all seven, reads included (the
  loan tools' pattern, `M/src/servicetag_mcp/server.py:159-265`); the global write minimum stays 8. On these seven
  only, an `ApiError` with status 404 and code **`not_found`** (the router's unknown-route code, `ApiJson.kt:144-145`)
  becomes `ToolError("APP_ROUTE_MISSING: the phone's app has no <route>; update ServiceTag to use <tool>")`; a
  `no_such_asset`, `NO_SUCH_ATTACHMENT` or `NO_SUCH_REFERENCE` 404 passes through as today. Every write tool reads
  first, so an old app is recognised on a GET with no bytes sent.
- **C29, the client.** `client.request` streams a file body with an explicit `Content-Length` header and no
  `Transfer-Encoding` (proved against the recording fake), taking a body **factory** so the one `ConnectError` retry
  (`client.py:211-224`) re-opens the file rather than resending a consumed iterator (0 bytes under the declared
  length). Budgets: upload `httpx.Timeout(30.0, read=300.0,
  write=300.0)`; materialize `httpx.Timeout(30.0, read=720.0)`. `MAX_ATTACHMENT_BYTES = 268_435_456` beside
  `MAX_IMPORT_BYTES` (`M/src/servicetag_mcp/client.py:29-32`).
- **C30, idempotency.** Every write tool is plan-before-write: a re-run of `add_attachment` (same key → REPLAYED),
  `materialize_reference` or `replace_asset` with the same arguments after success sends no write; the server stays
  authoritative (C13's 200 and 409, C17's 409, C22's 409) when a tool's read raced; `update_attachment` with the stored
  values is the route's own 200 no-op. No docstring names a screen-driving tool.

### B6 — the loader (C31–C32)

- **C31, the manifest.** `manifestVersion` stays 1; an optional top-level `replacements` list (absent = none; the
  shipped manifests load unchanged): `{"key", "predecessor": "<exact asset name>", "retiredOn": day|null,
  "successor": {<C20's successor keys>}, "carry": {"season", "manualPhase", "setup", "notes"}, "schedules": ["<exact
  title>"], "scheduleStartOn": day|null, "groups": ["<exact group name>"], "moveTags": ["<exact tag label>"]}`.
  `moveTags` names each binding individually (R92-2); each label must resolve to exactly one offered binding, and the
  loader sends those ids. `manifest.load` checks shape only, as today.
- **C32, plan and apply.** `phone.snapshot` adds, per replacement: every asset carrying the predecessor's name, each
  one's succession (`get_asset_succession`), and for the one candidate with no successor its offer and a
  `replace_asset(plan_only=True)` result. `plan.plan` stays pure and **evaluates in this order**: **IDENTICAL** first,
  iff exactly one named asset has a `replacedBy` whose successor carries `successor.name` (the successor usually keeps
  the old name, R86-8, so identity goes through the succession, never the name alone); **a named asset that is itself a
  successor (its succession has `replaces`) is never a CREATE candidate** — after an apply it keeps the old name and
  has no successor, so a CREATE-first order would plan a second replacement; **CREATE** iff exactly one remaining named
  asset has no successor, is not held, every schedule title, group name and tag label resolves exactly within its
  offer, and the phone's plan has no problems; **CONFLICT** iff the named asset was replaced by a differently named successor, or is held; **ERROR**
  otherwise (missing, ambiguous, unresolved name, or a plan problem — its code in the reason). `apply.apply` refuses an
  unclean plan, re-snapshots, calls `replace_asset(plan_only=False)` per CREATE, and returns counts and a re-plan that
  reads all `IDENTICAL`. It prints no id and no code.

## 3. Test matrix

Rows are hazard · class · case(s) · counted RED (the mutation that must fail it). App classes live in `T/api/` unless
named; `V1Client` drives the production router in process; socket rows bind 127.0.0.1 on the JVM.

| # | hazard | class · cases | counted RED |
|---|---|---|---|
| 1 | C5 the list reads another owner's rows or leaks the folder | `AttachmentRoutesTest` · `theListIsThisAssetsOwnRowsOldestFirst` (an event row absent), `theFolderIsItsStateNameNeverItsName` | `all()` for `forAsset` |
| 2 | C5 verbs | `anUnknownAssetIs404NoSuchAsset`, `aDeleteOnTheListIs404` | none: the shipped sub-resource `else` |
| 3 | C6 one row | `getAnswersAnEventOwnedRow`, `aMissingRowIsNoSuchAttachment` | code `not_found` |
| 4 | C7 the full command | `aPatchWithoutTheRoleKeyIs400`, `aNullRoleClears`, `aNoOpPatchIs200TheStoredRowAndWritesNothing` (`commits == 0`) | a default on `role` |
| 5 | C7 a role on an event row is a 500 | `aRoleOnAnEventRowIs422AndWritesNothing`, `aBlankNameIs422`, `aBadCapturedOnIs422` | the `accepts` pre-check removed (→ 500) |
| 6 | C8 provenance on the wire | `aSourcedRowReadsExactlyAsItsArchiveRow` — equality of **decoded values** (the answer decoded as `AttachmentDto` equals `row.toDto()`), never raw JSON text, since the archive codec's null and default settings may differ from `ApiJson`'s | `sourceUri` nulled |
| 7 | C9 caps by (method, path) | `HttpWireTest` · `theUploadShapeTakes256MiBOnlyForPost` (POST declared 256 MiB + 1 → 413 there; **PATCH on the shape, 64 KiB + 1 → 413**; 4 MiB + 1 on `/v1/assets` → 413; `/v1/assets/a1/attachments/x` keeps 64 KiB); `ApiRouterTest.kt:760-765` moved to the two-argument form | a prefix match, or the cap chosen by path alone |
| 8 | C9 the parser buffers an upload | `HttpWireTest` · `theUploadShapeIsLeftOnTheSocket` (stream present, body empty, zero body bytes consumed); every shipped case unchanged | `readExactly` on the shape |
| 9 | C10 a body read or staged before auth | `AttachmentUploadRoutesTest` · `aWrongTokenUploadIs401WithZeroBodyBytesReadAndNoStaging` (a counting stream reads 0; the staging dir stays empty; nothing decoded) | stage before the token |
| 9b | C10 an unauthenticated 256 MiB body holds the worker | `LoopbackApiServerTest` · `anUnauthenticatedUploadIsClosedWithinTheDrainBound` — observed **client-side** (review m1): a real JVM socket declares 200 MiB and keeps writing; it reads the 401, then **cannot deliver the body** (a reset or broken pipe after about 4 MiB plus the socket buffers), and the next request is answered | the drain budget raised to the upload cap (killed by the delivery assertion, not by the 5 s bound) |
| 10 | C12 the happy path | `AttachmentUploadRoutesTest` · `anUploadLandsOneRowWithTheStoresDigestAndSize` (staging dir empty after; `commits == 1`) | the `finally` discard dropped |
| 11 | C12 over a real socket, past the import cap | `LoopbackApiServerTest` · `aFiveMiBUploadCrossesARealSocket` | none: row 8 carries the RED |
| 12 | C12 refusals leave nothing | `noFolderIs409BeforeStagingAndNoRow`, `aTransferredOutAssetIs409BeforeStaging`, `anEmptyBodyIs422`, `aMalformedDigestIs422Invalid`, `aDigestMismatchIs422AndStoresNothing`, `aMissingOrBadHeaderIs400`, `anUnknownAssetIs404` — each: store empty, staging empty (never created for a step-1 refusal), `commits == 0`, body read to its end | the digest check removed |
| 13 | C11 R92-5 | `aRoleGivenAtCreationIsStored`; `noRoleIsInferred` (a `manual.pdf` of `application/pdf` with `kind` `MANUAL` and no role → `role` null) | a role inferred from the name or kind |
| 14 | C13 R92-6 the operation key | `AttachmentUploadRoutesTest` · `anExactReplayIs200TheSameRowAndWritesNothing` (same id; `commits` unchanged; store unchanged); `aKeyReusedWithOtherBytesIs409`; `aKeyReusedWithOtherMetadataIs409` (name, kind or role); `identicalBytesUnderANewKeyAre201ANewRow`; `aReplayAfterADeleteCreatesAgain`; `oneKeyOnTwoInstallationsDerivesTwoIds` (two `installationId`s, one asset id, one key); `aBadKeyIs422` | the fingerprint reduced to `sha256`; the installation left out of the derivation |
| 14b | C13 R92-7 strict replay after an edit | `AttachmentUploadRoutesTest` · `aReplayWithStaleMetadataAfterAnEditIs409` (upload; `PATCH` renames it and sets a role; the original request replayed under its key → 409 `OPERATION_KEY_REUSED` naming the row, nothing written); `aReplayMatchingTheCurrentMetadataIs200` (the same payload with the row's current name and role → 200 the row, nothing written) | the fingerprint compared with the original request, or with the payload alone once edited |
| 15 | C12 disk | `aStagingFailureIs409UploadNotStaged` (a staging area that throws) | the arm removed (→ 500) |
| 16 | C12 a dying peer | `aShortBodyIs400AndWritesNothing`; `aBodyPastTheDeadlineWritesNothing` (injected deadline, each of the three sinks); **`aStalledPeerPastTheSocketTimeoutWritesNothingAndIsNot409`** (a `SocketTimeoutException` from the request stream: no row, staging discarded, no 409, no 500) | no deadline; one broad `catch (IOException)` mapped to `UPLOAD_NOT_STAGED` |
| 17 | C13 the id derivation and the preset id | `T/api/AttachmentOperationIdsTest` · the golden vector of `docs/api/attachment-operation-ids.json`, the version-8 and variant bits, distinct installations or assets give distinct ids for one key; the golden kind cases equal `AttachmentKinds.inferFrom(MimeTypes.normalise(…))`; `CT/usecase/AttachmentUseCasesTest` (+3) · `aPresetIdIsTheRowsAndItsLocators`, `noPresetIdMintsAsShipped` (every shipped case unchanged), **`aPresetIdOfAnExistingRowThrowsBeforePutAndTouchesNothing`** (the store's `put` never called; the existing row and its bytes byte-equal) | the asset left out of the hash; the `require` moved after `put` |
| 18 | C14 the happy path | `MaterializeRoutesTest` · `aWebReferenceBecomesOneSourcedRow` (201; source keys; the reference byte-equal; `commits == 1`; staging empty) | `commit` skipped |
| 19 | C14 the designation | `absentFieldsTakeThePrefill` (name, PDF → `DOCUMENT`, no role, description as notes), `givenFieldsWin` | kind forced `OTHER` |
| 20 | C14 no fetch on a caller's mistake | `aBlankGivenNameIs422WithNoTransportCall`, `aTransferredOutAssetIs409WithNoTransportCall` | checks moved after `prepare` |
| 21 | C17 codes | `everyRefusalAndFetchProblemHasItsCode` (the two exhaustive mappers directly) + route cases `NotEligible` 409, `NoStore` 409, `TimedOut` 502, `ServerError` 502 with `problems` | `TimedOut` → 500 |
| 22 | C17 the id | `CT/…/MaterializeReferenceTest` · `alreadyHaveNamesTheEarliestRowsId`; route `theSameBytesAre409NamingTheRow` | the latest row's id |
| 23 | C16 leaving stops the download | `MaterializeRoutesTest` · `stoppingTheListenerCancelsTheDownloadAndLeavesNoStaging` (a transport blocked on a latch) | the `Job` never registered |
| 23b | C14, C16 R87-4 the commit is durable; the hand-off | `MaterializeRoutesTest` · `stopDuringTheCommitLeavesOneDurableRowAndNoOrphan` and `aClientDisconnectDuringTheCommitLeavesOneDurableRowAndNoOrphan` (a store `put` parked on a latch; `stop()` or the socket closed; release → exactly one row, its bytes in the store, staging empty, the reference byte-equal); `aCommitThatFailsLeavesNoRowAndNoBytes`; **`aStopAfterReadyBeforeCommitWritesNothing`** (no row, staging empty, reference byte-equal); **`aBlankNameReachingCommitStillDiscardsStaging`** | `commit` moved inside the registered `Job` **and without** `NonCancellable` (`put`'s `withContext` return then throws after the copy and orphans the bytes); the `finally` `discard` removed |
| 24 | C16 other routes unchanged | `LoopbackApiServerTest` shipped stop cases unchanged and green | none: pins |
| 25 | C19 the offer | `ReplaceRoutesTest` · `theOfferIsTheUseCasesOffer` (an ARCHIVED schedule, a held-row group, a LOST tag and a descendant parent all absent) | every schedule listed |
| 26 | C20 strict successor | `aDescriptionTemplateKeyOrSeasonPairInTheSuccessorIs400` | `AssetCommandRequest` reused |
| 27 | C21 the plan writes nothing | `thePlanWritesNothingAndMapsEveryProblem` (`readSnapshot` equal, `commits == 0`) | `run` called |
| 28 | C22 the apply | `anApplyRetiresCreatesMovesAndRecordsOneSuccession` (201; one group window; the succession row) | `movedTagIds` dropped |
| 28b | C22 R92-2 only selected bindings, metadata only | `ReplaceRoutesTest` · `onlyTheSelectedBindingsMoveAsAMetadataRetarget` (two ACTIVE bindings, one selected: it targets the successor with id, payload, label, `physicalUid`, `writtenAt`, `lastScannedAt` and `createdAt` byte-equal; the other stays); `anAllOrLabelFormIs400`; the B3 grep `'Ndef|NfcAdapter|IsoDep|MifareUltralight|writeNdef'` over `A/api` → 0 | every offered binding moved |
| 29 | C22 stale | `aChangeBetweenPlanAndApplyIs409StaleAndWritesNothing` (a ticked schedule edited; a moved tag's scan stamp; a definition archived) | digest over the predecessor only |
| 30 | C22 order | `aHeldPredecessorIs409`, `anAlreadyReplacedPredecessorIs409NamingItsSuccessor`, `aFutureDateIs422`, `aMissingPhaseIs422FieldManualPhase` | problems before state |
| 31 | C24 defence and the moved pin | `ReplaceStale` → 409 (the mapper directly); `SuccessionRoutesTest.aPostIs404` minus its `replace` entry; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` unchanged | none: pins |
| 32 | C23 the digest | `theDigestIsStableAndMovesWithEachSourceClass`; **`aDraftOtherThanThePlannedOneIs409Stale`** (a successor field, `carrySeason`, `carryNotes`, `manualPhase` or `scheduleStartOn` changed after the plan); `x.toDto().toDomain() == x` for each source class (asset, succession, schedule, group, tag, definition, profile) | the setup rows left out; the draft left out |
| 33 | C25–C26 the documents | `CommandShapesGoldenTest` (+5 commands; every C2 code named in `v1.md`); `ReferenceRoutesTest`'s sub-resource pin at twenty-five; `VersionAgreementTest` unchanged and green | a code missing from `v1.md` |
| 34 | C27 the tool list | `M/tests/test_tools.py` · 76 names; `test_argument_guard.py` retargeted | none: pins |
| 35 | C27, C29 the upload | `M/tests/test_attachment_tools.py` · sends `Content-Length`, no `Transfer-Encoding`, header decodes to C11's JSON, body equal; the Python id derivation equals the golden vector; **the default key is SHA-256 of (asset, sha256, size) only** — a changed role or name gives the same key; the local id derivation uses the status' `installationId`; the local check equals C13's (strict) rule: a matching row → REPLAYED with no POST, a differing or edited row → `OPERATION_KEY_REUSED` naming `update_attachment`, no POST; a new `operation_key` with the same file POSTs; a missing folder or asset refused before the file is opened; no role sent unless given; over 256 MiB refused before open; **the file re-opened on the `ConnectError` retry** (the retry's body equals the file); **the kind resolved and always sent** — the Python inference equals the golden cases, and `kind` is present in every header; **an attachment PATCHed from `DOCUMENT` to `MANUAL`, then the same call re-run with `kind=None` → local `OPERATION_KEY_REUSED`, no POST** | chunked streaming; metadata in the default key |
| 36 | C28 the gates | `…_every_new_tool_refuses_schema_15_with_nothing_sent`; `…_route_missing_is_APP_ROUTE_MISSING_and_no_such_asset_passes_through` | the translation on every 404 |
| 37 | C27 materialize and replace | `test_attachment_tools.py` · `materialize_reference(asset_id, reference_id)` reads `/v1/assets/{id}/references` then the list: **a `sourceUri` match → IDENTICAL with no POST**; a missing reference → `NO_SUCH_REFERENCE` with no POST; the 720 s budget; a 502's code in the error and no retry; `test_replace_tools.py` · plan-only by default, apply sends the plan's digest, problems stop before apply, `ASSET_ALREADY_REPLACED` with the name → IDENTICAL | apply on a plan with problems; the dedupe skipped |
| 38 | C27 the overlay | `test_attachment_tools.py` · `clear_fields` on `role`, `captured_on`, `notes`; `test_command_shapes.py` · the vendored `attachmentUpdate` equals the golden | the key list drifted |
| 39 | policy | `ReleaseProofPolicyTest` unchanged and green (no screen-driving name under `tools/`) | none: the tripwire |
| 40 | C31 the manifest | `S/tests/test_manifest.py` · `replacements` shape; a manifest without it loads unchanged | an unknown key tolerated |
| 41 | C32 the plan | `S/tests/test_plan.py` · CREATE; IDENTICAL through the succession when both assets share the name; CONFLICT; ERROR on ambiguity and on a plan problem | identity by name alone |
| 42 | C32 the apply | `S/tests/test_apply.py` · refuses unclean; applies; re-plan all IDENTICAL | no re-snapshot |
| 43 | C33 a retried upload across generations | `LoopbackApiServerTest` (real sockets) · `aRetriedUploadAcrossAStopAndStartLandsOnceWithTheFirstBytesIntact` — an upload parked in `put` (a gated store), `stop()` + `start()`, the MCP's replay of its key on the new generation: it waits for the lock, then answers 200; exactly one row; the first request's bytes intact at the locator; **the store's `put` called exactly once**. `FakeGraph()` on its default real dispatcher (`Dispatchers.Default`), never a virtual test scheduler | **the lock removed** (the replay stages, its `put` deletes the first file, both `upsert` one id) |
| 44 | C33 a second request racing the first under the lock | `LoopbackApiServerTest` (real sockets) · `aSecondMaterializeOfOneReferenceAcrossGenerationsWritesOnce` — a materialize parked in its commit, `stop()` + `start()`, the same reference materialized on the new generation: it waits for the lock, then R85-6 answers 409 `ATTACHMENT_ALREADY_HELD` naming the first row; exactly one row; and `anUploadWaitsForAMaterializeHoldingTheLock` (the upload's row lands after, both intact); `FakeGraph()` on its default real dispatcher, never a virtual test scheduler | the materialize path left outside the lock |
| 45 | C33 the compare-and-clear slot | `LoopbackApiServerTest` · `aStaleGenerationsFinallyLeavesTheNewDownloadRegistered` — a stale generation's handler finishing after `start()` leaves the new download registered, and a later `stop()` still cancels it (staging empty, no row); `FakeGraph()` on its default real dispatcher, never a virtual test scheduler | the slot cleared unconditionally |
| 46 | C5a the installation id (R92-8) | `T/prefs/InstallationIdentityTest` and `T/api/ApiRouterTest` (+1) · `oneIdStableAcrossProcessRestarts` (a second `InstallationIdentity` over the same directory reads the same id); `aFreshDirectoryMintsADifferentId`; **`concurrentFirstReadsYieldOneId`** (several threads read a fresh directory at once: one id, one file, no temporary left); **`aMalformedFileIsReplacedOnce`** (a truncated or non-UUID file is replaced by one new id, which later reads return); `theIdIs128CsprngBitsInUuidForm` (the 8-4-4-4-12 lowercase-hex shape; the generator is `SecureRandom`, 16 bytes, no version bits forced); `statusCarriesInstallationId`; `absentFromTheBackupCodecsOutput` (a full `BackupCodec` export of the Room-backed graph never contains it); `absentFromATransferPack` (a `TransferPackWriter` pack's every entry never contains it); B1a's grep `'installationId|InstallationIdentity'` over `C/backup`, `C/transfer`, `A/transfer` → 0 | the id minted on every read; the id written into `SharedPreferences` or the archive |

**Device rows: none.** The one platform fact #92 adds — bytes streamed through a **real granted SAF tree** — cannot be
reached by an instrumented test without driving the system picker (`SafTreeAttachmentStoreContractTest.kt:4-9`), which
the tripwire forbids, and the emulator has no attachment folder (`docs/release-proofs.md:114`). The store's `put` over
a real provider is already proven by that shipped class, which B1b does not touch. The granted tree, `adb forward`
throughput and a real https download are proven once on the **development phone** as D1 (§7), with the owner holding
the Developer API screen and the MCP speaking HTTP — no screen driving.

## 4. Files, fences, order and the gate budget

| brief | touches | never touches |
|---|---|---|
| B1a | `A/api/{AttachmentHandlers,AttachmentDtos,ApiRouter,ApiHandlers,ApiJson,ApiDtos}.kt`; `A/prefs/InstallationIdentity.kt` (new); `A/di/AppGraph.kt` (the `InstallationIdentity` wiring); `C/usecase/AssetCommands.kt` (one keyword); `T/api/AttachmentRoutesTest.kt`; `T/prefs/InstallationIdentityTest.kt`; `T/testing/FakeGraph.kt`; `T/api/{ApiRouterTest,LoopbackApiServerTest,MaintenanceCommandShapeTest,MaintenanceFixtures,MaintenanceRoutesTest,ReferenceRoutesTest}.kt`, `T/VersionAgreementTest.kt`, `T/ui/api/DeveloperApiViewModelTest.kt` (the C1 ripple, mechanical) | `C/model`, `C/backup`, every use case body, `docs`, `tools` |
| B1b | `A/api/{HttpWire,ApiRouter,LoopbackApiServer,AttachmentHandlers,AttachmentDtos,AttachmentOperationIds,ReferenceHandlers}.kt` (the last for its KDoc only); `A/di/AppGraph.kt` and `T/testing/FakeGraph.kt` (C33's lock); `C/usecase/AddAttachment.kt` (the preset id only); `docs/api/attachment-operation-ids.json` (the golden vector); `T/api/{HttpWireTest,LoopbackApiServerTest,ApiRouterTest,AttachmentUploadRoutesTest,AttachmentOperationIdsTest}.kt`; `CT/usecase/AttachmentUseCasesTest.kt` (+3) | `A/attachments/*`, `A/fetch/*`, `MaterializeReference.kt`, the rest of `docs` |
| B2-pre | `.superpowers/sdd/2026-09-30-issue-92/threat-review-materialize.md` only (no commit) | everything else |
| B2 | `A/api/{AttachmentHandlers,ApiRouter,LoopbackApiServer,ApiJson}.kt`; `C/usecase/MaterializeReference.kt` (`AlreadyHave`); `T/api/MaterializeRoutesTest.kt`; the four test constructions | `C/fetch/*`, `A/fetch/*`, `A/ui/**`, docs |
| B3 | `A/api/{ReplaceHandlers,ReplaceDtos,ApiRouter,ApiHandlers,ApiJson}.kt`; `T/api/{ReplaceRoutesTest,SuccessionRoutesTest}.kt`; `T/api/{ApiRouterTest,LoopbackApiServerTest,MaintenanceCommandShapeTest,MaintenanceFixtures,MaintenanceRoutesTest,ReferenceRoutesTest}.kt`, `T/VersionAgreementTest.kt`, `T/ui/api/DeveloperApiViewModelTest.kt` (the C1 ripple, mechanical) | `C/usecase/ReplaceAsset.kt`, `C/usecase/ReplaceSetup.kt`, `A/ui/replace/*` |
| B4 | `docs/api/{v1.md,command-shapes.json}`, `README.md`, `app/src/main/AndroidManifest.xml` (comment), `docs/release-proofs.md` (a note), `M/README.md`; `T/api/{CommandShapesGoldenTest,ReferenceRoutesTest}.kt` | any `.kt` under `src/main` |
| B5a | `M/src/servicetag_mcp/{server,client,command_shapes}.py`; `M/tests/{test_tools,test_argument_guard,test_command_shapes,test_attachment_tools}.py` | the app, `S/`, the replace tools |
| B5b | `M/src/servicetag_mcp/server.py` (the two replace tools); `M/tests/{test_replace_tools,test_tools,test_argument_guard}.py` (74 → 76) | the app, `S/`, the attachment tools |
| B6 | `S/src/servicetag_schedules/{manifest,phone,plan,apply,cli}.py`, `S/README.md`, `S/tests/*` | `M/src`, the app |

**Order:** B1a → B1b → B2-pre → (sign-off) → B2 → B3 → B4 → B5a → B5b → B6 (B1a creates the installation id before B1b's upload;
B2 needs B2-pre's signed-off review; B5a needs B4's golden file; B6 needs B5b's tools). B2-pre may run beside B1b (read-only, the owner's two-lane rule), but B2 waits
for its sign-off. **Gate budget** at the merged tip: core ≈ 1654 + 4, app ≈ 1586 + ~115 in six new classes and five
extended ones (C33's three socket rows in `LoopbackApiServerTest`), MCP 384 + ~55 at 76
tools, schedules + ~12; device classes **55, unchanged**; the whole time reported against #85's baseline (11.32 min
whole, 10.32 device), reporting only, no rerun-until-green.

## 5. Strings

**No user-visible string.** Nothing on the phone changes: no Compose literal, no `strings.xml` entry, no screen. The
API returns codes; the `message` sentences in C2 are developer-facing wire text on the owner's loopback, on the
precedent of every shipped `/v1` message, and are listed verbatim there for the plan review. The MCP-facing texts are
C28's `APP_ROUTE_MISSING: …`, C27's local `OPERATION_KEY_REUSED: …` refusal and `APP_SCHEMA_TOO_OLD: …` (the shipped
wording with the tool's name and "schema 16"), and the tools' `CREATED` / `REPLAYED` / `IDENTICAL` results. B2-pre's
threat review is a ledger document, not a string. If the owner wants any of these ratified as strings, C2, C27 and
C28 are the table.

## 6. Owner rulings (owner, 2026-09-30 — R92-1…8 DECIDED, recorded on issue #92)

| ruling | decision, binding as written | where it lands |
|---|---|---|
| **R92-1** | **YES.** API Replace **explicitly supersedes R86-18**; it reuses #86's canonical atomic replacement use case, its reviewed selections and the source-digest guard. | B3's preamble, C19–C24 (C22's order, C23's digest); `v1.md` (C25); `replace_asset` (C27); B6; rows 25–32 |
| **R92-2** | **YES.** Tag moves over the API only for **individually selected bindings**; a **metadata re-target, never an NFC hardware write**. | C20's `movedTagIds` row, C22's tag paragraph, C31's `moveTags`; row 28b and its grep; B3's Must NOT |
| **R92-3** | **YES, conditional.** Supersedes **R85-10** for an operation on an **existing `AssetReference` by id only** — never a supplied URL. **The deferred API-boundary threat review is completed before the materialize brief is implemented**; B2 depends on its sign-off. The synchronous route preserves #87's non-interruptible durable commit (R87-4). | B2-pre (TR1–TR13, its sign-off), C14 (no URL key, the hand-off), C15 (sync), C16 (R87-4 on the wire), C33; rows 20, 23, 23b, 44, 45; §4's order; §11–§12 |
| **R92-4** | **YES.** The list and get routes return `sourceUri` and the other structured provenance, **documented as sensitive**; the phone's never-render rule is unchanged. | C8; C25's sensitive marking; C27's docstrings; limit 5; row 6 |
| **R92-5** | **YES.** A canonical `DocumentRole` may be set at creation; **no filename or MIME inference** of the role. | C11 (the fragment and its note), C27's `add_attachment`; row 13 |
| **R92-6** | **REVISED.** Not a blanket same-hash refusal: **request-level idempotency** by a stable, owner-scoped **operation key** tied to the payload and the meaningful metadata (owner, kind, role, displayName, sha256, size). An exact replay returns the original attachment (**200, same id**); a reuse of the key with a different payload or metadata fails (**409 `OPERATION_KEY_REUSED`**); a separate upload of identical bytes under a **new key succeeds**. | C13 (stored in the row's own id — **no schema change**; lifetime = the row's; the table alternative rejected with its schema-17 cost), C12 steps 2 and 5, C2's two codes, limit 7, C27's `add_attachment` (payload-only default key, review C-5), C33; rows 14, 17, 35, 43 |
| **R92-7** | **STRICT.** After an upload-created attachment has been edited, a replay carrying the stale or original metadata under the same operation key → **409 `OPERATION_KEY_REUSED`**; an unchanged replay, or one whose meaningful metadata equals the row's **current** metadata with the same payload → **200** with the row. Metadata changes belong to `PATCH`. The relaxed alternative and rev 1.1's limit 7 are dropped. | C13's replay clause, C2's `OPERATION_KEY_REUSED` row, C27's local check; rows 14, **14b**, 35 |
| **R92-8** | **The collision is prevented in the server-side id namespace, not by the MCP key.** An opaque, random **ServiceTag installation id** (`installationId`): stable across restarts and upgrades, device-local, never in a backup, export, merge or Transfer Pack, exposed additively in the authenticated `GET /v1/status`, not an adb serial, Android id, model, hostname or hardware identifier, not authentication material. Upload attachment ids derive from `installationId + assetId + operationKey`. The MCP's default operation key stays (asset, sha256, size). Rev 1.1's limit 8 and its alternative are struck. | C5a (created by B1a, before the upload brief; one file under `noBackupFilesDir`; no Room or backup-format bump), C13's derivation, C27; rows **46**, 17, 35; B1a's greps |

**Mechanical corrections (coordinator, 2026-09-30).** (1) Authenticate before streaming: the token is checked on the
headers and an unauthenticated body is never read past the bounded drain (C10; rows 9, 9b). (2) The eight operations
against the seven tools: one tool per operation except `replace_asset`, which carries replace-plan and replace (C27's
mapping table and its reason).

**The plan review's conditions (2026-09-30, `plan-review.md`) — where each landed.** C-1 → C33 (the lock, the
re-check, the compare-and-clear slot), C12 steps 2–3, C14 steps 3–6, C16, TR4, TR7, TR10; rows 43–45. C-2 → C13's
preset id; row 17. C-3 → C12 step 3 and the deadline paragraph; row 16. C-4 → C9; row 7; B1b's Stop. C-5 → C27's default
key and local check; row 35. C-6 → R92-7 (DECIDED, strict), C13, row 14b. C-7 → R92-8 (DECIDED, server-side namespace), C5a, C13, row 46. C-8 → C27's `materialize_reference`; row 37. C-9 →
C14 steps 5–6; row 23b. C-10 → C23; row 32. C-11 → C1; §4; B1a and B3 pin lists. C-12 → C18, C25; B4's grep. C-13 →
TR1, TR3, TR9–TR13. C-14 → the greps of B1b, B2, B5a. Notes m1 → row 9b; m2 → row 23b; m3 → C12 step 1, C27; m4 →
`ATTACHMENT_SHA256_INVALID`; m5 → limit 2; m6 → row 6; m7 → C9's KDoc list; m8 → C32; m9 → Global constraints; m10 →
C2's 502 row, C25; m11 → C27, C29, row 35; m12 → C23, row 32; m13 → limit 7, D1; m14 → B5a/B5b; m15 → D1.

Placement: the owner moved #92 ahead of #90 (§8).

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green with zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; B5a, B5b and B6 their `uv run --frozen pytest`; B4 the app JVM suite (the
  document pins). **C33's clause and rows 43–45 get the one scoped re-review** (review C-1); every other condition
  closes by controller inspection.
- **The merged-tip gate, once:** R1 (JVM from scratch), R2 (the connected suite: **55 classes, unchanged**, zero
  skips), R3 (the three Python suites), R5 (`ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest`
  — unchanged), R6 greps. `ReleaseProofPolicyTest` **unchanged** and green. Timed; the 14- and 15-minute lines are
  reporting only.
- **D1, the development-phone proof (release acceptance, not the gate; the production phone never).** The owner sets a
  long screen timeout (limit 7; the setting is recorded in the evidence), opens Developer API on the dev phone; the
  MCP, and nothing else, runs: `list_attachments` on a test asset (`folder:
  READY`); `add_attachment` of a locally generated fictional PDF of about 50 MiB with `role=USER_MANUAL` (201; the row's
  sha256 equals the local one); the same call again (REPLAYED, no POST); the same file under a new `operation_key` (a
  second row); `update_attachment` to `kind=MANUAL`; `materialize_reference` on one existing https reference the owner
  chooses (201 with its source keys), then again (IDENTICAL); `replace_asset(plan_only=True)` then `plan_only=False`
  on a disposable test asset, moving one of its tag bindings (the tag is not written; a later scan opens the
  successor); `get_asset_succession`. It is an acceptable device proof, not a gap (review m15): the SAF `put` is the
  unchanged `AddAttachment` → `SafTreeAttachmentStore` path, and what no JVM test reaches is `adb forward` throughput
  under a long body and a real https fetch started off-screen. C33's generation overlap is **not** a device fact: it is
  proven on the JVM (rows 43–45). Evidence beside the release record.
- **What these routes turn into API/JVM proofs:** seeding attachments of every kind and every role (the 1.5.0 gate
  needed a staged merge and the sheet, `docs/release-proofs.md:107`, `:114`), a Replace in a gate, and the
  materialization outcomes. The **sheets** stay UI-proven (`ReplaceAssetFlowTest`; the Materialize sheet's
  person-tapped smoke): the routes prove the outcomes, not the screens.

## 8. Relationships

- **#76 — the sequence of record (owner, 2026-09-30): 1.5.0 SHIPPED → #92 → #90 → Phase 2 (#15 → #47 → #69).** #92
  runs on today's harness and gate (the 55-class connected suite, `tools/emulator/*` as shipped); no brief waits for,
  or relies on, a #90 change, and #90 then starts from #92's merged tip. Not on a release train; no version bump in this
  plan.
- **#90:** after #92; nothing here edits the harness, the gate script or `tools/emulator/*` (the common Must NOT), so
  #90 inherits #92's JVM and pytest rows unchanged.
- **#42** (equipment research → owner-reviewed import draft) lands a reviewed draft's documents through
  `add_attachment` and `materialize_reference`; it should wait for this.
- **#69** (supplies and components as owners; Phase 2, after #15 and #47) extends `AttachmentOwner`; the path shape
  `/v1/<owner>/{id}/attachments` and C12's handler generalise; the owner check stays `accepts`.
- **#91** (a role on references): `reviewPrefill` copies it, so the materialize route inherits it with no change.
- **#66 / P85-10:** the network denial stays a phone sentence; over the API it is `NETWORK_DENIED`, which a running
  listener cannot in practice produce.

## Briefs — common to all nine

Read §1–§8, the audit, issue #92 and every earlier report on this branch. B2-pre is read-only: of the rules below only
its own Read, Stop and Must NOT lines apply to it.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun`, zero failures and skips;
`:app:compileDebugAndroidTestKotlin`. Then the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`, over
`app/src/main` and `core/src/main` unless named); the untouched diff; the tombstone check → 0; `git ls-tree HEAD
libs/nfc-tag-core` → `7e0377a…`; each → 1: `'^\s*versionName = "1\.5\.0"$'`, `'^\s*versionCode = 18$'` in
`app/build.gradle.kts`; `git diff <base> -- app/build.gradle.kts core/build.gradle.kts tools/*/pyproject.toml
tools/*/uv.lock` → empty; `git diff <base> -- app/src/main/AndroidManifest.xml` → empty except in B4 (comment lines
only); `git status` clean.

**Pin rule.** A shipped assertion pinning a route's absence, a tool count, a sub-resource count, a document's wording
or a command's keys moves only where the brief's pin list names it. Confirm the set first with `git grep -nE
'/replace|/attachments|materialize|twenty-one|69|TOOL_NAMES' -- app/src/test tools/servicetag-mcp/tests`.

**Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a row with a natural RED is
committed test-first. **Report.** Every counted RED with its quoted failure; every row without one, and why; each
grep with its count; the wall time against the box; what is done, proven and not proven.

**Stop and report, always:** an edit to an untouched file; an assertion that must move outside the pin list; a JVM or
build failure the brief did not cause (reported, not retried); any network access from a test; a phone string of any
kind; a schema, format, table or count change; the cap reached, the 1-hour target passed with under half the rows
green, or the 2-hour hard stop.

**Must NOT, always:** delete a shipped assertion; commit outside the brief's files; add a device class or case or run
a device; bump a version; write a repository from a handler; call a use case's `run` twice in one request; log a URI,
header value, filename or byte; name a screen-driving tool anywhere under `tools/`; touch `tools/emulator/*`, a harness
helper or the gate script; add a dependency; write a real name, host or serial (fixtures: `example.invalid`,
`203.0.113.0/24`).

## 9. B1a — attachment reads and edits, and the installation id (C1, C2's attachment rows, C5–C8, C5a; app JVM)

**Creates the installation id before the upload brief (R92-8).** **Read:** audit §1, §3, §5;
`A/api/{ApiRouter,ApiHandlers,ApiJson,ApiDtos,ReferenceHandlers}.kt`; `A/prefs/AppPrefs.kt`; `A/di/AppGraph.kt`;
`C/backup/BackupCodec.kt`; `A/transfer/TransferPackWriter.kt`; `C/transfer/TransferPackCodec.kt`;
`C/usecase/{UpdateAttachment,AttachmentCommands}.kt`; `C/model/Attachment.kt:6-54`; the eight ripple files (C1).
**`<base>`** = master at dispatch (today `5b60788a` plus the rev 1.2 commit). **Rows:** 1–6, 46. **Rulings:** R92-4, R92-8.

**Connected:** none. **Greps:** `'^internal class AttachmentHandlers\('` → 1; `'attachmentRoutes'` in `ApiRouter.kt` →
3; `'"attachments" to "GET"'` in `ApiRouter.kt` → 1; `'\.upsert\('` in `AttachmentHandlers.kt` → 0;
`'updateAttachment\.run\('` → 1 there; `'^fun isIsoDate'` in `C/usecase/AssetCommands.kt` → 1; `'accepts\('` in
`AttachmentHandlers.kt` → 1; `'val installationId: String'` in `ApiDtos.kt` → 1; `'noBackupFilesDir'` in `AppGraph.kt` →
1; `'SecureRandom'` in `A/prefs/InstallationIdentity.kt` → 1; **`'installationId|InstallationIdentity'` over `C/backup`,
`C/transfer` and `A/transfer` → 0** (the backup codec and the pack writer never name it);
`'Settings\.Secure|ANDROID_ID|getSerial|Build\.SERIAL|Build\.MODEL'` over `A/` → 0. **Pin list:** the C1 ripple, all eight files: `T/api/{ApiRouterTest,LoopbackApiServerTest,MaintenanceCommandShapeTest,MaintenanceFixtures,MaintenanceRoutesTest,ReferenceRoutesTest}.kt`, `T/VersionAgreementTest.kt`, `T/ui/api/DeveloperApiViewModelTest.kt` — ten explicit constructions gain
`attachmentHandlersFor(graph)` (mechanical); any status-shape pin the additive `installationId` moves is reported, not
silently edited. **Untouched:** `C/**` but the one keyword; `A/ui/**`; `docs`; `tools`. **Must NOT:** add a delete
route; answer a folder's name or authority; let a role on an event row reach `UpdateAttachment`; derive
`installationId` from any Android or hardware identifier, force UUID version bits on it, keep it in
`SharedPreferences`, log it, treat it as a credential, or let it into a backup, export, merge or pack; bump the Room
schema or the backup format. **Counted RED (6):** rows 1, 3, 4, 5, 6, 46. **Caps:** 7 JVM mutation runs; **1 h target,
2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 190 production, 360 test lines.

## 10. B1b — the upload and the long-write lock (C9–C13, C33's upload half; core and app JVM, real JVM sockets)

**Read:** audit §0 fact 1, §1, §3, §7 risk 1; the plan review's C-1…C-4; C5a (B1a's installation id);
`A/api/{HttpWire,LoopbackApiServer,ApiRouter}.kt`; `A/ui/api/DeveloperApiViewModel.kt:40-80`;
`C/usecase/{AddAttachment,AttachmentCommands}.kt`; `C/ports/IdGenerator.kt`; `A/attachments/SafTreeAttachmentStore.kt:37-80`;
`A/fetch/CacheStagingArea.kt`; `A/share/ShareIntakeViewModel.kt:350-385`; `T/api/{HttpWireTest,LoopbackApiServerTest,
ApiRouterTest}.kt`; `CT/usecase/AttachmentUseCasesTest.kt`; `T/testing/FakeAttachmentStorage.kt`; B1a's report.
**`<base>`** = B1a's tip. **Rows:** 7–17 with 9b, 14b, 43. **Rulings:** R92-5, R92-6 (revised), R92-7, R92-8,
mechanical correction 1.

**Connected:** none. **Greps:** `'MAX_UPLOAD_BYTES'` over `A/api` → **2** (the definition and `bodyCapFor`'s one use);
`'fun bodyCapFor\(method: String, path: String\)'` → 1; `'readExactly\('` in `HttpWire.kt` → 2 (unchanged);
`'multipart'` over `A/api` → 1 (the reworded KDoc line, quoted); `'presetId'` in `AddAttachment.kt` → quoted, and
`git diff <base> -- C/usecase/AddAttachment.kt` shows only the preset-id lines, the `require` above `store.put`;
`'addAttachment\.run\('` in `AttachmentHandlers.kt` → 1; `'fun attachmentOperationId'` over `A/api` → 1;
`'val apiLongWrites'` in `A/di/AppGraph.kt` and in `T/testing/FakeGraph.kt` → 1 each; `'apiLongWrites\.withLock'` in
`AttachmentHandlers.kt` → 1; `'sameBytes'` over `A/api` and `C/usecase` → 0 (no same-hash refusal on the upload);
`'role\s*=.*(inferFrom|endsWith|mime)'` over `A/api` → 0 (no inferred role); `'catch \(e: IOException\)'` in the upload
handler → 0 (the two failure sources are told apart by stream); `'materializeStaging'` in `AttachmentHandlers.kt`
(or its fixture wiring) → ≥ 1; `git diff <base> -- A/attachments A/fetch C/usecase/MaterializeReference.kt` → empty;
every shipped `HttpWireTest`, `LoopbackApiServerTest`, `AttachmentUseCasesTest` and `MaterializeReferenceTest` case
green and unchanged except the listed pins. **Pin list:** `ApiRouterTest.kt:760-765` and `HttpWireTest`'s `caps` lambda
gain the method argument (mechanical); the five false KDocs (C9) are reworded. **Untouched:** the store, staging,
`MaterializeReference.kt`, `A/ui/**`, docs but the golden vector, tools. **Must NOT:** read, stage, hash or decode an
upload body before the token check; raise the drain budget; hold an upload in a byte array; answer an authenticated
request before its declared body is consumed; map a request-stream failure to 409 or 500; refuse identical bytes
under a new key; infer a role; accept a `source` from the wire; add a table or an `AttachmentProblem` member; give a
non-`POST` method the 256 MiB tier; widen any other request's cap; hold the lock in the router or the server.
**Counted RED (12):** rows 7, 8, 9, 9b, 10, 12, 13, 14, **14b (R92-7)**, 15, 17, **43 (C33: the lock removed)** (row
16's RED counts as a thirteenth if the cap allows). **Caps:** 13 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs,
45 min. **Stop also** if the stream seam needs `ApiRequest` to become a data class, or if the preset id needs more than
a defaulted parameter in `AddAttachment`. **Size:** about 230 production, 520 test lines — **if the 1 h target passes
with under half the rows green, stop: the controller splits the operation key (C13, rows 14, 14b and 17) into a B1c.**

## 11. B2-pre — the API-boundary threat review (TR1–TR13; read-only, ~45 min, no commit)

**Read:** audit §4 (its threat paragraph is the starting point, not the review); the plan review's C-1, C-9 and C-13;
#85 plan §1 (`:196-197`), Global constraints (`:41-112`), §7 (R85-4…15), §19 and its recorded limits (`:1471-1519`);
#87 plan §12 R87-4 (`:439-449`); `C/usecase/MaterializeReference.kt`; `C/fetch/*`; `A/fetch/*`;
`A/api/{ApiRouter,HttpWire,LoopbackApiServer,PairingCode}.kt`; `A/ui/api/DeveloperApiViewModel.kt`;
`A/ui/references/MaterializeViewModel.kt`; `A/attachments/SafTreeAttachmentStore.kt:37-80`; `docs/api/v1.md:21-105`;
this plan's C14–C18 and C33. **`<base>`** = B1b's tip (or B1a's `<base>` if run beside B1b; nothing it reads
differs but C33's lock, which it then reviews as written in C33). **Rows:** none. **Rulings:** R92-3.

**Deliverable:** `.superpowers/sdd/2026-09-30-issue-92/threat-review-materialize.md`, one section per TR1–TR13 (§2
B2-pre's table), each with the threat, the control and its `file:line`, what #92 changes, and a verdict: holds /
holds with a B2 condition / blocks. It ends with the list of B2 conditions (each traceable to a C14–C18 or C33 clause
or a test row, or named as a new one for the controller to add), TR9's answer on the docstring (confirmation or host
echo), TR12's answer on the `sourceUri` dedupe, and one line: "B2 may proceed" or "B2 is blocked by TRn".
**Sign-off:** the controller reviews it against the table and records the verdict in the ledger; the owner's
acknowledgement of R92-3's condition is recorded beside it. **Connected:** none. **Greps:** none required; every
claim cites a line. **Untouched:** everything but the one ledger file; no commit. **Must NOT:** edit code, tests,
docs or the plan; run Gradle, a device or the network; soften a #85 restriction to fit the route; propose a URL
argument. **Stop and report** at a "blocks" verdict, or at 45 minutes with what is written and what is not.
**Caps:** **45 min target, 1 h hard stop**. **Size:** about 160–220 document lines.

## 12. B2 — save as document (C14–C18, C33's materialize half; core and app JVM, real JVM sockets)

**Precondition:** the ledger records B2-pre's sign-off (the controller's verdict "B2 may proceed" and the owner's
acknowledgement). Without both lines, B2 is not dispatched. Any B2 condition the review added is part of this brief.

**Read:** audit §4; **B2-pre's threat review**; the plan review's C-1 and C-9; `C/usecase/MaterializeReference.kt`;
`C/fetch/{FetchProblem,FetchPorts,FetchDocument}.kt` (`FetchDocument.kt:15-60`);
`A/ui/references/MaterializeViewModel.kt:130-215` (and R87-4's `save()`, #87 plan `:439-449`);
`A/api/LoopbackApiServer.kt:216-352`; `T/testing/{FakeGraph,FakeDocumentTransport}.kt`; #85 plan §7 R85-6/8/9/10 and
§19's limits; B1b's report. **`<base>`** = B1b's tip. **Rows:** 18–24 with 23b, 44, 45. **Rulings:** R92-3
(conditional).

**Connected:** none. **Greps:** `'materializeReference\.(prepare|commit|discard)\('` in `AttachmentHandlers.kt` → 3
(quoted); `'reviewPrefill\('` there → 1; **`'withContext\(.*NonCancellable'` there → 1** (the import makes the bare
name 2, as `MaterializeViewModel.kt` has today); `'apiLongWrites\.withLock'` in `AttachmentHandlers.kt` → 2 (upload and
materialize); `'compareAndSet'` in the download slot → ≥ 1 (quoted); `'references\.(upsert|delete)'` over `A/api` → 0;
`'val attachmentId: AttachmentId'` in `MaterializeReference.kt` → 1; **`'"FETCH_[A-Z_]+"'` in `ApiJson.kt` → 12**;
`'"(uri|url)"'` in the materialize request DTO → 0; `git diff <base> -- C/fetch A/fetch A/ui` → empty. **Pin list:**
the four `AlreadyHave(` constructions (`CT/…/MaterializeReferenceTest.kt` ×3, `T/ui/references/MaterializeViewModelTest.kt`
×1) gain the id. **Untouched:** every fetch, staging and UI file; docs; tools. **Must NOT:** accept a URL, host or path
from the wire; start a fetch before the name and held checks; cancel anything but a `prepare`; let `stop()` or a
disconnect reach a commit that began; commit after a `stop()` that landed before it; leave an unspent `Ready`
undiscarded; clear the download slot unconditionally; add a job registry or a poll route; log or answer a URI.
**Counted RED (10):** rows 18, 19, 20 (2), 21, 22, 23, 23b, 44, **45 (C33: the slot cleared unconditionally)**.
**Caps:** 11 JVM mutation runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Stop also** if C16 needs
the router's `runBlocking` itself to become cancellable, or if a B2-pre condition cannot be met inside C14–C18 and C33.
**Size:** about 170 production, 480 test lines.

## 13. B3 — replace (C19–C24; app JVM)

**Read:** audit §2; the plan review's C-10; `C/usecase/{ReplaceAsset,ReplaceSetup}.kt`; #86 plan §2 B2 (C8–C15) and
R86-3…18; `A/ui/replace/ReplaceAssetViewModel.kt:150-410`; `A/api/{ApiDtos,ScheduleRowResponse,ValidationRefusals}.kt`;
`C/backup/BackupFormat.kt:297-356` and the `toDomain` mappers; `T/api/SuccessionRoutesTest.kt`;
`CT/usecase/ReplaceAssetTest.kt` (its fixtures); B2's report. **`<base>`** = B2's tip. **Rows:** 25–32 with 28b.
**Rulings:** R92-1 (supersedes R86-18), R92-2.

**Connected:** none. **Greps:** `'replaceAsset\.run\('` over `A/api` → 1; `'replaceAsset\.(offer|plan)\('` → quoted;
`'fun sourcesDigest'` → 1; `'is ReplaceStale ->'` in `ApiJson.kt` → 1; `'bindTag|BindTag'` over `A/api` → 0;
`'Ndef|NfcAdapter|IsoDep|MifareUltralight|writeNdef'` over `A/api` → 0; `git diff <base> -- C/ A/ui` → empty. **Pin
list:** `SuccessionRoutesTest.aPostIs404` loses its one `POST …/replace` entry; the C1 ripple, all eight files: `T/api/{ApiRouterTest,LoopbackApiServerTest,MaintenanceCommandShapeTest,MaintenanceFixtures,MaintenanceRoutesTest,ReferenceRoutesTest}.kt`, `T/VersionAgreementTest.kt`, `T/ui/api/DeveloperApiViewModelTest.kt` — ten explicit constructions gain
`replaceHandlersFor(graph)` (mechanical). **Untouched:** `C/**`, `A/ui/**`, docs, tools. **Must NOT:** re-implement a
replacement step; default `scheduleStartOn`; accept a successor description, notes, template key or season pair;
accept an "all", label or pattern form of tag selection; touch NFC hardware or any tag I/O; leave the draft out of
the digest; write before the digest and the problems pass; tick anything for the caller. **Counted RED (9):** rows 25,
26, 27, 28, 28b, 29, 30, 32 (2: the setup rows, the draft). **Caps:** 10 JVM mutation runs; **1 h target, 2 h hard
stop**; fix round 3 runs, 45 min. **Stop also** if a `toDto().toDomain()` round trip loses a field the stale rule
compares. **Size:** about 200 production, 450 test lines.

## 14. B4 — the wire documents (C18, C25–C26; docs and app JVM pins)

**Read:** `docs/api/v1.md` (whole), `docs/api/command-shapes.json`, `README.md:35-90`,
`app/src/main/AndroidManifest.xml:1-13`, `docs/release-proofs.md:100-115`, `M/README.md`;
`T/api/{CommandShapesGoldenTest,ReferenceRoutesTest}.kt`; `T/VersionAgreementTest.kt:300-315`; B1a–B3's reports and
B2-pre's review. **`<base>`** = B3's tip. **Rows:** 33. **Rulings:** R92-1…8.

**Connected:** none. **Greps:** in `docs/api/v1.md`, each C2 code → ≥ 1; the phrase "twenty-five" before the asset
sub-resource path (the `ReferenceRoutesTest` pin's shape) → 1; **`'only when (you|the owner) taps?'` over `README.md`,
`docs/api/v1.md` and `app/src/main/AndroidManifest.xml` → 0**; `'never connects out'` in `docs/api/v1.md` → 0;
`'no route here and no MCP tool reaches'` → 0; `'installationId'` in `docs/api/v1.md` → ≥ 1; `git diff <base> -- app/src/main/AndroidManifest.xml | grep -cE
'^[-+]\s*<(uses|application|activity|receiver|provider|service)'` → 0. **Pin list:** `CommandShapesGoldenTest` (+5
commands, the code names); `ReferenceRoutesTest`'s sub-resource pin; nothing in `VersionAgreementTest`.
**Untouched:** every `.kt` under `src/main`; tools. **Must NOT:** rewrite the 1.5.0 gate paragraph or its erratum;
document a route or code the branch does not answer. **Counted RED (1):** row 33. **Caps:** 2 JVM mutation runs;
**1 h target, 2 h hard stop**. **Size:** about 300 document lines, 40 test lines.

## 15. B5a — the MCP: gates, client and the attachment tools (C27's five, C28–C30; pytest)

**Read:** audit §1 (the MCP), §6; C5a and C13 (the installation id and the derivation over it); the plan
review's C-5, C-7, C-8 and m11; `M/src/servicetag_mcp/{server,client,command_shapes}.py`; `M/tests/{conftest,
test_succession_tools,test_reference_tools,test_tools,test_argument_guard,test_command_shapes}.py`; B4's golden file and
`docs/api/attachment-operation-ids.json`. **`<base>`** = B4's tip. **Rows:** 34–36, 37 (its attachment half), 38, 39.
**Rulings:** R92-3…8.

**Connected:** none. **Greps:** `'^@mcp\.tool'` in `server.py` → 74 (76 after B5b); **`'_MIN_ATTACHMENT_SCHEMA_VERSION'`
→ 2** (the constant and its one use); **`'_require_attachment_schema\('` → 6** (its definition and the five tools;
8 after B5b); `'APP_ROUTE_MISSING'` → ≥ 1; **`'"Transfer-Encoding"\s*:'` in `client.py` → 0**; `'def
_attachment_operation_id'` → 1, held to the golden vector; `'uiautomator|input tap|input text|dumpsys'` under `tools/`
→ 0. **Pin list:** `test_tools.py` (69 → 74, the five names), `test_argument_guard.py`, the vendored shapes. **Untouched:** the app; `S/`; the replace tools. **Must NOT:** send
bytes before a GET; put name, kind or role in the default key; compare locally by any rule but C13's; resend a
consumed body on retry; guess a role; offer a URL argument on `materialize_reference`; auto-retry a materialize; read
an upload into memory; derive a row id any way but the golden-vector function; change another tool's behaviour.
**Counted RED (4):** rows 35 (2: chunked streaming; metadata in the default key), 36, 38. **Caps:** 5 pytest mutation
runs; **1 h target, 2 h hard stop**; fix round 3 runs, 45 min. **Size:** about 260 production, 400 test lines.

## 15b. B5b — the MCP: the replace tools (C27's two; pytest)

**Read:** B5a's report; `M/tests/test_tools.py`; C19–C24. **`<base>`** = B5a's tip. **Rows:** 37 (its replace half).
**Rulings:** R92-1, R92-2.

**Connected:** none. **Greps:** `'^@mcp\.tool'` in `server.py` → 76; `'_require_attachment_schema\('` → 8;
`'plan_only: bool = True'` in `replace_asset`'s signature → 1. **Pin list:** `test_tools.py` (74 → 76, the two names),
`test_argument_guard.py` if it lists tools. **Untouched:** the app; `S/`; the five attachment tools.
**Must NOT:** apply a replace plan with problems or without its own digest; offer an "all" tag form. **Counted RED
(1):** row 37 (apply on a plan with problems). **Caps:** 2 pytest mutation runs; **1 h target, 2 h hard stop**.
**Size:** about 110 production, 180 test lines.

## 16. B6 — the loader (C31–C32; pytest)

**Read:** `S/README.md`; `S/src/servicetag_schedules/*.py`; `S/tests/*`; the #86 plan's R86-8 (the name prefill); the
plan review's m8; B5b's report. **`<base>`** = B5b's tip. **Rows:** 40–42. **Rulings:** R92-1, R92-2.

**Connected:** none. **Greps:** `'replacements'` in `manifest.py` → ≥ 1; `'replace_asset'` in `apply.py` → 1 and in
`plan.py` → 0 (the plan is pure); no print of an id (the shipped rule). **Untouched:** `M/src`, the app, the other
tools. **Must NOT:** resolve identity by name alone; evaluate CREATE before IDENTICAL, or treat a successor as a
CREATE candidate; write when the plan is unclean; bump `manifestVersion`. **Counted RED (3):** rows 40, 41, 42.
**Caps:** 4 pytest mutation runs; **1 h target, 2 h hard stop**. **Size:** about 170 production, 280 test lines.
