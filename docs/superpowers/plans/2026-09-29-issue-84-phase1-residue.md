# #84 — the Phase 1 residue sweep: plan and briefs (rev 1.1, 2026-09-29)

> **Rev 1.1** applies the plan review (`.superpowers/sdd/2026-09-29-issue-84/brief-review.md`: APPROVE with conditions):
> M-1, m-1…m-8 and the one-line NOTEs; the owner's amendments (eight "Save condition" sites; R84-4's invariant verbatim).

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-09-29-issue-84/progress.md`.
> Four briefs (§9–§12) on one branch `issue-84` from master `1c707d1d`, strictly sequential — B1, B2, B3, B4 — each
> `<base>` the previous accepted tip; one task review each, at most one bounded fix round each; one whole-branch review;
> the merge; one merged-tip gate. Planned read-only from issue #84 (body and the 2026-09-29 carry-list comment) and the
> audit (`.superpowers/sdd/2026-09-29-issue-84/audit.md`, the inventory of record), every citation re-verified on
> `1c707d1d`. **The owner ruled R84-1…4, D-1…3 and the scope on 2026-09-29 (§6). Nothing in this plan is conditional.**
> The plan review closed with rev 1.1; dispatch follows the controller.

**Goal:** close Phase 1's residue with no version, schema or format change. Put `ReferencesSectionViewModel` on the
test's clock; scroll the eight un-scrolled "Save condition" taps; finish #77's four polish items; fix three small facts
and three stale texts; apply the three rulings the owner took in (R84-1, R84-3, R84-4).

**Inputs:** issue #84; the audit; the #77 plan §15 (the carry list, the 13.87-minute gate); the #67, #72, #79 and #82
plans and ledgers the audit cites; `docs/superpowers/planning-policy.md`. `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`,
`T/` = `app/src/test/kotlin/com/loosecannon/servicetag/`, `AT/` = `app/src/androidTest/kotlin/com/loosecannon/servicetag/`.

## Global constraints

- **Roadmap (owner, 2026-09-29):** #84 → #86 → #85 → release 1.5.0 → #90. **No version bump:** `versionName = "1.4.1"`,
  `versionCode = 17` (`app/build.gradle.kts:51-52`); 1.5.0 is cut later. **Schema and format stay 14**
  (`AppGraph.kt:958`, `BackupCodec.kt:124`): #84 touches no entity, DAO, migration, `app/schemas`, codec or DTO.
- **Tests.**
  - JVM first, with the existing fakes. A device test only at a real Android or rendered boundary, and then only as
    assertions inside an existing case: #84 adds **no device class and no device case**.
  - **No #90 harness work:** no gate, runner, `tools/emulator/*` or helper change; no batching; no Robolectric or JVM
    Compose; no device class moved to the JVM. The ~119 other un-scrolled taps stay #90's; #84 edits only C2's eight.
  - **No rerun until green:** a device failure is reported with its log and stops the brief; it is never retried away.
    A device mutation only where the property is observable nowhere below the platform: one in the issue (row 13).
  - Every counted RED is a real mutation run with `--no-build-cache --rerun-tasks` (a `--tests` filter allowed), its
    failing assertion quoted, the mutation reverted before the commit. A row with no natural RED says why.
- **The merged-tip gate** runs once, after the merge (§8). The 14- and 15-minute lines are **reporting only** for #84.
- **Review budget:** one task review per brief; at most one bounded fix round per brief; one scoped re-review only for
  a substantive correctness finding; mechanical fixes (comments, ratified wording, renames, test tidies, under about
  50 lines) close by controller inspection plus the automated gates; one whole-branch review; the merge immediately
  after it; then the gate.
- **Bounded:** every brief states a counted-mutation cap, a time box and its stop conditions. A **fix round** is capped
  at **3 mutation runs (at most 1 on the device), 60 minutes**, touches only its findings' files, and stops on the first
  finding it cannot close.
- **Standing rules.**
  - Every user-visible string is ratified before it ships (§5: one new string, ratified); a reused string goes through
    its existing home, one home per literal.
  - The 2.6 tombstones `external_link` / `externalLinks` are never touched; the gitlink `libs/nfc-tag-core` stays
    `7e0377a`.
  - Commits: one casual lowercase subject line; no body, no trailers, no AI attribution. No personal data: fictional
    fixtures only ("Example Water Heater", "Sample Borrower", tag keys `TEST-…`).
  - Device runs on `emulator-5554` only. Implementers never start, stop or restart the emulator or adb
    (`tools/emulator/prepare-emulator.sh` only waits for the device and settles one setting).

## 1. Scope

**In scope: 15 contracts, one per audit item.**

| audit item | class | contract | brief | row |
|---|---|---|---|---|
| I-1 ReferencesSectionViewModel IO on the test clock | T | C1 | B1 | 1 |
| 79-2a eight un-scrolled "Save condition" taps (the audit's seven, plus `:187` by the owner's amendment) | T | C2 | B1 | 2 |
| 77-1 the Backup door's empty DONE body | P | C3 (D-3) | B2 | 3 |
| 77-2 a lost `Marked` | P | C4 | B2 | 4 |
| 77-3 P77-35 before the collector | P | C5 | B2 | 5 |
| 77-4a Share when the pack vanished | P | C6 (D-1) | B2 | 6 |
| 77-5 schedule detail's `editable` read once | P | C7 | B2 | 7 |
| 72-2 the Dashboard loan row names the parent | P | C8 (D-2) | B3 | 8 |
| 72-3 stale loan-timing and pick words | P | C9 | B3 | 9 |
| 79-1 one home for "Could not save this entry." | P | C10 | B3 | 10 |
| 67-3 the editor's comparator onto `newestFirst` | P | C11 | B3 | 11 |
| 67-5 a blank provider name falls back | P | C12 | B3 | 12 |
| 67-2 "More for <name>" twice | S, ruled in (R84-1) | C13 | B4 | 13 |
| 67-4 a document's kind from its role | B, ruled in (R84-3) | C14 | B4 | 14 |
| 72-1 strictly-once through a backward move | B, ruled in (R84-4) | C15 | B4 | 15 |

**Out of scope (X), once each:**
- **77-4b** Share with no receiver: R84-2 declined. The system chooser owns the empty state; the defensive
  `ActivityNotFoundException` catch (`A/ui/transfer/TransferFlowScreen.kt:121-123`) stays as a recorded platform edge.
  No string and no code; B2 only rewrites that catch's comment to cite R84-2.
- **79-4** shared Incident/repair predicates and **67-6** one home for "Remove": owner scope ruling (the predicates
  agree today; "Remove" would churn six unrelated screens).
- **I-2** `BackupViewModel`: the issue excludes it. **77-6** the sweep before DONE: R77-IMPORT-SWEEP rules that order.
  **77-7, 72-6**: tap sweeps that found nothing. **72-4**: `MergePlannerLoanTest` and `CrossConceptWriteTest` carry
  those mutations. **79-2b** the ~119 other un-scrolled taps and **82-2** the weak `awaitText(RENAMED)`: #90.
  **79-3** the two-tap relink: recorded and allowed (#79, C22).
- **Fixed already:** 72-5 (1928147a; its KDoc lags, C9 corrects it), 67-1 (cabf056b; plan-67's stale errata sentence
  at `:494` stays as a record of its time), 82-1 (419cb715), 82-3 (6184ab7e).

**The planner's corrections to the audit** (each re-verified on `1c707d1d`):
1. **I-1's RED.** "One site back on `Dispatchers.IO` makes a write-then-read case RED" cannot be relied on: master
   passes today with all three sites on the real `Dispatchers.IO`, so a reverted site races rather than fails. Row 1
   observes the context with a counting wrapper instead, which fails deterministically.
2. **R84-4's step.** "Answers Quiet, so the standing post and its stamp are both kept" is half right: `Quiet` keeps the
   stamp, but the kept set is built only from shown posts (`A/reminders/DigestPolicy.kt:228-232`), so `Quiet` takes a
   standing Once down for good. C15 holds a standing post (`Standing`) and answers `Quiet` only when none stands; either
   way nothing is forgotten, stamped or announced. That meets the ruling's words and the recommendation it approved.
3. **An eighth site.** `AT/ui/condition/ChangeConditionSheetTest.kt:187` (`operationalAsksNothing`) is an un-scrolled
   "Save condition" tap with no keyboard that the audit's §3 does not list. The planner found it; the owner's amendment
   (2026-09-29) takes it into C2. There is no further hunting: every other un-scrolled tap stays #90's.
4. **77-2's row cannot go RED at base.** `phase = MARKED` is already set (`A/ui/transfer/TransferPackViewModel.kt:246`);
   the defect is that the screen navigates only on the event. C4 moves navigation onto the state and removes the
   event, so one shipped assertion moves (`T/ui/transfer/TransferPackViewModelTest.kt:372`); row 4 pins the state.
5. **77-1's carrier.** A plain holder `remember`ed in the root loses the line on a rotation and does not guard the
   double pop. C3 puts a once-only hand-off on the import view model (JVM-tested, rotation-safe) and carries the line in
   saveable root state.
6. **77-3's home and mechanism.** The rows belong in `T/ui/asset/AssetTransferStateTest.kt`, which holds the held-owner
   fixtures and the shipped P77-35 cases; the mechanism is a buffered channel, because a state-backed `message` would
   force those shipped assertions (they collect `messages`) to move.

## 2. Contracts

### B1 — test clock and tap hygiene

**C1 (I-1).** `ReferencesSectionViewModel` gains `private val io: CoroutineContext = Dispatchers.IO` as its **last**
primary-constructor parameter, the #65 shape (`A/ui/attachments/AttachmentsSectionViewModel.kt:153-157`, 9500ce87).
- The three `viewModelScope.launch(Dispatchers.IO)` (`A/ui/references/ReferencesSectionViewModel.kt:137, :167, :185`)
  launch on `io`. `constructor(graph, assetId)` passes nothing.
- The test's factory (`T/ui/references/ReferencesSectionViewModelTest.kt:96-108`) passes a context on `scheduler`.
  Every case that builds a model ends with `clearModels()` (`store.clear()`, then `advanceUntilIdle()`, inside
  `runTest`), as `T/ui/attachments/AttachmentsSectionViewModelTest.kt:155-165` does; `tearDown` keeps its
  `store.clear()`.
- Nothing a user sees changes. No shipped assertion moves.

**C2 (79-2a).** Exactly these eight taps gain `.performScrollTo()` in the same chain, before the tap. Site 1 keeps its
`assertIsEnabled()`, after the scroll. Each is the bottom row of `ChangeConditionSheet`'s scrolled column
(`A/ui/condition/ChangeConditionSheet.kt:110-112, :175, :202`).

| # | site (`AT/…`) | keyboard up? |
|---|---|---|
| 1 | `ui/condition/ChangeConditionSheetTest.kt:94` | yes (typed at `:93`) |
| 2 | `ui/condition/ChangeConditionSheetTest.kt:140` | yes (`:139`) |
| 3 | `ui/condition/ChangeConditionSheetTest.kt:165` | yes (`:164`) |
| 4 | `ui/JournalSmokeTest.kt:115` | yes (`:114`) |
| 5 | `ui/maintenance/ScanSheetTest.kt:672` | no |
| 6 | `ui/maintenance/ScanSheetIncidentNavigationTest.kt:82` | no |
| 7 | `ui/asset/AssetDetailConditionHealthSeasonTest.kt:457` | no |
| 8 | `ui/condition/ChangeConditionSheetTest.kt:187` (`operationalAsksNothing`) | no |

Nothing else in the five classes changes. Every assertion, wait and fixture stays. The dialog answers that follow Save
("Log incident details", "Save condition only") are `AlertDialog` buttons and stay. The sheet's un-scrolled "Cancel"
taps are not in C2 (§7).

### B2 — the #77 transfer polish (JVM only)

**C3 (77-1; D-3).** On the Backup door, a completed import returns to the Backup screen, and P77-50 shows there once, as
that screen's snackbar.
- `TransferImportViewModel.handOff(): String?` answers the DONE line, exactly `state.done` (P77-50 through
  `TransferImportStrings.imported`), **once**. It answers `null` before DONE, after a refusal, and on every later call.
  It writes nothing, deletes nothing (the import's end already deleted the copy), and does **not** set `finished`.
- `TransferImportScreen(graph, copy, onBack, onImported: (String) -> Unit = {})` is the Backup door only
  (`ShareIntakeActivity.kt:92` draws `TransferImportContent`, which does not change). At DONE it calls `handOff()` and
  passes a non-null line to `onImported`. Its own `LaunchedEffect(state.done)` snackbar
  (`A/ui/transfer/import/TransferImportScreen.kt:63`) goes, so the line is never shown on both screens.
- `ServiceTagRoot` (`A/ui/nav/ServiceTagRoot.kt:429-438`) holds the line in saveable state; `onImported` stores it and
  pops once. `BackupScreen(graph, onBack, onImportPack = {}, importedLine: String? = null, onImportedLineShown: () -> Unit
  = {})` shows it in its own snackbar host (`A/ui/backup/BackupScreen.kt:89, :97`) and clears it as it starts showing,
  so a recomposition or resume never shows it twice. `EmptyStoreRestorePromptTest`'s `BackupScreen(graph, onBack)`
  compiles unchanged.
- **The effect that shows the line is never cancelled by its own clear (M-1).** Clearing `importedLine` recomposes the
  screen with `null`; an effect keyed on that value would restart, cancel `showSnackbar` mid-show, and Material3's
  `finally` would dismiss the snackbar. So capture the line and show it in the screen's remembered scope
  (`rememberCoroutineScope()`, already at `BackupScreen.kt:90`), or key the effect so that the clear does not restart it.
- **Exactly one pop.** `LaunchedEffect(state.finished)` (`:62`) still pops for Cancel and Back only; the DONE path pops
  through `onImported` alone. REFUSED, Cancel and Back are unchanged. The Share door is unchanged.

**C4 (77-2).** A completed mark ends the flow on the Assets list whether or not anyone collected an event.
- `TransferPackEvent.Marked` is removed. `TransferFlowScreen` calls `onMarked()` from an effect keyed on
  `made.phase == PackPhase.MARKED`. `onMarked` is `switchTopLevel(Route.Assets)` (`ServiceTagRoot.kt:190`), which is
  idempotent.
- `Leave` and `Say` stay as they are; the owner named `Marked` only (§7).
- The mark's order is unchanged: the write, one sweep, then `MARKED` (`TransferPackViewModel.kt:238-247`).

**C5 (77-3).** P77-35 on the schedule and group detail screens is held until a collector reads it.
`ScheduleDetailViewModel` (`A/ui/maintenance/ScheduleDetailViewModel.kt:295-296`) and `GroupDetailViewModel`
(`A/ui/maintenance/GroupDetailViewModel.kt:161-162`) each change their `messages` to:

```kotlin
private val _messages = Channel<String>(Channel.BUFFERED)
val messages: Flow<String> = _messages.receiveAsFlow()
// every _messages.tryEmit(line) becomes _messages.trySend(line)
```

- A line sent while nobody collects is delivered to the next collector **exactly once**. A delivered line is never
  delivered again.
- The screens' collectors (`ScheduleDetailScreen.kt:114`, `GroupDetailScreen.kt:107`) do not change, and neither does
  MJ-1's property (no prompt, no form, nothing written for a held owner).

**C6 (77-4a; D-1).** `TransferPackViewModel.packFile()` (`:145`) keeps its signature. When the pack is offered and its
file is no longer found, it sets `gone = true` and answers `null`. P77-60 (`TransferStrings.PACK_GONE`) then shows
through `goneLine`, and Share, Save a copy and Mark disable through `offersActions` (`:74-83`). A present file answers
as today, with no state change. The call site (`TransferFlowScreen.kt:117-118`) is unchanged.

**C7 (77-5).** While the model lives, `ScheduleDetailState.editable` follows `transfers.observeHeldIds()`
(`core/…/ports/Repositories.kt:468`).
- An OUT appended for the owner (its asset, or an asset any row of its group names) makes it `false`. A WITHDRAWN that
  ends the hold makes it `true` again. Neither needs `refresh()` or a resume.
- The rule keeps one home, `heldOwner(schedule, group, held)` (defined at `:504`, called at `:325`). The operation-time re-check (`refusedAsHeld`,
  `:429-435`) stays. `transfers == null` holds nothing, as today.
- No new port method and no new `Dispatchers.IO`. `GroupDetailViewModel` already observes the held set (`:169`).

### B3 — small fixes, tidies and stale words (JVM only)

**C8 (72-2; D-2).** `LoanAttentionRow` (`A/ui/dashboard/DashboardViewModel.kt:100-107`) gains `parentName: String? =
null`, last, so the shipped constructions (`T/ui/dashboard/DashboardFiltersTest.kt:304`) compile unchanged.
- `loanAttentionRowsOf` (`:434`) sets it to the parent's name for a component and to `null` for a root: inv. 122, the
  rule the asset rows use (`:350`).
- `LoanRow` (`A/ui/dashboard/DashboardScreen.kt:382-398`) draws `QuietLine(partOfLine(it))` under P72-44, as the
  condition row does (`:305`).
- Order, filters and the maintained-here rule are unchanged.

**C9 (72-3).** Words only; no statement, signature or test count changes.
- `tools/servicetag-mcp/src/servicetag_mcp/server.py:2583` (the loans block comment), `update_loan`'s docstring
  (`:2679-2680`) and `tools/servicetag-mcp/tests/test_loan_tools.py:12-14` (module docstring) say the exact rule: a
  loan written here posts only at the phone's next sweep at or after its digest hour, and a standing reminder the write
  takes down (a return; an update to `NONE`, to another mode or to a new due date — mode and date are both in the loan
  hash, `core/…/reminders/BuildLoanSubjects.kt:70`) comes down at the next sweep of any kind, the midnight sweep included.
  Each docstring keeps "sweep of any kind" on one line, so §11's line-based greps see it.
- The template is unchanged and untouched: `return_loan`'s docstring (`:2696-2700`), `docs/api/v1.md:971-975`, the MCP
  README (`:163-165`). `lend_asset`'s docstring (`:2636`) is exact for a new loan and stays.
- `A/ui/loan/LoanEditViewModel.kt:80`'s KDoc: a pick that arrives before the form loads is held and replayed once it
  loads (`:201-209`, 1928147a). "In the pick's callback" goes.

**C10 (79-1).** "Could not save this entry." gets one home. `private const val CANNOT_SAVE`
(`A/ui/journal/EventEntryViewModel.kt:686`) becomes `internal`, the precedent `BAD_CURRENCY` set
(`A/ui/asset/AssetViewModels.kt:2831`), and `A/ui/service/ServiceCaseViewModel.kt:255` uses it. Nothing a user sees
changes.

**C11 (67-3).** `attachedDocumentsOf` (`A/ui/asset/AssetViewModels.kt:2713-2719`) sorts with the shipped
`newestFirst({ it.capturedOn }, { it.createdAt })` (already imported at `:124`) in place of its own comparator. The
order is identical.

**C12 (67-5).** A blank provider name falls back like a null one.
- The name rule moves out of `ContentResolver.pickedFile` (`A/ui/attachments/AttachmentPickers.kt:145-153`) into one
  `internal` pure function, `pickedName(reported: String?, uriSegment: String?): String`.
- It answers `reported` when that is not blank; else the last `/`-part of the URI's last path segment, when that is not
  blank; else `"file"`. A real name is kept verbatim.
- Both pickers (`:55`, `:92`) go through `pickedFile`, so the section's snackbar path is covered too. `ContentResolver`
  stays untested, as today.

### B4 — the ruled items

**C13 (67-2; R84-1).** The overflow's content description gets one home, `internal fun overflowLabel(displayName:
String, keyRole: DocumentRole?): String`, beside `DocumentRow` in `A/ui/attachments/DocumentsSection.kt`.
- It answers `"More for <name>"` when `keyRole` is null, and `"More for <name>, <keyRole.label()>"` otherwise, through
  the role labels' home (`A/ui/attachments/AttachmentsSectionViewModel.kt:60-65`).
- `KeyDocumentsBlock` (`:218-221`) passes its group's role. The DOCUMENTS list passes null, so every other row keeps
  `More for <name>`. `AT/ui/AttachmentsDeviceProofTest.kt:675` matches a role-less row and is unaffected.
- The row, its tap, its actions and the sheet are unchanged (R67-4).

**C14 (67-4; R84-3).** A document newly added **from the asset editor** takes its default kind from its role.
- One `internal` pure function in `A/ui/asset/AssetViewModels.kt`, `editorKindFor(role: DocumentRole): AttachmentKind?`,
  an exhaustive `when` with no `else`: PURCHASE_INVOICE_OR_RECEIPT → RECEIPT; USER_MANUAL and SERVICE_MANUAL → MANUAL.
  `null` would mean the file-type default, so a future role must choose.
- `copyOne` (`:2474-2486`) passes `kind = editorKindFor(doc.role)` in place of `kind = null`.
- **Where it does not apply.** `AddAttachment`'s `cmd.kind ?: AttachmentKinds.inferFrom(…)` (`core/…/usecase/AddAttachment.kt:76`)
  is unchanged: the use case never derives a kind from a role (R67-7, pinned by core
  `AttachmentUseCasesTest.addAttachmentCarriesTheRole`). The share intake's kind chips, the edit sheet, the API, the MCP
  tools and every existing row are unchanged, with no migration. A kind the owner later chooses in the sheet always
  wins, and the role never constrains it.

**C15 (72-1; R84-4).** In `DigestPolicy.loanOnce` only (`A/reminders/DigestPolicy.kt:454-484`), the before-the-due-day
branch (`:465`) becomes:

| before the due day, and… | step | effect |
|---|---|---|
| no stamp, or the stamp's `announcedHash` ≠ `subject.contentHash` (a new due date, mode or content) | `Forget` | as shipped: the stamp is forgotten, a standing post comes down, the new occurrence re-arms |
| the hash is unchanged, and the post's tag is standing | `Standing(post)` | held: kept, not re-posted; nothing stamped or forgotten |
| the hash is unchanged, and nothing stands | `Quiet` | nothing shown; nothing stamped or forgotten |

- The earlier branches (no facts, no due date, not Active → `Forget`) and every step from the due day on are unchanged.
  So are `untilCleared` (`:508`; its before-due `Forget` at `:519`), `decide`, the warranty branch and `Notifications.kt`.
- The post's tag is `itemTag(key, contentHash)` (`:563`), so an unchanged hash names the same post.
- `loanOnce`'s KDoc first bullet (`:433-435`) is rewritten to say this. JVM only; no device proof.

## 3. Test matrix

| row | hazard | test (class · case) | RED mutation |
|---|---|---|---|
| **B1** 1 | C1: IO work outlives its test | `ReferencesSectionViewModelTest` · new `everyWriteRunsOnTheInjectedContext` (as `io`, a thin counting wrapper over `StandardTestDispatcher(scheduler)`; an add, an edit and a remove each dispatch on it at least once); the 9 shipped model-building cases each end with `clearModels()`, their assertions unchanged; the two pure cases (`:134`, `:336`) are unchanged | one of the three sites back on `Dispatchers.IO` (its count stays 0) |
| 2 | C2: the keyboard or the fold clips Save | the five classes, one connected run each, every case green | none: no natural RED (these sites never failed); proof is the run and the greps |
| **B2** 3 | C3: DONE's line lost, shown twice, or a double pop | `TransferImportViewModelTest` · new `theDoneLineIsHandedOverOnce` (null in PREVIEW; after a successful import exactly the P77-50 line, then null; null after a refusal); `importSaysP77_50AndTheRowsArrive`, `cancelWritesNothing`, `everyEndDeletesTheCopy` unchanged; the screen, root and Backup wiring by inspection | `handOff()` does not consume (a second call answers the line again) |
| 4 | C4: a lost `Marked` strands the ready screen | `TransferPackViewModelTest` · new `aMarkWithNoCollectorStillEndsTheFlow` (no events collector: after the mark, `phase == MARKED`, `offersActions == false`, no errors, one sweep); `markWritesThenSweepsOnce`'s `:372` moves to "no event" (the one named pin move); the screen's effect by inspection | the mark stops at MARKING (the `MARKED` update dropped) |
| 5 | C5: P77-35 dropped before the collector | `AssetTransferStateTest` · new `aScheduleDetailKeepsP77_35ForALateCollector` (held owner; `complete()` with nobody collecting; then one collector gets exactly `["This asset was transferred out."]` and a second gets nothing) and `aGroupDetailKeepsP77_35ForALateCollector` (a refused `setArchived`, likewise); the shipped P77-35 cases (`:472`, `:494`, `:524`, `:542`) unchanged | each model back on `MutableSharedFlow(replay = 0)` (2 runs) |
| 6 | C6: Share silent when the file vanished | `TransferPackViewModelTest` · new `shareFindingNoFileSaysP77_60AndOffersNothing` (a verified ready pack whose file is deleted: `packFile() == null`, `goneLine == PACK_GONE`, `offersActions == false`; a present file answers itself with no state change); `:486` unchanged | leave `gone` unset when the file is missing |
| 7 | C7: a stale `editable` | `AssetTransferStateTest` · new `aScheduleDetailFollowsTheHoldWhileOpen` (open and editable; an OUT appended → `false` with no `refresh()`; a WITHDRAWN → `true`; the case clears its model inside `runTest`, as C1 does); `scheduleDetailIsReadOnlyForAHeldOwner` unchanged | read the held set in `load()` only (the observation dropped) |
| **B3** 8 | C8: a lent component's row hides whose part it is | `DashboardFiltersTest` · new `aLentComponentsLoanRowNamesItsParent` (component → the parent's name; root → null); the shipped loan-row cases unchanged | `parentName` always null |
| 9 | C9: stale words | §11's greps; `(cd tools/servicetag-mcp && uv run --frozen pytest)` once, green, 379 passed (68 tools) | none: words only |
| 10 | C10: two homes drift | `EventEntryViewModelTest` (`:720-729`) and `ServiceCaseViewModelTest` (`:349-367`) unchanged | change the shared constant's text (1 run: both RED) |
| 11 | C11: two comparators drift | `AssetViewModelsTest.theEditorListsTheAssetsKeyDocumentsByRole` unchanged | `newestFirst` sorts `capturedOn` ascending |
| 12 | C12: a blank name cannot be fixed in the editor | new `T/ui/attachments/PickedNameTest` · `""`, whitespace-only and null → the segment's last part; a blank segment → `"file"`; a real name, spaces included, verbatim | accept a blank `reported` |
| **B4** 13 | C13: TalkBack hears two identical labels | `DocumentsSectionTest` · new `theOverflowNamesTheRoleOnlyInKeyDocuments` (each role; null); device `AssetDetailKeyDocumentsTest.aReceiptShowsInKeyDocumentsAndInDocumentsAndOpensTheSameAttachment` gains exactly two assertions: content description `More for Hot tub receipt.pdf` counted 1, and `More for Hot tub receipt.pdf, Purchase invoice or receipt` counted 1 (exact match) | JVM: the keyed form drops the role. **Device (1 run):** `KeyDocumentsBlock` passes no role |
| 14 | C14: the editor files a receipt as a Document | `AssetViewModelsTest` · new `theEditorDefaultsAKindFromTheRole` (every `DocumentRole` → its kind); `savingANewAssetAttachesTheStagedFilesWithTheirRoles` moves by ruling (receipt.pdf → RECEIPT, manual.pdf → MANUAL, wiring.jpg → MANUAL), the one named pin move; core `AttachmentUseCasesTest.addAttachmentCarriesTheRole` and the share-intake kind cases unchanged | `copyOne` passes `kind = null` again |
| 15 | C15: a backward move re-arms a Once | `DigestPolicyTest`, on `LoanRun` and `at` (`:1255-1300`) · new `aOnceIsNeverPostedTwiceThroughABackwardMove` (posted at `at(0, 9, 0)`; a sweep at `at(-1, 12, 0)` cancels nothing, forgets nothing and counts `(0, 0, 1)`; `at(0, 9, 0)` again posts nothing; the same across the date line: posted at 09:00 UTC+14, swept an hour later at `at(-1, 10, 0)` UTC−10); `aSwipedOnceStaysDownThroughABackwardMove` (swiped, then `at(-1, …)`: nothing posted, stamped or forgotten; the due day again: nothing); `aReDatedOnceStillReArmsWhileTheClockIsBack` (posted, moved back, re-dated: the stamp forgotten and the old tag cancelled; the new due day's digest hour posts once; "the stamp forgotten" is the assertion that catches mutation (2) and must stay); every shipped loan case and `warranty-day-matrix.txt` unchanged | (1) `Forget` restored before the due day; (2) the hash ignored (held whenever a stamp exists); (3) `Quiet` for a standing post |

**Planned counted REDs: 17** (B1 1, B2 6, B3 4, B4 5 JVM + 1 device). **New JVM cases: about 14**, all in `app`.

## 4. Files, fences and order

One branch `issue-84` from `1c707d1d`: B1 → B2 → B3 → B4, each `<base>` the previous accepted tip. B3 and B4 both edit
`A/ui/asset/AssetViewModels.kt` (C11, C14) and `T/ui/asset/AssetViewModelsTest.kt`; no other file is shared. With
77-4b declined, B4 does not touch `TransferFlowScreen.kt`.

| brief | production | tests |
|---|---|---|
| B1 | `A/ui/references/ReferencesSectionViewModel.kt` | `T/ui/references/ReferencesSectionViewModelTest.kt`; the five device classes of C2, one line per site |
| B2 | `A/ui/transfer/import/{TransferImportViewModel,TransferImportScreen}.kt`, `A/ui/backup/BackupScreen.kt`, `A/ui/nav/ServiceTagRoot.kt`, `A/ui/transfer/{TransferPackViewModel,TransferFlowScreen}.kt`, `A/ui/maintenance/{ScheduleDetailViewModel,GroupDetailViewModel}.kt` | `T/ui/transfer/import/TransferImportViewModelTest.kt`, `T/ui/transfer/TransferPackViewModelTest.kt`, `T/ui/asset/AssetTransferStateTest.kt` |
| B3 | `A/ui/dashboard/{DashboardViewModel,DashboardScreen}.kt`, `A/ui/journal/EventEntryViewModel.kt`, `A/ui/service/ServiceCaseViewModel.kt`, `A/ui/asset/AssetViewModels.kt`, `A/ui/attachments/AttachmentPickers.kt`, `A/ui/loan/LoanEditViewModel.kt` (KDoc only), `tools/servicetag-mcp/src/servicetag_mcp/server.py` (one comment, one docstring) | `T/ui/dashboard/DashboardFiltersTest.kt`, new `T/ui/attachments/PickedNameTest.kt`, `tools/servicetag-mcp/tests/test_loan_tools.py` (module docstring only) |
| B4 | `A/ui/attachments/DocumentsSection.kt`, `A/ui/asset/AssetViewModels.kt`, `A/reminders/DigestPolicy.kt` | `T/ui/attachments/DocumentsSectionTest.kt`, `T/ui/asset/AssetViewModelsTest.kt`, `T/reminders/DigestPolicyTest.kt`; `AT/ui/asset/AssetDetailKeyDocumentsTest.kt` (one case, two assertions) |

**Untouched by every brief** (`git diff <base> --stat -- <paths>` → empty): `core/src/main/**`; `A/data/**`,
`app/schemas/**`; `A/reminders/Notifications.kt`; `A/share/**`; `A/api/**`; `app/src/main/AndroidManifest.xml` and
`ManifestContractTest`; `app/build.gradle.kts`; `libs/nfc-tag-core`; `tools/emulator/**`, the controller's gate
script and every androidTest helper; `docs/api/**`, `docs/release-proofs.md`, `ReleaseProofPolicyTest`,
`tools/servicetag-mcp/README.md`; `share-test-sender/**`. No changed line names `external_link` or `externalLinks`.
Each brief adds its own fence (§9–§12).

## 5. Strings

One new string, ratified. Everything else is reused through its home.

| id | text | treatment | status |
|---|---|---|---|
| R84-1 | `More for <name>, <role label>`, e.g. `More for Pump manual.pdf, User manual` | the overflow's content description, in Key documents only | **RATIFIED** (owner, 2026-09-29) |
| R84-2 candidate | `No app on this phone can receive the pack. Save a copy instead.` | snackbar | **DECLINED** (R84-2): written nowhere |

| reused string | home | used by |
|---|---|---|
| P77-50 `Transfer Pack imported: <counts>` | `TransferImportStrings.imported` | C3, now the Backup screen's snackbar |
| P77-60 `This Transfer Pack is no longer on this phone. Create it again.` | `TransferStrings.PACK_GONE` | C6 |
| P77-35 `This asset was transferred out.` | `TransferImportStrings.ASSET_TRANSFERRED_OUT` | C5, emit sites unchanged |
| `Part of <parent>` | `partOfLine` (`A/ui/maintenance/DueItemRow.kt:332`) | C8 |
| `More for <name>` | the inline literal (`DocumentsSection.kt:269`) moves into `overflowLabel`: still one home | C13 |
| the three role labels | `DocumentRole?.label()` (`AttachmentsSectionViewModel.kt:60-65`) | C13 |
| `Could not save this entry.` | `CANNOT_SAVE`, now `internal` in `EventEntryViewModel.kt` | C10 |

C9's comment, docstrings and KDoc are not app strings. They follow the abeb9a45 precedent: corrected without
ratification.

## 6. Owner rulings (2026-09-29, binding)

- **R84-1 (67-2).** *Question:* should the Key documents copy's overflow be distinguishable? **Ruled:** yes, in Key
  documents only: `More for <name>, <role label>`, ratified. The DOCUMENTS list keeps `More for <name>`; the row and
  its actions are unchanged. *Alternative, not taken:* both labels identical (R67-4 read literally).
- **R84-2 (77-4b).** *Question:* should the app speak when no app can receive the pack? **Ruled:** no. Android's
  chooser owns the no-receiver case; there is no ServiceTag string; the defensive catch stays as a recorded platform
  edge. *Alternative, not taken:* §5's snackbar.
- **R84-3 (67-4).** *Question:* should a role set a new document's default kind? **Ruled:** yes, narrowly. Only for
  documents newly added from the editor: the receipt role → Receipt; either manual role → Manual; otherwise the
  file-type default. An explicitly chosen kind still wins, and the role never constrains later kind edits (R67-7). No
  migration, no rewriting of existing documents; the share intake's choices are unchanged. *Alternative, not taken:*
  keep file-type defaults and record the choice.
- **R84-4 (72-1).** *Question:* should a posted Once survive a backward clock, zone or date-line move across its due
  day? **Ruled:** yes. With unchanged content it stays quiet, and its stamp is not forgotten merely because
  `today < dueOn` again. A changed due date, mode or hash re-arms as shipped. The change lives in
  `DigestPolicy.loanOnce` alone, is proved on the JVM, and has no `Notifications.kt` work and no device proof.
  **The owner's invariant, verbatim:** "once a Once reminder has been sent, a backward clock/date movement must neither
  re-announce it nor forget that it was sent; whether the notification is presently visible is separate state".
  **Refinement, agreed by the owner and validated by the plan review (n-1):** `Standing` while a post stands, `Quiet`
  when none does, the stamp kept either way (C15; §1, correction 2). *Alternative, not taken:* keep the deferral and
  record the limit.
- **Defaults, accepted as stated:** **D-1** (77-4a) a vanished pack on Share → P77-60, with the actions disabled.
  **D-2** (72-2) the Dashboard loan row gets the shipped "Part of <parent>". **D-3** (77-1) a completed Backup-door
  import → P77-50 as the Backup screen's snackbar.
- **Scope:** 79-4 and 67-6 are dropped (X, §1). **Amended (owner, 2026-09-29):** B1 covers all eight identified
  "Save condition" taps with the known off-screen/keyboard mechanism, `ChangeConditionSheetTest.kt:187` included.
  Everything else stays #90's, with no further hunting.

## 7. What this plan does not do, and records

No version, schema, format, manifest, API, MCP-tool, device-class or device-case change; no core edit; no new route or
view model. No change to the Share door, the import sweep's order (R77-IMPORT-SWEEP), `untilCleared`, the warranty branch
or `Notifications.kt`.

**Recorded, not changed**, each named in the final report for #90 or the owner:
- The sheet's bottom-row "Cancel" is also tapped with no scroll, outside the owner's eight: `ChangeConditionSheetTest.kt:124`
  (keyboard up), `ScanSheetTest.kt:551`, `AssetDetailConditionHealthSeasonTest.kt:232` — for #90, beside 79-2b.
- `AppGraph.kt:711` still says "read once in the pick's callback", lagging 1928147a as C9's KDoc did; outside B3's files.
- The same replay-0 shape where the owner named nothing and no shipped path emits before the collector:
  `ScheduleDetailViewModel._needsForm` (its collector starts before the deep link's `complete()`), the asset detail's
  `messages` (`AssetViewModels.kt:1037`, tap-driven) and `TransferPackEvent.Say` (Save a copy's result). A rotation
  during the write can still drop them.
- `untilCleared` (the Until-returned loan step) forgets before the due day as `loanOnce` did (`:519`). A backward move
  costs it one extra post in that period. R84-4 names the Once only.
- The audit's adjacent findings (§5): #77 B4 NOTE 5 (`runCatching { createSavedStateHandle() }`); #67 n-5 (documents of
  record, at release); #67 n-7; #79 N1; #79 N3; #72 N2.

## 8. The merged-tip gate and reporting

After the whole-branch review, merge `--no-ff issue-84` into master at once. Then run the controller's existing gate
**once**, at the merge commit: the #77 script (`.superpowers/sdd/2026-09-28-issue-77/gate/run.sh`) copied unchanged
into `.superpowers/sdd/2026-09-29-issue-84/gate/`, with `extra-classes.env` unchanged (#77's four classes; #84 adds
none).

**Expected:** 54 device classes; a device time near the baseline (the eight scrolls and two assertions cost well under
a second); JVM core 1319 (no core test added) and app 1474 plus about 14; MCP 379 at 68 tools.

**Record in the ledger and in this plan's errata** (reporting only: the 14- and 15-minute lines trigger nothing for #84):
- the whole time, from start to GATE DONE, and its JVM, device and MCP portions;
- the device class count;
- **#84's delta** against 13.87 minutes whole, 12.89 minutes device and 54 classes, and whether the whole crossed 14
  or 15 minutes (reported; no action).

**A failure is recorded, never rerun:** its class, case and log, and whether it is one of #84's touched classes. A flake
at an untouched site goes to #90 with the log. One at a C2 site or at row 13 is #84's to diagnose; any fix is a new
commit and a new gate, by the controller's decision. A genuinely unusable gate (systematic failures or a pathological
runtime, not a missed line) goes to the owner as its own problem.

## Briefs — common to all four

Read §1–§8, the audit, issue #84 and its comment, and every earlier report on this branch.

**Gate.** `./gradlew :core:test :app:testDebugUnitTest --rerun` with zero failures and skips;
`:app:compileDebugAndroidTestKotlin` in every brief (B2 and B3 change signatures androidTest consumes). Before a connected class, `tools/emulator/prepare-emulator.sh`; then
one class per run, **once**: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. Then the brief's anchored greps (`git grep -nE '<pattern>'
-- <paths>`, over `app/src/main` unless named); the untouched diff; `git diff <base> | grep -cE
'^[-+].*(external_link|externalLinks)'` → 0; `git ls-tree HEAD libs/nfc-tag-core` → `7e0377a…`; each → 1:
`'^\s*versionName = "1\.4\.1"$'` and `'^\s*versionCode = 17$'` in `app/build.gradle.kts`, `'^\s*const val
SCHEMA_VERSION = 14$'` in `AppGraph.kt`, `'^\s*const val FORMAT_VERSION = 14$'` in `BackupCodec.kt`; `git status` clean.

**Pin rule.** A shipped assertion moves only where §3 names it: row 4 (`TransferPackViewModelTest.kt:372`) and row 14
(`savingANewAssetAttachesTheStagedFilesWithTheirRoles`). Every other assertion stays. **Commits.** One casual lowercase
subject line; no body, no trailers, no attribution; a row with a natural RED is committed test-first. **Report.** Every
counted RED with its quoted failure; every row with no natural RED, and why; each device run with its class, cases and
time; each grep with its count; what is done, proven and not proven.

**Stop and report, always:**
- an edit to an untouched file;
- an assertion that must move outside rows 4 and 14;
- any device failure, with its log (never rerun) — except row 13's counted device mutation run, which must fail on
  exactly its two new assertions (quoted), is reverted, and precedes the class's one green run;
- a JVM or build failure the brief did not cause (reported, not retried);
- the emulator unavailable for 30 minutes;
- a user-visible string not in §5;
- the cap or the time box reached, with what is done, proven and not.

**Must NOT, always:** delete a shipped assertion; commit outside the brief's files; add a device class or case; use a
device other than `emulator-5554`; start, stop or restart the emulator or adb; touch a harness helper,
`tools/emulator/*` or the gate script; add Robolectric or a JVM Compose dependency; bump a version; write a real name,
address, serial or tag key.

## 9. B1 — test clock and tap hygiene (C1, C2)

**Read:** audit §2 (I-1, 79-2a) and §3; `ReferencesSectionViewModel.kt`; the #65 shape
(`AttachmentsSectionViewModel.kt:145-165`, `AttachmentsSectionViewModelTest.kt:95-170`, 9500ce87);
`ChangeConditionSheet.kt:100-205`; the #82 precedent (30d8b583, 6184ab7e). **`<base>`** = `1c707d1d`. **Rows:** 1, 2.

**Connected, one run each, no reruns, each with its time:** `ui.condition.ChangeConditionSheetTest`,
`ui.JournalSmokeTest`, `ui.maintenance.ScanSheetTest`, `ui.maintenance.ScanSheetIncidentNavigationTest`,
`ui.asset.AssetDetailConditionHealthSeasonTest`.

**Greps.**
- In `A/ui/references/ReferencesSectionViewModel.kt`: `'launch\(Dispatchers\.IO\)'` → 0; `'launch\(io\)'` → 3;
  `'^\s+private val io: CoroutineContext = Dispatchers\.IO,$'` → 1.
- Over `app/src/androidTest`: `'("Save condition"|SAVE_CONDITION)\)\.performScrollTo\(\)'` → 8;
  `'("Save condition"|SAVE_CONDITION)\)(\.assertIsEnabled\(\))?\.performClick'` → 0.

**Untouched:** every production file but `ReferencesSectionViewModel.kt`; every androidTest file but the five; every
line of the five but the eight sites. **Must NOT:** touch any other tap (the "Cancel" taps in §7 included); add a
wait, retry or helper; change a shipped reference case's assertions.

**Counted RED (1):** row 1. **Caps:** 3 mutation runs (JVM), 0 device mutations; **2 hours**. **Stop also** if a site
needs more than `.performScrollTo()`, or a shipped reference case needs more than `clearModels()` appended.
**Size:** about 6 production and 40 test lines; 8 device-test lines.

## 10. B2 — the #77 transfer polish (C3–C7)

**Read:** the #77 plan §2 (C16, C18, C19, C22) and §15; `sdd/2026-09-28-issue-77/task-b3-review.md` NOTE 5 and
`task-b4-review.md` NOTEs 3, 6 and 9; `TransferImport{ViewModel,Screen}.kt`; `BackupScreen.kt:78-100`;
`ServiceTagRoot.kt` (`:64-76`, `:180-192`, `:425-440`); `TransferPackViewModel.kt`; `TransferFlowScreen.kt:60-130`;
`ScheduleDetailViewModel.kt` (`:270-440`, `:515-535`); `GroupDetailViewModel.kt:155-245`;
`AssetTransferStateTest.kt:470-560`. **`<base>`** = B1's tip. **Rows:** 3–7. **Connected:** none.

**Greps.**
- `'fun handOff\(\): String\?'` in `TransferImportViewModel.kt` → 1; `'showSnackbar'` in `TransferImportScreen.kt` → 0
  (its only use today is `:63`).
- `'TransferPackEvent\.Marked|data object Marked'` over `app/src` → 0; `'PackPhase\.MARKED'` in `TransferFlowScreen.kt`
  → 2 (was 1).
- In the two detail view models: `'MutableSharedFlow<String>\(replay = 0'` → 0; `'Channel<String>\(Channel\.BUFFERED\)'`
  → 2, one each.
- `'observeHeldIds\(\)'` in `ScheduleDetailViewModel.kt` → 1; `'R84-2'` in `TransferFlowScreen.kt` → 1.
- `git diff <base> -- <B2's files> | grep -cE '^\+.*Dispatchers\.IO'` → 0.

**Untouched:** `A/share/**` (the Share door) and `TransferImportContent`'s body; `ScheduleDetailScreen.kt`,
`GroupDetailScreen.kt`, `TransferShare.kt`, `BackupViewModel.kt`; every androidTest file. **Must NOT:** pop twice; show
P77-50 on both screens; show P77-50 from an effect keyed on the value it clears (M-1); change the Share door, the
import's sweep order or the mark's order; add a route, a view model or a port method; deliver a P77-35 line twice; move
a shipped assertion other than `TransferPackViewModelTest.kt:372`. **The task review reads by inspection** C3's
Backup-side effect (M-1) and C4's `MARKED` effect, confirming `switchTopLevel`'s `clear()` + `add()` makes a second
call harmless.

**Counted RED (6):** row 3 (1), 4 (1), 5 (2), 6 (1), 7 (1). **Caps:** 8 mutation runs (JVM), 0 device; **3 hours**.
**Stop also** if C3 needs `ShareIntakeActivity.kt` or a new route; if C5 needs a change to either screen's collector;
if C7 needs a new port method or a second statement of the held-owner rule. **Size:** about 60 production and 130 test
lines.

## 11. B3 — small fixes, tidies and stale words (C8–C12)

**Read:** audit §2 (72-2, 72-3, 79-1, 67-3, 67-5); plan-72 §13–§14; `DashboardViewModel.kt` (`:96-110`, `:340-356`,
`:425-452`); `DashboardScreen.kt` (`:296-310`, `:375-400`); `DueItemRow.kt:320-340`; `server.py` (`:2578-2590`,
`:2625-2700`) and `test_loan_tools.py:1-20`, with `docs/api/v1.md:965-976` and the MCP README `:158-166` as the
template; `LoanEditViewModel.kt` (`:75-90`, `:195-212`); `EventEntryViewModel.kt:620-690`;
`ServiceCaseViewModel.kt:215-260`; `AssetViewModels.kt:2700-2721`; `AttachmentsSectionViewModel.kt:70-78`;
`AttachmentPickers.kt` (`:40-100`, `:140-165`). **`<base>`** = B2's tip. **Rows:** 8–12. **Connected:** none. **MCP
pytest:** once.

**Greps.**
- `'^internal const val CANNOT_SAVE = "Could not save this entry\."$'` in `EventEntryViewModel.kt` → 1;
  `'^[^*]*"Could not save this entry\."'` over `app/src/main` → 1 (the home; KDoc lines excluded).
- `'thenByDescending \{ it\.capturedOn \}'` in `AssetViewModels.kt` → 0.
- In `AttachmentPickers.kt`: `'^internal fun pickedName\('` → 1; `'pickedName\('` → 2 (the definition and the one
  call). The cursor's null check stays allowed: `pickedName` handles null and blank either way.
- `'QuietLine\(partOfLine\('` in `DashboardScreen.kt` → 3 (was 2).
- Over `tools/servicetag-mcp`: `'a loan written here settles at'` → 0. In `server.py`: `'The change settles at the
  phone.s next$'` → 0 and `'sweep of any kind'` ≥ 3 (was 1). In `test_loan_tools.py`: `'sweep of any kind'` ≥ 1.
- `"in the pick's callback"` in `LoanEditViewModel.kt` → 0.

**Untouched:** every file outside B3's row of §4; in `server.py`, `test_loan_tools.py` and `LoanEditViewModel.kt`, no
changed line outside a comment, docstring or KDoc. **Must NOT:** add a string; change a Python statement or a tool
signature; change the editor's list order; trim or rewrite a real picked name.

**Counted RED (4):** rows 8, 10, 11, 12, one each. **Caps:** 6 mutation runs (JVM), 0 device; **2 hours**. **Stop
also** if the pytest count moves; if C10's home needs a file beyond the two; if `pickedName` cannot run on the JVM
without an Android runtime. **Size:** about 30 production, 50 test and 12 comment or docstring lines.

## 12. B4 — the ruled items (C13–C15)

**Read:** §6; audit §2 (67-2, 67-4, 72-1) and §4; plan-67's R67-4 and R67-7; plan-72 §13 (the PD-2 addendum) and §14;
`DocumentsSection.kt:200-275`; `AttachmentsSectionViewModel.kt:55-66`; `AssetViewModels.kt` (`:1700-1712`,
`:2470-2490`); `AddAttachment.kt:70-80` and `AttachmentUseCasesTest.kt:375-388` (read only); `DigestPolicy.kt`
(`:85-130`, `:220-235`, `:425-485`, `:540-610`); `DigestPolicyTest.kt:1125-1300` (`LoanRun`, `at`);
`AssetDetailKeyDocumentsTest.kt:185-222`. **`<base>`** = B3's tip. **Rows:** 13–15.

**Connected:** `ui.asset.AssetDetailKeyDocumentsTest`, once, plus row 13's one device mutation run.

**Greps.**
- Over `app/src/main`: `'"More for '` → 1 (in `overflowLabel`). In `DocumentsSection.kt`:
  `'^internal fun overflowLabel\('` → 1.
- In `AssetViewModels.kt`: `'kind = null,'` → 0; `'^internal fun editorKindFor\('` → 1.
- In `DigestPolicy.kt`: `'if \(facts\.today\.isBefore\(dueOn\)\) return DeadlineStep\.Forget$'` → 1 (was 2;
  `untilCleared`'s stays).
- `git diff <base> -- app/src/main/kotlin/com/loosecannon/servicetag/reminders/Notifications.kt core/src/main
  app/src/test/resources` → empty.

**Untouched:** `core/src/main/**` (`AddAttachment.kt` included); `A/share/**`; `A/api/**`;
`A/ui/attachments/{AttachmentEditSheet,AttachmentsSectionViewModel}.kt`; `A/reminders/*` but `DigestPolicy.kt`; every
androidTest file but `AssetDetailKeyDocumentsTest.kt`, and in it every case but the one. **Must NOT:** derive a kind
from a role in the use case, the sheet, the intake or the API; touch an existing row; change `untilCleared`, `decide`, the
warranty branch or any step from the due day on; add a device case; move a shipped assertion other than row 14's.

**Counted RED (6: 5 JVM, 1 device):** row 13 (1 JVM, 1 device), 14 (1), 15 (3). **Caps:** 7 JVM mutation runs and 1
device mutation run; **3 hours**. **Stop also** if C15 needs a file other than `DigestPolicy.kt`; if a shipped
`DigestPolicyTest` assertion or the warranty matrix moves; if the device case needs more than its two assertions; if
`AssetDetailKeyDocumentsTest` fails its run (reported with the log; never rerun). **Size:** about 30 production and 100
test lines; 2 device-test lines.
