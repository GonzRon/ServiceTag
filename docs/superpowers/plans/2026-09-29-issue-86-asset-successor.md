# #86 — replace an asset with a distinct successor: plan and briefs (rev 1.1, 2026-09-29)

> **Rev 1.1** applies the plan review (`.superpowers/sdd/2026-09-29-issue-86/brief-review.md`: APPROVE with conditions):
> C-1…C-12 (a future replacement date refused, PROVISIONAL; kept successions re-inserted unguarded; ten more pins;
> MN-1…MN-9) and the one-line NOTEs; §6 is the final table for the R86-23 gate.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-29-issue-86/progress.md`.
> Four briefs (§8–§11) on one branch `issue-86`, strictly sequential — B1, B2, B3, B4 — each `<base>` the previous
> accepted tip; one task review each, at most one bounded fix round each; one whole-branch review; the merge; one
> merged-tip gate. Planned read-only from issue #86 (`.superpowers/sdd/2026-09-29-issue-86/issue-86.json`, 15 ACs) and
> the audit (`.superpowers/sdd/2026-09-29-issue-86/audit.md`, the inventory of record), every citation re-verified on
> master `5167979d` (schema/format 14 unreleased under 1.4.1, MCP 68 tools, nineteen merge tables).
> **The owner ruled R86-1…22 on 2026-09-29 (§7): the audit's recommendations, with R86-13 and R86-15 amended. They are
> DECIDED.** The owner gate CLOSED 2026-09-29: R86-13a DECIDED (no replacement date later than today; the reused
> sentence; no P86-29) and R86-23 RATIFIED (§6, with P86-3, P86-14 and P86-25 amended). **GO B1.**

**Goal:** the owner replaces one physical asset with a *different* one. One reviewed phone workflow, **Replace asset**,
retires the predecessor (unless it is already retired), creates the successor as a new identity, copies only the
configuration the owner ticked (as new rows with new ids and no history), moves only the NFC tags the owner chose, and
appends one immutable succession row, all in **one** write after the final confirm. The predecessor keeps its identity
and its whole history. Both details show a tappable line to the other asset. Backup, restore, merge and #77's transfer
seams learn the row. The API and MCP read it; nothing but the phone writes it.

**Inputs:** issue #86; the audit; the #77 plan (§2 C1–C15, §14 rulings, §15 errata) and the #84 plan (§8, §13: the
11.30-minute gate); `docs/superpowers/planning-policy.md`. Paths: `C/` = `core/src/main/kotlin/com/loosecannon/servicetag/core/`,
`CT/` = `core/src/test/kotlin/com/loosecannon/servicetag/core/`, `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`,
`T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`, `AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`,
`M/` = `tools/servicetag-mcp/`.

## Global constraints

- **The owner's product intent, binding on every brief:**
  - **R86-C1.** The successor is **distinct**: a new identity with a new id. The predecessor's identity and history
    are kept as history. The predecessor is never mutated into the new machine: no write of Replace changes its name,
    manufacturer, model, serial, purchase, warranty, documents, journal, closures, conditions, activations, cases,
    loans or components. Its one change is the retirement the owner confirms (R86-3).
  - **R86-C2.** Every carry-forward is explicit and reviewed: unticked by default, listed on the review, written only
    as new rows with new ids, never history.
  - **R86-C3.** NFC reassignment follows its own deliberate rule (C14): per tag, Leave by default, confirmed once,
    never implicit, never an NDEF rewrite.
- **Roadmap (owner, 2026-09-29):** #86 → #85 → release 1.5.0 → #90. **No version bump:** `versionName = "1.4.1"`,
  `versionCode = 17` (`app/build.gradle.kts:51-52`); 1.5.0 is cut later. **Schema 15 / format 15** ride master
  unreleased: `MIGRATION_14_15`, `Migration14To15Test`, `SCHEMA_VERSION`, `VersionAgreementTest`, `FORMAT_VERSION`,
  `FIRST_SUCCESSION_FORMAT = 15`, **`LAST_LEGACY_FORMAT` stays 7** (`C/backup/LegacyArchive.kt:34`), the "≤ 14
  carrying one is refused" pattern, `MergeTable` + tallies on both sides of the wire, "twenty tables".
- **One backup format, one merge engine, one NFC identity.** No DTO fork (`AssetDto` unchanged, R77-17's reasoning),
  no UPDATE verdict, no NDEF write, no new tag use case, no new `Resolution`. The 2.6 tombstones `external_link` /
  `externalLinks` are never touched, bumped, repurposed or dropped. The #77 transfer records stay append-only and
  unchanged in shape.
- **No manifest change;** no new exported component, permission, intent filter or notification channel.
- **Tests.**
  - JVM first, with the existing fakes (`FakeUnitOfWork.commits` / `rollbacks` prove atomicity; `FakeGraph` is
    Room-backed, so app-side cascades are real). A device case only where a rendered boundary requires it, justified
    per class (C19): #86 adds **one** instrumented Compose class, ≤ 6 cases, **no device mutations** (R86-21).
  - **No #90 harness work:** no gate script, runner, `tools/emulator/*` or androidTest helper change (`clearInstall()`
    stays as it is: the CASCADE empties `asset_succession` with the assets); no Robolectric or JVM Compose.
  - **No rerun until green.** A device failure is reported with its log and stops the brief.
  - Every counted RED is a real mutation run with `--no-build-cache --rerun-tasks` (a `--tests` filter allowed), its
    failing assertion quoted, reverted before the commit. A row with no natural RED says why.
- **The merged-tip gate runs once**, after the merge (§4). Its 14- and 15-minute lines are **reporting only**; #86's
  device delta is recorded against the #84 baseline: **11.30 min whole, 10.33 min device, 54 classes**.
- **Review budget:** one task review per brief; at most one bounded fix round per brief; one scoped re-review only for
  a substantive correctness finding; mechanical fixes (comments, ratified wording, renames, test tidies, under about 50
  lines) close by controller inspection plus the automated gates; one whole-branch review; the merge immediately after
  it; then the gate.
- **Bounded:** every brief states a counted-mutation cap, a time box and its stop conditions (§8–§11). A **fix round**
  is capped at **6 mutation runs and 90 minutes for B1 and B2, 3 mutation runs and 60 minutes for B3 and B4, none on
  the device**; it touches only its findings' files and stops on the first finding it cannot close.
- **Standing rules.**
  - Every user-visible string is ratified before it ships (§6; R86-23 ratified). A reused string goes through its existing
    home, one home per literal; B3 hoists a reused inline literal to a named constant in its home file, text
    byte-identical (§6).
  - The gitlink `libs/nfc-tag-core` stays `7e0377a`.
  - Commits: one casual lowercase subject line; no body, no trailers, no AI attribution. **No personal data:**
    fictional fixtures only ("Example Water Heater", "Sample Pool Pump", "Example Garage Door Opener", tag keys
    `TEST-…`).
  - Device runs on `emulator-5554` only. Implementers never start, stop or restart the emulator or adb
    (`tools/emulator/prepare-emulator.sh` only waits for the device and settles one setting).
  - **Wording (R86-15, owner):** deleting an asset is never called an "undo" — not in the plan's code, KDoc, docs or
    strings. Slice 1 has **no succession unlink and no undo**; that is its recorded limitation (§1).

## 1. Goal and scope

**Requirements.** The audit's 108 (85 body, 15 ACs, 8 non-goals) stand as tagged: **KEEP 89, NARROW 12, DEFER 6,
DROP 1** — the owner approved the dispositions. RQ-60 stays **DROPPED** (no lost/retired/released-tag workflow is
manufactured). The six DEFERs stay deferred: the REPLACEMENT event link (RQ-13), the note (RQ-14), other cardinalities
(RQ-21), other entry points (RQ-35), #15 supplies (RQ-44), #47 installed components (RQ-65).

| requirements | contract |
|---|---|
| RQ-2…12, 15–20; AC-1, 2, 5 | C1, C2, C10 |
| RQ-81, 82, 84, 85; AC-12, 13 | C3–C6 |
| RQ-24…34, 36, 37, 79, 80; AC-2, 3, 14 | C8–C10, C17 |
| RQ-38…43, 45…55; AC-6, 7 | C11–C13 |
| RQ-56…59, 61, 62; AC-8, 9 | C14 |
| RQ-63, 64, 66…78; AC-10, 11 | C8 (named, never moved), C10 (nothing else written) |
| RQ-22, 23; AC-4 | C16 |
| RQ-83 | C20 |
| AC-15 | Global constraints |

**Planner findings beside the audit** (each re-verified on `5167979d`; the briefs carry them):
1. **The MANUAL phase question.** The editor's S35 `IS_THIS_ASSET_IN_SEASON` ("Is this asset in season right now?",
   `A/ui/asset/AssetEditScreen.kt:115`) is asked "with no default". R86-13 (amended) dates the successor's activation
   on the replacement date, which may be back-dated, so "right now" is wrong there: P86-28 is proposed (§6), with the
   editor's S36/S37 options reused and **no default** (C13).
2. **The activation date.** `SaveAssetSettings` writes `manualSwitchActivation(…, today.localDate(), …)`
   (`C/usecase/SaveAssetSettings.kt:137-138`; `SeasonCommands.kt:184-201`). The extracted body takes the activation
   day as a parameter; `run` passes today, so shipped behaviour is unchanged (C15).
3. **Archived set-up rows.** An unarchived DERIVED definition may name an archived source, which archiving allows
   (`C/usecase/ArchiveDefinition.kt` KDoc), and a schedule may name an archived definition or profile. "Never archived
   rows" (audit §5.2) would break the remap, so C12 clones the reference closure and keeps a referenced archived row
   archived.
4. **Profiles have no key.** `EventProfile` carries no `key` (`ApplyTemplate.kt:84-101`); a copied schedule's
   `profileId` is remapped through the clone's id map, and its meter definition through the key map (C11, C12).
5. **Groups.** The guard refuses a group save when any row names a held asset (#77 C12). An archived group is not a
   place to join. The offer therefore excludes both (C8). `SaveGroup` writes no recompute (`SaveGroup.kt:43-83`), so
   the successor's window needs none.
6. **Tag rows.** ACTIVE rows include provisioned-unwritten ones (`writtenAt == null`); they are movable like any ACTIVE
   row. The form shows each by `TagBinding.identityLine()` (`A/ui/scan/ScanViewModels.kt:375`) and its placement,
   which are data, not words.
7. **Reused strings.** The audit's 19 grow to 24: S36 `IN_SEASON_NOW`, S37 `OUT_OF_SEASON_NOW`, the parent picker's
   `NO_PARENT` ("None", `AssetViewModels.kt:1872`) and its " (archived)" label, reached by extracting the editor's
   `choicesIn` rule (`AssetViewModels.kt:2635-2647`), and (rev 1.1, C-1) `DATE_NOT_LATER_THAN_TODAY`. Most reused labels
   are inline literals today, so B3 hoists them; `Cancel` gets its home in the retire dialog (C-7).
8. **Pins the audit did not list:** `M/tests/test_tools.py:757-806` (nineteen tallies, "1–14") and the review's seven
   (MJ-3, rows 7 and B4); the README has no tool count (the tool joins its schema-minimum list). `CommandShapesGoldenTest` and `ReferenceRoutesTest` read only
   `docs/api/v1.md`, so they move in B4 with the document, not in B1.
9. **`entangledRefs` gains no succession rule.** `retain` drops a succession naming a held asset like a loan, and a
   transfer back keeps and re-inserts it (C6); neither is ever `Entangled`.

**In scope:** C1–C21. **Out of scope** (follow-up candidates): many-to-one and one-to-many succession; an API or MCP
Replace command; a later "move to the successor" action on a replaced predecessor's tags; tag lifecycle actions (mark
lost, retire, release); **a succession unlink or undo** — slice 1 has none, and deleting an asset is a destructive
operation that happens to remove the row and may itself be refused (`AssetMembershipReferenced`,
`C/usecase/DeleteAsset.kt:62-63`); entries from the retire follow-on, #82 or #79; a list, Dashboard or scan-sheet
indicator; a REPLACEMENT-event link and a note column; copying health subjects, references or attachments; carrying the
health aggregation or primary subject; lineage in Transfer Packs; #47 and #15; an UPDATE merge verdict; draft
persistence across process death (recorded: the draft is lost and nothing was written); a `versionName` bump; the phone.

## 2. Contracts

### B1 — the succession record (C1–C7)

```kotlin
// C/model/AssetSuccession.kt (B1). Immutable, appended once. It leaves only by an endpoint's CASCADE,
// ImportBackupReplace's wipe, or a transfer back's cascade (which re-inserts it, C6).
data class AssetSuccession(
    val id: String,
    val predecessorAssetId: AssetId,
    val successorAssetId: AssetId,
    val replacedOn: String,        // ISO YYYY-MM-DD; on the phone, the predecessor's retiredOn (R86-3)
    val createdAt: Long,
)
// Pure (B1): the one home of I1–I4, used by the codec's graph check, merge's MS3/MS4 and ReplaceAsset.
sealed interface SuccessionProblem {
    data class SelfLink(val id: String) : SuccessionProblem
    data class PredecessorTaken(val id: String, val holder: String) : SuccessionProblem
    data class SuccessorTaken(val id: String, val holder: String) : SuccessionProblem
    data class Cycle(val ids: List<String>) : SuccessionProblem   // every row on the cycle, sorted
}
fun successionProblems(rows: List<AssetSuccession>): List<SuccessionProblem>
// C/ports/Repositories.kt (B1): no update, no delete.
interface AssetSuccessionRepository {
    suspend fun append(row: AssetSuccession)
    suspend fun all(): List<AssetSuccession>
    suspend fun replacedBy(predecessor: AssetId): AssetSuccession?
    suspend fun replaces(successor: AssetId): AssetSuccession?
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<AssetSuccession>>   // rows naming it at either end
}
```

- **C1, invariants (R86-1, R86-2).** I1 no self; I2 at most one row per predecessor and one per successor; I3 chains
  are rows (A → B → C is two); I4 acyclic; I5 both endpoints exist; I6 appending never writes an asset column; I7 no
  update; I8 no **new** row names a held asset — a local row a transfer back re-inserts is not new (C6); I9 `replacedOn` is an ISO date. "Succession" names the concept in
  code, schema and wire; "Replace asset", "Replaced by" and "Replaces" name it in the UI (R86-2: "replacement"
  collides with `EventKind.REPLACEMENT`, `ImportBackupReplace` and the typed `REPLACE` restore). No `note` and no
  `replacementEventId` column (R86-17, DEFER).
- **C2, schema 15 (R86-1, R86-15, R86-20).** `MIGRATION_14_15` creates `asset_succession` (`id` TEXT PRIMARY KEY,
  `predecessor_asset_id`, `successor_asset_id`, `replaced_on` TEXT, `created_at` INTEGER; no `updated_at`), both foreign
  keys to `asset(id)` **`ON DELETE CASCADE`**, `index_asset_succession_predecessor_asset_id` UNIQUE and
  `index_asset_succession_successor_asset_id` UNIQUE, SQL copied verbatim from the exported `15.json`, and nothing else,
  on the `Migrations.kt:677-696` pattern. Wired: `AppDatabase` `version = 15`, `AppGraph`'s migration list,
  `SCHEMA_VERSION = 15`, `MigrationTestSupport` (`MIGRATION_14_15`, `V15_TABLES = setOf("asset_succession")`). The
  Room port: `append` is an abort-on-conflict insert. The core fake `InMemoryAssetSuccessionRepository` mirrors
  CASCADE through `assets.cascadesTo(it::cascadeFromAsset)` (the #72 loans precedent,
  `CT/testing/InMemoryRepositories.kt:136-157`), wired in `BackupInstall` and every core fixture that builds a graph.
  **Delete (R86-15):** deleting either endpoint cascades the row, with no refusal and no re-linking; A → C is never
  synthesised. **Unretire** stays allowed and the row stays: history is history.
- **C3, format 15 (R86-20).** `FORMAT_VERSION = 15`, `FIRST_SUCCESSION_FORMAT = 15` beside `FIRST_TRANSFER_FORMAT`
  (`BackupCodec.kt:150`); `BackupData.assetSuccessions = emptyList()` appended after `transferRecords`, sorted by id,
  closing `data.json`; manifest `counts` gain `assetSuccessions` (25 → 26); a format < 15 archive carrying one is
  `BackupCorrupt` (the `:365-366` pattern). **Graph check** (after the loans and records blocks): ids unique; both
  endpoints in `assets`; `successionProblems(rows)` empty. **Content check:** blank id; `SelfLink`; `replacedOn` not an
  ISO date; `createdAt ≤ 0`. Encode stays non-validating (#77 §1 (3)); C5 keeps encode's inputs valid.
- **C4, merge (R86-19).** `MergeTable` appends `SUCCESSIONS` after `TRANSFERS` (twenty tables); `MergeReason` appends
  `SUCCESSION_TAKEN` then `SUCCESSION_CYCLE` after `TRANSFER_DIVERGED`; no ordinal moves; the snapshot gains the rows
  and nothing else; `MergeWrites.successions`; the core report, `ApiDtos`' `successions: MergeTallyDto` (last) and the
  wire gain the tally. Evaluated in the order MS1 → MS2 → MS3 → MS4 → M2, with S′ = the local rows ∪ this plan's rows
  still INSERT (NT-1; an in-plan holder or cycle cannot come from a decoded archive, so row 4's in-plan cases build
  `BackupData` directly): **MS1** present and equal → `IDENTICAL`, present
  and different → CONFLICT `CONTENT_DIFFERS`; **MS2** each endpoint here or inserted by this plan, else CONFLICT
  `OWNER_NOT_AVAILABLE` naming the missing asset (the loans pattern, `MergePlanner.kt:1016-1017`); **MS3** another row
  in S′ with the same predecessor or the same successor → CONFLICT `SUCCESSION_TAKEN`, detail the holder's row id (the
  `ASSET_ALREADY_LENT` pattern, `:1018-1019`), whether the holder is local or in the plan; **MS4** an inserted row on a
  cycle of S′ → CONFLICT `SUCCESSION_CYCLE` on each inserted row of the cycle; **M2 (#77)**
  `TransferOwnership.of(succession) = [OfAsset(pred), OfAsset(succ)]` joins the `owned` list
  (`MergePlanner.kt:1110-1129`), so an insert naming a held endpoint is CONFLICT `ASSET_TRANSFERRED_OUT`. **No UPDATE.**
  Apply order: after the assets and the loans, before the transfer records (`ApplyBackupMergePlan.kt:176-201`). **The
  dev ↔ production limit is documented, never weakened:** a replacement on one install retires the predecessor there,
  so merging it into an install holding the unretired predecessor is CONFLICT `CONTENT_DIFFERS` on that asset, and
  nothing lands; the owner makes the replacement on the target install, or takes a Replace restore from the source.
- **C5, backups.** `readSnapshot` (`ExportBackupSet.kt:151`) and `BackupRepositories` gain the port. `ExportBackupSet`
  exports every row but those `retain` drops (C6). `ImportBackupReplace` wipes the table by name before the assets (the
  loans precedent, `:170-178`) and inserts after the assets, after R77-13's held check, which is untouched. A Replace
  restore of a format ≤ 14 archive empties the table. `StoreIsEmpty` is unchanged (both keys cascade; its KDoc's loans
  reasoning holds).
- **C6, the #77 seams (R86-16).** `TransferTables.CLASSES["assetSuccessions"] = SENDER_ONLY`. `TransferGraph.select`
  carries `carry("assetSuccessions", data.assetSuccessions) { false }` (the `transferRecords` precedent, `:243`), and
  gains no refusal: lineage never forces selection. `TransferPackReader` answers `Damaged` for a pack carrying one
  (the `:176` precedent). **Hazard 1:** `retain` drops every row naming a held endpoint (the loans rule) and never
  answers `Entangled` for it; `entangledRefs` gains no rule. **Hazard 2:** `ReturnScope` gains `keptSuccessions` —
  every local row naming a returning asset at either end — and the return re-inserts them after the assets, beside
  `keptLoans` (`ApplyBackupMergePlan.kt:176-181`), **through the unguarded store**: they existed before the
  transaction, so I8 does not apply, and the other end may still be held (a row A → B where A returns and B left in
  another pack). **Guard (I8):** `HeldWriteGuard.successions(port)` refuses an `append` naming a held asset at either end
  (`AssetTransferredOut`); `AppGraph` and `FakeGraph` hand every consumer the guarded port **except
  `ApplyBackupMergePlan`, which takes the raw succession port** (MJ-2) — safe, because M2 refuses a held endpoint in the
  plan, which the apply re-plans inside its own write. `DeleteAsset` of a held endpoint stays allowed (R77-4); the row
  cascades.
- **C7, pins at 15.** Every shipped assertion pinning a version, table count, count key, reason, tally or order moves
  with B1, by the #77 B2a row-13 precedent, every other assertion kept (row 7 lists them).

### B2 — `ReplaceAsset` (C8–C15)

```kotlin
// C/usecase/ReplaceAsset.kt (B2)
data class ReplaceDraft(
    val predecessorId: AssetId,
    val retiredOn: String?,          // required iff the predecessor is not retired; ignored otherwise (R86-3)
    val successor: AssetCommand,     // the form's fields; description and notes blank (C13 fills them)
    val carrySeason: Boolean,        // P86-9 (R86-13)
    val manualPhase: SeasonPhase?,   // P86-28's answer; required iff carrySeason and the predecessor is MANUAL
    val carrySetup: Boolean,         // "Readings & actions" (R86-11)
    val carryNotes: Boolean,         // P86-10
    val scheduleIds: Set<ScheduleId>,// R86-10
    val scheduleStartOn: String?,    // P86-12; required iff a ticked schedule has a time rule
    val groupIds: Set<GroupId>,      // P86-11 (R86-12)
    val movedTagIds: Set<TagId>,     // P86-15 (R86-14); every other ACTIVE tag stays
)
class ReplaceAsset(/* guarded ports, uow, ids, clock, today, the five use cases whose bodies C15 extracts */) {
    suspend fun offer(predecessorId: AssetId): ReplaceOffer          // uow.read around its in-transaction body
    suspend fun plan(draft: ReplaceDraft): ReplacePlan               // uow.read around its in-transaction body
    suspend fun run(draft: ReplaceDraft, reviewed: ReplacePlan): ReplaceResult     // one uow.write
}
data class ReplacePlan(
    val problems: List<ReplaceProblem>,  // empty ⇔ Review (and the confirm) is enabled
    val sources: ReplaceSources,         // every row the draft names, exactly as read (C10's stale rule)
    val replacedOn: String,              // the date P86-20/21 and the activation use; never after today (C9)
)
```

- **C8, the offer (R86-5, R86-6, R86-7, R86-8, R86-12, R86-14).** `offer` reads and answers: **eligible** iff the
  predecessor exists, is not held and has no successor — archived, retired, component, DOWN/DEGRADED and case-bearing
  assets are all eligible, and so is a lent one; the predecessor's `retiredOn` (drives P86-3 or P86-4); **schedules** —
  every asset-targeted schedule of the predecessor that is not ARCHIVED (PAUSED included; group-targeted never);
  **groups** — every group with an open window for the predecessor that is not archived and has no row, current or
  removed, naming a held asset; **setup** — offered iff the predecessor has any unarchived definition or profile;
  **season** — offered iff the predecessor is not YEAR_ROUND or has a break; **notes** — offered iff description or
  notes is non-blank; **tags** — the predecessor's `ACTIVE` rows from `tags.forAsset` (link tombstones never enter;
  `LOST`, `RETIRED`, `UNBOUND` never shown); **the parent rule** — the editor's picker rule (`choicesIn`,
  `AssetViewModels.kt:2635-2640`, with `self` = the predecessor): any asset that exists, is not held, and is not the
  predecessor or one of its descendants, or none; **the prefill** — name, category, location, and the predecessor's
  parent iff it passes the parent rule, all editable (R86-8); **named, never moved** — the
  children's names (P86-18) and whether an open loan exists (P86-19). Children, loans, cases, conditions, documents
  and references are never offered.
- **C9, the plan (R86-4, R86-10, R86-13).** `plan` writes nothing. Its problems: `NameRequired`; `BadDate(field)` for
  `retiredOn`, `purchaseOn`, `inServiceOn`, `scheduleStartOn` (blank where required included); the successor's
  `validateAsset` problems; `NotOffered(id)` for any schedule, group or tag the offer does not hold now, and for a
  parent that fails C8's parent rule; `NeedsSetup(scheduleId)` for a ticked schedule naming a meter definition or a
  profile while setup is unticked (P86-13); `NeedsSeason(scheduleId)` for a ticked PRE_SERVICE schedule while the season
  item is unticked (P86-14) — a PRE_SERVICE schedule on a predecessor with no boundary (reachable only by merge or
  import) can never be carried, because the season item is not offered (NT-3); `PhaseRequired` for an unanswered
  P86-28; **`ReplacedOnAfterToday`** (R86-13a, DECIDED) when `replacedOn` is later than `today.localDate()` — the
  one read of today in `ReplaceAsset` — which blocks Review whatever is ticked; an already-retired predecessor whose
  stored `retiredOn` is in the future is refused by the same check (the owner corrects the retirement on the detail
  first). No silent auto-tick. `replacedOn` = the typed `retiredOn`, or the stored one when the predecessor is already
  retired.
- **C10, the one write (R86-3, R86-4, AC 14).** Nothing is written before the final confirm. `run` opens exactly one
  `uow.write` and inside it, in this order: (1) re-reads the offer and the plan through their in-transaction bodies,
  never the public reads (C15); a held predecessor throws `AssetTransferredOut`; a predecessor with a successor, any
  problem, or `fresh.sources != reviewed.sources` throws `ReplaceStale`, all with `commits == 0`; (2) retires the predecessor with `replacedOn` iff it is not retired, through
  `RetireAsset`'s body (its `onLifecycleChanged` recompute included); an already-retired predecessor's asset row is
  not written at all (R86-3); (3) creates the successor through `SaveAssetSettings`' create body (C13); (4) clones set-up iff ticked
  (C12); (5) copies each ticked schedule in id order (C11); (6) adds one window per ticked group in id order (C13);
  (7) retargets each moved tag in id order (C14); (8) appends the succession row: a minted id, `replacedOn` equal to
  the predecessor's stored `retiredOn` after step 2, `createdAt` = now. It returns the successor and the row.
  **Sources** are the predecessor's asset row, its current successor row (null), each ticked schedule's row, each
  ticked group's row, each moved tag's row, and (iff set-up is ticked) every definition and profile row of the
  predecessor; any difference — an edit, an archive, a scan's stamp, a retarget, a merged-in row — is stale (the #77
  `PackOutdated` precedent). **The predecessor invariant (R86-C1):** after the write, every canonical row
  (`readSnapshot`) the store held before is byte-equal except the predecessor's asset row's `retiredOn` and `updatedAt`
  (only when it was not retired), each moved tag's `target` and `updatedAt`, and each ticked group's `updatedAt` plus
  one new member row; derived `schedule_state` is outside it, since `onLifecycleChanged` rebuilds it (MN-9).
- **C11, schedules (R86-10).** A copy is a create (`ScheduleCommand` with `targetAssetId` = the successor) through
  `SaveSchedule`'s create body, so `scheduleProblems` and PRE_SERVICE's 409 (`SaveSchedule.kt:166-178`) judge it. It
  carries title, description, the time rule (interval, unit, basis), `leadDays`, the meter rule (interval, lead) with
  the definition remapped by key, `servicePolicy` and its offset, `completionMode`, `profileId` remapped through the
  clone's id map, `remindersEnabled` and the providers. It **never** carries the schedule id, `postponedDueOn`,
  closures, completion events, derived state, health subjects or device-local delivery rows; its status is the
  create's ACTIVE and `ruleChangedAt` the create's own. **Anchors:** every copied time rule's `anchorOn` is the one
  reviewed `scheduleStartOn` (default: the successor's in-service date, else `replacedOn`, following that date until the
  owner edits it, as the in-service date follows `Retired on`, NT-6) — never the predecessor's
  anchor or last completion; `anchorMeter` is null (the successor's first reading anchors it,
  `ScheduleRecompute.kt:86`). The predecessor's schedules are untouched: its retirement quiesces them by the shipped
  lifecycle rule.
- **C12, set-up (R86-11).** One item clones the predecessor's set-up on `ApplyTemplate`'s key algorithm
  (`ApplyTemplate.kt:53-110`), in a new in-transaction function: definitions minted per `key` (unique per asset),
  entered before derived, derived sources remapped; profiles minted per source id, their fields and consumables with
  new child ids and remapped definitions; `sortOrder` and profile `templateKey` kept; the successor's `templateKey` set
  to the predecessor's (provenance), as `ApplyTemplate` stamps it — **only when set-up is ticked**; the create body
  (C13) always receives `templateKey = null`, so it never seeds a template (`SaveAssetSettings.kt:98-102`, `:146-147`;
  NT-5). **The clone set** is every unarchived definition
  and profile, closed under derived sources, profile fields and the definitions and profiles the ticked schedules name;
  a referenced archived row is cloned **archived** (planner finding 3). Measurements, readings, health subjects and
  their links never carry. It answers the id maps C11 uses.
- **C13, the successor, season, groups and notes (R86-8, R86-9, R86-12, R86-13).** The successor is created by
  `SaveAssetSettings`' create body with the draft's `AssetCommand`, a default health policy, no warranty reminder,
  and: season unticked → YEAR_ROUND, no break; season ticked → the predecessor's mode and CALENDAR window as a
  `SeasonModeCommand`, its break as a `BreakCommand`, and for MANUAL the draft's `manualPhase`. **R86-13 (amended):** a
  MANUAL successor gets exactly one new activation, START for IN_SEASON else END, **dated `replacedOn`, never today**;
  no predecessor activation is copied. A future `replacedOn` never reaches the write: C9 refuses it (R86-13a,
  DECIDED), because a future-dated row would break `RecordSeasonActivation`'s rules (a date after today is
  `SeasonDateOutOfRange`, and every later START or END before that day would be refused as earlier than the latest
  row, `RecordSeasonActivation.kt:23`, `:58-59`) and would read OUT_OF_SEASON until then (`SeasonContext.kt:61-72`).
  **Notes:** iff `carryNotes`, the
  predecessor's description and notes verbatim; otherwise blank. **Groups:** per ticked group, `SaveGroup`'s body with
  **every** open member kept by id — `SaveGroup` soft-removes any open member a command omits (`SaveGroup.kt:52-58`),
  so every other window, open or closed, stays byte-equal (MN-2) — and one new member for the successor (a new id,
  `addedAt` = now, `sortOrder` after the last); the predecessor's window stays open and its history untouched —
  retirement bounds it
  (`GroupCommands.kt:200-208`); a group-targeted schedule is never copied. **Nothing unticked is copied** (R86-9).
- **C14, tags (R86-14, R86-C3).** Each moved tag must be `ACTIVE` and target the predecessor at the write (else
  stale). The move is `BindTag`'s retarget rule, in-transaction: `target` = the successor, `status` ACTIVE,
  `updatedAt` = now; id, payload format and key, label (the placement), `physicalUid`, `writtenAt`,
  `lastScannedAt` and `createdAt` kept. **No NDEF write, no new tag use case, no new `Resolution`, no scan-sheet or
  `OverwriteSubjects` change.** A moved tag then resolves `OpenAsset(tag, successor)`; a tag left behind resolves
  `OpenAsset(tag, predecessor)` (`ResolveTag.kt:96-106`, unchanged), whose detail shows P86-26. A held predecessor
  cannot be replaced, so its tags cannot move; the guard also refuses a retarget whose current target is held.
- **C15, extractions.** `ReplaceAsset` calls in-transaction bodies, never another use case's `run`
  (`FakeUnitOfWork.write` is not re-entrant, `ApplyTemplate.kt:47-52`). **Its own reads likewise (MN-6):** `offer` and
  `plan` each have an internal in-transaction body; the public functions wrap it in `uow.read`, and `run` calls the
  bodies inside its write (a read nested in a write is illegal, `ExportBackupSet.kt:147-149`). Extracted as `internal`, each `run` delegating
  so that its refusals, their order, its return value and its commit count are unchanged: `RetireAsset` (the retire
  upsert and `onLifecycleChanged`), `SaveAssetSettings` (the whole body, **plus the activation day as a parameter**
  that `run` fills with `today.localDate()`), `SaveSchedule` (the create path), `SaveGroup` (the body), `BindTag` (the
  retarget). `CreateAsset` is not used. **The shipped suites of the five, and of `ApplyTemplate` and `CreateAsset`,
  stay green and unchanged.**

### B3 — the phone (C16–C19)

- **C16, the detail (R86-5, R86-22).** `DetailMenuItem.REPLACE` ("Replace asset", P86-1) sits after RETIRE or UNRETIRE
  and before TRANSFER; it is present iff the asset is not held and has no successor (a held asset's menu stays DELETE
  alone). `AssetDetailState` gains `replacedBy` and `replaces` (the other asset's id and name, and `replacedOn`), read
  from one observed `successions.observeForAsset(id)` beside the rows the model already observes. Under the plate,
  after `PartOfLine` and before `TransferredOutBlock` (`AssetDetailScreen.kt:344-349`): on the predecessor P86-26
  "Replaced by <new> on <date>", on the successor P86-27 "Replaces <old>"; a chain shows both. Each is a tappable
  `QuietLine` on the `PartOfLine` pattern (`:835-846`: full width, 44 dp) calling `onOpenAsset(other)`. A held
  endpoint's read-only detail still draws its line (navigation writes nothing). `<date>` is `d MMM uuuu` (P77-33).
  No Assets-list, Dashboard, scan-sheet or retire-dialog change (R86-22).
- **C17, the Replace screen (R86-3, R86-4, R86-8, R86-9).** `Route.ReplaceAsset(assetId)` opens `ReplaceAssetScreen`
  with `ReplaceAssetViewModel` (new `A/ui/replace/`), one screen in two phases.
  - **FORM.** P86-2, then P86-3 with the reused `Retired on` date field (default today) or P86-4 with no field; a
    `ReplacedOnAfterToday` problem draws the reused `DATE_NOT_LATER_THAN_TODAY` under that field, or under P86-4 when
    the stored date is the future one (R86-13a, DECIDED). P86-5, P86-6, then the fields on the editor's composables
    and labels (`CategoryField` and `ChoiceField` become `internal`, MN-3; the Replace model supplies its own category
    choices): `Name`, `Category`, `Location`, `Part of` (the extracted `choicesIn`, `None` and "(archived)" included,
    C8's parent rule) prefilled per C8; `Manufacturer`, `Model`, `Serial
    number`, `Purchase date` blank; `In service date` following the replacement date until edited (R86-8). P86-7,
    P86-8, then each offered item, **every one unticked** (R86-9): P86-9 (with P86-28 and the S36/S37 options when the
    predecessor is MANUAL, no default), `Readings & actions`, P86-10, `Schedules` (one checkbox per schedule, by
    title), P86-12 (shown iff a ticked schedule has a time rule; default per C11), `Maintenance groups` (P86-11 per
    group). P86-13 / P86-14 under a blocked schedule, as text. P86-18 (its one-child or several-children form) and
    P86-19 when they apply. `Tags`: per offered
    tag its identity line and placement, then P86-16 (selected by default) or P86-15; P86-17. `Review` is enabled iff
    `plan` answers no problem.
  - **REVIEW.** P86-20 or P86-21; P86-22; under P86-7 the ticked items by their labels in form order (P86-9, then for
    MANUAL the chosen S36/S37 word on its own line under it, with no separator literal; a schedule by its title;
    P86-11 per group), or P86-23; under P86-15
    the moved tags' identity lines. `Replace asset` (P86-1) commits; `Cancel` returns to FORM, writing nothing.
  - **Commit.** `saving` is set before the first suspension, so a double tap writes once. Success → one
    `ReminderReconcile` sweep after the write (R77-23), then a state-backed `done = successorId` the screen consumes
    once (the #84 one-shot lesson). `AssetTransferredOut` → P77-35; `ReplaceStale` → P86-25, back to FORM with the offer
    re-read, ticks and moves no longer offered dropped, typed fields kept; anything else → P86-24. Cancel or Back at
    any point writes nothing.
- **C18, navigation, wiring and homes.** The detail's REPLACE adds `Route.ReplaceAsset(id)`; `done` replaces the
  Replace entry with `Route.AssetDetail(successor)` (`backStack.removeLastOrNull(); backStack.add(…)`, the
  `ServiceTagRoot.kt:409` precedent), so Back returns to the predecessor. `AppGraph` builds `ReplaceAsset` over the
  guarded ports; `FakeGraph` likewise; the `AssetDetailViewModel` constructor ripple is mechanical and listed. Every P86
  literal has one home, `A/ui/replace/ReplaceStrings.kt` (P86-1 is referenced by the detail menu from there). Every
  reused string is referenced through its home constant (§6), `Cancel` included: it is hoisted from the retire dialog
  (`AssetDetailScreen.kt:730`) beside the `Retired on` hoist (MN-4); the file's three other inline `Cancel` sites are
  left as they are.
- **C19, rendered UI (R86-21).** One instrumented Compose class, `AT/ui/replace/ReplaceAssetFlowTest`, ≤ 6 cases, no
  fixed waits, no device mutations (rows 15–16 carry the REDs). **Its boundary:** rendered semantics — the enabled
  state of Review, the drawn dependency line, the review's drawn lines, and the detail lines' click actions — which no
  JVM test in this build observes (no Robolectric, no JVM Compose; the #77 C21a precedent). No platform boundary is new
  (no intent, grant, manifest or notification change); the standing post's take-down after retirement is #77's proven
  path (`ReminderPlatformDeviceProofTest`), with no new case.

### B4 — API, MCP, docs and the gate at 15 (C20–C21)

- **C20, API and MCP (R86-18).** `/v1/status` `counts` gain `assetSuccessions` (the `transferRecords` precedent,
  `ApiHandlers.kt:187-189`). **`GET /v1/assets/{id}/succession`** → 200 `{"replaces": <row>|null, "replacedBy":
  <row>|null}`, each row the archive's DTO `{id, predecessorAssetId, successorAssetId, replacedOn, createdAt}`; 404 for
  an unknown asset; any other verb 404 (the sub-resource convention, `ApiRouter.kt:139-141`); the twenty-first
  sub-resource. **No write route**, no `AssetDto` field. **MCP** `get_asset_succession(asset_id)` at a schema-15
  per-tool minimum (`_MIN_SUCCESSION_SCHEMA_VERSION = 15`, refusing an older app with nothing sent, the loans pattern):
  68 → 69 tools. No MCP Replace command.
- **C21, docs and the gate at 15 (R86-15, R86-16, R86-19, R86-20).** `docs/api/v1.md`: the route; the count; format
  **1–15** at both sites with "15 since #86 (asset successions)"; twenty tables; `SUCCESSION_TAKEN` and
  `SUCCESSION_CYCLE` with MS1–MS4 and M2; that merge never retires anything (the dev ↔ production limit); that a
  succession never travels in a Transfer Pack and leaves ordinary backups while an endpoint is held; that deleting an
  endpoint removes the row; twenty-one sub-resources in the 405 row. MCP: `import_merge`'s docstring and README "format
  1–15", "twenty tables", `successions` last; the tool in the README's schema-minimum list. **The gate paragraph**
  (`docs/release-proofs.md:107`) becomes the direct **1.4.1 (schema 8) → schema 15** proof (§5). `ReleaseProofPolicyTest`
  and R4 unchanged.

## 3. Test matrix

| row | hazard | test (class · case) | RED mutation |
|---|---|---|---|
| **B1** 1 | C1: invariants | core `CT/model/AssetSuccessionTest` · `aSelfLinkIsRefused`; `aChainOfThreeIsValid`; `aSecondSuccessorOfOnePredecessorIsTaken`; `aSecondPredecessorOfOneSuccessorIsTaken`; `cyclesOfTwoAndThreeAreRefusedNamingEveryRow`; `theFakeCascadesLikeRoom` (either endpoint deleted) | (1) skip the cycle walk; (2) drop the successor-side uniqueness |
| 2 | C2: schema 15 | app `T/data/room/AssetSuccessionRoomTest` · `aSecondRowForOnePredecessorIsRefusedByTheIndex`; `aSecondRowForOneSuccessorIsRefused`; `deletingThePredecessorCascades`; `deletingTheSuccessorCascades`; `observeForAssetSeesBothEnds`; `Migration14To15Test` · rows survive, the table exists empty, the schema equals a fresh 15 | (1) drop the successor UNIQUE; (2) `SET_NULL` instead of `CASCADE` on the successor key |
| 3 | C3: format 15 | core `BackupFormat15Test` · round trip; `counts` gain the key (26); shuffled rows give equal bytes; the list closes `data.json` after `transferRecords`; a format-14 archive carrying one is `BackupCorrupt`; `BackupCodecTest` · `anEndpointMissingIsCorrupt`, `twoSuccessorsOfOnePredecessorAreCorrupt`, `aCycleIsCorrupt`; `BackupContentCheckTest` · blank id, self-link, bad date, `createdAt ≤ 0` | (1) skip the ≤ 14 check; (2) skip `successionProblems` in the graph check; (3) skip the date check |
| 4 | C4: merge | core `CT/merge/MergePlannerSuccessionTest` · MS1 identical and differs; MS2 missing endpoint, and an endpoint the plan inserts; MS3 taken by a local row and by an in-plan row, each side; MS4 a two-row and a three-row cycle by union; M2 either endpoint held; `theDevProductionLimitConflictsOnThePredecessor` (nothing lands); `successionsTally`; `ImportBackupMergeTest.successionsLandAfterAssetsAndLoans` | (1) drop MS3; (2) drop MS4; (3) drop the succession from M2's owned list; (4) apply before the assets |
| 5 | C5: backups | `ExportBackupSetTest` · `aSuccessionNamingAHeldAssetIsNotExportedAndTheArchiveDecodes`; `successionsAreExported`; `ImportBackupReplaceTest` · `successionsAreWipedAndReloaded`, `aFormat14RestoreEmptiesTheTable`; `StoreIsEmptyTest` unchanged | keep the list whole in `retain` (Hazard 1) |
| 6 | C6: the #77 seams | `TransferTableClassificationTest` · the new list classified (its shipped reflection case is the natural RED); `TransferGraphTest.successionsNeverTravel` (both endpoints selected; the pack carries none); `TransferPackCodecTest.aPackCarryingSuccessionsIsDamaged`; `TransferGraphRetainTest.aSuccessionNamingAHeldAssetDropsAndIsNeverEntangled`; `TransferBackTest.aReturnKeepsTheLocalSuccessionRows` (a returning predecessor and a returning successor); `TransferBackTest.aReturnKeepsASuccessionWhoseOtherEndIsStillHeld` (A → B, A returns, B stays held: the return applies and the row is back, MJ-2); `HeldWriteGuardTest.aSuccessionNamingAHeldAssetIsRefused` (either end; `commits == 0`) | (1) omit `keptSuccessions` (Hazard 2); (2) write the kept successions through the guarded port; (3) unwrap the port; (4) bypass `carry` in `select` (`assetSuccessions = data.assetSuccessions`, with `sorted()` carrying the list; MN-1: `carry` returns empty for every SENDER_ONLY class whatever the predicate); (5) drop the reader check |
| 7 | C7: pins at 15 | the row-7 list in §8, mechanical, every other assertion kept; `Format7RestoreContractTest` one connected run | none: a pin moves with the number |
| **B2** 8 | C8: the offer | core `CT/usecase/ReplaceAssetTest` · `aHeldAssetIsNotEligible`; `aReplacedAssetIsNotEligible`; `archivedRetiredComponentLentAndCaseBearingAreEligible`; `offersNonArchivedAssetSchedulesOnly` (PAUSED in, ARCHIVED and group-targeted out); `offersOpenWindowsOfUnarchivedGroupsWithNoHeldRow`; `offersActiveTagsOnly` (LOST, RETIRED, UNBOUND and a link tombstone never); `theParentPrefillDropsAHeldParent`; `childrenAndAnOpenLoanAreNamed` | (1) offer an ARCHIVED schedule; (2) offer a LOST tag; (3) offer a group with a held row |
| 9 | C9: the plan | · `planWritesNothing` (`commits == 0`); `aScheduleNamingAMeterOrProfileNeedsSetup`; `preServiceNeedsTheSeason`; `aManualSeasonNeedsTheAnswer`; `aTimeRuleNeedsTheStartDate`; `aNameIsRequired`; `anIdTheOfferDoesNotHoldIsNotOffered` (a parent failing C8's parent rule included); `aFutureReplacementDateIsRefused` (a typed future `retiredOn`, and an already-retired predecessor whose stored `retiredOn` is in the future: `ReplacedOnAfterToday`; today itself passes) | (1) no `NeedsSetup` for a profile-only schedule; (2) drop the future-date check (R86-13a) |
| 10 | C10: the one write | · `replacesInOneWrite` (`commits == 1`, `rollbacks == 0`); `aRefusalWritesNothing` (each refusal: `commits == 0`); `thePredecessorChangesOnlyItsRetirement` (the C10 invariant, by `readSnapshot` before and after); `anAlreadyRetiredPredecessorKeepsItsDate` (its row byte-equal; `replacedOn` = the kept date); `aHeldPredecessorIsRefused`; `anAlreadyReplacedPredecessorIsStale`; `aStaleDraftIsRefused` (a ticked schedule edited, a moved tag scanned, the predecessor retired elsewhere); `theRowCarriesTheRetirementDate` | (1) retire in its own write (`commits == 2`); (2) rewrite `retiredOn` when already retired; (3) compare source ids only |
| 11 | C11: schedules | · `aCopiedScheduleHasANewIdAndNoHistory` (no closure, event, postponement or state row; ACTIVE; the create's `ruleChangedAt`); `theAnchorIsTheReviewedDate`; `aMeterAnchorIsEmpty`; `profileAndMeterAreRemappedToTheClone`; `thePredecessorsSchedulesAreUntouched` | (1) copy `anchorOn` from the predecessor; (2) copy `postponedDueOn`; (3) keep the predecessor's profile id |
| 12 | C12, C13: set-up, season, groups, notes | · `setupIsClonedWithNewIdsAndRemappedSources` (derived, fields, consumables); `anArchivedSourceOfACarriedDerivedDefinitionIsClonedArchived`; `aManualSuccessorHasOneActivationDatedTheReplacementDate` (a back-dated replacement: `occurredOn == replacedOn`, not today; START for IN_SEASON); `calendarWindowAndBreakCarry`; `aGroupGainsANewWindowAndThePredecessorsStaysOpen`; `aGroupGainsANewWindowAndEveryOtherWindowIsByteEqual` (three or more members, one closed; MN-2); `notesOnlyWhenTicked`; `nothingUntickedIsCopied` (every flag false: no definition, schedule, window or activation; YEAR_ROUND; blank notes; no template seeded) | (1) reuse a definition id; (2) close the predecessor's window; (3) send only the predecessor's window; (4) date the activation today; (5) copy the notes unticked |
| 13 | C14: tags | · `aMovedTagKeepsItsIdKeyLabelAndWrittenAt` (only `target` and `updatedAt` differ); `anUnmovedTagStaysOnThePredecessor`; `aLinkTombstoneIsNeverRead`; `ResolveTagTest.aMovedTagOpensTheSuccessorAndALeftTagThePredecessor` (`ResolveTag.kt` unchanged) | (1) move every ACTIVE tag; (2) mint a new tag row |
| 14 | C15: extractions | the shipped suites (`RetireDeleteAssetTest`, `SaveAssetSettingsTest`, `SaveScheduleRuleFieldTest`, `ScheduleCommandRulesTest`, `ScheduleOperationsTest`, `GroupMembershipTest`, `TagBindingUseCasesTest`, `ApplyTemplateTest`, `SeasonCommandHarness` users, `CrossConceptWriteTest`) green and unchanged | none: the proof is the unchanged suites and the untouched diff |
| **B3** 15 | C17: the Replace model | app `T/ui/replace/ReplaceAssetViewModelTest` · `everyItemStartsUnticked`; `thePrefillIsNameCategoryLocationAndAValidParent` (identity and purchase blank; in service follows the replacement date); `aDependencyLineBlocksReview` (P86-13, P86-14 as text); `anUnansweredPhaseBlocksReview`; `aFutureDateSaysTheShippedSentenceAndBlocksReview` (`DATE_NOT_LATER_THAN_TODAY`, under the field or under P86-4); `oneChildAndSeveralChildrenTakeTheirP86_18Forms`; `nothingIsWrittenBeforeTheConfirm` (the store equal after form, review and cancel); `aDoubleTapReplacesOnce`; `successSweepsOnceThenOpensTheSuccessor`; `aStaleRefusalSaysP86_25AndReReads`; `aHeldRefusalSaysP77_35`; `anyOtherFailureSaysP86_24`; `theReviewListsWhatIsTicked` (P86-20/21, P86-22, labels or P86-23, moved tags) | (1) pre-tick an item; (2) sweep before the write; (3) set `saving` after the suspension; (4) keep a dropped tick after P86-25 |
| 16 | C16: the detail | app `T/ui/asset/AssetSuccessionStateTest` · `thePredecessorSaysReplacedByWithItsDate`; `theSuccessorSaysReplaces`; `aChainShowsBothLines`; `theMenuOffersReplaceBetweenRetireAndTransfer`; `theMenuHidesReplaceWhenHeldOrReplaced`; `aHeldEndpointStillDrawsItsLine`; `AssetTransferStateTest.kt:209-214` retargeted (REPLACE after RETIRE) | (1) offer Replace on a replaced asset; (2) read the line from the wrong end |
| 17 | C18: strings as data | app `T/ui/replace/ReplaceStringsTest` · each template names its assets; `<date>` reads `d MMM uuuu` | format the date as ISO |
| 18 | C19: rendered | device `ReplaceAssetFlowTest` · `theFormDrawsItsUntickedItemsAndADependencyLine`; `reviewIsDisabledOnAProblem`; `theReviewDrawsItsLines`; `bothDetailLinesDrawAndOpenTheOtherAsset` | none: rows 15–16 carry the REDs (R86-21, no device mutation) |
| **B4** 19 | C20: API | app `T/api/SuccessionRoutesTest` · `aPredecessorAnswersReplacedBy`; `aSuccessorAnswersReplaces`; `aChainAnswersBoth`; `anUnrelatedAssetAnswersNulls`; `anUnknownAssetIs404`; `aPostIs404`; `statusCountsAssetSuccessions`; `ReferenceRoutesTest.theApiDocumentAgreesWithTheRouter` and `CommandShapesGoldenTest` retargeted | (1) map an unknown asset to 200 nulls; (2) drop the status count |
| 20 | C20: MCP | pytest `M/tests/test_succession_tools.py` · the route and the answer as sent; an app below schema 15 refused with nothing sent; `test_argument_guard.py` and `test_tools.py` retargeted (69 tools; twenty tallies; 1–15) | none counted: the Python pins are the proof |
| 21 | C21: docs, gate at 15 | B4's anchored greps; `ReleaseProofPolicyTest` green and unchanged | none: words only |

**Planned counted REDs: 44** (B1 17, B2 18, B3 7, B4 2); **no device mutation**. **New JVM cases:** about 70 in core,
about 45 in app; about 4 pytest.

## 4. Files, fences, order and the gate budget

One branch `issue-86` from the plan's ratified commit: **B1 → B2 → B3 → B4**, each `<base>` the previous accepted tip.

| brief | production | tests |
|---|---|---|
| B1 | new `C/model/AssetSuccession.kt`; `C/ports/Repositories.kt`; `C/backup/{BackupCodec,BackupFormat,BackupContentCheck}.kt`; `C/merge/{MergePlan,MergePlanner}.kt`; `C/usecase/{ExportBackupSet,ImportBackupReplace,ApplyBackupMergePlan,BuildBackupMergePlan}.kt`; `C/transfer/{TransferGraph,TransferOwnership,TransferPackReader,HeldWriteGuard}.kt`; `A/data/room/**` (entity, DAO, repository, `AppDatabase`, `Migrations`); `app/schemas/…/15.json`; `A/api/ApiDtos.kt` (the tally only); `A/di/AppGraph.kt` | new `AssetSuccessionTest`, `BackupFormat15Test`, `MergePlannerSuccessionTest`, `AssetSuccessionRoomTest`, `Migration14To15Test`; `CT/testing/{InMemoryRepositories,BackupInstall}.kt`; `T/testing/FakeGraph.kt`; the row-6 classes; row 7's pins |
| B2 | new `C/usecase/ReplaceAsset.kt` and one set-up clone file beside it; `C/usecase/{RetireAsset,SaveAssetSettings,SaveSchedule,SaveGroup,BindTag}.kt` (extraction only) | new `CT/usecase/ReplaceAssetTest.kt`; `ResolveTagTest.kt` (one case) |
| B3 | new `A/ui/replace/{ReplaceAssetScreen,ReplaceAssetViewModel,ReplaceStrings}.kt`; `A/ui/asset/{AssetDetailScreen,AssetViewModels,AssetEditScreen}.kt`; `A/ui/nav/{Route,ServiceTagRoot}.kt`; `A/di/AppGraph.kt` | new `ReplaceAssetViewModelTest`, `AssetSuccessionStateTest`, `ReplaceStringsTest`, `AT/ui/replace/ReplaceAssetFlowTest`; `T/testing/FakeGraph.kt`; `AssetTransferStateTest.kt:209-214`; the constructor ripple |
| B4 | `A/api/{ApiRouter,ApiHandlers,ApiDtos}.kt` (route, handler, response, count); `M/src/servicetag_mcp/server.py`; `M/README.md`; `docs/api/v1.md`; `docs/release-proofs.md` (:107 only) | new `T/api/SuccessionRoutesTest.kt`, `M/tests/test_succession_tools.py`; the B4 pins |

**Untouched by every brief** (`git diff <base> --stat -- <paths>` → empty): `app/src/main/AndroidManifest.xml`,
`ManifestContractTest`; `app/build.gradle.kts`; `libs/nfc-tag-core`; `tools/emulator/**`, the controller's gate script
and every androidTest helper (`clearInstall()` included); `share-test-sender/**`; `C/usecase/ResolveTag.kt`,
`C/usecase/ProvisionTag.kt`, `C/nfc/**`; `A/ui/scan/**` (`TagResultSheet.kt` included); `A/ui/asset/AssetsScreen.kt`;
`A/ui/dashboard/**`; `A/ui/maintenance/**`; `A/reminders/**`; `A/share/**`; `docs/versioning.md`;
`ReleaseProofPolicyTest`. No changed line names `external_link` or `externalLinks`. Each brief adds its own fence
(§8–§11).

**Collisions.** B1 and B3 both edit `A/di/AppGraph.kt` and `T/testing/FakeGraph.kt` (sequential). B1 and B4 both edit
`A/api/ApiDtos.kt` (B1 the tally, B4 the response). No live lane.

**Gate budget.** After the whole-branch review, merge `--no-ff issue-86` into master at once, then run the controller's
gate **once** at the merge commit: `.superpowers/sdd/2026-09-29-issue-84/gate/run.sh` copied unchanged into
`.superpowers/sdd/2026-09-29-issue-86/gate/`, with `extra-classes.env` set to
`EXTRA_CLASSES="com.loosecannon.servicetag.ui.replace.ReplaceAssetFlowTest"`. **Expected:** 55 device classes; JVM
core 1319 + about 70, app 1491 + about 45; MCP 379 + about 4 at 69 tools; the whole about 11.5–11.8 minutes.
**Record in the ledger and the plan's errata** (reporting only; the 14- and 15-minute lines trigger nothing for #86):
the whole time from start to GATE DONE with its JVM, device and MCP portions; the class count; **#86's delta against
11.30 minutes whole, 10.33 minutes device and 54 classes**, and `ReplaceAssetFlowTest`'s own time. **A failure is
recorded, never rerun:** its class, case and log, and whether it is #86's class; a flake at an untouched site goes to
#90 with the log; a fix is a new commit and a new gate, by the controller's decision.

## 5. Owner-gate summary

**What changes.** Room 14 → 15 and backup format 14 → 15: one append-only table, `asset_succession` (two CASCADE
foreign keys to `asset`, a UNIQUE index on each end, no `updated_at`); one new archive list `assetSuccessions` (manifest
counts 25 → 26); nothing existing moves. **Merge:** twenty tables, two appended reasons (`SUCCESSION_TAKEN`,
`SUCCESSION_CYCLE`), MS1–MS4 and M2, **no UPDATE**; the dev ↔ production limit stands and is documented. **Transfer:** a
succession never travels in a pack; ordinary backups drop a row while an endpoint is held; a transfer back re-inserts
the local rows. **API:** one status count and one read-only route; MCP 68 → 69 (`get_asset_succession`). **Phone:**
one overflow item, one screen, two detail lines. **No `versionName` bump.**

**The gate at 15** (`docs/release-proofs.md:107`, written by B4): the direct **1.4.1 (schema 8) → schema 15** proof,
every step with its 14s moved to 15 ("Room schema 15 / backup format 15", `schemaVersion` 15, `backupFormatVersion` 15,
"the format-15 round trip", "Master builds carry schema 15"), "#86's asset successions at schema 15" beside the kept
"#77's transfer records at schema 14"; `counts` gain `assetSuccessions: 0` (the five new keys, each 0); the
post-upgrade export carries `"assetSuccessions": []`; the pre-upgrade export re-plans applicable, zero INSERT, every row
IDENTICAL; the fresh format-15 export re-plans IDENTICAL and tallies `successions` 0. No Replace is made in the gate.

**What the owner gate asks:** ratify §6's table — P86-1…28 (29 literals: P86-18 is a one / several pair) with their
treatments and home, and the 24 reused strings with theirs (R86-23) — and confirm R86-13a, the future-date refusal (§7).

## 6. Strings — the final table, RATIFIED (owner, 2026-09-29; R86-23)

`<old>` and `<new>` are asset names; `<name>` is one child's name; `<names>` are the children's names, sorted by name,
joined with `, `; `<group>` is a group's name; `<date>` is `d MMM uuuu` (P77-33's precedent). **Home:** every P86
literal is one constant, on a line of its own, in `A/ui/replace/ReplaceStrings.kt`; the detail menu (P86-1) and the
detail lines (P86-26, P86-27) reference it from there. "C" marks a string that exists because of a ruling.

| id | where | text | treatment | home |
|---|---|---|---|---|
| P86-1 | detail overflow; Replace top bar; the review's confirm | `Replace asset` | `DropdownMenuItem` · title · `Button` | `ReplaceStrings` |
| P86-2 | form | `Old asset` | `SectionHeader` | `ReplaceStrings` |
| P86-3 | form, not yet retired | `<old> will be retired with its history, documents and service record kept as they are.` | body, above the reused `Retired on` field | `ReplaceStrings` |
| P86-4 | form, already retired | `<old> was retired on <date>. Its history, documents and service record are kept as they are.` | body, no field | `ReplaceStrings` |
| P86-5 | form | `New asset` | `SectionHeader` | `ReplaceStrings` |
| P86-6 | form | `A separate asset with its own identity. None of the old asset's history is copied.` | `QuietLine` | `ReplaceStrings` |
| P86-7 | form; review | `Carry forward` | `SectionHeader` · `FieldLabel` | `ReplaceStrings` |
| P86-8 | form | `Only what you tick is copied, as new records with no history.` | `QuietLine` | `ReplaceStrings` |
| P86-9 C | form; review (R86-13) | `Operating season and maintenance break` | `Checkbox`; in the review a line, then for MANUAL the chosen S36/S37 word on its own line under it (no separator) | `ReplaceStrings` |
| P86-10 | form | `Description and notes` | `Checkbox` | `ReplaceStrings` |
| P86-11 C | form; review (R86-12) | `Add to <group>` | `Checkbox` per group · review line | `ReplaceStrings` |
| P86-12 | form | `Copied schedules start on` | date field label, shown iff a ticked schedule has a time rule | `ReplaceStrings` |
| P86-13 | form | `Needs Readings & actions.` | supporting error line under a schedule | `ReplaceStrings` |
| P86-14 | form | `Needs Operating season and maintenance break.` | supporting error line under a schedule | `ReplaceStrings` |
| P86-15 | form; review | `Move to the new asset` | radio · `FieldLabel` | `ReplaceStrings` |
| P86-16 | form | `Leave with the old asset` | radio, the default | `ReplaceStrings` |
| P86-17 | form | `A moved tag is not rewritten. Scanning it opens the new asset.` | `QuietLine` | `ReplaceStrings` |
| P86-18 | form, one child / several (MN-8) | `<name> stays part of <old>.` / `<names> stay part of <old>.` | `QuietLine`, iff children exist | `ReplaceStrings` |
| P86-19 C | form (R86-6) | `<old> is lent out. The loan stays with it.` | `QuietLine` | `ReplaceStrings` |
| P86-20 | review | `Retire <old> on <date>` | line | `ReplaceStrings` |
| P86-21 | review | `<old> stays retired from <date>` | line | `ReplaceStrings` |
| P86-22 | review | `Create <new>` | line | `ReplaceStrings` |
| P86-23 | review | `Nothing carried forward` | line | `ReplaceStrings` |
| P86-24 | failure | `Could not replace <old>. Nothing was changed.` | error line | `ReplaceStrings` |
| P86-25 | stale refusal | `The asset or related records changed while you were reviewing. Nothing was changed.` | error line; the form re-reads | `ReplaceStrings` |
| P86-26 | predecessor detail | `Replaced by <new> on <date>` | tappable `QuietLine` | `ReplaceStrings` |
| P86-27 | successor detail | `Replaces <old>` | tappable `QuietLine` | `ReplaceStrings` |
| P86-28 C | form, P86-9 ticked on a MANUAL predecessor (R86-13 amended) | `Was the new asset in season on <date>?` (`<date>` = the replacement date, never after today, C9) | body above the S36/S37 radios; no default | `ReplaceStrings` |

**Reused (24), through their homes.** B3 hoists each literal marked † into one named constant beside its shipped use,
the shipped call site switched to it, text byte-identical; `A/ui/replace/` references the constants and quotes none of
these literals.

| # | text | where #86 uses it | home |
|---|---|---|---|
| 1 | `Name` | form field label | `AssetEditScreen.kt:387` † |
| 2 | `Manufacturer` | form field label | `AssetEditScreen.kt:392` † |
| 3 | `Model` | form field label | `AssetEditScreen.kt:393` † |
| 4 | `Serial number` | form field label | `AssetEditScreen.kt:394` † |
| 5 | `Location` | form field label | `AssetEditScreen.kt:402` † |
| 6 | `Part of` | form picker label | `AssetEditScreen.kt:404` † |
| 7 | `Purchase date` | form date label | `AssetEditScreen.kt:560` † |
| 8 | `In service date` | form date label | `AssetEditScreen.kt:566` † |
| 9 | `Category` | form field label | `AssetEditScreen.kt:856` † |
| 10 | `In season` | P86-28's option; the review's phase word | S36 `IN_SEASON_NOW`, `AssetEditScreen.kt:118` |
| 11 | `Out of season` | P86-28's option; the review's phase word | S37 `OUT_OF_SEASON_NOW`, `AssetEditScreen.kt:121` |
| 12 | `Retired on` | the retirement date field | `AssetDetailScreen.kt:726` † |
| 13 | `Readings & actions` | the set-up checkbox; its review line | `AssetDetailScreen.kt:493` † |
| 14 | `Tags` | the tags section header | `AssetDetailScreen.kt:1293` † |
| 15 | `Cancel` | the review's return to the form | `AssetDetailScreen.kt:730` † (the retire dialog, MN-4) |
| 16 | `Give the asset a name` | the name refusal | `AssetViewModels.kt:2783` † |
| 17 | `Enter a date as YYYY-MM-DD` | every bad or blank required date | `AssetViewModels.kt:2788` † |
| 18 | `None` | the parent picker's no-parent choice | `NO_PARENT`, `AssetViewModels.kt:1872`, through the extracted `choicesIn` |
| 19 | `<name> (archived)` | an archived parent choice | `AssetViewModels.kt:2644`, inside the extracted `choicesIn` |
| 20 | `Schedules` | the schedules group label | `SCHEDULES_SECTION`, `MaintenanceScreen.kt:39` |
| 21 | `Maintenance groups` | the groups label | `GROUPS_SECTION`, `MaintenanceScreen.kt:40` |
| 22 | `Review` | the form's button | `TransferStrings.REVIEW`, `TransferStrings.kt:31` |
| 23 | `This asset was transferred out.` | P77-35, the held refusal | `TransferImportStrings.ASSET_TRANSFERRED_OUT`, `TransferImportStrings.kt:12` |
| 24 | `The date cannot be later than today.` | the future replacement date (R86-13a, DECIDED) | `DATE_NOT_LATER_THAN_TODAY`, `A/ui/condition/ChangeConditionViewModel.kt:35` |

**Count:** 28 new ids (P86-1…28; 29 literals with P86-18's pair), 4 conditional (P86-9, 11, 19, 28), no
accessibility-only string; 24 reused (15 hoisted by B3). The dependency lines are text, never colour. **Collisions
anchored apart:** `Replace asset` against the Backup screen's Replace restore and typed `REPLACE`; `Replaced by` and
`Replaces` against nothing shipped; `Carry forward`, `Old asset` and `New asset` have no home today; `Out of season` has
two shipped homes (S37 and `AssetsScreen.OUT_OF_SEASON`), and #86 uses S37's.

**Wording flags for the gate (review NT-7; flags, not rulings):** P86-3 says "is retired" before the confirm; P86-14
says "season **or** maintenance break" where its checkbox (P86-9) says "season **and** maintenance break"; P86-25 names
`<old>` although the change it reports may have been to a schedule, group or tag. P86-28's future-date reading is
closed by R86-13a, and P86-9's review separator is gone (the phase word takes its own line).

## 7. Rulings (owner, 2026-09-29 — DECIDED)

**Decided constraints:** R86-C1, R86-C2, R86-C3 (Global constraints). **R86-1…22 APPROVED as the audit recommended,
with R86-13 and R86-15 amended**; the owner's clarifications are binding. Each ruling names the contracts and rows it
governs.

| ruling | decision | contracts · rows |
|---|---|---|
| R86-1 | A new append-only table `asset_succession`, CASCADE on both keys, UNIQUE on each end. | C1–C7, C10, C16, C20 · rows 1–7, 10, 16, 19 |
| R86-2 | "Succession" in code, schema and wire; "Replace asset", "Replaced by", "Replaces" in the UI. | C1, C3, C4, C16, C20 |
| R86-3 | Retirement is part of Replace, with one date: for a predecessor not yet retired, `replacedOn == retiredOn`; an already-retired predecessor keeps its date and slice 1 uses it as the succession date. | C9, C10, C17 · rows 10, 15 |
| R86-4 | One atomic write from a dedicated screen; **absolutely nothing is written before the final confirmation**; price, vendor, warranty and documents come afterwards through the editor. | C10, C17 · rows 9, 10, 15 |
| R86-5 | Eligible: any asset not held and without a successor — archived, retired, component, DOWN/DEGRADED and case-bearing included. | C8, C16 · rows 8, 16 |
| R86-6 | An open loan is allowed, stays and keeps reminding (R72-10); the form names it (P86-19). | C8, C17 · rows 8, 15 |
| R86-7 | Children stay with the predecessor, named (P86-18); a later move goes through the editor's `Part of`. | C8, C17 · rows 8, 15 |
| R86-8 | Name, category, location and a valid parent prefilled and editable; manufacturer, model, serial and purchase facts blank; in service defaults to the replacement date. | C8, C17 · rows 8, 15 |
| R86-9 | Every carry-forward item unticked. | C13, C17 · rows 12, 15 |
| R86-10 | Per-schedule opt-in; new ids and no history; one explicitly reviewed time anchor; the meter anchor starts empty. | C9, C11, C17 · rows 9, 11, 15 |
| R86-11 | One `Readings & actions` item, cloned by key. | C12, C17 · row 12 |
| R86-12 | Opt-in per open group; a new successor window; the predecessor's membership history left alone, bounded by retirement. | C8, C13, C17 · rows 8, 12 |
| R86-13 **(amended)** | Mode, window and break as one item. For MANUAL, exactly one new, reviewed activation, **effective on the replacement/succession date, not today**; no predecessor activation copied. | C9, C13, C15, C17 · rows 9, 12, 15; P86-28 |
| R86-13a **(DECIDED, owner 2026-09-29)** | A replacement date later than today is refused, whatever is ticked: `ReplacedOnAfterToday` blocks Review and draws the reused `DATE_NOT_LATER_THAN_TODAY`; an already-retired predecessor with a future `retiredOn` is refused the same way. (Review MJ-1: a future-dated MANUAL activation would lock out every START/END until that day and read out of season meanwhile.) **Contracts this option touches:** C9 (the problem, the one `today` read), C13 (the future-date sentence), C17 (where the line draws), §6 (reused #24), row 9 (case and RED), row 15 (`aFutureDateSays…`), B2's `today` grep (→ 1), B3's literal grep. **The alternative** — refuse only when the season item is ticked on a MANUAL predecessor, with a new P86-29 under P86-28 in place of the reuse — edits exactly the same places: C9's condition narrows, C13's sentence says a future date is allowed without a MANUAL carry (the predecessor then reads retired at once, `Asset.kt:46`, as `RetireAsset` already allows), C17 draws P86-29 under P86-28, §6 swaps reused #24 for P86-29, and rows 9 and 15 rename their cases; nothing else moves. | C9, C13, C17 · rows 9, 15; §6 |
| R86-14 | Per ACTIVE tag, Move or Leave, Leave by default; retargeted with the existing binding semantics inside the Replace write; never an NDEF rewrite; no new scan resolution; no tag-release feature. | C8, C14, C17 · rows 8, 13, 15 |
| R86-15 **(wording amended)** | Deleting either endpoint cascades the row; unretiring is allowed and leaves the succession intact; A → C is never synthesised. Deletion is never described as an "undo"; slice 1 has no unlink or undo (recorded limitation). | C2, C21, Global constraints · row 2 |
| R86-16 | SENDER_ONLY; dropped from ordinary backups while an endpoint is held; the guard and M2 refuse new rows naming a held asset; a transfer back re-inserts the local rows. | C6, C21 · row 6 |
| R86-17 | No event link and no note in slice 1 (DEFER). | C1 |
| R86-18 | The status count, read-only `GET /v1/assets/{id}/succession`, and the tool `get_asset_succession` (MCP 68 → 69). No API or MCP Replace command. | C20 · rows 19, 20 |
| R86-19 | MS1–MS4 and M2; `SUCCESSION_TAKEN`, `SUCCESSION_CYCLE` appended; no UPDATE; the dev ↔ production limit documented, never weakened. | C4, C21 · row 4 |
| R86-20 | Schema/format 15, unreleased under 1.4.1, no `versionName` bump; B4 moves the gate paragraph to 15. | C2, C3, C7, C21 · rows 2, 3, 7, 21 |
| R86-21 | One new rendered Compose class, ≤ 6 cases, no device mutations. | C19 · row 18 |
| R86-22 | No list or Dashboard badge, no scan-sheet change, no entry from the retire dialog, #82 or #79; Replace lives on Asset detail. | C16, §4 fences |
| R86-23 | **RATIFIED (owner, 2026-09-29)** — §6's final table: P86-1…28 (29 literals) and the 24 reused strings, verbatim, with P86-3, P86-14 and P86-25 as amended. | §6, C18 · row 17 |

**Where the plan refines the audit's text** (planner findings 1–9, §1), each inside the rulings above: P86-28 and no
default for the phase (finding 1, R86-13); the activation day as a parameter of `SaveAssetSettings`' body (finding 2);
the archived set-up closure (finding 3, R86-11); profiles remapped by id (finding 4, R86-10); archived and held-row
groups not offered (finding 5, R86-12); the reused set at 24 (finding 7, R86-23). The review (`brief-review.md`) found
all ten departures correct; rev 1.1 applies its conditions C-1…C-12.

## Briefs — common to all four

Read §1–§7, the audit, issue #86 and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun` with zero failures and skips;
`:app:compileDebugAndroidTestKotlin` in every brief. Before a connected class, `tools/emulator/prepare-emulator.sh`;
then one class per run, **once**: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Then the brief's anchored greps (`git grep -nE '<pattern>'
-- <paths>`, over `app/src/main` unless named); the untouched diff; `git diff <base> | grep -cE
'^[-+].*(external_link|externalLinks)'` → 0; `git ls-tree HEAD libs/nfc-tag-core` → `7e0377a…`; each → 1:
`'^\s*versionName = "1\.4\.1"$'` and `'^\s*versionCode = 17$'` in `app/build.gradle.kts`; `git diff <base> -U0 --
<changed files> | grep -ciE '^\+.*\bundo\b'` → 0 (R86-15; MN-5: shipped lines such as `MergePlan.kt:305` and
`v1.md:182`, `:1569` already carry the word, so new succession text goes on lines of its own and never rewords a
shipped line); `git status` clean.

**Pin rule.** A shipped assertion pinning a version, table, count key, reason, tally, status key, tool count, menu
order or document wording moves only where the brief's row list names it; every other assertion stays. Confirm the set
first with `git grep -nE '\b(14|19|25|68)\b|Fourteen|[Nn]ineteen|entries\.last\(\)|V14_TABLES|FORMAT_14_TABLES'` over
`core/src/test app/src/test app/src/androidTest tools/servicetag-mcp/tests`; the report lists every move and every
mechanical constructor ripple. **Commits.** One casual lowercase subject line; no body, no trailers, no attribution; a
row with a natural RED is committed test-first. **Report.** Every counted RED with its quoted failure; every row with no
natural RED, and why; each connected run with its class, cases and time; each grep with its count; what is done,
proven and not proven.

**Stop and report, always:**
- an edit to an untouched file;
- an assertion that must move outside the brief's pin list;
- any device failure, with its log (never rerun);
- a JVM or build failure the brief did not cause (reported, not retried);
- the emulator unavailable for 30 minutes;
- a user-visible string not in §6;
- the cap or the time box reached, with what is done, proven and not.

**Must NOT, always:** delete a shipped assertion; commit outside the brief's files; add a device class or case outside
C19; run a device mutation; use a device other than `emulator-5554`; start, stop or restart the emulator or adb; touch a
harness helper, `tools/emulator/*` or the gate script; add Robolectric or a JVM Compose dependency; bump a version;
move a `MergeTable` or `MergeReason` ordinal; add an UPDATE verdict; write NDEF; write a real name, address, serial or
tag key; describe deletion as an undo.

## 8. B1 — the succession record (C1–C7, JVM, plus one connected run of a moved pin)

**Read:** audit §3.4, §4; the #77 plan C6–C10, C12, C15 and §15; `BackupCodec.kt` (:113-160, :240-260, :355-370,
:700-760), `MergePlan.kt` (:55-140, :300-330), `MergePlanner.kt` (:1000-1130), `ApplyBackupMergePlan.kt` (:140-330),
`TransferGraph.kt` (:20-70, :200-290), `TransferOwnership.kt`, `HeldWriteGuard.kt`, `TransferPackReader.kt` (:160-180),
`ExportBackupSet.kt`, `ImportBackupReplace.kt`, `Migrations.kt` (:677-696), `AssetLoanEntity.kt`,
`CT/testing/InMemoryRepositories.kt` (:130-160), `BackupInstall.kt`, the #77 B2a report's row-13 list
(`.superpowers/sdd/2026-09-28-issue-77/task-b2a-report.md:174-238`). **`<base>`** = the ratified plan commit.
**Rows:** 1–7. **Rulings:** R86-1, 2, 15, 16, 17, 19, 20.

**Row 7's pins** (re-located on `5167979d`; every other assertion kept):
- core backup: `BackupCodecTest.kt:1056` (Fourteen → Fifteen), `:255`, `:484` (counts gain `"assetSuccessions" to 0`);
  `BackupFormat6Test.kt:284` (19 → 20), `:355` (14 → 15), `:393` (key), `:397` (25 → 26); `BackupFormat7Test.kt:216`,
  `:290`, `:311`; `BackupFormat8Test.kt:277`, `:280`, `:281` (15/15/14 → 16/16/15), `:310`; `BackupFormat9Test.kt:70`,
  `:102` (`takeLast(5)` → `takeLast(6)`), `:120` (the format-8 fixture's `counts - … - "transferRecords"` also drops
  `"assetSuccessions"`, so `:132`'s `20` holds; #77's own row-13 item); `BackupFormat12Test.kt:70` (`dropLast(2)` → `dropLast(3)`);
  `BackupFormat13Test.kt:54`, `:58` (`dropLast(1)` → `dropLast(2)`), `:68`; `BackupFormat14Test.kt:48`, `:52`
  (`tree.keys.last()` → `dropLast(1).last()`), `:65`; `StageABundleConformanceTest.kt:97`, `:207` (`FORMAT_15_TABLES`).
- core merge: `MergePlannerMaintenanceTest.kt:271`, `MergePlannerReferenceTest.kt:372`,
  `MergePlannerSeasonHealthTest.kt:134`, `:144` (twenty tables, `SUCCESSIONS`); **`MergePlannerTransferTest.kt:382`**
  (`entries.last()` → TRANSFERS' own position) and `:383` (`assertEquals(19, MergeTable.entries.size, "nineteen
  tables")` → 20).
- core use cases: `ExportBackupSetTest.kt:44`, `Format7ImportIdentityTest.kt:241` (14 → 15); `BackupUseCasesTest.kt:438`.
- app: `VersionAgreementTest.kt:81-83`, `:149-150`; `MaintenanceRoutesTest.kt:1157`, `:1206`, `:1280`, `:1330`
  (`dropLast(3)` → `dropLast(4)`), `:1482` (the name `statusReports14And14AndTheNewCounts` → `…15And15…`, as #77
  renamed its twin; a name, not an assertion), `:1499-1500`, `:1533`, `:1543` (`assertEquals(19, tallies.size)` → 20),
  `:1545`; `Migration10To11Test.kt:101`,
  `Migration11To12Test.kt:167`, `Migration12To13Test.kt:150`, `Migration13To14Test.kt:109`,
  `ReferenceMigrationTest.kt:104-105` (each subtracts `V15_TABLES`).
- androidTest: `Format7RestoreContractTest.kt:164` (14 → 15, with its name), the precedented fenced retarget.

**Connected:** `Format7RestoreContractTest`, once (its pin moved). **Greps** (each → 1 unless named):
`'^\s*const val FORMAT_VERSION = 15$'` and `'^\s*private const val FIRST_SUCCESSION_FORMAT = 15$'` in `BackupCodec.kt`;
`'^\s*const val LAST_LEGACY_FORMAT: Int = 7$'` in `LegacyArchive.kt`; `'^\s*const val SCHEMA_VERSION = 15$'` in
`AppGraph.kt`; `'^\s+version = 15,$'` in `AppDatabase.kt`; `'"assetSuccessions" to sorted\.assetSuccessions\.size,$'`;
`'\bLOANS, TRANSFERS, SUCCESSIONS,'`; `'^\s+SUCCESSION_TAKEN,$'` and `'^\s+SUCCESSION_CYCLE,?$'` in `MergePlan.kt`;
`'^\s+val successions: MergeTallyDto\b'` in `ApiDtos.kt`; `'\bMIGRATION_14_15\b'` in `Migrations.kt`, `AppGraph.kt` and
`MigrationTestSupport.kt` (each ≥ 1); `'"assetSuccessions" to TransferTableClass\.SENDER_ONLY'`;
`'carry\("assetSuccessions", data\.assetSuccessions\) \{ false \}'`; `'onDelete = ForeignKey\.CASCADE'` in the entity → 2;
`'unique = true'` in the entity → 2; `'fun successions\(port: AssetSuccessionRepository\)'` in `HeldWriteGuard.kt`;
the `ApplyBackupMergePlan` wiring in `AppGraph.kt` and `FakeGraph` passes the raw succession port (MJ-2; by inspection,
quoted in the report);
`'fun (update|delete)\('` in `AssetSuccessionRepository` → 0; `15.json` present and `git diff <base> -- …/14.json` empty.

**Untouched:** `A/ui/**`, `A/{share,reminders,contacts,links,prefs,attachments}`, `A/api` but `ApiDtos.kt`,
`C/{reminders,schedule,health,condition,warranty,nfc}`, every use case but C5's and C6's, `res`, `app/src/androidTest`
but `Format7RestoreContractTest.kt`, `tools`, `docs`. **Must NOT:** add a column beyond C2's; give the port an update or
delete; let `retain` answer `Entangled` for a succession; carry a succession in a pack; touch R77-13's held check.
**Counted RED (17):** rows 1 (2), 2 (2), 3 (3), 4 (4), 5 (1), 6 (5). **Caps:** 19 JVM mutation runs, 0 device
mutations, 1 connected run; **6 hours**. **Stop also** if the merge snapshot needs anything beyond the rows, or the
return needs more than `keptSuccessions`. **Size:** about 650 production, 1,100 test lines.

## 9. B2 — `ReplaceAsset` (C8–C15, core, JVM only)

**Read:** audit §3.1–§3.3, §5, §6; `RetireAsset.kt`, `SaveAssetSettings.kt`, `SeasonCommands.kt` (:136-201),
`SaveSchedule.kt`, `ScheduleCommands.kt` (:31-51, :249-318), `SaveGroup.kt`, `GroupCommands.kt`, `BindTag.kt`,
`TagTargets.kt`, `ApplyTemplate.kt`, `ArchiveDefinition.kt`, `ResolveTag.kt` (:90-110), `TransferOwnership.kt`,
`HeldWriteGuard.kt`, `ExportBackupSet.kt` (`readSnapshot`, :147-151), `RecordSeasonActivation.kt` (:20-60),
`SeasonContext.kt` (:55-75), `CT/testing/InMemoryRepositories.kt` (:620-670, the non-re-entrant write). **`<base>`** =
B1's tip. **Rows:** 8–14. **Rulings:** R86-3…14, R86-13a (DECIDED).

**Connected:** none. **Greps:** `'^class ReplaceAsset\('` over `C/usecase` → 1; `'uow\.write'` in `ReplaceAsset.kt` → 1;
`'today\.localDate\(\)'` in `ReplaceAsset.kt` → 1, the future-date check alone (R86-13a; the activation reads
`replacedOn`, R86-13 amended); `'NdefCodec|TagPayload|ProvisionTag'` in
`ReplaceAsset.kt` and the clone file → 0; `'\.run\('` in `ReplaceAsset.kt` → 0 (bodies, never nested use cases);
`'internal (suspend )?fun \w+InTransaction\('` over the five extracted files → ≥ 5, and in `ReplaceAsset.kt` ≥ 2 (the
offer and plan bodies, MN-6); `'uow\.read'` in `ReplaceAsset.kt` → 2; `git diff <base> --
CT/usecase/{RetireDeleteAssetTest,SaveAssetSettingsTest,SaveScheduleRuleFieldTest,ScheduleCommandRulesTest,ScheduleOperationsTest,GroupMembershipTest,TagBindingUseCasesTest,ApplyTemplateTest,CrossConceptWriteTest}.kt`
→ empty.

**Untouched:** `app/**`, `C/{backup,merge,transfer,reminders,schedule,health,condition,warranty,nfc}`, every use case but
`ReplaceAsset.kt`, the clone file and the five extractions (`CreateAsset.kt`, `ApplyTemplate.kt`, `ResolveTag.kt`
included), `tools`, `docs`. **Must NOT:** write anything outside the one `uow.write`; change a shipped use case's
refusal, order, return or commit count; copy a schedule id, anchor, postponement, closure, event or state; copy an
activation, attachment, reference, condition, case, loan or health subject; close or rewrite the predecessor's
windows; omit any open member from a group save; move a non-ACTIVE tag or a tag not in `movedTagIds`; date the
activation today; accept a replacement date after today; seed a template on the successor's create.
**Counted RED (18):** rows 8 (3), 9 (2), 10 (3), 11 (3), 12 (5), 13 (2). **Caps:** 19 JVM mutation runs, 0 device;
**6 hours**. **Stop also** on any shipped assertion that must move, or if an extraction cannot keep its `run`
byte-identical in behaviour. **Size:** about 750 production, 1,200 test lines.

## 10. B3 — the phone (C16–C19)

**Read:** audit §7, §9; the #77 plan C19, C21a; the #84 plan C3–C5 (state-backed events); `AssetDetailScreen.kt`
(:330-360, :600-640, :700-780, :830-850, :1285-1320), `AssetViewModels.kt` (:675-800, :830-900, :1108-1145,
:1630-1660, :2020-2040, :2630-2650, :2775-2800), `AssetEditScreen.kt` (:110-125, :380-460, :550-570, :850-860),
`ServiceTagRoot.kt` (:120-140, :400-415), `Route.kt`, `TransferStrings.kt`, `MaintenanceScreen.kt` (:35-45),
`AssetTransferStateTest.kt` (:190-220), `AssetTransferDetailTest.kt`, `ChangeConditionViewModel.kt:35`. **`<base>`** =
B2's tip. **Rows:** 15–18. **Rulings:** R86-3…9, 12–14, 13a, 21–23 (strings as ratified).

**Connected, one run each, no reruns, each with its time:** `ui.replace.ReplaceAssetFlowTest` (new, ≤ 6 cases);
`ui.asset.AssetTransferDetailTest` (re-run: the overflow it draws changed). **Greps:** every P86 literal → 1, anchored,
in `A/ui/replace/ReplaceStrings.kt`, and 0 elsewhere; `git grep -nE '"(Name|Manufacturer|Model|Serial number|Location|Part
of|Purchase date|In service date|Category|Retired on|Readings & actions|Tags|Schedules|Maintenance groups|Review|Give the
asset a name|Enter a date as YYYY-MM-DD|This asset was transferred out\.|In season|Out of season|None|Cancel|The date
cannot be later than today\.)"' -- A/ui/replace` → 0; each of the fifteen hoisted constants' definitions → 1;
`'^(internal )?(private )?fun (CategoryField|ChoiceField)\('` in `AssetEditScreen.kt` → 2, both `internal`; `'DetailMenuItem\.REPLACE'` ≥ 2; `'backStack\.removeLastOrNull\(\);
backStack\.add\(Route\.AssetDetail'` over `A/ui/nav` → 2; the manifest and `ManifestContractTest` diffs → empty.

**Untouched:** `core/src/main/**`; `A/{api,data,reminders,share}`; `A/ui/{scan,dashboard,maintenance,backup,transfer}`;
`A/ui/asset/AssetsScreen.kt`; `res`; shipped androidTest files; `tools`; `docs`. `AssetEditScreen.kt` changes by the
nine hoists and `CategoryField` / `ChoiceField` becoming `internal` (MN-3, a mechanical ripple, listed);
`AssetDetailScreen.kt` by its four hoists (`Cancel` from `:730` included), the menu text and the two lines;
`AssetViewModels.kt` by its two hoists, the `choicesIn` extraction, the state and the menu. **Must NOT:** write before the
confirm; pre-tick or pre-move anything; sweep before the write; add an entry point outside the detail overflow; change
a held asset's menu; carry a line by colour alone. **Counted RED (7 JVM):** rows 15 (4), 16 (2), 17 (1); row 18 none.
**Caps:** 9 JVM mutation runs, 0 device mutations, 2 connected runs; **6 hours**. **Stop also** past about 80 changed
lines in `AssetDetailScreen.kt`, or at any manifest entry. **Size:** about 950 production, 900 test lines.

## 11. B4 — API, MCP, docs and the gate at 15 (C20–C21)

**Read:** `ApiRouter.kt` (:120-165), `ApiHandlers.kt` (:160-200), `ApiDtos.kt`, `docs/api/v1.md` (:180-195, :530-545,
:1160-1165, :1475-1480), `M/src/servicetag_mcp/server.py` (:100-170, :1110-1150, `get_loan`), `M/README.md` (:30-40,
:150-160, :280-310), `M/tests/{test_argument_guard,test_tools,test_loan_tools,test_reference_tools}.py`,
`docs/release-proofs.md` (:107, R4), `ReleaseProofPolicyTest`, `CommandShapesGoldenTest.kt:114-150`,
`ReferenceRoutesTest.kt:465-575`. **`<base>`** = B3's
tip. **Rows:** 19–21. **Rulings:** R86-15, 16, 18, 19, 20.

**B4's pins:** `MaintenanceRoutesTest`'s status-counts case (the key); `CommandShapesGoldenTest.kt:114-150`
(`…Format15AndTwentyTables`, "15 since #86 (asset successions)"); `ReferenceRoutesTest.kt:539-575` (twenty tables,
1–15, twenty-one sub-resources); `M/tests/test_argument_guard.py:8`, `:46`, `:51`, `:182`, `:203-204`, `:207`, `:256-258`
(68 → 69, the case's name included), `:341-342` (68 / 68 → 69); `ReferenceRoutesTest.kt:469-484`
(`statusCarriesTheReferenceCountBesideEveryShippedKey`: the exact status key set gains `"assetSuccessions"`);
`M/tests/test_tools.py:103-113` (`test_tool_names_are_sixty_eight` → `…sixty_nine`, `EXPECTED_TOOLS` gains the tool,
68 → 69) and `:757-806` (twenty tallies, `successions` last, 1–15, "each of **twenty** tables");
`M/tests/test_reference_tools.py:53-58` (`test_the_registered_tool_count_is_68` → `…69`, 68 → 69).

**Connected:** none new; `ReplaceAssetFlowTest` is **not** re-run (B3 measured it). `(cd tools/servicetag-mcp && uv run
--frozen pytest)` once, green, at 69 tools. **Greps:** in `v1.md` both `'format \*\*1–15\*\*'` and `'\*\*format 1–15\*\*'`
→ 1 each; `'15 since #86 \(asset successions\)'` ≥ 1; `'twenty tables'` ≥ 1 in `v1.md`, `server.py` and `M/README.md`;
`'nineteen tables'` over `docs/api M/src M/README.md` → 0; `'SUCCESSION_TAKEN|SUCCESSION_CYCLE'` in `v1.md` ≥ 2;
``'twenty-one `/v1/assets/\{id\}/…` sub-resources'`` → 1; `'"get_asset_succession",'` in `server.py` → 1;
`'^_MIN_SUCCESSION_SCHEMA_VERSION = 15$'` → 1; in `docs/release-proofs.md`: `'schema 8\) → schema 15'` → 1,
``'Room schema 14 / backup format 14|`schemaVersion` 14|`backupFormatVersion` 14|format-14 round trip|carry schema 14 under'`` → 0,
`'transfer records at schema 14'` → 1, `'asset successions at schema 15'` → 1, `'"assetSuccessions": \[\]'` ≥ 1;
`'\bundo\b'` (case-insensitive) in any added doc line → 0.

**Untouched:** `core/src/main`, `A/{ui,reminders,data,share}`, `app/schemas`, `app/src/androidTest`, `tools/emulator`,
`tools/servicetag-{bundle,schedules}`, `share-test-sender`, `docs/versioning.md`, the manifest, R4 and
`ReleaseProofPolicyTest`. **Must NOT:** add a write route or a second tool; change `AssetDto`; bump `versionName`;
rewrite ratified spec text; add a device class. **Report:** `ReplaceAssetFlowTest`'s measured time from B3, for the
gate's `EXTRA_CLASSES`. **Counted RED (2):** row 19 (2). **Caps:** 4 JVM mutation runs, 0 device; **3 hours**. **Stop
also** if the gate paragraph needs a step the 1.4.1 API cannot drive. **Size:** about 150 production and 60 Python,
220 test, 150 docs lines.
