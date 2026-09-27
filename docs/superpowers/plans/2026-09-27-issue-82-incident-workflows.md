# #82 — condition, Incident and repair workflows: audit, plan and briefs (rev 2.1, reviewed 2026-09-27)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review
> budget in `docs/superpowers/planning-policy.md`. Three briefs (§9 B1, §10 B2, §11 B3), strictly
> sequential; one implementer at a time, one task review each, one batched fix round each, at most one
> scoped re-review each, one whole-branch review. Planned read-only from issue #82 and the audit
> `.superpowers/sdd/2026-09-27-issue-82/audit.md` on master 5c36286b; #67 has since merged to master
> (df5d183e, errata 9934998c) and master's tip carries `tools/emulator/prepare-emulator.sh` (04b881ca).
> **Rev 2.1** closes the scoped re-review's residuals r1–r7 by controller inspection: `<base>` and the lanes
> re-stated for #67 being merged (B1's #67 gate dropped), R82-8's alternatives with their deltas, row 16's
> view-model guard, row 2's exact mutation, the §10.7 amendment quoting the words, the contract G erratum
> in §12, row 20's fixture, and the emulator prep step in every gate.
> **Rev 2** folded in the brief review (`brief-review.md`, REJECT on mechanics, design confirmed): anchored,
> scoped greps (M1); the four retargeted shipped cases (M2); row 2; `<base>`; the untouched gates and the
> `conditions.insert(` pin; the merge/replace wording; C1's gone event; C6's `onShown()`; C8's labels;
> C11's seam; no-op host defaults; R82-6's hazard; S53/S25 "(re)"; the #67 ledger precedent; the route
> round trip; NOTEs 1–9. Nothing is dispatched until every string in §6 and every ruling in §7 is answered.

**Goal:** a real failure is recorded as one coherent workflow without collapsing distinct facts. Recording
DOWN or DEGRADED offers to log the Incident, and the two land as one immutable condition row linked to one
INCIDENT event in one transaction, or as the row alone. Logging an Incident on an OPERATIONAL or unrecorded
asset asks explicitly whether it is now down, degraded or unchanged. The shipped "Mark operational?" offer
closes the loop unchanged. An impaired asset with no Incident for its current failure leads with **Log incident**.

**Spec:** issue #82 (AC 1–11 and its invariants); the 1.4 spec
`docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md` (§5.1, §5.3, §5.4 —
amended under R82-13 —, §5.5, §10.1 (item 3 amended), §10.3, §10.7, invariants 81, 83, 107, 109, 110, 119);
the 1.4 master plan's contract G (`docs/superpowers/plans/2026-09-24-servicetag-1.4/master-plan.md:311-317`,
extended by C2) and decision 51; the #71 plan's E1 (in service = `Asset.inService`); the planning policy.

## Global constraints

- **The writers of new condition facts stay closed.** Every condition the owner records is inserted by
  `RecordCondition`; merge apply and replace insert restored rows directly (`ApplyBackupMergePlan.kt:141`,
  `ImportBackupReplace.kt:165`) and stay as they are. #82 adds exactly two callers of `RecordCondition` —
  `RecordConditionWithIncident` (Workflow A) and `AcceptImpairmentOffer` (Workflow B) — each behind an
  explicit owner answer; §5.4, invariant 81 and `CrossConceptWriteTest` are amended together (R82-13).
- **Immutable facts, soft links.** No row amended or deleted; `eventId` set at insert or never; no duplicate
  row to attach a link; `event_id` stays soft, never an owner (inv. 107, 109, 110).
- **Option A (R82-1): no schema, format, DTO, merge, API route, MCP tool or argument change.**
- **Nothing silent, nothing twice.** A held condition is written only by the owner's second choice; every
  commit is idempotent on a pre-allocated row id or on "a row already names this event".
- **Health untouched:** `core/health/**`, `ui/health/AssetHealthReadModel.kt`, the dashboard; INCIDENT feeds
  no health; invariant 119 unaffected (K6). **#79 is not built** — C13's seams only.
- **Strings.** Every visible string in §6 verbatim, ratified before dispatch; reused strings through their
  shipped homes. Each P82 literal has one home under `ui/condition/`: a `const val … = "…"`, or a template
  function whose line ends in the literal's closing quote (the anchors of §9–§11 depend on it).
- **Tests.** JVM first; Compose instrumented on `emulator-5554` only, one class per invocation; no UI harness.
  Every RED is a real mutation run with `--no-build-cache --rerun-tasks`, its failing assertion quoted. **No
  shipped assertion is deleted; a shipped case moves only as §3 row 11a lists.** Hygiene, gitlink `7e0377a`,
  one-line commits, the tombstone rule, no personal data.

## 1. Audit (the audit file, spot-checked by the planner and the brief review, 2026-09-27)

**Condition.** `RecordCondition.run` (`core/…/usecase/RecordCondition.kt:42-71`) is one `uow.write`: it
requires the asset, checks shape, a future date and `FOREIGN_EVENT` (:53-55), **mints the row id** (:59) and
inserts one row. `ConditionRepository` has no get-by-id (`Repositories.kt:311-319`). Room's `@Insert` and the
in-memory fake both abort on a duplicate key. `AcceptOperationalOffer` (`OperationalOffer.kt:43-65`) nests
`RecordCondition` (decision 51; Room nesting, `RoomRepositories.kt:127-129`). Change condition writes
`eventId = null`, `occurredTime = null`, device zone, at S16 (`ChangeConditionViewModel.kt:89-123`); its view
model holds state in a `MutableStateFlow`, no `SavedStateHandle` (:68). **Events.** `buildEvent` mints the id
(`EventCommands.kt:244`), has no future-date rule and **throws** `EventValidation` (:241). The entry presets
"Incident" (`EventEntryViewModel.kt:116-117, 206`), offers for new entries only (:424), and is keyed from the
route (`EventEntryScreen.kt:82`). **Offers** (`ui/condition/Offers.kt`): sealed `EventOffer` requiring
`acceptLabel`/`declineLabel` (:56-63), two members, three exhaustive `when`s (:105-115, :232-237),
`offersAfter` a list (:226-230), a two-button `EventOfferDialog` drawn by `EventEntryScreen.kt:94` and
`CompletionFlow.kt:388`; S53 is `seasonOfferBody` (:48-49). **Navigation.** Each `NavDisplay` entry has
saveable state and its own ViewModelStore (`ServiceTagRoot.kt:103-118`); `onLogOutcome` pushes
`EventEntry(kind=…)` (:195-197). The scan sheet's resume refresh is guarded by a `remember`ed flag
(`MaintenanceSheet.kt:109-116`), so a first resume after return may not refresh (review NOTE 1). The scan
view model reads only through one-method read-only seams (`MaintenanceSheetViewModel.kt:133-188`).
**Seams for tests.** `FakeUnitOfWork` restores its stores and counts a rollback **per nesting level**
(`InMemoryRepositories.kt:601-615`); `FakeGraph` is Room-backed (`FakeGraph.kt:151`); `RouteTest.kt` exists.
Write gate: `CrossConceptWriteTest.kt:412-441`, :466-469, :470-474, :480-482. **Ratified surfaces:** §5.4
(`SPEC:398-405`), invariant 81 (`SPEC:1051`), §10.1 item 3 (`SPEC:781`), the ledger's "(re)" rule
(`SPEC:875`). "Done" is the notification action that completes a schedule (`DigestPolicy.kt:34`) and
WriteTag's close (`WriteTagScreen.kt:192`). **Met on master:** AC 6, 7, 8.

## 2. Behaviour (the briefs' contract)

```kotlin
data class IncidentWithCondition(val incident: AssetEvent?, val condition: AssetCondition)
/** The combined Save refused before any write: both halves' problems, collected once. */
class IncidentConditionRefused(
    val eventProblems: List<FieldProblem>,
    val conditionProblems: List<ConditionProblem>,
    val incidentAfterToday: Boolean,
) : IllegalArgumentException("combined save refused")
class RecordConditionWithIncident(/* LogEvent's collaborators + conditions, today, record */) {
    suspend fun run(assetId: AssetId, conditionId: String, condition: ConditionCommand,
                    incident: EventCommand): IncidentWithCondition
}
// RecordCondition, amended: rowId checked first — a row of the asset holding it is returned, nothing written.
suspend fun run(assetId: AssetId, cmd: ConditionCommand, rowId: String? = null): AssetCondition
fun impairmentOfferFor(history: ConditionHistory, event: AssetEvent): Boolean
class AcceptImpairmentOffer(/* conditions, record, uow */) {
    suspend fun run(assetId: AssetId, event: AssetEvent, condition: OperationalCondition): AssetCondition
}
fun currentIncident(rows: List<AssetCondition>, events: List<AssetEvent>): AssetEvent?
fun interface IncidentNeed { suspend fun of(assetId: AssetId): Boolean }   // the scan sheet's seam (C11)
```

- **C1, the combined write (Workflow A; AC 3; K3).** One `uow.write`: (1) the asset exists (`NoSuchAsset`);
  (2) **idempotency** — a row of the asset with id `conditionId` is returned as `IncidentWithCondition(the
  event it names if that still resolves, else null; the row)`, nothing written (no event, no recompute);
  callers treat any return as success; (3) `require` before any write: the incident is kind INCIDENT,
  `scheduleId == null`, this asset's; the condition DOWN or DEGRADED with `eventId == null`; (4) **validation
  collected before any write** into one `IncidentConditionRefused`: the event half through
  `resolveOwnedProfile` + `buildEvent` (catching `EventValidation`), the condition half through
  `conditionFactProblems` and the future-date rule, and `incidentAfterToday`; `EventOwnership` and
  `NoSuchAsset` propagate; (5) `events.upsert`, `recompute.forAsset`, then `record.run(assetId,
  condition.copy(eventId = incident.id), rowId = conditionId)` (by name) nested — any throw rolls back both.
- **C2, the one writer takes a row id.** `RecordCondition.run` gains `rowId: String? = null`, checked **before
  validation**; `ConditionCommand` unchanged, so the API body, `ConditionRequest`, `CommandShapesGoldenTest`
  and MCP cannot reach it; only C1 and C6's P82-4 pass one. This extends contract G (`master-plan.md:313`);
  the erratum is recorded in the §5.4 amendment and in §12.
- **C3, Workflow B in core (AC 4, 5; K1, K2).** `impairmentOfferFor` is pure and true iff the event is kind
  INCIDENT, `scheduleId == null`, the current row is null or OPERATIONAL and of the event's asset, and **no
  row names the event**. `AcceptImpairmentOffer.run`: `require(condition != OPERATIONAL)`; one `uow.write`
  that re-reads the rows — a row already naming the event is returned, nothing written — else
  `RecordCondition` dated `max(incident, current)` with the later known time that day (decision 51), the
  event's `tzId`, `reason = ""`, `eventId = event.id` (R82-5).
- **C4, the current Incident (AC 9; seam S-b; R82-6).** `currentIncident`, pure, in `core/condition/`: null
  unless the current row is DOWN or DEGRADED. The **impaired stretch** is the latest contiguous run of
  DOWN/DEGRADED rows in history order (DEGRADED → DOWN stays one; an OPERATIONAL row ends it). It returns
  (a) the latest existing INCIDENT non-completion event named by a row of the stretch, else (b) the latest
  INCIDENT non-completion event of the asset dated on or after the stretch's first `occurredOn` **or**
  created at or after that row's `createdAt` (R82-6 recommended; (b)'s date clause alone is the
  alternative); "latest" by `(occurredOn, occurredTime nulls first, createdAt, id)`. `needsIncident` = in
  service ∧ current DOWN/DEGRADED ∧ `currentIncident == null`.
- **C5, the write gate and the amendment (R82-13; K4).** `CrossConceptWriteTest`: the map gains
  `"RecordConditionWithIncident" to setOf("asset_event", "asset_condition", derived)` and
  `"AcceptImpairmentOffer" to setOf("asset_condition")`; the writer set becomes four, message "only the four
  condition writers write asset_condition"; the composite joins the `asset_event` exclusion; one condition
  row each, one event row for the composite. The spec gains paragraphs headed `**Amendment (#82, <the
  ratification date>)`, as #67 amended the intake spec; ratified text is not rewritten. **§5.4:** "Two more
  paths write a condition row, each through `RecordCondition` and only on the owner's answer: the combined
  flow (S16 with DOWN or DEGRADED on an in-service asset asks P82-1 first; the row is then written by P82-4
  alone or, linked, by the Incident's Save in one transaction with it), and P82-7/P82-8 after a new Incident
  on an OPERATIONAL or unrecorded asset, linked to it. An INCIDENT's kind or text never writes one.
  `RecordCondition.run` takes an optional row id (contract G erratum)." The same note under **invariant 81**;
  **§10.3** and (if R82-8 ≠ no) **§10.1 item 3** gain "Log incident"; **§10.7** gains one paragraph that
  quotes each ratified word beside its id, as #67's intake-spec amendment does (share-intake spec :555-559):
  question "Log incident details?" · body "<asset> is <DOWN/DEGRADED>. Record what went wrong in the service
  record?" · "Log incident details" · the P82-4 word as ratified · entry line "Saving also records <asset> as
  <DOWN/DEGRADED>." · question "Did this affect whether the asset can be used?" · "Mark down" · "Mark
  degraded" · "No change" · action "Log incident" — "Ratified by the owner as #82's P82-1 … P82-10", **no
  S-numbers** (#71/#73/#74 kept their P-ids plan-local) — and S53 and S25 marked "(re)" in their new roles.
- **C6, Change condition holds (AC 1, 2; K5; R82-2).** `ChangeConditionViewModel` gains the asset (name,
  `inService`), the conditions (read only) and the id generator. Save with OPERATIONAL, or on an asset out
  of service: exactly as today. Save with DOWN or DEGRADED on an in-service asset: after the unchanged S25
  check **nothing is written**; the state holds `HeldCondition(id, condition, occurredOn, reason)` and shows
  P82-1 over the sheet; S16 while a hold exists is ignored. **P82-4** → `record.run(…, rowId = held.id)` then finish; `saving` is set before the
  first suspension, so the double-tap and cancel-while-saving guards now live here. **P82-3** → a
  `handingOver` guard set before one `PendingCondition(id, condition, occurredOn, reason)` is emitted to the
  host; nothing written; the hold kept. **Dismissing the question** drops the hold and returns to the form
  as typed, writing nothing. **`onShown()`**, called by the sheet from a `LifecycleResumeEffect` (entering
  composition and every resume): with a hold, `checking` hides the question until one `conditions.forAsset`
  read answers — the held id present → finish without a write; absent → clear `handingOver`, ask again.
  C2 backs a missed trigger: P82-4 on a landed id writes nothing. The sheet gains
  `onLogIncidentDetails: (PendingCondition) -> Unit`; `AssetDetailScreen` and `MaintenanceSheet` gain it with
  a **no-op default** (so #67's `AssetDetailKeyDocumentsTest` and every other host compile untouched), and
  `ServiceTagRoot` wires both to `Route.EventEntry(asset, null, null, kind = "INCIDENT", pending = …)`.
- **C7, the entry's pending mode (R82-3).** `Route.EventEntry` gains `pending: PendingConditionArgs? = null`
  (serializable; absent in a stored back stack decodes as null); the view-model key includes the pending
  id. It opens as kind INCIDENT, no profile; title = the reason's first non-blank line trimmed, or the preset
  "Incident"; notes = the reason's remaining lines trimmed, else empty; date = the held date; no time; all
  editable; P82-5 once as a `QuietLine` under the eyebrow. Save runs `RecordConditionWithIncident.run(asset,
  pending.id, ConditionCommand(condition, held date, null, the entry's zone, held reason), cmd)` — never
  `LogEvent`, never `offersAfter`. Success → `saved`. Refusal precedence: field rows first (their first line,
  as today); else `incidentAfterToday` or `DateInFuture` → S25 as `firstProblem`; else "Could not save this
  entry." (logged), also for `NoSuchAsset`/`EventOwnership`. Back writes nothing; a Save after a lost pop
  (process death) hits C1 step 2.
- **C8, the impairment offer in the app (AC 4, 5; seam S-c).** `ImpairmentOfferPrompt(event, assetName,
  accepting = false, chosen: OperationalCondition? = null)` is an `EventOffer`: `title` P82-6, `body` S53
  (its one home), `acceptLabel` P82-7, `declineLabel` P82-9, plus its own `alternateLabel` P82-8.
  `ImpairmentOffers.offerFor(event)` = `impairmentOfferFor` ∧ dated no later than today ∧ zone resolves ∧
  in service. `offersAfter` asks operational, season, then impairment (still a list); the three `when`s gain
  the member; `EventOffers.accept` requires `chosen` ∈ {DOWN, DEGRADED}. The entry's `acceptImpairment(c)`
  sets `chosen` and `accepting` before the write; the generic `acceptOffer()` ignores this member;
  `declineOffer` (P82-9, dismissal) writes nothing. `EventEntryScreen` **branches on the member before
  `EventOfferDialog`** and draws the three-answer dialog (all disabled once an accept is tapped);
  `EventOfferDialog` and `CompletionFlowHost` are unchanged (C3 excludes completions).
- **C9, Workflow C unchanged (AC 6, 7).** `operationalOfferFor`, `AcceptOperationalOffer`,
  `OperationalOffers`, `MarkOperationalDialog`, S7/S17–S20 untouched; an INCIDENT on an impaired asset asks
  nothing, writes only the event (R82-9).
- **C10, asset detail (AC 8, 9; R82-7).** `AssetDetailState` gains `offersLogIncident` (in service) and
  `leadsWithLogIncident` (`needsIncident`), computed where `conditionHistory` is built from the same
  `ConditionHistory` and `events` — no new flow or read. `ConditionSection` draws P82-10 on every in-service
  asset: a `FilledTonalButton` first in the row when leading, else an `OutlinedButton` after S6; its tap is
  the existing `onLogOutcome(assetId, "INCIDENT")`. History rows and links unchanged (AC 8).
- **C11, the scan sheet (R82-8).** `IncidentNeed` is a one-method read-only seam built in `AppGraph` over
  `conditions.forAsset`, `events.forAsset` and C4 (the house pattern, no write method reachable).
  `MaintenanceSheetViewModel` reads it in `refresh()` and in a new `onShown()` that re-reads **only** this
  flag, called from its own `LifecycleResumeEffect` independent of the shipped `resumed` guard (NOTE 1).
  `sheetBlocks(content, hasItems, logIncident)` takes the flag as a separate argument, `logIncident =
  content.offersMarkOperational ∧ need`; `SheetBlock.ConditionActions` gains `logIncident`; P82-10 is an
  `OutlinedButton` after S6 whose tap is `onLogIncident(assetId)` (no-op default; `ServiceTagRoot` pushes the
  INCIDENT entry). `ScanSheetContent.kt` is untouched; the flag never opens the sheet; the scan writes nothing.
- **C12, compatibility, API, MCP (AC 10; R82-12).** Nothing on the wire changes. `docs/api/v1.md`'s
  Condition paragraph gains a paragraph opening `**Recording a failure with its Incident.**` — `POST
  /v1/events {kind: INCIDENT, …}` then `POST /v1/assets/{id}/conditions {…, eventId}`, two transactions (the
  app's combined flow is one). The `log_event` and `record_condition` docstrings each gain one sentence
  pointing at the other; `record_condition` keeps "can never be amended or deleted" (pytest-pinned).
- **C13, #79's seams (AC 11).** The Incident is an ordinary `AssetEvent`; C4 is one pure function; the queue
  stays a list; `operationalOfferFor` stays callable with any event; no case, status, reminder or deadline.

## 3. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| 1. both written, linked (C1, AC 3) | core `RecordConditionWithIncidentTest` · `writesOneIncidentAndOneRowLinkedToIt` (one INCIDENT event; one row with the supplied id and `eventId == incident.id`; the command's date and reason; recompute ran; no other table) | drop the `eventId`; mint a fresh row id |
| 2. both or neither (C1) | · `aThrowingConditionInsertLeavesNeitherFact` (events and rows empty; the throw leaves the outer write); `aFailureAfterTheEventWriteLeavesNeitherFact` (the asset holds one schedule and the recompute's state write throws, after `events.upsert`); app (B2) `IncidentWorkflowRoomTest` · `theCombinedSaveRollsBackOnRoom` (FakeGraph's Room; a wrapping `ConditionRepository` throws on insert → no `asset_event` row) | upsert the event in its own write; recompute and the row after |
| 3. refused whole, collected (C1, K3) | · `aBlankTitleAndATooLongReasonAreReportedTogetherAndWriteNothing` (both lists; `commits == 0`); `anIncidentDatedAfterTodayIsRefusedWhole` | fail fast on the event half; drop the date check |
| 4. idempotent (C1, C2, K5) | · `aStoredRowIdWritesNothingAndReturnsThePair`; `aStoredRowWhoseEventWasDeletedReturnsANullIncident`; `RecordConditionTest` · `aSuppliedRowIdIsTheRowsId`; `aSuppliedIdAlreadyStoredWritesNothingEvenIfTheCommandIsNowInvalid`; `withoutAnIdTheRowIsMintedAsBefore` | skip the check; check after validation; ignore `rowId` |
| 5. programming errors (C1, C3) | · `rejectsOperationalACompletionAnotherKindOrAPresetLink` (before any write); `AcceptImpairmentOfferTest` · `refusesOperational` | drop a `require` |
| 6. Workflow B predicate (C3, K1, K2) | core `ImpairmentOfferTest` · a table: INCIDENT on OPERATIONAL → true, on none → true, on DEGRADED/DOWN → false; an INCIDENT completion → false; NOTE, MAINTENANCE → false; named by a row → false; another asset's history → false | drop each clause in turn |
| 7. Workflow B accept (C3, R82-5) | `AcceptImpairmentOfferTest` · `acceptingDatesTheRowMaxOfIncidentAndCurrentWithTheLaterTimeAndLinksIt` (DOWN and DEGRADED; empty reason; the event's zone); `aSecondAcceptWritesNothing`; `itWritesOneRowAndNothingElse` | the Incident's own date; drop the re-check |
| 8. an Incident writes nothing by itself (AC 5, inv. 81, K6) | core `IncidentWorkflowTest` · `loggingAnIncidentWritesNoConditionAndMovesNoHealth` (INCIDENT on OPERATIONAL, DOWN, none; rows unchanged; the harness's health result equal before and after) | `LogEvent` records a row for INCIDENT |
| 9. current Incident (C4, R82-6) | core `CurrentIncidentTest` · null when OPERATIONAL or none; a linked INCIDENT wins over a later unlinked one; an unlinked INCIDENT dated the stretch's first day counts, the day before does not unless logged after the stretch began (`aBackdatedIncidentLoggedDuringTheStretchCounts`, per R82-6); DEGRADED → DOWN is one stretch; OPERATIONAL ends it; a linked MAINTENANCE, a dangling link, an INCIDENT completion never count | use `since`; accept any kind; drop the `createdAt` clause |
| 10. the write gate (C5, R82-13) | `CrossConceptWriteTest` (amended): the two new cases; the four-writer message; one condition row each; one `asset_event` row for the composite | write a second row; touch `asset`; leave a writer out |
| 11. the hold (C6, AC 1) | app `ChangeConditionViewModelTest` · `downOrDegradedOnAnInServiceAssetHoldsAndAsks`; `operationalWritesAtOnceAndAsksNothing` (with two taps: one row, the shipped guard kept for this path); `anOutOfServiceAssetWritesAtOnce`; `downOverDownAsksToo`; `aLaterDateIsS25BeforeAnyQuestion` | write at S16; ask for OPERATIONAL; ask out of service |
| 11a. shipped cases retargeted (C6, M2) | `nothingIsPreselectedAndSaveWritesOnce` — keeps its preselection and held-Save checks; two S16 taps now give no row and one question; two P82-4 taps then give **one** row with every shipped field assertion and `finished == 1`. `cancelWhileSavingIsIgnored` — `saving` is asserted after P82-4; Cancel ignored; one row, finished once. `thePastIsAllowed` — the backdated DEGRADED is committed by P82-4 and still sorts behind `c-now`. Device `ChangeConditionSheetTest.threeOptionsHelpersAndSave` — keeps every string and helper assertion; Save condition shows P82-1…P82-4; P82-4 → trail `["done"]` and the same row. `aFutureDateShowsS25AndWritesNothing`, `cancelWritesNothing`, `ChangeConditionSheetTest.cancel`, `ScanSheetTest.cancelWritesNothing` unchanged | drop `saving` from P82-4; drop the double-tap guard |
| 12. decline and dismiss (C6, AC 2) | · `saveConditionOnlyWritesOneUnlinkedRowWithTheHeldIdAndNoEvent`; `dismissingTheQuestionKeepsTheFormAndWritesNothing` | write an event; commit on dismiss |
| 13. hand-over and return (C6, K5) | · `logIncidentDetailsHandsOverTheDraftAndWritesNothing`; `aDoubleTapOnLogIncidentDetailsHandsOverOnce`; `onShownWithTheHeldIdStoredClosesWithoutAWrite`; `onShownWithoutItAsksAgain`; `theQuestionIsHiddenUntilTheReadAnswers`; `saveConditionOnlyAfterALandedCombinedSaveWritesNothing` | write before handing over; drop the guard; skip the read |
| 14. pending entry (C7, R82-3) | app `EventEntryViewModelTest` · `aPendingEntryOpensAsAPrefilledIncident` (kind; title = first line or `Incident`; notes = the rest; date; no time; P82-5's data); `savingItWritesOnceLinkedAndAsksNothing` (1 event, 1 row with the held id, `offer` null, `saved` once); `leavingItWritesNothing`; `aLaterIncidentDateSaysS25AndWritesNothing`; `fieldRowsWinOverS25`; `aSecondSaveAfterTheCommitWritesNothing` (a fresh view model on the same route) | `logEvent` then `recordCondition` separately; call `offersAfter`; drop the idempotency |
| 14a. the pending route survives (C7) | app `RouteTest` · `anEventEntryWithAPendingConditionRoundTrips`; `anEventEntryWithoutPendingDecodesAsBefore` | drop `@Serializable`; no default |
| 15. Workflow B asked (C8, AC 4) | app `OffersTest` · `anIncidentOnAnOperationalOrUnrecordedAssetAsksTheImpairmentOffer` (P82-6, S53); one case per exclusion: completion, out of service, after today, unresolved zone, named by a row, impaired; `EventEntryViewModelTest` · `anEditNeverAsks` | remove each exclusion in turn |
| 16. Workflow B answered (C8) | · `markDownAndMarkDegradedEachWriteOneLinkedRow`; `noChangeAndDismissWriteNothing`; `aCompletionOfAnIncidentKindTaskAsksNothing`; `theGenericAcceptIgnoresIt`; `EventEntryViewModelTest` · `aSecondTapOnAnAnswerIsIgnored` (two `acceptImpairment` calls: one accept reaches `EventOffers`, counted through a recording offers double, and `saved` emits once — rows cannot show it, C3 re-reads) | write OPERATIONAL; write on No change; accept with no choice; drop the `accepting` guard |
| 17. Workflow C kept (C9, AC 6, 7) | shipped `OperationalOfferTest` (3) and `OffersTest` `aMaintenanceOrReplacementEventOffersAnInspectionDoesNot`, `notYetWritesNothing`, `anOperationalOfferAfterACompletionOnADownAsset` unchanged; new `OffersTest` · `anIncidentOnAnImpairedAssetAsksNothing` | add INCIDENT to `restores` |
| 18. the sheet on screen (C6) | device `ChangeConditionSheetTest` · `downAsksTheQuestionAndSaveConditionOnlyRecordsOneRow`; `logIncidentDetailsReachesTheHostAndWritesNothing`; `operationalAsksNothing`; `AssetDetailConditionHealthSeasonTest` · `theDetailsSheetHandsTheDraftToItsHost`; `ScanSheetTest` · `theScanSheetsSheetHandsTheDraftToItsHost` | skip the question; drop a host's pass-through |
| 19. three answers on screen (C8) | device `IncidentOfferDialogTest` (new; the dialog drawn alone) · `titleBodyAndThreeAnswers`; `eachAnswerReachesItsCallbackAndDisablesTheOthers` | swap two callbacks |
| 20. detail affordance (C10, R82-7) | app `ConditionIncidentAffordanceTest` (new; FakeGraph detail state, sparing #67's `AssetViewModelsTest`) · `logIncidentOnEveryInServiceAsset`; `itLeadsWhenImpairedWithoutACurrentIncident`; `aStandaloneIncidentInTheStretchStopsItLeading`; `aLinkedIncidentStopsItLeading` (the fixture where `since` differs from the stretch's start: current DOWN after DEGRADED, the Incident linked to the DEGRADED row); `neverOutOfService` | compute from `since` (goes RED on that fixture); ignore in-service |
| 21. detail on screen (C10, AC 8, 9) | device `AssetDetailConditionHealthSeasonTest` · `logIncidentOpensAnIncidentEntry` (captures `onLogOutcome(asset, "INCIDENT")`); `aDownAssetWithoutAnIncidentLeadsWithLogIncident`; shipped `conditionSectionAndActions` green | pass another kind; drop the lead |
| 22. scan (C11, R82-8) | app `MaintenanceSheetViewModelTest` · `anImpairedAssetWithoutAnIncidentOffersLogIncident`; `notWhenOneIsLinked`; `notWhenOperational`; `theFlagNeverOpensTheSheet`; `onShownReReadsTheFlagAfterAnIncidentIsLogged`; device `ScanSheetTest` · `logIncidentOnlyNavigates` (rows and events unchanged) | open on the flag; skip the `onShown` read; write on tap |
| 23. reads write nothing; writers closed | `ReadPathsWriteNothingTest`, `ApiReadsWriteNothingTest` green; the `conditions.insert(` pin of §9–§11 | — |
| 24. compatibility (AC 10) | `CommandShapesGoldenTest`, `SeasonHealthRoutesTest`, `MergePlannerSeasonHealthTest`, `BackupContentCheckTest`, `BackupFormat9Test`, `VersionAgreementTest` unchanged by #82 and green; MCP pytest count unchanged | — |

## 4. Files (indicative; the implementer owns the placement)

**B1 (core):** new `core/…/usecase/RecordConditionWithIncident.kt`, `usecase/ImpairmentOffer.kt`,
`core/…/condition/CurrentIncident.kt`; `usecase/RecordCondition.kt`; tests: new
`RecordConditionWithIncidentTest`, `ImpairmentOfferTest`, `AcceptImpairmentOfferTest`, `CurrentIncidentTest`,
`IncidentWorkflowTest`; `RecordConditionTest`, `CrossConceptWriteTest`, `ConditionHealthHarness` (wiring);
the 1.4 spec (C5). **B2 (app flows):** `ui/condition/{ChangeConditionViewModel,ChangeConditionSheet,Offers}.kt`
(P82-1 … P82-9's homes), `ui/journal/{EventEntryViewModel,EventEntryScreen}.kt`, `ui/nav/{Route,ServiceTagRoot}.kt`,
`ui/asset/AssetDetailScreen.kt` and `ui/maintenance/MaintenanceSheet.kt` (the defaulted callback, passed
through), `di/AppGraph.kt`, `testing/FakeGraph.kt`; tests: `ChangeConditionViewModelTest`,
`EventEntryViewModelTest`, `OffersTest`, `RouteTest`, new `IncidentWorkflowRoomTest`; device
`ChangeConditionSheetTest`, new `IncidentOfferDialogTest`, one case each in
`AssetDetailConditionHealthSeasonTest` and `ScanSheetTest`. **B3 (surfaces, docs):** `ui/asset/AssetViewModels.kt`,
`AssetDetailScreen.kt` (`ConditionSection`), `ui/maintenance/{MaintenanceSheetViewModel,MaintenanceSheet}.kt`,
`ServiceTagRoot.kt` (`onLogIncident`), `ui/condition/ConditionWords.kt` (P82-10's home), `di/AppGraph.kt` +
`FakeGraph.kt` (`IncidentNeed`), `docs/api/v1.md`, `tools/servicetag-mcp/src/servicetag_mcp/server.py`;
tests: new `ConditionIncidentAffordanceTest`, `MaintenanceSheetViewModelTest`; device
`AssetDetailConditionHealthSeasonTest`, `ScanSheetTest`. **Untouched:** `core/{backup,merge,health,model,ports}`,
`usecase/{OperationalOffer,LogEvent,UpdateEvent,DeleteEvent,CompleteSchedule,EventCommands,ConditionCommands}.kt`,
`data/room/**`, `app/schemas`, `api/**`, `ui/health/**`, `ui/dashboard/**`, `MarkOperationalDialog.kt`,
`CompletionFlow.kt`, `ScanSheetContent.kt`, `docs/versioning.md`, `docs/release-proofs.md`,
`tools/servicetag-{bundle,schedules}`. **#67 collisions:** B1 **none**; B2 **low** (`AppGraph.kt` other
hunks; `ServiceTagRoot.kt` adjacent; `AssetDetailScreen.kt` the Details and attachments calls); B3 **high**
(`AssetViewModels.kt` +444 in `AssetDetailState` and the combine; `AssetDetailScreen.kt`; `v1.md`,
`server.py` low); `AssetViewModelsTest` (+700) avoided by row 20's new class. #67 is merged (df5d183e), so
every brief reads the merged files and no lane waits on it.

## 5. Schema, backup and merge implications (summary for the owner)

**Option A (recommended) changes nothing stored or exchanged:** an INCIDENT event plus a row whose existing
`eventId` names it. Deleting the Incident leaves a readable dangling link (S24) and Log incident leads again;
an edit keeps the link; restore and merge see two ordinary facts (events first, `eventId` never an owner);
every pre-#82 archive re-plans as before; API and MCP shapes are identical. Its cost is behavioural: the
combined flow commits at the owner's second choice, not at S16. **Option B** (schema 11 / format 11, a soft
`asset_event.condition_id`) links "condition saved, then Incident later" literally, at the cost of a B0 of
about 600–900 lines across about 15 of #67's files (migration, schema json, `AppDatabase`, `SCHEMA_VERSION`,
entity, mapper, DTO, `FORMAT_VERSION`, content check, merge verdicts, event command, API DTO and golden test,
MCP, the release gate → 11), one relationship stored in two directions — and a rev 3 of these briefs.

## 6. Strings — for ratification

| id | where | text | treatment |
|---|---|---|---|
| P82-1 | Workflow A question, over the Change condition sheet (detail and scan) | `Log incident details?` | dialog title |
| P82-2 | its body | `<asset> is <DOWN/DEGRADED>. Record what went wrong in the service record?` | body; the word through S2/S3's home |
| P82-3 | its accept | `Log incident details` | `TextButton` (confirm) |
| P82-4 | its decline, which writes the row alone | `Save condition only` (recommended; the issue's `Done` is the alternative, R82-14) | `TextButton` (dismiss) |
| P82-5 | the Incident entry in the combined flow, under the eyebrow | `Saving also records <asset> as <DOWN/DEGRADED>.` | `QuietLine` |
| P82-6 | Workflow B question, after a new Incident | `Did this affect whether the asset can be used?` | dialog title (the issue's wording) |
| P82-7 | answer | `Mark down` | `TextButton` |
| P82-8 | answer | `Mark degraded` | `TextButton` |
| P82-9 | answer; also what dismissing means | `No change` | `TextButton` |
| P82-10 | detail Condition section (every in-service asset) and scan sheet (C11) | `Log incident` | detail: `FilledTonalButton` first when leading, else `OutlinedButton` after S6; scan: `OutlinedButton` after S6 |
| — | reused verbatim | ratified: S2, S3 (inside P82-2/5), S6, S7, S16 `Save condition`, S17–S20, S21, S23, S24; **S25 (re)** — also the combined Save's refusal on the entry; **S53 (re)** `You logged <event title>.` — also Workflow B's body; shipped: `Cancel`, `Could not save this entry.`, the preset `Incident`, the entry's field-problem lines | through their homes |

Ten new strings, none accessibility-only (every new control is a text button without an icon); P11 is not
proposed (R82-11). P82-7/P82-8 are lower case on purpose, following S7 "Mark operational", not the badge
words S2/S3. P82-4 begins with S16's text; shipped tests match "Save condition" exactly, so they do not
collide. **The "Done" collision:** "Done" completes a schedule (`DigestPolicy.ACTION_DONE`) and closes
WriteTag; under hold-and-commit P82-4 **writes the row**, and "Done" would hide that write. Recommended:
`Save condition only`.

## 7. Rulings and owner questions (controller, 2026-09-27)

- **R82-1 (recommended: A).** One core use case writes the Incident and its linked row in one transaction; no
  schema or format change, so AC 10 holds by construction. B's cost is §5; choosing B voids these briefs
  (a rev 3 with a B0). **Owner: A or B.**
- **R82-2 (recommended: hold-and-commit).** For DOWN/DEGRADED on an in-service asset S16 writes nothing; the
  row is written by P82-4 alone or, linked, by the Incident's Save. Back from the entry returns to the
  question; dismissing it returns to the form, writing nothing; a retired or archived asset writes at S16 as
  today. Writing unlinked at S16 cannot meet AC 3 without a schema change. Sub-choices: **(a)** every
  DOWN/DEGRADED record asks, DOWN over DOWN included (recommended, AC 1 as written), or an improvement
  (current DOWN → DEGRADED) writes at once without the question; **(b)** after process death the sheet
  reopens empty and nothing is written twice (recommended, disclosed), or the hold moves into a
  `SavedStateHandle` (one more constructor seam) so the question survives. **Owner: confirm; choose (a), (b).**
- **R82-3 (recommended).** The row keeps S15's date, no time, the device zone. The Incident is prefilled and
  editable: that date, no time, title = the reason's first line (or `Incident`), notes = its remaining lines.
  An Incident dated after today refuses the combined Save whole with S25. Alternative: title `Incident`,
  notes = the whole reason. **Owner: confirm or choose.**
- **R82-4 (recommended).** Workflow B asks only after a **new** INCIDENT entry that is not a completion, when
  the current condition is OPERATIONAL or not recorded, the asset in service, the Incident dated no later
  than today in a resolving zone, no row naming it — never in the combined flow. DEGRADED → "Mark down" is
  left out (Change condition covers it; adding it is one branch and its tests, no string). **Owner: confirm.**
- **R82-5 (recommended).** The accepted row is dated `max(incident, current row)` with the later known time
  that day (decision 51), so it becomes current; empty reason (the link names the Incident); the event's
  zone. Alternative: the Incident's own date, which can sort before the current row. **Owner: confirm.**
- **R82-6 (recommended).** "No linked current Incident" = `currentIncident == null` over the contiguous
  DOWN/DEGRADED stretch (not the `since` run, so a worsening does not re-demand one). Sub-choice (b): count an
  unlinked INCIDENT dated on or after the stretch's start **or logged (`createdAt`) after it began**
  (recommended), or by date alone — under which a standalone Incident backdated before the day DOWN was
  recorded (failed Monday, recorded Tuesday) never counts and Log incident leads until a new condition row.
  **Owner: confirm the stretch; choose (b).**
- **R82-7 (recommended).** Log incident on every in-service asset — the only profile-less path to Workflow
  B — leading the row, tonal, when `needsIncident`. AC 9 is read for in-service assets only: a retired or
  archived DOWN asset gets no Log incident (the house in-service rule). **Owner: confirm.**
- **R82-8 (recommended: yes).** The scan sheet shows Log incident as navigation only, only when
  `needsIncident`; the flag never opens the sheet. **Owner: confirm, or choose an alternative**, folded by the
  controller at ratification:
  - **"no"** → C11, row 22, B3's `'^\s+onLogIncident = '` and `'^fun interface IncidentNeed\b'` greps and the
    `ScanSheetTest` scan cases drop; §10.1's amendment is not written, so B1's amendment count is 4;
  - **"every impaired asset"** → `logIncident = content.offersMarkOperational` without `need`, and row 22's
    `notWhenOneIsLinked` inverts.

  **R82-9 (confirmation):** a standalone Log incident on an impaired asset writes only the event, asks nothing.
- **R82-10 (recommended: defer).** No free-form "Log repair/replacement"; the UPS example's step 3 needs a
  REPLACEMENT quick action or the retirement follow-on. **Owner: confirm.** **R82-11 (recommended: not in
  #82):** no INCIDENT kind on history links or service-record rows; P11 withdrawn.
- **R82-12 (recommended: docs only; not a contract change).** No composite endpoint or new argument —
  `record_condition` already takes `event_id`; the recipe and two docstrings (C12). An atomic API composite
  would be a contract change, scoped separately. **Owner: confirm.**
- **R82-13 (recommended).** The amendments of C5, on the #67 precedent: §5.4 (with the contract G erratum),
  invariant 81, §10.3, §10.1 item 3 unless R82-8 = no, and a §10.7 paragraph quoting P82-n with S53/S25 "(re)" —
  no new S-numbers. No `versionName` bump in #82 (#74/#67 precedent); the shipping release is classified
  then (a new feature: MINOR). **Owner: confirm the amendment; choose the vehicle.**
- **R82-14.** Ratify P82-1 … P82-10, **S53 (re)** as Workflow B's body and **S25 (re)** as the combined
  Save's refusal on the entry. **Owner: choose P82-4 — `Save condition only` (recommended) or `Done`.**

## 8. What this plan does not do

No schema, format, API route, MCP tool or argument change; no Service Case, outcome, status, reminder or
deadline (#79/#72); no kind on links or rows; no scan-sheet history link; no free-form repair entry; no
dashboard, health or read-model change; no inference; no `versionName` bump; no phone.

## 9. Brief B1 — core: the combined write, Workflow B, the current Incident, the gate, the amendment

**Read first:** §1–§8; issue #82; the audit; `RecordCondition.kt`, `ConditionCommands.kt`,
`OperationalOffer.kt`, `LogEvent.kt`, `EventCommands.kt`, `ConditionHistory.kt`, `CrossConceptWriteTest.kt`,
`ConditionHealthHarness`, `InMemoryRepositories.kt` (`FakeUnitOfWork`), `RecordConditionTest`; the 1.4 spec
§5, §10, §11; #67's intake-spec amendment (share-intake spec :555-559, on master). **Lane:** alone, own
worktree, `issue-82` from master at or after this plan's ratified commit (which follows df5d183e, so #67 is
in). **`<base>`** = that branch point. **Contracts:** C1–C5, C13. **Test matrix:** rows 1–10.
**Gate:** B1 has no connected class; were one run, `tools/emulator/prepare-emulator.sh` runs first.
`./gradlew :core:test :app:testDebugUnitTest --rerun` zero failures/skips. Anchored greps:
`git grep -nE '\browId: String\? = null' -- core/src/main` → 1 (`RecordCondition.kt`);
`git grep -nE '\browId = ' -- core/src/main` → 1 (the composite's nested call);
`git grep -nE '\browId\b' -- app/src/main tools` → 0;
`git grep -nE '"only the four condition writers write asset_condition",?$' -- core/src/test` → 1;
`git grep -nE '\bconditions\.insert\(' -- core/src/main app/src/main` → exactly `RecordCondition.kt`,
`ApplyBackupMergePlan.kt`, `ImportBackupReplace.kt`;
`git grep -nE '^\*\*Amendment \(#82, ' -- docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md`
→ 5 unless R82-8 = no, then 4. Untouched: `git diff <base> --stat -- app tools docs/api core/src/main/kotlin/com/loosecannon/servicetag/core/{backup,merge,health,model,ports} core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/{OperationalOffer,LogEvent,UpdateEvent,DeleteEvent,CompleteSchedule,EventCommands,ConditionCommands}.kt`
→ empty. Hygiene; gitlink `7e0377a`; `git status` clean.
**Must NOT:** insert a row except through `RecordCondition`; write before validation completes; check
`rowId` after validation; put `rowId` on `ConditionCommand` or any API type; read or write health; rewrite
ratified spec text or give P82 strings S-numbers; touch the app. **Size:** about 250 production, 500 test lines.

## 10. Brief B2 — app flows: the hold, the pending entry, the impairment offer (C6–C9)

**Read first:** §1–§8; B1's report; `ChangeConditionViewModel.kt`, `ChangeConditionSheet.kt`, `Offers.kt`,
`EventEntryViewModel.kt`, `EventEntryScreen.kt`, `Route.kt`, `ServiceTagRoot.kt`, `AssetDetailScreen.kt`,
`MaintenanceSheet.kt`, `CompletionFlow.kt` (`CompletionFlowHost`), `AppGraph.kt`, `FakeGraph.kt`,
`RouteTest.kt`, and every case §3 rows 11–19 name. **Lane:** on `issue-82`, after B1 is accepted (#67 is
already in). **`<base>`** = B1's accepted tip. **Contracts:** C6–C9. **Test matrix:** row 2's Room case,
rows 11–19 (11a in full).
**Gate:** `tools/emulator/prepare-emulator.sh` before the first connected class. JVM as B1;
`:app:compileDebugAndroidTestKotlin`; connected on `emulator-5554`, one class per run
(`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`):
`ChangeConditionSheetTest`, `IncidentOfferDialogTest`, `AssetDetailConditionHealthSeasonTest`,
`ScanSheetTest`, `JournalSmokeTest`. Anchored greps over `-- app/src/main`, each → 1:
`'= "Log incident details\?"$'`, `'= "Log incident details"$'`, `'= "Save condition only"$'` (if R82-14
picks `Done`: `'= "Done"$'` → 2, the base's `ACTION_DONE` plus one),
`'= "Did this affect whether the asset can be used\?"$'`, `'= "Mark down"$'`, `'= "Mark degraded"$'`,
`'= "No change"$'`, `'Record what went wrong in the service record\?"$'`, `'"Saving also records \$'`,
`'\browId = '`; unchanged from `<base>`: `'"You logged \$'` → 2, `'= "The date cannot be later than today\."$'`
→ 1, the `conditions.insert(` pin; `git grep -nE '^\s+onLogIncidentDetails = ' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt`
→ 2; `git grep -nE '\browId\b' -- app/src/main/kotlin/com/loosecannon/servicetag/api tools` → 0. Untouched:
`git diff <base> --stat -- core/src/main app/schemas app/src/main/kotlin/com/loosecannon/servicetag/{api,data,ui/health,ui/dashboard} app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetViewModels.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/condition/MarkOperationalDialog.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/{CompletionFlow,MaintenanceSheetViewModel,ScanSheetContent}.kt tools docs`
→ empty; `AssetDetailScreen.kt` and `MaintenanceSheet.kt` diffs show only the defaulted parameter and its
pass-through. Hygiene; gitlink; clean.
**Must NOT:** write at S16 for DOWN/DEGRADED on an in-service asset; write on dismiss, back, Cancel or
hand-over; call `LogEvent` or `offersAfter` in pending mode; let the generic accept or `CompletionFlow`
accept the impairment member; change Workflow C or `EventOfferDialog`; **delete a shipped assertion or
retarget a case other than as row 11a lists**; commit a core change; use any device but `emulator-5554`.
**Size:** about 450 production, 750 test lines.

## 11. Brief B3 — surfaces and docs: Log incident on detail and scan, the recipe (C10–C12)

**Read first:** §1–§8; B1's and B2's reports; `AssetViewModels.kt` as merged with #67 (`AssetDetailState`,
the detail view model's combine, `historyOf`), `AssetDetailScreen.kt` (`ConditionSection`),
`MaintenanceSheetViewModel.kt` (the read-only seams, `sheetBlocks`, the constructor), `MaintenanceSheet.kt`
(`ConditionActions`, the resume effect), `docs/api/v1.md` (the Condition paragraph), `server.py`
(`log_event`, `record_condition`), `tests/test_season_health_tools.py`. **Lane:** on `issue-82`, after B2.
**`<base>`** = B2's accepted tip. **Contracts:** C10–C12. **Test matrix:** rows 20–24.
**Gate:** `tools/emulator/prepare-emulator.sh` before the first connected class. JVM as B1;
`:app:compileDebugAndroidTestKotlin`; connected on `emulator-5554`, one class per run:
`AssetDetailConditionHealthSeasonTest`, `ScanSheetTest`, `DashboardAttentionTest`,
`AssetDetailKeyDocumentsTest` (#67's host of the same screen); `(cd tools/servicetag-mcp && uv run --frozen
pytest)` green, count unchanged. Anchored greps: `git grep -nE '= "Log incident"$' -- app/src/main` → 1;
`git grep -nE '^\s+onLogIncident = ' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt`
→ 1; `git grep -nE '^fun interface IncidentNeed\b' -- app/src/main` → 1;
`git grep -nE '^\*\*Recording a failure with its Incident\.\*\*' -- docs/api/v1.md` → 1;
`git grep -nE 'EventKind\.INCIDENT' -- app/src/main/kotlin/com/loosecannon/servicetag/ui/health app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard`
→ 0; the `conditions.insert(` pin unchanged. Untouched: `git diff <base> --stat -- core app/schemas app/src/main/kotlin/com/loosecannon/servicetag/{api,data,ui/health,ui/dashboard,ui/journal} app/src/main/kotlin/com/loosecannon/servicetag/ui/condition/{ChangeConditionSheet,ChangeConditionViewModel,Offers,MarkOperationalDialog}.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/{ScanSheetContent,CompletionFlow}.kt docs/superpowers`
→ empty; the `server.py` diff touches only the two docstrings. Hygiene; gitlink; clean.
**Must NOT:** add a flow or per-asset fan-out for the flags; give the scan sheet a repository with a write
method; let the flag open the sheet or write anything; draw Log incident out of service; change the history
rows, links or S24; touch the dashboard, health, `AssetViewModelsTest` or any API/MCP signature; use any
device but `emulator-5554`. **Size:** about 220 production, 380 test, 20 docs lines.

## 12. Errata

- **Contract G (1.4 master plan, `docs/superpowers/plans/2026-09-24-servicetag-1.4/master-plan.md:313`) is
  extended by #82:** `RecordCondition.run(assetId, ConditionCommand, rowId: String? = null)` — an optional
  row id, checked before validation, never on `ConditionCommand` (C2). The master plan is not edited; this
  entry and the spec's §5.4 amendment are the record.
