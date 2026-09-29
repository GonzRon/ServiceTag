# #77 — transfer selected assets to another ServiceTag installation: plan and briefs (rev 2.1, 2026-09-28)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> Six briefs (§9–§13) on one branch `issue-77`, strictly sequential — B1, B2a, B2b, B3, B4, B5 — each with one task
> review, one batched fix round, at most one scoped re-review; one whole-branch review. Planned read-only from issue #77
> and the audit (`.superpowers/sdd/2026-09-28-issue-77/`) on master 79a1ee3d (schema/format 13 unreleased under 1.4.1,
> MCP 68 tools, eighteen merge tables). **Owner roadmap ruling (2026-09-28):** #90 is deferred from the critical path;
> #77 proceeds at once on the **existing** test infrastructure and waits for nothing; it never touches the device
> harness, `tools/emulator/*` or the gate script, and edits `docs/release-proofs.md` and `ReleaseProofPolicyTest` only in
> B5. **Nothing is dispatched until §6 and the open rulings in §7 are answered.**
>
> **Rev 2 folds in the brief review** (`brief-review.md`: REJECT — 0/11/14/9; architecture confirmed): MJ-1…MJ-10 as the
> controller ruled (MJ-11 void: no #90 merge), every minor and note; the owner's directions — stale resurrection refused,
> explicit transfer back accepted, double mark resolvable (C6, C10, C15, R77-12, R77-25); no ordinary mutation of a held
> asset, every guard's home enumerated (C12, C19, R77-4); the roadmap ruling (§4's budget, R77-22). B2 splits into B2a/B2b.
>
> **Rev 2.1 applies the re-review's conditions** (`brief-re-review.md` §5: RM-1, MJ-10 (iv), rm-2…rm-12); nothing else moved.

**Goal:** the owner selects some assets and creates one immutable Transfer Pack — an outer ZIP around the unchanged
data and artifacts archives — shares it or saves a copy, then separately marks those assets transferred out: archived,
recorded, quiesced in every reminder and actionable projection, inspectable but immutable, and absent from every later
ordinary backup, which carries only the transfer records. A recipient imports the pack additively through the shipped
merge core; a later explicit pack can bring an asset back; no stale archive can.

**Spec:** issue #77 (AC 1–18); SPEC12 (amended by B5); P79 = the #79 plan (C5, C26, §17–§18); P72 = the #72 plan (C9,
C24, R72-10–12, §14); the planning policy with its #72 amendment.

## Global constraints

- **One backup format, one merge engine, one attachment store, one NFC identity.** The pack wraps `BackupCodec.encode`
  and `ArtifactsCodec.write` output byte-for-byte; no DTO fork, no UPDATE verdict, no tag rewrite. The codec and DTOs
  change only by format 14's record list (C7). The one new write shape is C15's scoped replace-on-return.
- **Owner ruling R77-20 binds every brief (§14):** a held asset has no sender-side maintenance, warranty or loan
  reminder and no due, attention, dashboard or health projection, by an explicit record rule independent of ARCHIVED;
  the pre-existing defect (a live schedule on an archived or retired asset still reminding; the false comments at
  `ReminderHealthCheck.kt:276-277` and `ScheduleStatus.kt:72-74`) is corrected inside #77 with standing posts proven down.
- **Schema 14 / format 14** on the #72 precedent: `MIGRATION_13_14`, `Migration13To14Test`, `SCHEMA_VERSION`,
  `VersionAgreementTest`, `FORMAT_VERSION`, `FIRST_TRANSFER_FORMAT = 14`, `LAST_LEGACY_FORMAT` stays 7, the "≤ 13
  carrying one is refused" pattern, `MergeTable` + tallies on both sides of the wire, "nineteen tables"; no `versionName`
  bump; the gate becomes the direct 1.4.1 (schema 8) → 14 proof, written by B5.
- **No manifest change** (R77-2, R77-19): only one `<cache-path>` in `res/xml/file_paths.xml`; no `ACTION_VIEW`, no new
  exported component, no extra read from `MainActivity`'s intent.
- **Strings** §6 verbatim, ratified before dispatch, one home per P77 literal; reused strings through their homes.
- **Tests (R77-22).** JVM first with the existing fakes: view-model state, eligibility, ordering, strings as data and
  every domain rule. This build has no Robolectric or JVM Compose, so a rendered-UI check stays an instrumented Compose
  class on the existing pattern, and only where no JVM test reaches it; platform proofs only at a real Android boundary.
  #77 adds four device classes and one case (§4's budget); device mutations only where a property is observable nowhere
  below the platform, each capped per brief — never ground on the device. Every RED is a real mutation run with
  `--no-build-cache --rerun-tasks` (a `--tests` filter allowed), its failing assertion quoted. One-line commits, gitlink
  `7e0377a`, **no personal data**: fictional fixtures only ("Example Water Heater", "Sample Garage Door Opener",
  "Example Buyer", tag keys `TEST-…`).

## 1. Audit additions (the planner's and the review's findings)

(1) Removal is soft (`SaveGroup.kt:52-58`) and no group delete exists: a group that ever held a retained asset stays
mixed until every staying former member is deleted, which a recorded round usually refuses (`DeleteAsset.kt:28`).
(2) The API reuses the archive's `AssetDto` (`ApiDtos.kt:83`), which merge compares field by field (R77-17). (3) Encode
does not validate (`BackupCodec.kt:159-257`), hence C9's check and C10's M3. (4) The export's artifacts plan comes from the
rows read, not the DTOs (`ExportBackupSet.kt:84-85, :112-119`; MJ-1). (5) `SaveGroup` accepts an empty group
(`GroupCommands.kt:46-60`; MJ-2). (6) `SaveGroup` soft-removes every open member a command omits, and an event may name
another asset's group schedule (`BackupCodec.kt:746-751`; MJ-3). (7) No single choke point exists in the use cases or
the API (four private `asset(id)` resolvers); every write passes through sixteen repository ports, where C12 puts the
guard. (8) `ScheduleCommands.kt:291-294` refuses a group schedule's meter definition or profile and `ApplyTemplate`
writes no schedule (`:106-107`): recorded unreachable, as are REFERENCE attachments (`Attachment.kt:18-19`). (9) The
chooser would offer ServiceTag's own share target, which refuses the app's authority (`AppGraph.kt:472-474`). (10) The
kept-set rule cancels every tag outside it (`DigestPolicy.kt:229-231`): `Withdrawn` or absence takes a post down.

## 2. Behaviour (the briefs' contract)

```kotlin
// core/…/model/TransferRecord.kt (B2a) — append-only; never updated or deleted; soft asset id
enum class TransferKind { OUT, IN, WITHDRAWN }
data class TransferRecord(
    val id: String, val assetId: AssetId, val kind: TransferKind,
    val packId: String,          // OUT, IN: the pack; WITHDRAWN: the OUT's pack it withdraws
    val lineage: List<String>,   // pack ids the asset travelled in before packId, oldest first; [] at origin
    val at: Long, val packSha256: String, val nameSnapshot: String, val note: String,
)
// Pure, B2a. An OUT(q) is OPEN unless an IN here lists q in its lineage or a WITHDRAWN here names q;
// an IN(p) is CURRENT unless an OUT here lists p in its lineage.
fun heldIds(records: List<TransferRecord>): Set<AssetId>                    // assets with an open OUT
fun lineageFor(records: List<TransferRecord>, asset: AssetId): List<String> // current IN's lineage + its packId, else []
// core/…/model/Asset.kt (B2b): the lifecycle predicate stays argument-free; custody sits beside it (decision 27)
val Asset.isInService: Boolean get() = status == AssetStatus.ACTIVE && !isRetired
fun Asset.maintainedHere(held: Set<AssetId>): Boolean = isInService && id !in held
```

**"Wholly" (one definition, MJ-2):** a group is *wholly in* a set iff it has **≥ 1 membership row** and every row,
current or removed, names an asset in the set. An empty group is wholly in nothing.

**The pack (B1).**
- **C1, classification (AC 2, 18).** One table classifies every `BackupData` list: ASSET_OWNED (definitions, profiles,
  events with measurements and consumables, attachments of the asset and its events, references, activations,
  conditions, subjects, asset-targeted schedules with providers and closures, cases and entries, tags whose target is
  the asset, LOST/RETIRED included); CROSS_ASSET (groups, their schedules and closures); GLOBAL_IN_USE (categories);
  SENDER_ONLY (loans; B2a adds records); TOMBSTONE (`externalLinks`, link-targeted tags). A reflection test fails on an
  unclassified list, so a future table — #15's SupplyItems and stock included — needs a decision (AC 18).
- **C2, selection (AC 1–3).** `TransferGraph.select(data, rootIds)`: roots plus every descendant (forced); their
  ASSET_OWNED rows; each group **wholly in** the selection with its schedules and closures; the custom categories in use;
  soft links as-is. Refusals, all collected: `MixedGroup(groupId, stayingIds)` (a group with any row naming a selected
  asset that is not wholly in the selection), `ParentNotSelected`, `OpenLoan`, `OutsideReference` (an event or subject
  naming a non-pack schedule). Output sorted; every `Selected.data` decodes.
- **C3, retain.** `TransferGraph.retain(data, held)` → `Retained(data)` / `Entangled(refs)`: drops the held assets,
  their ASSET_OWNED rows, each group **wholly in** `held` (schedules, closures), and every tag, loan and link naming a
  held asset (returned loans leave later backups: nt-4); any retained row with a hard reference to a dropped row —
  an event naming a dropped group's schedule included — is `Entangled`. Exactly the held set; no recomputed closure.
- **C4, container (AC 1, 14; R77-1).** Outer ZIP, exactly three entries in order, inner ones STORED:
  `transfer-manifest.json` (≤ 64 KiB), `data.zip` (≤ `MAX_PACK_DATA_BYTES` 16 MiB; its entries inflated ≤
  `MAX_PACK_JSON_BYTES` 64 MiB, pre-read bounded before `BackupCodec.decode`), `artifacts.zip` (streamed). Manifest:
  `packFormatVersion` 1, `packId`, `createdAt`, `appVersion`, `schemaVersion`, `dataFormatVersion`,
  `artifactFormatVersion`, `rootAssetIds`, `assetIds`, `lineage` (asset id → pack ids; every pack asset keyed; sorted),
  `counts` (+ `attachments`, `attachmentBytes`), `dataSha256`/`artifactsSha256` (the inner files), `contentSha256` (the
  inner manifest's `dataSha256`, deterministic: sorted rows, no set id or stamp, `BackupCodec.kt:171-229`), `note`
  (≤ 200 characters, one line, default ""). `TransferPackReader.read` answers `NotAPack`, `NewerPack`, `Damaged` (a
  missing, extra, reordered or directory entry; any sha or set-id or format disagreement; `assetIds` ≠ the decoded
  assets; a `lineage` key set ≠ `assetIds`; an artifacts manifest disagreeing with the data's MANAGED rows by id, sha or
  size, either way; an over-cap entry; a bad note; truncation; any `BackupCorrupt`; records present) or `Pack(…)`.
- **C5, create (AC 1, 2, 15).** `readSnapshot(repos)` is extracted from `ExportBackupSet` as a function over the
  repositories that each caller wraps in its own transaction (a read for export and create; the write for C8; mn-8).
  `CreateTransferPack.run(rootIds, note)` selects, encodes the data with the pack id as set id, and returns a draft (the
  manifest fields, data, the artifacts plan of the pack's MANAGED rows); `lineage` comes from a `lineageOf(assetId)`
  parameter (B1 passes `{ emptyList() }`; B2a wires `lineageFor`). Over `MAX_PACK_DATA_BYTES` it refuses (P77-59). It
  writes nothing. B4's `TransferPackWriter` streams artifacts to `cache/transfer-work/`, writes the pack to
  `cache/transfer/`, deletes the work file; any failure or cancellation leaves no file.

**Records, backups and merge (B2a).**
- **C6, schema 14.** `MIGRATION_13_14` = `CREATE TABLE asset_transfer` (the fragment's columns, `lineage` a JSON text;
  `id` TEXT PRIMARY KEY; **no foreign key**) and an index on `asset_id`; nothing else; wired on #72's path. Port
  `TransferRecordRepository`: `append`, `all`, `forAsset`, `heldIds`, `observeHeldIds`, `deleteAll` — no update, no delete.
- **C7, format 14.** `FORMAT_VERSION = 14`, `FIRST_TRANSFER_FORMAT = 14`; `BackupData.transferRecords = emptyList()`,
  sorted by id; `counts` gain `transferRecords` (24 → 25); `< 14` carrying one → `BackupCorrupt`. **Graph check:** ids
  unique; **no asset in the archive is held by the archive's own records**. **Content check:** blank id, asset id, pack
  id or name; a sha not 64 lowercase hex; `at ≤ 0`; a WITHDRAWN without an OUT of that pack in the file; a note over
  200 characters or multi-line.
- **C8, mark (AC 9, 10, 15).** `MarkTransferredOut.run(CreatedPack(packId, packSha256, rootIds, assetIds, lineage,
  contentSha256, note))`, one `uow.write`: blank id or sha refused; `AlreadyTransferred` (P77-57) when any asset is held;
  re-selects over `readSnapshot`, re-encodes and compares `contentSha256`, `assetIds` and `lineageFor` → any difference is
  `PackOutdated(assetId)` (P77-51) — a row added, changed or scanned since creation included; `OpenLoan` (P77-17);
  else each asset `ARCHIVED` + `updatedAt`, `onLifecycleChanged` per asset, each group wholly in the pack archived, then
  one OUT record per asset (`lineage` = the pack's). Nothing else written.
- **C9, backups (AC 11–13).** `ExportBackupSet` applies C3 with `heldIds`, exports `Retained.data` and every record, and
  builds the artifacts plan from **`Retained.data`'s MANAGED rows only** (MJ-1); `Entangled` throws
  `TransferredGraphEntangled` before any byte (P77-58, mapped by B3). An archived asset that is not held exports as today.
  `ImportBackupReplace` wipes and reloads records; `StoreIsEmpty` gains a seventh kind. A Replace restore of a
  pre-transfer backup resurrects the graph and wipes the records (R77-13).
- **C10, merge (R77-12).** `MergeTable` appends `TRANSFERS` after `LOANS` (nineteen tables; snapshot, writes applied
  last, core and API reports gain `transfers`). `MergeReason` appends `ASSET_TRANSFERRED_OUT`, `TRANSFER_DIVERGED`.
  With R' = local records ∪ planned inserts: records by id present → IDENTICAL / CONFLICT `CONTENT_DIFFERS`; absent:
  **M1** an IN or WITHDRAWN that would close an OUT open here → CONFLICT `ASSET_TRANSFERRED_OUT` (an ordinary archive
  never supersedes); an OUT leaving an asset with two open OUTs in R' → CONFLICT `TRANSFER_DIVERGED` (both pack ids in
  the detail, with their short ids as P77-54's file names show them, rm-8); else INSERT — so another install's OUT makes the asset held here too. **M2** any incoming row the rules
  would INSERT whose **owning asset** (C12's ownership function, one home shared with the guard) is in `heldIds(R')` —
  the asset row itself, an event, attachment, condition or tag of a held asset — → CONFLICT `ASSET_TRANSFERRED_OUT`
  naming that asset, so a plan never says "applicable" for an apply the guard would refuse (rm-2). **M3** `retain(snapshot ∪ inserts,
  heldIds(R'))` must be `Retained`; each entangling reference is CONFLICT `ASSET_TRANSFERRED_OUT` on the incoming row that
  introduced it (the inserted row, or the inserted OUT when the referencing row is local). Stop-rule scope: the snapshot
  gains the records and nothing else.

**Quiescence and the write guard (B2b).**
- **C11, quiescence (R77-20).** `targetInService(assetOf, groupOf, held)` takes the held set with **no default** (callers:
  `DueReadModel`, `ReminderHealthCheck`, B2b files): an asset target iff `maintainedHere(held)`, a group target iff not
  archived and not wholly in `held`. `BuildReminderSubjects` reads assets and records (its KDoc rewritten): after the
  ARCHIVED-schedule rule, a target not in service → `Withdrawn` (**correcting the defect** for archived and retired
  assets; both false comments rewritten); archived group stays null. `BuildDeadlineSubjects` drops held assets explicitly;
  `BuildLoanSubjects` drops held assets and keeps R72-10 otherwise. `DueReadModel`, `AttentionReadModel`,
  `DashboardViewModel` (in-service set and loan row), `AssetHealthReadModel`'s fleet and `ReminderHealthCheck` use
  `maintainedHere`. The UI extension `Asset.inService` (`AssetHealthReadModel.kt:412-413`) delegates to `isInService`
  with its signature unchanged; its callers in `AssetViewModels.kt:214, :905`, `WarrantyWords.kt:90`,
  `ChangeConditionViewModel.kt:154`, `Offers.kt:213, :234, :261`, `EventDetailViewModel.kt:107` are detail or sub-screen
  facts that C12 and C19 cover; `git grep -nE 'targetInService\(|\binService\b|isRetired'` sites are listed and
  classified; an edit outside B2b's files stops. The invariant is pinned against a held asset forced ACTIVE.
- **C12, the write guard (R77-4, owner direction).** One home: `core/…/transfer/HeldWriteGuard.kt` wraps the sixteen
  asset-owned repository ports (asset, tag, definition, profile, event, group, schedule, closure, attachment, reference,
  activation, condition, subject, case, entry, loan); each write reads `heldIds` in the caller's transaction and throws
  `AssetTransferredOut(assetId)` when the row's owning asset is held: the asset itself and its `parentAssetId`; a tag's
  new **and current** target; an event, definition, profile, reference, activation, condition, subject, case or loan by
  `assetId`; an attachment by its asset or its event's asset; an entry by its case's asset; a schedule by its asset target
  or a group target with any held row; a closure by its schedule; a group with **any** row, current or removed, naming a
  held asset (so a save that soft-removes held members or adds a retained one is refused). `AppGraph` and `FakeGraph`
  hand every use case the guarded ports. **Exceptions, each tested:** `AssetRepository.delete` (DeleteAsset; records
  kept); an asset upsert changing only `category` and `updatedAt` (the catalog commands `RenameCategory`/`PromoteCategory`);
  the derived and device-local tables (schedule state, the two delivery tables) are not wrapped. `ResolveTag.run` does
  not stamp a held asset's tag. Marking passes (its OUT records are appended last); C15's return appends its IN first.
  Coverage (row 17) drives **every** writing use case against a held asset: asset — `UpdateAsset`, `ArchiveAsset` (both
  directions), `RetireAsset` (both), `SaveAssetSettings`, `SetWarrantyReminder`, `SetSeasonMode`, `SetMaintenanceBreak`,
  `SetHealthPolicy`, `CreateAsset` (held parent); model — `SaveDefinition`, `ArchiveDefinition`, `DeleteDefinition`,
  `ReorderDefinitions`, `SaveProfile`, `ArchiveProfile`, `DeleteProfile`, `ReorderProfiles`, `ApplyTemplate`; journal —
  `LogEvent`, `UpdateEvent`, `DeleteEvent`, `RecordCondition`, `RecordConditionWithIncident`, `RecordSeasonActivation`;
  documents — `AddAttachment`, `UpdateAttachment`, `DeleteAttachment`, `AddReference`, `UpdateReference`,
  `RemoveReference`; maintenance — `SaveSchedule`, `PauseSchedule`, `PostponeSchedule`, `ArchiveSchedule`,
  `CompleteSchedule`, `CompleteGroupMembers`, `CloseRound`, `SaveGroup`, `ArchiveGroup` (both); offers —
  `AcceptImpairmentOffer`, `AcceptOperationalOffer`, `AcceptSeasonOffer` (rm-6); health — `SaveHealthSubject`, `ArchiveHealthSubject`; cases — `OpenServiceCase`, `UpdateServiceCase`,
  `AddServiceCaseEntry`; custody — `LendAsset`, `UpdateLoan`, `ReturnLoan`, `RelinkLoanContact`; tags — `BindTag`,
  `ProvisionTag`. A command that fails its own validation first answers its 422 (the guard fires at the write).
  **A bulk writer skips held rows; it never fails on them (RM-1):** `RepairScheduleProviders`' plan filters its universe
  with `targetInService(…, held)` exactly as `ReminderHealthCheck` does, so a held, providerless ACTIVE schedule never makes
  the repair (the Reminder Health screen, `/v1/repairs/schedule-providers/apply`, the MCP tool) answer 409. A quick action
  on a newly held asset's standing post throws, and `QuickActionWorker`'s retry, its nonce spent, only reconciles
  (`QuickActionReceiver.kt:216-224`) — benign, recorded.
- **C13, API seam.** `ApiJson.kt`: `AssetTransferredOut` → 409 `asset_transferred_out`, problems
  `["AssetTransferredOut(assetId=…)"]`, for every write route that reaches a held row: `POST /v1/assets` with a held
  `parentAssetId` (rm-6); the asset's `PATCH`, `components`,
  `retire`, `archive`, `season`, `season-mode`, `maintenance-break`, `health-policy`, `conditions`, `warranty-reminder`;
  groups `POST`/`PATCH`/`archive`; schedules `POST`/`PATCH`/`pause`/`archive`/`postpone`/`complete`/`close-round`;
  definitions and profiles `POST`/`archive`; events `POST`/`PATCH`/`DELETE`; references `POST`/`PATCH`; health subjects
  `POST`/`PATCH`/`archive`; service cases `POST`/`PATCH`/`entries`; loans `POST`/`PATCH`/`return`;
  `/v1/import-merge/apply` only as a backstop (M2 refuses in the plan first, rm-2). Provider repair skips held rows
  (RM-1). The API has no tag write. The merge tally rides the wire from B2a.

**The recipient (B3).**
- **C14, import (AC 5–8, 16, 17; R77-14).** `ImportTransferPack`: (0) the app copies the pack to `cache/transfer-in/`;
  (1) C4's reader; (2) preview — `BuildBackupMergePlan.run(data, incoming, returning)` with the artifact overlay applied
  only to locators absent from the store; nothing written; (3) stage absent locators from `artifacts.zip`, digest and size
  verified, never overwriting, written locators recorded; (4) apply through `ImportBackupMerge.run`, re-planning inside
  its write; (5) on any refusal or failure, `sweepBytes(written)` only. Artifacts without a folder refuse before (2).
  Every pack asset gains an IN record (`lineage` = the pack's) in the apply. Known limit: a crash between (3) and (4)
  leaves unnamed files.
- **C15, explicit transfer back (R77-12, R77-25).** A pack asset held here is **returning** iff an open OUT(q) here has
  q ∈ the pack's `lineage` for it; any other held pack asset is CONFLICT `ASSET_TRANSFERRED_OUT` (a stale or foreign
  pack; P77-67). For returning assets the plan runs on the snapshot minus C3's drop set for them plus each local group
  with a row naming one; the apply, in its one write, appends their IN records first (closing q), removes that drop set
  through the ports' deletes (a `GroupRepository.delete` is added, used only here), then inserts as planned; after
  success, bytes of removed attachment rows the pack does not name are swept. Returning assets arrive as the pack says.
  The asset's local-only returned loans follow R77-25's answer (rm-9).
- **C16, doors and states (R77-2).** (a) Backup screen → P77-38 → `OpenDocument(application/zip,
  application/octet-stream)` → `Route.TransferImport`, folder refusal the reused `NoAttachmentFolder`. (b) A share whose
  first local entry is `transfer-manifest.json` becomes `IntakePath.TRANSFER_PACK`: the intake copies the stream while its
  grant lives and hosts the same screen; folder refusal `IntakeStrings.NO_FOLDER`; success shows P77-50 in the screen with
  the intake's `Close`, which finishes; any other ZIP keeps the byte form. States: reading → preview (P77-39–42, counts,
  P77-66 per returning asset, P77-46 per duplicate) → P77-43 (all IDENTICAL) / P77-44 + P77-45 per bound tag / P77-67 /
  Import → P77-50 or P77-44 / P77-52; reader refusals P77-47–49; `importing` set before the first suspension; Cancel
  writes nothing; every end deletes the copy. B3 widens `BackupSetIncomplete.wording()` to `internal` and maps
  `TransferredGraphEntangled` to P77-58 in `BackupViewModel`.

**The sender's phone (B4).**
- **C17, select and review.** P77-1 in the Assets overflow and the detail overflow (preselecting). A tree of assets not
  held, parents first, each child under `Part of <parent>`; a forced child is checked, disabled, state description
  P77-61. Nothing to offer → P77-56. Review (disabled when empty) runs C2: counts (P77-5–11, zero lines hidden but
  assets), P77-12, the note (P77-13, one line, ≤ 200), refusals (P77-15–18) blocking Create; Create → P77-19 → P77-21, or
  P77-20 with the shipped missing-file wording, P77-59, or the reused folder sentence.
- **C18, ready and mark (AC 4, 9, 14; R77-16).** `SavedStateHandle` holds `CreatedPack` and the file name. Share: a
  chooser (P77-25) over `ACTION_SEND`, `application/zip`, `EXTRA_STREAM` = the `${applicationId}.files` URI, the URI as
  `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`, `EXTRA_EXCLUDE_COMPONENTS` = ServiceTag's `ShareIntakeActivity`. Save a
  copy: `CreateDocument` named P77-54 → P77-26 or P77-55. P77-24 via the shipped `asFileSize()`. P77-27 with P77-30:
  P77-28 → C8 → one `ReminderReconcile` → the Assets list; the reused `Not now` writes nothing. Refusals P77-17, 51, 53, 57.
- **C19, history and read-only (AC 10, 11; R77-4).** Keyed on **held**, never on ARCHIVED: the plate and row show P77-31
  (word + glyph, `seasonInactive` tone) instead of "Archived"; held rows are listed only with the archived control on,
  whatever their status. The detail opens with a P77-32 block (P77-33, P77-34, the note, P77-62) and offers no write:
  hidden are Edit, Setup, Write tag, Log event, Log outcome, Log incident details, Add component, Add schedule, every
  section action (condition, season, health, warranty, lending, service cases, documents, references) and the overflow
  but Delete. The four sub-screens it opens — event, service case, schedule and group detail (also reached from the
  journal, the Due list, group lists and schedule deep links) — read the held set and carry `editable = false` for a held
  owner (a group with any held row). A write refused by C12 from a stale screen shows P77-35. **Every picker is keyed on
  held (rm-5):** the share intake's "Attach to" (`ShareIntakeViewModel.kt:192`; B3, which also maps `AssetTransferredOut`
  there to P77-35 as the fallback), scan-to-bind targets (`ScanViewModels.kt:212`), the asset editor's parent choices
  (`AssetViewModels.kt:1779`), the group member picker (`GroupEditViewModel.kt:186-187`) and the schedule editor's target
  picker never offer a held asset.
- **C20, scan (R77-11).** `Resolution.TransferredOut(tag, asset, record)` from `classify`, unstamped; `ScanViewModels`
  (`:50`, `:232`) render P77-36/P77-37, never the maintenance sheet; the #70 overwrite offers no overwrite on a
  `TransferredOut` peek. After a Replace restore the tags are gone and the shipped "not in this phone's records" answers.
- **C21, one platform contract (level 3).** Device `TransferShareContractTest`: a file in `cache/transfer/` resolves
  through `FileProvider.getUriForFile` and reads back byte-equal; one in `cache/transfer-in/`, `cache/transfer-work/` or
  `files/` throws; the chooser's inner intent carries C18's action, type, stream, ClipData, flag and exclusion (nt-6:
  with no Robolectric in this build, `Intent` and `ClipData` are real only on a device, so the case stays here).
- **C21a, rendered UI (R77-22).** Three instrumented Compose classes on the existing pattern, each ≤ 6 cases, no fixed
  waits, no device mutations (their logic's REDs live in the view-model tests): `TransferImportScreenTest` (B3: the
  preview's lines, Import disabled on a conflict, the refusal sentences drawn); `TransferFlowScreenTest` (B4: the tree's
  forced child checked, disabled and described P77-61, refusals blocking Create, the ready screen's three actions, the
  mark question); `AssetTransferDetailTest` (B4: a held asset's detail draws the block and P77-31 word + glyph and no node
  for any hidden write action; an archived asset's actions unchanged). The boundary each names: rendered semantics —
  enabled state, state description, node absence — that no JVM test in this build can observe.
- **C22, cache life and sweeps (R77-18, R77-23).** Leaving the ready screen deletes its pack; app start deletes
  `cache/transfer-in/` and `cache/transfer-work/` and any pack in `cache/transfer/` older than 24 hours; a restored ready
  screen verifies its file against `packSha256` and, if missing or different, disables Share, Save and Mark and shows
  P77-60 (MJ-7). Archive, unarchive, retire, unretire, mark and withdraw each run `ReminderReconcile` once after the write;
  API writes settle at the next sweep (R72-15).
- **C23, withdrawal (R77-5).** P77-62 → a dialog (P77-63, P77-64, P77-65 / `Cancel`) → `WithdrawTransferRecord.run(assetId,
  packId)`: appends WITHDRAWN for that open OUT (refused if none), so the asset is no longer held; it stays archived; then
  one sweep. Phone only; the resolution for a double mark (C10) and the explicit undo.

**API, MCP, docs, gate (B5).**
- **C24, API, MCP, docs (R77-17).** `/v1/status` counts gain `transferRecords`. `v1.md`: `ASSET_TRANSFERRED_OUT` and
  `TRANSFER_DIVERGED` with M1–M3; the 409 on C13's routes; a held asset's writes refused, validation first; maintenance
  withdrawal for an archived, retired or held asset at the next sweep; 1–14, "14 since #77 (transfer records)", nineteen
  tables; `import_merge` inserts an archive's records under M1 and can merge a pack's inner `data.zip` (its documents
  then SKIPPED without bytes, and no return is ever applied — only the phone's pack import replaces); no route creates,
  imports, marks or withdraws. MCP: 68 tools; `import_merge` docstring and README "formats 1–14", "nineteen tables", the
  tally, the two reasons. SPEC12 gains `**Amendment (#77, <the ratification date>)**` at its subject rule.
- **C25, the gate at 14.** §5's paragraph in `docs/release-proofs.md`; R4 and the tripwire unchanged unless R77-2 (c) or
  R77-19 is ruled in; `ReleaseProofPolicyTest` green and unchanged otherwise. B5's report names #77's device classes for
  the merged-tip gate's `EXTRA_CLASSES` (§4's budget) and each one's measured run time.
- **C26, seams.** P79 C26 (lead on `AssetDto`, cases and entries travel, C5 quiesced by C11) and P72 C24 (open loan
  refuses, loan builder excludes held assets, lending refused, no loan row reused, returned loans stay local) are met;
  #15 meets C1; #86's successor is a new identity; #44 stays separate.

## 3. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| **B1** 1. classification (C1) | core `TransferTableClassificationTest` · `everyBackupDataListIsClassified`; `loansNeverTravel`; `linkTombstonesNeverTravel` | classify loans ASSET_OWNED |
| 2. selection carries (C2, AC 2, 17) | core `TransferGraphTest` · `aRootCarriesEveryAssetOwnedTable` (season, condition, health, lead included); `descendantsAreForced`; `aWholeGroupTravels`; `onlyCategoriesInUseTravel`; `softLinksAsIs`; `unrelatedRowsNeverTravel`; `shuffledInputGivesEqualBytes` | carry an unselected asset's tag |
| 3. selection refuses (C2, AC 3) | · `aMixedGroupNamesItsStayingAssets`; `aRemovedMembershipStillMixes`; `anEmptyGroupNeverTravels`; `aChildWithoutItsParent`; `anOpenLoanRefusesAndReturnedLoansStay`; `anEventNamingARetainedScheduleIsOutside`; `everyRefusalTogether` | ignore `removedAt`; treat empty as whole |
| 4. packs decode (C2) | · `everySelectedArchiveDecodes` | omit closures |
| 5. retain (C3) | core `TransferGraphRetainTest` · `heldAndEverythingNamingItDrops`; `anEmptyGroupIsAlwaysRetained`; `theRetainedArchiveDecodes`; `aRetainedRowNamingAHeldRowIsEntangled` (parent, member, event → a dropped group's schedule) | drop empty groups |
| 6. container (C4) | core `TransferPackCodecTest` · round trip; manifest first, inner STORED; extra/missing/reordered/directory entry; not a pack; newer; either sha; set ids; lineage keys; artifacts vs MANAGED rows (both ways); over-cap data and inflated JSON; bad note; truncation | skip the order check; skip the artifact cross-check |
| 7. create (C5, AC 15) | core `CreateTransferPackTest` · `countsEqualTheInnerManifest`; `thePlanNamesOnlyThePacksManagedRows`; `aRefusedSelectionCreatesNothing`; `overTheCapIsRefused`; `createWritesNothing`; `ExportBackupSetTest` green | include a retained attachment |
| **B2a** 8. records, schema 14 (C6) | core `TransferRecordsTest` · open/held/current/lineage over OUT, IN, WITHDRAWN chains, multi-hop; app `TransferRecordRoomTest` · append-only, `deletingTheAssetKeepsItsRecords`; `Migration13To14Test` · rows survive, table empty, schema equals fresh 14 | close an OUT by packId instead of lineage; add a cascading FK |
| 9. format 14 (C7) | `BackupFormat14Test` · round trip; counts; shuffled bytes; format 13 carrying one refused; `BackupCodecTest` · `anAssetHeldByTheArchivesOwnRecordsIsCorrupt`; `BackupContentCheckTest` · each field rule; `TransferPackCodecTest.aPackCarryingRecordsIsDamaged` | skip the ≤ 13 check; drop the held rule |
| 10. mark (C8, AC 9) | core `MarkTransferredOutTest` · `marksInOneWrite`; `aWhollyInGroupIsArchivedAndAnEmptyOneIsNot`; `aRowAddedOrChangedSinceThePackIsPackOutdated`; `aNewChildMemberOrLoanIsRefused` (`commits == 0`); `alreadyHeldIsAlreadyTransferred`; `markingRewritesNoHistory` | compare ids only; archive an empty group |
| 11. backups (C9, AC 11–13) | `ExportBackupSetTest` · `aHeldGraphIsNotExportedAndDecodes`; `theArtifactsPlanNamesNoHeldAttachment`; `artifactCountEqualsThePlan`; `recordsAreExported`; `archivedButNotHeldStillExports`; `entangledFailsBeforeWriting`; `ImportBackupReplaceTest` · post-transfer restores records only; `StoreIsEmptyTest` · records alone are not empty | plan from all rows; subtract archived |
| 12. merge (C10) | core `MergePlannerTransferTest` · **stale** `aPreTransferBackupOfAHeldAssetIsRefused` (M2 and `CONTENT_DIFFERS`); `anOrdinaryArchivesInClosingAnOpenOutIsRefused` (M1); **convergence** `anotherInstallsOutMakesTheLiveAssetHeldHere`; **double mark** `twoOpenOutsAreTransferDiverged`; `aWithdrawnOutThenMergesCleanly`; M3 `anIncomingEventNamingAHeldGroupScheduleConflicts`, `anIncomingOutEntanglingALocalRowConflicts`; M2
`anIncomingEventOfAHeldAssetConflictsInThePlan` (rm-2); `theWithdrawingInstallsExportMergesCleanlyIntoTheOther` with the
converged end state `{OUT(p1), OUT(p2), WITHDRAWN(p2)}`, held under p1, asserted on both sides (rm-11); `recordsTally`; `ImportBackupMergeTest.recordsLandLast` | drop M3; let an archive close an OUT |
| 13. **B2a retargets** (13 → 14, 18 → 19 tables, 24 → 25 counts, six → seven kinds) | `VersionAgreementTest` (:80, renamed); `Format7RestoreContractTest` re-export 14; `MaintenanceRoutesTest` import-merge case and wire order; `BackupCodecTest` format pin; `BackupFormat8Test` newer-format numbers and `counts.size`; `BackupFormat7Test`; the two `MergePlanner*Test` table lists; `StoreIsEmptyTest`; `ReferenceMigrationTest`; `StageABundleConformanceTest` — every other assertion kept | — |
| **B2b** 14. **the corrected defect** (R77-20) | first, at `<base>`: app `LocalReminderProviderTest`, the builder constructed as `AppGraph.kt:391` wires it over `FakeGraph`'s repositories (rm-3) · `archivingAnAssetTakesItsStandingMaintenancePostDown`, `retiringAnAsset…` (fail at base); then core `BuildReminderSubjectsTest` · archived and retired → `Withdrawn`; unarchive → Active; a member's retirement leaves the group schedule Active; shipped archived-schedule and archived-group cases unchanged | gate on the schedule's status only |
| 15. **quiescence invariant** (C11) | core `TransferQuiescenceTest` (held asset forced ACTIVE: a due schedule, a lead in window, a hand-built open loan, a wholly held unarchived group) · `scheduleAndGroupScheduleWithdrawn`; `noDeadlineSubject`; `noLoanSubject`; app `TransferQuiescenceProjectionTest` · absent from `DueReadModel`, `AttentionReadModel`, `DashboardViewModel` (both rows), the health fleet, `ReminderHealthCheck` | one run per projection passing an empty set (9); drop the clause from `maintainedHere` |
| 16. **standing posts down** (R77-20) | app `LocalReminderProviderTest` · `transferringTakesTheStandingMaintenancePostDown` (tag cancelled, nonce cleared, no re-post); `anArchivedOrRetiredAssetsOpenLoanStillReminds` (R72-10); warranty day matrix unchanged | keep a `Withdrawn` tag in the kept set; gate loans on lifecycle |
| 17. **write guard coverage** (C12) | core `HeldWriteGuardTest` · every C12 use case against a held asset → `AssetTransferredOut`, `commits == 0`; `aGroupSaveOmittingHeldMembersIsRefused`; `rebindingATagWhoseCurrentTargetIsHeldIsRefused`; `aCategoryOnlyRewritePasses`; `deleteAssetPassesAndKeepsRecords`; `derivedStateIsUnguarded`; `aHeldTagScanIsNotStamped`; `anOrdinaryArchivedAssetUnarchives` (AC 11); the three offer
acceptors (rm-6); `theProviderRepairSkipsHeldSchedules` (RM-1) | the shared check (1); unwrap one port per family: asset, event, schedule/group, tag, loan (5); drop the category exception; drop the repair's held filter |
| 18. API refusal (C13) | app `TransferRefusalRoutesTest` · one route per C13 family → 409 with its problem, `POST /v1/assets` with a held parent included (rm-6); a malformed body → its 422 | map to 500 |
| 19. **device take-down** (R77-20) | device `ReminderPlatformDeviceProofTest` · `markingAnAssetTransferredTakesItsMaintenancePostDown` (a due schedule's post standing; `MarkTransferredOut`, then `app.graph.reminderRuns.reconcileAll()`; the tag gone from the platform's active list — the one place a standing post is observable) | drop C11's asset clause (1 device run) |
| 20. **B2b retargets** | the builder constructors' tests (`DigestPolicyTest`, `LocalReminderProviderTest`, `ContentHashTest`, `ReminderPortContractTest`, the three builder tests, …) and `targetInService` callers — mechanical, listed in the report | — |
| **B3** 21. preview (C14) | core `ImportTransferPackTest` · `aDisjointPackPlansEveryInsertIncludingBytes`; `thePreviewWritesNothing`; `theOverlayNeverMasksAPresentLocator`; `artifactsWithoutAFolderAreRefused`; `allIdenticalIsAlreadyHere`; `BuildBackupMergePlanTest` shipped cases unchanged | stage before the preview |
| 22. stage, apply, sweep (C14) | · `stagesThenMergesAndWritesInRecords`; `aDigestMismatchSweepsWhatItWrote`; `aRefusedApplySweepsOnlyStaged`; `aPresentLocatorIsNeverOverwritten`; `importingTwiceIsIdentical`; `aRecipientScanMakesTheSecondImportConflict` | stage into a present locator; sweep all |
| 23. recipient semantics (AC 7, 8, 16, 17) | · `aTagBoundHereElsewhereRefusesNamingIt`; `unrelatedRowsAreByteIdentical`; `seasonConditionHealthArrive`; `aDuplicateCandidateIsReported`; `tagsResolveToTheImportedAsset` | re-key a tag on insert |
| 24. **transfer back** (C15) | core `TransferBackTest` · **accepted** `aPackWhoseLineageNamesMyOpenOutReturnsTheAsset` (drop set replaced, IN first, no longer held, quiescence lifted); `aMultiHopReturnIsAccepted`; `aReturningGroupIsReplaced`; `removedRowsBytesAreSwept`; **stale** `myOwnOldPackIsRefused`; `aForeignPackForAHeldAssetIsRefused` | accept any pack for a held asset; insert before the IN |
| 25. intake (C16) | app `ShareIntakeViewModelTest` · `aManifestFirstZipRoutesToImport`; `otherZipsKeepTheByteForm`; `thePackIsCopiedBeforeFinish`; `noFolderSaysTheIntakesSentence`; `successShowsTheLineThenCloseFinishes`; `attachToNeverOffersAHeldAsset`, `aHeldTargetRefusalSaysP77_35` (rm-5) | sniff by file name; offer held assets |
| 26. import states (C16) | app `TransferImportViewModelTest` · each state and sentence; `aDoubleTapImportsOnce`; `cancelWritesNothing`; `everyEndDeletesTheCopy` | set `importing` after the suspension |
| 27. Backup seams | app `BackupViewModelTest` · `anEntangledExportSaysP77_58`; the shipped wording cases unchanged | fall to the generic line |
| 28. import screen rendered (C21a) | device `TransferImportScreenTest` · `thePreviewDrawsItsLines`; `importIsDisabledOnAConflict`; `aRefusalDrawsItsSentence` | — (row 26 carries the REDs) |
| **B4** 29. select, review (C17) | app `TransferSelectionViewModelTest` · parents first, held never offered; `nothingToOfferSaysP77_56`; forced children with P77-61; refusals block Create; note bounded | offer a held asset |
| 30. create, ready, mark (C18, AC 4, 9, 14, 15) | app `TransferPackViewModelTest` · progress then ready; missing file leaves no file; `cancelLeavesNoFileAndNoRecord`; `markOnlyAfterCreation`; `markWritesThenSweepsOnce`; `notNowWritesNothing`; P77-51/57 refusals; `saveACopyCopiesEveryByte` (sink fake); P77-55; app `TransferPackWriterTest` · layout, work file gone, sha | mark before creation |
| 31. cache life (C22, MJ-7) | · `leavingDeletesThePack`; `aRestoredScreenKeepsAFreshPack`; `aRestoredScreenWhoseFileIsGoneOffersNothing` (P77-60); `startSweepsCopiesWorkFilesAndDayOldPacksOnly` | sweep every pack at start |
| 32. history, read-only (C19) | app `AssetTransferStateTest` · the block and no write but Delete; `heldButActiveIsHiddenWithoutTheArchivedControl`; `anArchivedAssetKeepsEveryAction` (AC 11); per sub-screen: `EventDetail…`, `ServiceCase…`, `ScheduleDetail…`, `GroupDetail…ReadOnlyForAHeldOwner`; per picker (rm-5): `scanToBind…`, `parentChoices…`, `groupMemberPicker…`, `scheduleTargetPicker…NeverOfferAHeldAsset` | gate on ARCHIVED (1); drop each sub-screen flag (4); key a picker on status (1) |
| 33. withdrawal (C23) | core `WithdrawTransferRecordTest` · appends WITHDRAWN, no longer held, stays archived; no open OUT refused; app VM · dialog then one sweep | withdraw without an open OUT |
| 34. scan (C20) | core `ResolveTagTest` · `aHeldAssetsTagIsTransferredOutAndUnstamped` (run, peek); app `ScanViewModelsTest` · never the sheet; the overwrite offers nothing | classify as `OpenAsset`; stamp it |
| 35. lifecycle sweeps (C22) | app `AssetLifecycleSweepTest` · each action sweeps once after its write; a failed write sweeps nothing | sweep before the write |
| 36. provider contract (C21) | device `TransferShareContractTest` · resolves and reads back; outside paths refused; chooser carries stream, grant, ClipData, exclusion | drop the `<cache-path>`; omit the exclusion |
| 37. flow and detail rendered (C21a) | device `TransferFlowScreenTest` · `aForcedChildIsCheckedDisabledAndDescribed`; `refusalsBlockCreate`; `theReadyScreenOffersShareSaveAndTheQuestion`; device `AssetTransferDetailTest` · `aHeldAssetDrawsTheBlockAndBadgeAndNoWriteAction`; `anArchivedAssetKeepsItsActions` | — (rows 29, 30, 32 carry the REDs) |
| **B5** 38. status, docs, MCP (C24) | `MaintenanceRoutesTest.statusReports…` (moved here, mn-14); `ReferenceRoutesTest` · counts + `transferRecords`, document agrees; `CommandShapesGoldenTest.theContractDocumentNamesFormat14AndNineteenTables`; pytest nineteen tallies, 68 tools | — |
| 39. gate at 14 (C25) | B5's anchored greps over `docs/release-proofs.md`; `ReleaseProofPolicyTest` green (unchanged unless R77-2 (c) or R77-19 is ruled in) | — |

## 4. Lanes, files and collisions

One branch `issue-77` from the ratified commit: B1 → B2a → B2b → B3 → B4 → B5, each `<base>` the previous accepted tip.
**Files** (indicative; each untouched diff fences the rest): B1 new
`C/transfer/{TransferGraph,TransferPack*}.kt`, `C/usecase/{CreateTransferPack,ExportBackupSet}.kt`; B2a
`C/model/TransferRecord.kt`, `C/ports/Repositories.kt`, `C/{backup,merge}/*`, `C/usecase/{MarkTransferredOut,ExportBackupSet,
ImportBackupReplace,StoreIsEmpty,ApplyBackupMergePlan,BuildBackupMergePlan,CreateTransferPack}.kt`, `A/data/room/**`, `14.json`,
`A/api/ApiDtos.kt`, `AppGraph.kt`; B2b `C/model/Asset.kt`, `C/transfer/HeldWriteGuard.kt`, `C/schedule/ScheduleStatus.kt`,
`C/reminders/Build{Reminder,Deadline,Loan}Subjects.kt`, `C/usecase/{ResolveTag,RepairScheduleProviders}.kt`, `A/api/ApiJson.kt`,
`A/reminders/ReminderHealthCheck.kt`, `A/ui/{maintenance/{DueReadModel,AttentionReadModel},dashboard/DashboardViewModel,
health/AssetHealthReadModel}.kt`, `AppGraph.kt`, `FakeGraph.kt`, the one case in `ReminderPlatformDeviceProofTest`; B3
`C/usecase/{ImportTransferPack,BuildBackupMergePlan,ApplyBackupMergePlan}.kt`, `GroupRepository.delete`, new
`A/ui/transfer/import/*`, `A/ui/backup/{BackupScreen,BackupViewModel}.kt`, `A/share/*`, `Route.kt`, `ServiceTagRoot.kt`,
`TransferImportScreenTest`; B4 new `A/ui/transfer/*`, `A/transfer/TransferPackWriter.kt`, `C/usecase/WithdrawTransferRecord.kt`,
`res/xml/file_paths.xml`, `A/ui/scan/*`, `A/ui/asset/*`, the four sub-screen view models, `ServiceTagIcons.kt` + one
drawable, `Route.kt`, `ServiceTagRoot.kt`, `TransferShareContractTest`, `TransferFlowScreenTest`, `AssetTransferDetailTest`;
B5 `A/api/*` (status), `docs/api/*`, `tools/servicetag-mcp/**`, SPEC12, `docs/release-proofs.md`. **Collisions.** No live
lane: #90 is parked, and #77 edits no harness helper, `tools/emulator/*` script or the gate script. B2b's guard wraps every
port the #72/#79 use cases use; B4 edits `AssetDetailScreen.kt` (1,405 lines), `AssetViewModels.kt` (2,603) and four
sub-screens — the gates render only for a held owner, so no shipped fixture scrolls. #84 takes B4's findings.

**Gate budget (owner roadmap ruling).** The merged-tip gate stays the controller's existing script — the 41 base classes
plus, through `EXTRA_CLASSES`, `TransferShareContractTest`, `TransferImportScreenTest`, `TransferFlowScreenTest` and
`AssetTransferDetailTest` (row 19's case rides a base class); nothing else joins the device suite, each class ≤ 6 cases
and about 30 s. After the merge the controller measures the whole gate (start → GATE DONE) and its device portion:
**14 minutes is the warning line; above 15 — or growth that would put the next feature over it — #90 is promoted before
further feature work.** The numbers go in the ledger and the post-merge errata.

## 5. Schema, backup, merge and API implications (summary for the owner)

Room 13 → 14, one append-only table without a foreign key (C6); format 14 carries `transferRecords` and never the held
graph (C7, C9); nineteen merge tables, two new reasons, three rules, **no UPDATE** — the only replace is a phone pack
import returning an asset (C10, C15); one 409 across C13's routes, one status count, no pack route or tool; MCP stays
at 68. **Release gate (R77-21):** `docs/release-proofs.md:107` becomes the direct **1.4.1 (schema 8) → schema 14** proof:
every step with its 13s moved to 14 ("Room schema 14 / backup format 14", `schemaVersion` 14, `backupFormatVersion` 14,
"the format-14 round trip", "Master builds carry schema 14") and "#77's transfer records at schema 14" beside the kept
"#72's loans at schema 13", plus: `counts` gain `transferRecords: 0`; the post-upgrade export carries
`"transferRecords": []`; the pre-upgrade export re-plans applicable, zero INSERT, every row IDENTICAL; the fresh format-14
export re-plans IDENTICAL and tallies `transfers` 0. No Transfer Pack is made in the gate.

## 6. Strings — for ratification

| id | where | text | treatment |
|---|---|---|---|
| P77-1 | Assets overflow, detail overflow, selection title | `Transfer assets` | menu item; top bar |
| P77-2 / 3 | selection | `Select what is leaving this ServiceTag` / `Components go with the asset they belong to.` | heading / `QuietLine` |
| P77-4 / 5 | selection button / review heading | `Review` / `Transfer Pack` | `Button` / `SectionHeader` |
| P77-6…11 | review counts, P77-50 | `%d assets` · `%d NFC tags` · `%d records` (journal entries) · `%d schedules` · `%d documents and photos` · `%d service cases` | plurals with `1 …` singulars; zero lines hidden but assets |
| P77-12 | review | `These records may contain serial numbers, locations, purchase information, receipts and service history.` | body line |
| P77-13 / 14 | review | `Note for the new owner (optional)` / `Create Transfer Pack` | field label / `Button` |
| P77-15 | review refusal | `<group> also covers <staying assets>, which stay here. A group transfers only with every asset it has ever covered.` | error line |
| P77-16 / 17 | refusals | `<child> is a component of <parent>. Select <parent> too.` / `<asset> is lent out. Mark it returned first.` | error lines; 17 also a snackbar |
| P77-18 | review refusal | `<asset> has a record that points outside this transfer.` | error line |
| P77-19 / 20 | create | `Creating Transfer Pack…` / `Transfer Pack not created: <reason>. Nothing was changed.` | progress / error line |
| P77-21…24 | ready | `Transfer Pack ready` · `Share` · `Save a copy` · `Size: <asFileSize>` (the shipped B / KB / MB helper) | heading · `Button` · `OutlinedButton` · `QuietLine` |
| P77-25 / 26 | chooser title / save done | `Share Transfer Pack` / `Saved` | chooser / snackbar |
| P77-27, 28, 30 | the mark question (P77-29 withdrawn: the reused `Not now`) | `Mark these assets transferred out on this phone?` · `Mark transferred` · `Their reminders stop and they leave your lists. You can still open them under Archived.` | body · `Button` · `QuietLine` |
| P77-31 | plate, list row | `Transferred` | `StatusBadge` + `ic_handover` glyph, `seasonInactive` |
| P77-32…34 | detail block | `Transferred out` · `Transferred on <date>` · `Transfer Pack <short id>` (8 hex) | `SectionHeader` · body · `QuietLine`; `d MMM uuuu` |
| P77-35 | a refused write from a stale screen | `This asset was transferred out.` | snackbar |
| P77-36 / 37 | scan | `Asset transferred out` / `<asset> was handed over on <date>. This phone no longer maintains it.` | sheet title / body |
| P77-38 | Backup entry, import title | `Import Transfer Pack` | `OutlinedButton`; top bar |
| P77-39…42 | preview | `Note: <note>` · `Created <date>` · `Contains` · `Import` | body · `QuietLine` · `FieldLabel` · `Button` |
| P77-43…45 | import outcomes | `This Transfer Pack is already on this phone.` · `This Transfer Pack conflicts with records on this phone, so nothing was imported.` · `An NFC tag in this pack is already used for <asset> here.` | body · error lines |
| P77-46 | preview | `<incoming> may already be here as <local>.` | `QuietLine` |
| P77-47…49 | reader refusals | `This file is not a Transfer Pack.` · `This Transfer Pack needs a newer version of ServiceTag.` · `This Transfer Pack is damaged and cannot be imported.` | error lines |
| P77-50 | import done | `Transfer Pack imported: <counts>` (anchored apart from the shipped `Imported: …`) | snackbar; in the share host, a line |
| P77-51 C | mark refusal (R77-16) | `<asset> changed after this Transfer Pack was made. Create it again.` | error line |
| P77-52 / 53 | failures, logged | `Could not import this Transfer Pack. Nothing was changed.` / `Could not mark these assets. Nothing was changed.` | error lines |
| P77-54 | file name | `servicetag-transfer-<uuuu-MM-dd>-<short id>.zip` | SAF and chooser name |
| P77-55 / 56 / 57 | save failure / empty selection / already held | `Could not save a copy.` / `No assets can be transferred.` / `These assets are already marked transferred out.` | snackbar / body / error line |
| P77-58 | export failure (mn-6) | `Export failed: records on this phone still point to a transferred asset. Nothing was saved.` | the shipped export error line |
| P77-59 / 60 | data cap / restored file gone | `These assets have too many records for one Transfer Pack. Transfer fewer at a time.` / `This Transfer Pack is no longer on this phone. Create it again.` | error lines |
| P77-61 A | forced child (accessibility only) | `Included with <parent>` | state description |
| P77-62…65 C | detail block, withdrawal (R77-5) | `Withdraw transfer record` · `Withdraw the record for Transfer Pack <short id>?` (rm-8) · `Use this only if the asset did not leave, or another phone's record should stand. It stays archived.` · `Withdraw` | `TextButton` · dialog title · body · confirm |
| P77-66 C | preview, a returning asset (R77-25) | `Coming back: <asset>` | `QuietLine` |
| P77-67 | import refusal, stale or foreign pack | `<asset> was transferred out from this phone, and this Transfer Pack does not bring it back.` | error line |
| P77-68 | Replace-restore refusal (R77-13), one / several | `Restore failed: this backup still contains <asset>, which was transferred out from this phone. Nothing was replaced.` / `Restore failed: this backup still contains <n> assets that were transferred out from this phone. Nothing was replaced.` | the shipped restore error line |
| — | reused (8) | `Choose an attachment folder in Settings first` (`NoAttachmentFolder`); `Choose an attachment folder in ServiceTag Settings, then share this again.` (`IntakeStrings.NO_FOLDER`); `This ServiceTag tag is not in this phone's records.`; `Cancel`; `Close` (`IntakeStrings.CLOSE`); `Not now` (`NOT_NOW`); `Part of <parent>` (inline, `AssetDetailScreen.kt:799`); the missing-file wording (`BackupSetIncomplete.wording`) | through their homes |

**66 new ids** (P77-1…67 less the withdrawn P77-29), **6 conditional** (P77-51, 62–66), **1 accessibility-only**
(P77-61), **8 reused**. **Collisions:** `Transferred` / `Transferred out`, `Import` / `Import Transfer Pack`, `Withdraw` /
`Withdraw transfer record`, `Transfer Pack imported:` / the shipped `Imported:` each anchor apart; `Share`, `Saved`,
`Review`, `Contains` have no home today; the folder sentence has three shipped homes, unchanged.

## 7. Rulings and owner questions (controller, 2026-09-28)

- **R77-1 (recommended).** C4's nested ZIP, P77-54, sent as `application/zip` (the shipped filter admits it); a custom
  extension arrives as `application/octet-stream`, too wide to declare. **Owner.**
- **R77-2 (recommended: a + b).** In-app import and share-to-ServiceTag, no manifest change; (c) tap-to-open breaks the
  exact exported set and "no VIEW" (`ManifestContractTest.kt:84-105, :173`) and adds a foreign-UID proof — a follow-up.
- **R77-3 (recommended).** ARCHIVED plus the append-only `asset_transfer` records (C6–C9); wholly-in groups archived at
  marking. Alternative S2 (columns on `asset`) carries the whole row in every later backup, and cannot express a return.
- **R77-4 (recommended, the owner's direction): lock everything.** A held asset keeps inspection and no ordinary
  mutation: C12's one guard over the sixteen ports, with the full use-case list and its exceptions (DeleteAsset; the
  category-only rewrite; derived state); C13's routes answer 409; C19 hides every write on the detail and its four
  sub-screens. Alternatives: lock the listed set (reactivate, bind, entangle — rev 1: about 30 history writes stay open
  through the API and the sub-screens); lock the asset screen only. **Owner; and whether DeleteAsset stays allowed.**
- **R77-5 (recommended).** An explicit withdrawal (C23): append-only, phone-only, the undo and the double-mark
  resolution. Alternative: none (a double mark is then unresolvable). **What the owner should know:** a withdrawal is
  repeated on each install, because M1 refuses one arriving by merge (rm-7); withdrawing the OUT whose pack actually
  left strands a later return of that pack, refused as foreign, recoverable only by deleting the asset and importing —
  P77-63 names the pack's short id to match the file name (rm-8). **Option (rm-8):** count a pack whose lineage names a
  *withdrawn* OUT here as returning, which makes a mistaken withdrawal recoverable (changes C15). **Owner.**
- **R77-6 (recommended).** An open loan refuses creation and marking; returned loans stay in the local store only and
  leave every later backup (C3), so they do not survive a Replace restore. **R77-7 (confirm).** Open cases travel.
- **R77-8 (recommended: refuse).** A child without its parent (P77-16); the subtree always forced.
- **R77-9 (recommended: refuse, limit acknowledged).** A group with any row naming a staying asset, current or removed,
  is refused and names them (P77-15); it becomes transferable only whole, or once every staying former member is deleted
  (usually refused while a recorded round covers it). "Leave the group behind" is a follow-up.
- **R77-10 (recommended).** Any asset not held may be a root; rows travel verbatim.
- **R77-11 (recommended).** A `Resolution` member; a held tag's scan is not stamped (C20).
- **R77-12 (recommended: the record model of C6/C10/C15).** Facts for dev ↔ production: (1) a transfer on production,
  merged into dev, inserts the OUT and makes the live asset held on dev — quiesced, read-only, dropped from dev's exports
  (refused by M3 if dev has retained rows pointing at it); (2) if both installs mark the asset, the second merge is
  CONFLICT `TRANSFER_DIVERGED` naming both packs, nothing written; the owner withdraws one install's record (C23) and
  merges again; (3) after a transfer back on production, dev converges by importing the same pack — an ordinary archive
  never closes an OUT; (4) a return is legitimate only by a pack whose lineage names this install's open OUT; any older
  pack or ordinary archive is refused; (5) deleting a held asset keeps its records. Alternatives: rev 1's single receipt
  (a return refused forever; a double mark a permanent `CONTENT_DIFFERS`; production → dev refused until dev deletes the
  asset, which a recorded round can refuse); receipts SKIPPED on merge (silent divergence). **Convergence the owner
  should know (rm-7):** an install that missed a return must import that pack, or take a Replace restore from the other
  install; a withdrawal never propagates. **Option (rm-7):** accept an incoming WITHDRAWN by merge — always an explicit
  act, never stale state. **Tie-break (rm-12):** with two current INs for one asset (two installs importing different
  packs), `lineageFor` takes the latest `at`, then the id (recommended); alternative: refuse as divergence. **Owner.**
- **R77-13 (recommended: a documented limit).** A Replace restore of a pre-transfer backup wipes the records and
  resurrects the graph — explicit (typed REPLACE), but the one remaining resurrection path against the owner's direction
  (rm-10). Options: Replace keeps the local records and drops their held graph from the incoming archive, or refuses such
  an archive (either changes C9). No record list surface in slice 1. **R77-14.** Documents need a folder on both sides. **R77-15.**
  One optional note, shown as P77-39.
- **R77-16 (recommended).** Marking only on the ready screen, re-validated by content (P77-51 active); leaving without
  marking means a new pack later. Alternative: pending packs persisted.
- **R77-17 (recommended).** The 409 and the status count; no `AssetDto` field, no pack, mark or record route, no MCP
  tool. Alternative: a sibling `transferredAt` on `AssetResponse` — single-asset GETs only; lists carry bare `AssetDto`s.
- **R77-18 (recommended).** C22's cache life: leaving deletes; start sweeps copies, work files and day-old packs; a
  restored screen checks its file (P77-60). A receiver reading lazily after deletion fails; "Save a copy" is the archive.
- **R77-19 (recommended: none).** No outbound foreign-UID proof; R4 and `ReleaseProofPolicyTest` stay.
- **R77-20 — RULED (owner, 2026-09-28; §14).**
- **R77-21 (recommended).** No `versionName` bump; 14 rides master under 1.4.1, emulator only; the gate is §5's proof.
- **R77-22 (recommended; the roadmap ruling).** On the existing infrastructure: view-model state, eligibility, ordering,
  strings as data and domain rules on the JVM with the existing fakes; C21a's three instrumented Compose classes for
  rendered semantics no JVM test in this build reaches; two platform proofs (C21's provider table, row 19's active
  notification list); device mutations only for those two (one each, plus C21's exclusion). Alternative: no
  instrumented Compose for #77, the rendered UI resting on view-model tests and the review. **Owner.**
- **R77-23 (recommended).** Phone lifecycle actions sweep once after the write (C22); API writes at the next sweep.
- **R77-24.** Ratify P77-1…67 (P77-29 withdrawn) with treatments; P77-31's case (TRANSFERRED drawn, "Transferred" read)
  and tone; the glyph `ic_handover` / `ServiceTagIcons.Handover`.
- **R77-25 (recommended: replace on return).** A returning asset's stale local history is removed and the pack's
  inserted in one write (C15), IN first. Alternative: the owner deletes the held asset first and the pack then inserts
  normally — manual, and blocked wherever a recorded group round refuses the delete. **Loans (rm-9):** packs never carry
  loans, so the drop set deletes the asset's local-only returned loans (contact links included). (a, recommended) keep
  them: the return replaces the asset row in place and removes every other drop-set row but the loans — history stays
  history (R77-20's spirit); (b) delete them, the asset delete cascading as written. **Owner.**
- **Risks.** Sender integrity → C3, C9, C10 M3, C12 (rows 5, 11, 12, 17); the replace-on-return write → C15 (row 24);
  staging without a spanning transaction → C14 (row 22); the guard's reach over every port → row 17; the first outbound
  share and phone merge → C18, C21 (rows 25–31, 36); the defect correction touching ordinary archive (AC 11 read with
  R77-20) → rows 14–16, 19, 35; gate growth → §4's budget (four classes and one case).

## 8. What this plan does not do, and records

No second format, merger, store or tag identity; no UPDATE verdict, tag rewrite, `ACTION_VIEW`, manifest change, pack,
mark or withdraw route, MCP tool, pending-pack store, record list surface, partial group, detached child or supply-item
handling; no change to warranty or loan delivery or R72-10 for assets not held; no `versionName` bump, no phone.
**Records:** tap-to-open, "leave the group behind", a record history list and a phone merge UI for ordinary archives
are follow-up candidates; REFERENCE attachments and C2/C12's group-meter case are unreachable (§1 (8)).

## Briefs — common to all six

Read §1–§8, the issue, the audit, the brief review and every earlier report. **Gate:** `./gradlew :core:test
:app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`; before a connected class
`tools/emulator/prepare-emulator.sh`, then one class per run `ANDROID_SERIAL=emulator-5554 ./gradlew
:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`; the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`, over `app/src/main` unless
named) and untouched diff (`git diff <base> --stat -- <paths>` → empty; `A/`, `C/` expanded); hygiene; gitlink
`7e0377a`; `git status` clean. **Pin rule:** a shipped assertion pinning a version, table, field, reason, count key,
status key, tool count or order, a golden shape or the R4 row moves with the brief that bumps it, every other assertion
kept; confirm the set with `git grep -nE '\b(13|18|24|68)\b|Thirteen|[Ee]ighteen|[Ss]ix kinds|targetInService\('` over
the test trees first; the report lists every move and every mechanical constructor ripple (nt-9). **Caps:** each brief
states a counted RED list, a mutation cap and a time box; a fix round is capped at **6 mutation runs and 90 minutes**,
touches only its findings' files, and stops on the first finding it cannot close. **Stop and report, always:** an edit
to an untouched file; an assertion that must move outside a retarget row or the pin rule; a gate failure the brief did
not cause surviving one re-run; the emulator unavailable for 30 minutes; the cap or time box reached — with what is done,
proven and not. **Must NOT, always:** delete a shipped assertion; commit outside the brief's files; use a device other
than `emulator-5554`; touch a harness helper, `tools/emulator/*` or the gate script; edit `ReleaseProofPolicyTest` or
`docs/release-proofs.md` outside B5; add a device class or case outside §4's budget; change the manifest or `ManifestContractTest`; write a
real name, serial, address or tag key.

## 9. B1 — graph, container, creation (C1–C5)

**Read:** audit §1–§2; `BackupCodec.kt` (:26-40, :113-136, :159-257, :399-839), `ArtifactsCodec.kt`, `ExportBackupSet.kt`,
`Attachment.kt`. **`<base>`** = the ratified commit. **Rows:** 1–7. **Needs:** R77-1, 6–10, 15. **Connected:** none.
**Greps** (over `core/src/main`, each → 1): `'const val PACK_FORMAT_VERSION = 1$'`; `'= "transfer-manifest\.json"$'`;
`'= "data\.zip"$'`; `'= "artifacts\.zip"$'`; `'MAX_PACK_DATA_BYTES ='`; `'MAX_PACK_JSON_BYTES ='`; `'^import android\.'` over
`C/transfer` → 0; `git diff <base> -- C/backup` → empty. **Untouched:** `app/**`, `C/{backup,merge,reminders,model,ports,
schedule}`, every use case but `ExportBackupSet.kt` (extraction, output byte-identical) and the new one. **Must NOT:** edit a
codec or DTO; write from creation; carry a loan, tombstone or link tag. **Counted RED (12):** rows 1, 4, 7 one each; 2, 3,
5, 6 two, plus 3's empty-group and 6's artifact cross-check. **Caps:** 14 JVM, 0 device; **3 hours**. **Size:** about 700
production, 1,050 test lines.

## 10. B2a — records, schema and format 14, marking, backups, merge (C6–C10)

**Read:** P72 §2 C5–C7, §9; `MergePlan.kt` (:55-140, :418-446), `MergePlanner.kt`, `BuildBackupMergePlan.kt`,
`ApplyBackupMergePlan.kt`, `StoreIsEmpty.kt`, `ImportBackupReplace.kt`, `ArchiveGroup.kt`. **`<base>`** = B1's tip.
**Rows:** 8–13. **Needs:** R77-3, 6, 12, 13, 16, 21. **Connected:** `Format7RestoreContractTest`, `AppSmokeTest`.
**Greps:** each → 1: `'const val FORMAT_VERSION = 14$'`, `'const val FIRST_TRANSFER_FORMAT = 14$'`,
`'const val LAST_LEGACY_FORMAT: Int = 7$'`, `'const val SCHEMA_VERSION = 14$'`, `'^\s+version = 14,$'` in
`AppDatabase.kt`, `'"transferRecords" to sorted\.transferRecords\.size,$'`, `'\bLOANS, TRANSFERS,'`,
`'^\s+TRANSFER_DIVERGED,?$'`, `'^\s+val transfers: MergeTallyDto\b'` in `ApiDtos.kt`; `'\bMIGRATION_13_14\b'` in
`Migrations.kt`, `AppGraph.kt`, `MigrationTestSupport.kt`; `14.json` present; `'fun (update|delete)\('` in the record port → 0 (`deleteAll` stays).
**Untouched:** `A/ui`, `A/{share,reminders,contacts,links,prefs,attachments}`, `A/api` but `ApiDtos.kt`,
`C/{reminders,schedule,health,condition,warranty}`, every use case but C6–C10's, the manifest, `res`, `app/src/androidTest`,
`tools`, `docs`. **Must NOT:** add an UPDATE verdict; update or delete a record; move a `MergeTable` or `MergeReason`
ordinal; let an ordinary archive close an OUT; archive an empty group. **Counted RED (18):** 8 (2), 9 (2), 10 (4:
ids-only compare, empty group, lineage, loan), 11 (4: plan from all rows, subtract archived, entangled check, records
omitted), 12 (6: M1 close, M1 divergence, M2 asset, M2 owned row, M3 structural, records order). **Caps:** 18 JVM, 0 device; **6 hours**;
stop also if the snapshot needs anything beyond the records. **Size:** about 900 production, 1,300 test lines.

## 11. B2b — the corrected defect, quiescence, the write guard, the 409 (C11–C13)

**Read:** P79 C5, §17; `BuildReminderSubjects.kt`, `ReminderHealthCheck.kt` (:268-370), `ScheduleStatus.kt` (:60-100),
`DashboardViewModel.kt` (:285-430), `AssetHealthReadModel.kt`, `DueReadModel.kt`, `AttentionReadModel.kt`,
`LocalReminderProvider.kt`, `DigestPolicy.kt` (:225-260), `FakeGraph.kt`, `Repositories.kt`, `ApiJson.kt`, `TagTargets.kt`,
`BindTag.kt`, `SaveGroup.kt`, `RenameCategory.kt`, `PromoteCategory.kt`, `ReminderPlatformDeviceProofTest`. **`<base>`** =
B2a's tip. **Rows:** 14–20.
**First commit:** row 14's app cases, each building `BuildReminderSubjects(graph.schedules, graph.groups,
graph.recomputeSchedules)` itself over `FakeGraph`'s repositories — the construction `AppGraph.kt:391`'s `subjectsFor`
wires (`FakeGraph` has no subject builder) — and feeding a `LocalReminderProvider` with a fake notifier, so they compile
against the base constructor; run at `<base>`, failing for the recorded reason — the defect evidenced before any production edit; the
core builder cases follow the constructor change; no commit gives `targetInService` its third argument without every
caller passing the held set. **Needs:** R77-4, 11, 20, 23. **Connected:** `ReminderPlatformDeviceProofTest` (row 19's
case beside its shipped ones). **Greps:** each → 1:
`'^val Asset\.isInService: Boolean'`, `'^fun Asset\.maintainedHere\('`, `'"asset_transferred_out"'` in `ApiJson.kt`;
`'status == AssetStatus\.ACTIVE && !'` over `core/src/main app/src/main` → 2 (`isInService`, and the
`GroupEditViewModel.kt:187` picker, reported and kept); `'hands a retired (one over as|obligation to the provider as)'` over both trees → 0
(both sentences rewritten to state the corrected lifecycle-and-held rule); `git diff <base> --
A/reminders/{DigestPolicy,Notifications,LocalReminderProvider,DeadlineDelivery}.kt` → empty. **Untouched:** `A/ui` but the
four read models, `A/{share,data,contacts,links,prefs,attachments}`, `A/api` but `ApiJson.kt`, `A/reminders` but
`ReminderHealthCheck.kt`, `C/{backup,merge}`, every use case but `ResolveTag.kt` and `RepairScheduleProviders.kt`, the manifest, `res`, `app/src/androidTest`
but row 19's case, `tools`, `docs`. **Must NOT:** give `targetInService` a default; key a projection on ARCHIVED for a held asset; drop a
loan subject of an asset not held; guard derived or device-local tables; change a use case's validation order. **Counted
RED (23 JVM, 1 device):** 14 (2), 15 (10), 16 (2), 17 (8), 18 (1); 19 (1 device) — row 20 none. **Caps:** 24 JVM, 1
device; **7 hours**; stop also if a
use case writes a held row through a path the guard does not wrap. **Size:** about 900 production, 1,300 test lines.

## 12. B3 — import, transfer back, the doors (C14–C16)

**Read:** audit §4; `BuildBackupMergePlan.kt`, `ApplyBackupMergePlan.kt` (:46-56, :117-133), `MergePlanner.kt` (:701-761),
`RestoreArtifacts.kt`, `AttachmentSweep.kt`, `BackupScreen.kt`, `BackupViewModel.kt` (:50-62, :260-348), `SharedItem.kt`,
`ShareIntake{Activity,ViewModel,Screen}.kt`. **`<base>`** = B2b's tip. **Rows:** 21–28. **Needs:** R77-2, 12, 14, 15, 22,
25. **Connected:** `TransferImportScreenTest` (new); `ShareBoundaryTest`, unchanged, re-run. **Greps:** every P77 literal B3 owns (35, 38–50, 52,
58, 66, 67) → 1 anchored (P77-35's home lands here for the intake fallback; B4 reuses it); `'Choose an attachment folder in Settings first'` → 3 (unchanged); `'internal fun
BackupSetIncomplete\.wording'` → 1; the manifest and `ManifestContractTest` diffs → empty; `'fun delete\('` in
`GroupRepository` → 1. **Untouched:** `C/{backup,reminders,model,schedule}`, `C/merge` but the plan's `returning`
parameter, every use case but C14/C15's, `A/{api,reminders,data}` but the group DAO delete, `A/ui/asset`, `res`, shipped
androidTest files, `tools`, `docs`. **Must NOT:** write before Import; overwrite a present locator; sweep a locator it
did not write; replace anything but a returning asset's drop set; read a pack path from an intent extra. **Counted RED
(16):** 21 (2), 22 (3), 23 (2), 24 (4), 25 (3), 26 (1), 27 (1) — row 28 none. **Caps:** 16 JVM, 0 device; **6 hours**; stop also if the
return needs more than three new port methods, or the overlay cannot stay additive. **Size:** about 1,000 production,
1,200 test lines.

## 13. B4 — the sender's phone (C17–C23) · B5 — API, MCP, docs and the gate at 14 (C24–C25)

**B4. Read:** audit §5; `AssetsScreen.kt` (:330-477), `AssetDetailScreen.kt` (:170-200, :600-800), `AssetViewModels.kt`,
the event, service-case, schedule and group detail view models, `ScanViewModels.kt`, `TagResultSheet.kt`, `ResolveTag.kt`,
`DocumentsSection.kt` (:334-338), `file_paths.xml`, the lend form's `ReminderReconcile` call. **`<base>`** = B3's tip.
**Rows:** 29–37. **Needs:** R77-1, 4, 5, 10, 11, 16, 18, 22–24. **Connected:** `TransferShareContractTest`,
`TransferFlowScreenTest`, `AssetTransferDetailTest` (new); `AssetsIndicatorsTest`, `AssetsFiltersTest`,
`AssetModelDeviceProofTest`, `AppSmokeTest` re-run. **Greps:**
every P77 literal B4 owns (1–28, 30–34, 36, 37, 51, 53–57, 59–65) → 1 anchored; `'<cache-path name="transfer" path="transfer/" />'`
→ 1 and `'transfer-(in|work)'` in `file_paths.xml` → 0; `'EXTRA_EXCLUDE_COMPONENTS'` over `A/ui/transfer` → 1;
`'ACTION_VIEW'` there → 0; `'is Resolution\.TransferredOut'` ≥ 2. **Untouched:** `core/src/main` but
`WithdrawTransferRecord.kt`, `A/{api,data,reminders,share}`, `A/ui/{backup,dashboard,health}`, the manifest,
`ManifestContractTest`, shipped androidTest files, `tools`, `docs`. **Must NOT:** offer Mark before a created pack or
Withdraw without an open OUT; keep a pack after leaving; show a write affordance but Delete and Withdraw for a held owner
on any of the five screens; change an ordinary archived asset's actions; carry an indicator by colour alone. **Counted
RED (16 JVM, 2 device):** 29 (1), 30 (3), 31 (2), 32 (6), 33 (1), 34 (2), 35 (1); 36 (2 device) — row 37 none. **Caps:**
16 JVM, 2 device; **6 hours**; stop also past about 300 lines in `AssetDetailScreen.kt`, or at any manifest entry. **Size:** about
1,450 production, 1,300 test lines.

**B5. Runs after B4 like any brief.** **Read:** `v1.md`; the MCP `server.py` import block and README;
`docs/release-proofs.md` (:107, R4); `ReleaseProofPolicyTest`; SPEC12's subject rule. **`<base>`** = B4's tip. **Rows:**
38–39. **Needs:** R77-2, 17, 19, 21. **Connected:** none new; #77's four device classes run once each for their times;
`(cd tools/servicetag-mcp && uv run --frozen pytest)` at 68 tools. **Greps:** every `'format \*?\*?1–'` hit over `docs/api
tools/servicetag-mcp/src tools/servicetag-mcp/README.md` reads `1–14`; `'14 since #77 \(transfer records\)'` in `v1.md`
≥ 1; `'eighteen tables'` over `docs/api tools/servicetag-mcp/src` → 0, `'nineteen tables'` ≥ 1 in `v1.md` and
`server.py`; `'ASSET_TRANSFERRED_OUT|TRANSFER_DIVERGED'` in `v1.md` ≥ 2; in `docs/release-proofs.md` → 0: ``'Room schema 13 / backup format 13|`schemaVersion` 13|`backupFormatVersion` 13|format-13 round trip|carry schema 13 under'``,
`'schema 8\) → schema 14'` → 1, `'loans at schema 13'` → 1; the R4 row and `ReleaseProofPolicyTest` unchanged unless
R77-2 (c) or R77-19 is ruled in; `'^\*\*Amendment \(#77, '` in SPEC12 → 1. **Untouched:** `core/src/main`,
`A/{ui,reminders,data,share}`, `app/schemas`, `app/src/androidTest`, `tools/emulator`, `tools/servicetag-{bundle,schedules}`,
`share-test-sender`, `docs/versioning.md`, the manifest. **Must NOT:** add a tool or a device class; bump `versionName`;
rewrite ratified spec text. **Report:** #77's device classes for the gate's `EXTRA_CLASSES`, each with its measured time
(§4's budget). **Counted RED (1):** 38 (1). **Caps:** 4 JVM, 0 device; **3 hours**; stop also if the gate paragraph needs
a step the 1.4.1 API cannot drive. **Size:** about 200 production and 60 Python, 250 test, 150 docs lines.

## 14. Owner rulings (binding on every brief)

**R77-20 — reminder quiescence; the discovered defect fixed inside #77 (owner, 2026-09-28; no separate issue).**
- A transferred-out asset produces **no** sender-side maintenance reminder or maintenance attention after the transfer
  is committed; its warranty and deadline subjects are quiesced too — the sender no longer owns maintenance
  responsibility (C11).
- The existing defect is fixed in #77: active maintenance schedules on an **ARCHIVED or retired** asset stop projecting
  maintenance reminders. This **corrects pre-existing behaviour** and the wrong comments (`ReminderHealthCheck.kt:276-277`,
  `ScheduleStatus.kt:72-74`); it is not a silent change, and AC 11 is read with it (ordinary archive stays reversible,
  exported and unarchivable).
- Existing **standing** maintenance notifications are proven withdrawn at that transition, not merely not re-posted
  (rows 14, 16, 19, 35; C22).
- Reminder semantics stay domain-specific: R72-10 stands — a retired or archived asset with an open loan still reminds;
  #77 refuses a transfer while a loan is open, so no loan reminder can be active on a held asset (C2, C8, C12).
- History stays history: returned loans, maintenance history, service cases and warranty facts are kept; quiescing
  deletes nothing (C8, row 10).
- The transfer carries an **explicit, tested invariant** — held asset ⇒ no sender-side reminder or actionable
  projection — through every subject builder, the dashboard and attention projections and every actionable list,
  independent of ARCHIVED (C11, row 15, real mutations, JVM first).
- The rest of the audit's direction stands: the outer pack around the unchanged archives; schema/format 14 for the
  record; refusing any cut that would invalidate the sender's remaining estate; refusing a transfer with an open loan.

**Owner directions for rev 2 (2026-09-28)**, designed to as recommendations for the gate: R77-12/R77-25 (stale
resurrection refused, explicit transfer back accepted, a double mark detectable and resolvable) and R77-4 (inspection
only, every guard's home enumerated). The roadmap ruling (§4's budget, R77-22) binds; the rest of §7 awaits the gate.

**Gate rulings (owner, 2026-09-28) — rev 2.1 approved; every ruling below binds every brief and supersedes §2/§7 text
where they differ.**
- **R77-1** the nested ZIP, a `.zip` filename, `application/zip`. **R77-2 (a)+(b)** in-app import and share-to-ServiceTag;
  no `ACTION_VIEW`, no manifest change. **R77-3** ARCHIVED plus the append-only OUT / IN / WITHDRAWN records.
- **R77-4 full read-only:** a held asset is inspectable and ordinarily immutable everywhere — UI, child surfaces, API and
  MCP-backed writes. **`DeleteAsset` stays allowed** as the explicit destructive "forget this local history" operation,
  and **the transfer records survive it**; category-only rewrites and derived/device-local state stay the documented
  exceptions.
- **R77-5** an explicit, append-only, phone-only withdrawal; a withdrawal does **not** propagate through ordinary merge —
  it is repeated explicitly on each installation. **The rm-8 recovery is APPROVED (amends C15):** a later pack whose
  lineage names an OUT this phone has since withdrawn still counts as an explicit return, so a mistaken withdrawal never
  permanently strands a legitimate later return.
- **R77-6** an open loan refuses creation and marking; returned loans stay sender-local and leave later ordinary
  backups. **R77-7** open service cases and entries travel. **R77-8** a child cannot transfer without its parent;
  selecting a parent forces its subtree. **R77-9** a group with any row naming a staying asset, historical membership
  included, moves whole or not at all — no partial-group semantics. **R77-10** any asset not currently held may be a
  root, archived and retired included; its lifecycle facts travel verbatim. **R77-11** an explicit transferred-out
  `Resolution`; a scan does not stamp the old phone's tag record.
- **R77-12** the record model with lineage: stale ordinary archives never close an OUT; explicit packs can; a double
  mark is `TRANSFER_DIVERGED`, resolved by withdrawing the incorrect OUT; an incoming ordinary merge never closes another
  installation's OUT (withdrawal stays local and explicit); for several current INs `lineageFor` takes the latest `at`,
  then the id.
- **R77-13 — the controller's recommendation is NOT accepted; C9 is amended:** a Replace restore must not let a stale
  pre-transfer backup silently resurrect an asset this installation transferred out. Before wiping local records,
  `ImportBackupReplace` compares the archive against the local open OUTs and **refuses** the archive (nothing wiped) if it
  carries the graph of an asset held here, **unless the archive's own transfer records contain a later IN whose lineage
  legitimately closes that local OUT** (the same closing rule as C15, the rm-8 recovery included). This keeps stale
  resurrection blocked under Replace while keeping the "Replace restore from the other installation after a legitimate
  return" recovery path. The refusal is a named `BackupSetIncomplete`-style outcome with its own ratified sentence
  proposal routed through the controller before B2a ships it.
- **R77-14** a pack with managed documents needs an attachment folder on the recipient. **R77-15** one optional,
  blank-by-default note. **R77-16** marking only from the ready screen, re-read, re-hashed and re-validated inside the
  write; a stale pack is refused. **R77-17** the API gets the 409 semantics and the status count only — no pack, mark or
  withdraw routes, no new MCP tools, no transfer field on `AssetDto`; `v1.md` documents that a held-but-ACTIVE row (from
  merged transfer history) may read ACTIVE while every mutation answers 409. **R77-18** leaving the ready screen deletes
  the working pack; start-up cleans intake/work copies and aged packs; a restored ready state re-validates its file and
  disables its actions if it is gone. **R77-19** no new outbound foreign-UID proof. **R77-20** stands as ruled above.
  **R77-21** schema/format 14 unreleased under 1.4.1, no `versionName` bump. **R77-22** the existing infrastructure,
  JVM-first: the three justified rendered Compose classes and the two platform proofs, no gratuitous device coverage, no
  device-mutation grinding; the merged-tip timing rule stands (14 min warns; > 15 min promotes #90). **R77-23** phone
  lifecycle mutations reconcile once after the successful write; API mutations settle at the next normal sweep.
- **R77-24 — P77-1…67 (P77-29 withdrawn) RATIFIED verbatim**, with their treatments: the `TRANSFERRED` visual treatment
  and spoken "Transferred", `ic_handover`, all six conditional strings, P77-61's accessibility state description, and the
  eight reused strings. (R77-13's refusal sentence is the one string still to ratify.)
- **R77-25 replace on return, option (a):** append the IN first, replace the stale local asset graph with the returning
  pack atomically, **keep the sender-local returned loans and their contact snapshots** as historical facts. A pack whose
  valid lineage returns the asset is a bearer instrument by design; `v1.md` and the in-app help say so plainly.
- **Rows the amendments add (the pin rule applies):** row 11 (B2a) gains `aReplaceOfAPreTransferBackupIsRefusedForAHeldAsset`
  (nothing wiped; RED "skip the open-OUT check") and `aReplaceCarryingALaterClosingInRestores` (RED "refuse every held
  graph"); row 24 (B3) gains `aPackWhoseLineageNamesAWithdrawnOutStillReturns` (RED "treat a withdrawn OUT as closed for
  good"); row 25 (B4) gains `deleteAssetOnAHeldAssetKeepsTheRecords` (RED "cascade the records"). **Caps move with
  them:** B2a 18 → 20 counted RED, B3 16 → 17, B4 16 → 17; time boxes unchanged.
- **P77-68 RATIFIED verbatim (owner, 2026-09-28)** — R77-13's refusal sentence, one / several, in the §6 table. B2a adds
  the typed refusal only; B3 maps it in `BackupViewModel`'s restore error path (the one-asset form names the asset as
  P77-67 does; two or more take the `<n>` form), beside `TransferredGraphEntangled` → P77-58. All #77 strings are now
  ratified.
- **B2a review rulings (owner, 2026-09-28; the B2a task review's MJ-1 and NOTE 1):**
  - **R77-B2a-MJ1 — C6's closing rule amended, option (a).** An IN closes an OUT when both are for the same asset
    and **either** the IN's lineage names the OUT's pack **or** the IN's own pack id is the OUT's. A recipient's
    `IN(q)` means "q arrived here": an `OUT(q)` merged in later cancels against it for custody rather than making the
    actual holder "transferred out". **`returnsHere` stays lineage-only (R77-13 unchanged):** a same-pack IN is
    evidence that the pack arrived, never evidence of a later transfer back. Consequence, for B5's `v1.md`: after S
    transfers X to R, ordinary sender/recipient merge is **directional, recipient ← sender** while R holds X. The
    reverse refuses loudly (M1's `ASSET_TRANSFERRED_OUT`) and never tries to synchronise custody.
  - **R77-B2a-MARK — C8 amended.** `MarkTransferredOut` succeeds only if the **complete** retained estate after the
    proposed mark, `retain(snapshot, held ∪ packAssets)`, is `Retained`. Any `Entangled` result refuses (P77-58), with
    no before/after exemption for entanglements this mark did not introduce. It is stricter on purpose: an estate that
    cannot produce a valid backup gets no further ownership transition until the bad reference is resolved.
  - **R77-B2b-GUARD — C12 widened.** B2b's write guard refuses an ordinary write not only when the row is owned by a
    held asset, but also when a retained row would gain a hard reference into a held graph. Example: a staying asset's
    event naming a transferred asset's schedule, profile or measurement definition, or a subject naming a held
    schedule. The reference semantics are `TransferGraph.retain`'s, the one canonical definition; there is no separate
    list. The refusal is `AssetTransferredOut` / API 409, and there is no new phone string because the path is
    API-reachable.
- **R77-B3-RETURN (owner, 2026-09-28; the B2a review's NOTE 2) — C15 amended.** A Transfer Pack is accepted as a
  return only if importing it leaves the asset **not held** on this installation. `returnsHere(...)` establishes
  legitimate return ancestry; it is necessary, not sufficient. Before any scoped replacement, C15 computes the
  transfer state that would exist once the incoming IN is appended. If **any** OUT of that asset would stay open, it
  answers the ratified stale/foreign refusal **P77-67** for that asset and performs no write and no byte replacement.
  The rm-8 shape is the case in point: a withdrawn OUT named by the lineage, plus an unrelated open OUT. Recovery is
  explicit: withdraw the remaining OUT (C23), then import again. A withdrawn OUT still establishes ancestry (rm-8);
  it never grants a pack permission to erase or ignore a different outstanding transfer. No new string.
- **Whole-branch review rulings (owner, 2026-09-29; the branch review's MJ-1, MN-1, MN-3 and NOTE 4):**
  - **R77-WITHDRAW — C23 and R77-5 amended.** A withdrawal is **atomic and pack-wide**. It works on the selected
    open OUT's pack id: it appends a WITHDRAWN for every asset whose OUT of that pack is still open here, in one
    transaction. Before committing, the complete estate that would remain must retain cleanly by marking's rule;
    otherwise it refuses the whole withdrawal and writes nothing. A withdrawal now means "undo this Transfer Pack's
    local OUT disposition". If reality was mixed, withdraw the pack and create a new Transfer Pack for the subset
    that actually left. There are no partial-withdraw semantics.
  - **R77-CREATE-SAFETY — C2/C18 amended.** `CreateTransferPack` evaluates the hypothetical post-mark estate,
    `retain(snapshot, held ∪ selected)`, and refuses before a file exists if it is entangled; a selected held asset
    is refused there too (P77-57). Marking re-checks inside its write. Create proves the transfer can safely be
    committed now; Mark re-proves it is still safe.
  - **R77-IMPORT-SWEEP — R77-23 widened.** Every successful Transfer Pack import, from either door, runs one reminder
    reconciliation afterwards, as a post-write step. It is not a condition of the import's success.
  - **Strings RATIFIED verbatim:**
    - **P77-64 amended:** `Use this only if this Transfer Pack did not leave, or another phone's record should stand. Its assets stay archived.`
    - **P77-71:** `Could not withdraw this record. Nothing was changed.`
    - **P77-72:** `Could not withdraw this record: records on this phone would still point to a transferred asset. Nothing was changed.`
    - **P77-20's reasons:**
      - the entangled refusal: `Transfer Pack not created: records on this phone still point to a transferred asset. Nothing was changed.`
      - the generic one: `Transfer Pack not created: the file could not be written. Nothing was changed.`
    - **P77-10's singular:** `1 document or photo`.
    
    **P77-69 and P77-70 stay pending** until their exact text has been put to the owner.
- **P77-69 and P77-70 RATIFIED (owner, 2026-09-29).** P77-69 was ratified as amended.
  - **P77-69:** `Anyone with this file can import these assets. Share it only with the person or device that should receive them.`
    It is the ready screen's quiet line under P77-24 and is R77-25's plain statement; the app has no help screen.
  - **P77-70:** `Could not mark these assets: records on this phone still point to a transferred asset. Nothing was changed.`
    It is the Mark refusal under R77-B2a-MARK.
  
  Every #77 string is now ratified.
