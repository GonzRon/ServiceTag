# #79 — warranty status, expiration reminders and service cases: plan and briefs (rev 2.1, reviewed 2026-09-27)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget in
> `docs/superpowers/planning-policy.md`. Split form (R79-scope): #79a (§9 A1, §10 A2, §11 A3, §12 A4) on schema/format
> 11, then #79b (§13 B1, §14 B2, §15 B3) on 12; each brief closes with its **one-issue reading**, so either ruling runs
> without a rev 3. Strictly sequential; one implementer at a time, one task review, one batched fix round and at most one
> scoped re-review each, one whole-branch review per branch. Planned read-only from issue #79 and the audit
> `.superpowers/sdd/2026-09-27-issue-79/audit.md`, spot-checked on master 082bf4b8 (#82 merged at 264b1645). **Nothing
> is dispatched until R79-scope, every string in §6 and every ruling in §7 are answered.**
>
> **Rev 2** folds in the brief review (`brief-review.md`: REJECT on rev 1 — 0 blocker, 4 major, 14 minor, 10 notes;
> the scope facts confirmed in the code, the split read as the better answer): M1, the derived warranty status gets its
> own `GET /v1/assets/{id}/warranty` response and never rides on the backup row DTO the API reuses (seven routes,
> seven tools); M2, every shipped version and table pin each brief moves is listed in its retarget row (10, 25, 32,
> 44, 54); M3, the two new tables' tallies join core `MergeReport` and the API's `MergeReportResponse` in B1
> (seventeen tables); M4, the `subjectsFor` composition is proven on device through `app.graph.reminderRuns`; m1–m14,
> the notes and the strings list applied; §9–§15 re-cut in the split form; §1 cut to what the audit does not say.
> **Rev 2.1** closes the scoped re-review's R1 and r1–r6 by controller inspection: every pin it listed joins the
> retarget rows, with a class rule in the briefs' common block; #72 taking 12; A1's one-issue lift; allow-0 threaded;
> lowerCamel command-shape keys; the season rule in R79-15; two citations.

**Goal:** the asset detail says IN WARRANTY or OUT OF WARRANTY from the stored date and `Today`, and the owner may ask
for a warning a chosen number of days before expiry, delivered once through the one reminder pipeline (Part A);
outside service becomes an asset-owned **Service case** — provider, contact, case/RMA number, explicit coverage,
status, tracking, cost — with an append-only timeline, soft links to #82's Incident and the resolving MAINTENANCE/
REPLACEMENT event, and documents on those events (Part B). Nothing writes a condition, event, schedule or completion.

**Spec:** issue #79 (AC 1–16); SPEC12 = `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`
(§2.5, §5.1, §9.1, inv. 44–61); SPEC14 = `docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md`
(§5.4, inv. 81 untouched; §10.3, §10.4, §10.7 amended); the #82 plan (C4, C13, §12); the #67 plan (C3–C4, R67-12,
§13); the planning policy.

## Global constraints

- **The condition writers stay closed; nothing silent.** No case, entry or warranty write touches `asset_event`,
  `asset_condition`, `schedule_state`, a schedule or a closure; return to OPERATIONAL is the resolving event's shipped
  "Mark operational?" (Workflow C). The four writers, §5.4, inv. 81, inv. 119, `core/{health,condition}/**` and
  `ui/{health,dashboard,condition}/**` are untouched; nothing joins `offersAfter`.
- **The closed schedule-reminder semantics are untouched except the shared seam (C3–C8):** statuses, summary and
  counts, Done/Snooze, nonces, schedule tag and hash bytes, the two maintenance channels, the six receivers, the three
  health findings and their sentences.
- **Schema and format:** 11 for Part A and 12 for Part B (split), or 11 for both (one issue), each on the #67
  precedent; `LAST_LEGACY_FORMAT` stays 7; the lead never on `AssetCommand` (K4); no derived value on
  `core.backup.AssetDto` (M1); `deadline_local_delivery` never exported, merged or named in `core/{backup,merge}`;
  `docs/versioning.md` and `versionName` untouched until a release. **#72 is not built** (§8).
- **Strings.** §6 verbatim, ratified before dispatch; each P79 literal has one home — a `const val … = "…"`, a template
  function whose line ends in the literal's closing quote, or (P79-62) the price sentence's concatenation form, which
  §9–§15's anchors depend on; reused strings through their shipped homes.
- **Tests.** JVM first; the device and gate rules are the briefs' common block; no UI harness. #79 crosses no new OS
  boundary, so **no foreign-UID proof**; the Android-contract layer is `ReminderPlatformDeviceProofTest`. Every RED is a
  real mutation run with `--no-build-cache --rerun-tasks`, its failing assertion quoted. One-line commits, the tombstone
  rule, no personal data (fictional fixtures: "Example Heater", "RMA-0001").

## 1. Audit additions (the audit is the base; these are the planner's and the review's findings)

(1) `scheduleIdOfTag` reads **every** standing tag (`DigestPolicy.kt:135, :168, :171`; `LocalReminderProvider.kt:227`), so
the schedule branch would cancel a standing warranty post. (2) `ContentHash.of` joins a fixed six-field list
(`ContentHash.kt:32-40`); a field appended for every subject re-posts every maintenance notification on upgrade.
(3) `QuickActionTarget` declares `scheduleId` on the sealed interface (`QuickActions.kt:26-43`). (4) 35 shipped
constructions call `DeliveryInput(subject, facts, delivery)`; `DigestDecision.rows` is schedule-typed
(`DigestPolicy.kt:198, :382`). (5) A stamp alone cannot tell a restart from a swipe. (6) The permission request "lives
here and nowhere else" (`ScheduleEditViewModel.kt:448`; 1.2 decision 23; SPEC12 §5.1) and `ManifestContractTest` bans it
under `ui/nav/**` (:229-241). (7) `EventEntryViewModel.saved` already emits the `EventId` (:227-228). (8) API responses
reuse `core.backup.AssetDto` (`ApiDtos.kt:3, :31-34`); derived values get their own response (`/health`, `/season`).
(9) `inService` is app-internal (`AssetHealthReadModel.kt:412`); core has `status == ACTIVE && !isRetired`
(`ScheduleStatus.kt:95`). (10) The in-memory asset delete cascades nothing (`InMemoryRepositories.kt:125`); Room's
cascades carry it (`DeleteAsset.kt:37-40`). (11) `Asset.applying` (`AssetCommands.kt:167`) serves `UpdateAsset` and
`SaveAssetSettings`. (12) `v1.md` names the report's table count and the asset sub-resource count, pinned by
`CommandShapesGoldenTest` and `ReferenceRoutesTest` (:527-548).

## 2. Behaviour (the briefs' contract)

```kotlin
// core/…/reminders/ReminderPort.kt — the one shared extension (#72 adds one member to each enum, nothing else)
sealed interface SubjectKey {
    data class Schedule(val scheduleId: ScheduleId) : SubjectKey
    data class Deadline(val kind: DeadlineKind, val subjectId: String) : SubjectKey
}
enum class DeadlineKind { WARRANTY_EXPIRY }
enum class DeadlineRepeat { ONCE }
// ReminderSubject gains `val repeat: DeadlineRepeat? = null` — null exactly when the key is a Schedule.

// core/…/model/ServiceCase.kt (Part B)
enum class CaseType { WARRANTY_SERVICE, REPAIR, OTHER_SERVICE }
enum class CaseCoverage { IN_WARRANTY, OUT_OF_WARRANTY, UNKNOWN }        // + PARTLY_COVERED iff R79-6 (a)
enum class CaseStatus { OPEN, SENT_OUT, AT_SERVICE_CENTER, RETURNED, CLOSED, CANCELLED }
data class ServiceCase(
    val id: ServiceCaseId, val assetId: AssetId, val title: String, val type: CaseType,
    val openedOn: String, val closedOn: String?, val provider: String, val contact: String,
    val caseRef: String, val coverage: CaseCoverage, val status: CaseStatus,
    val outboundTracking: String, val outboundCarrier: String, val returnTracking: String,
    val returnCarrier: String, val costMinor: Long?, val currency: String?, val notes: String,
    val incidentEventId: EventId?, val resolutionEventId: EventId?, val createdAt: Long, val updatedAt: Long,
)
data class ServiceCaseEntry(
    val id: ServiceCaseEntryId, val caseId: ServiceCaseId, val occurredOn: String, val occurredTime: String?,
    val tzId: String, val note: String, val status: CaseStatus?, val createdAt: Long,
)
```

**Part A (#79a).**
- **C1, status (AC 2; K7).** `enum class WarrantyStatus { IN_WARRANTY, OUT_OF_WARRANTY, NOT_RECORDED }` and a pure
  `warrantyStatusOf(expiresOn: String?, today: LocalDate)` in `core/…/warranty/`: null or unparseable → NOT_RECORDED;
  `today ≤ expiry` → IN_WARRANTY (the expiry day is in); else OUT. Never stored, never on `core.backup.AssetDto`;
  every reader passes `Today`; `expiredOn` and `warrantyExpired` go.
- **C2, the lead (AC 3, 4; K4; R79-11, R79-12).** `Asset.warrantyReminderLeadDays: Int? = null`, **not** on
  `AssetCommand`. `WarrantyReminderCommand(leadDays: Int?)`; problems `LeadNotPositive` (< 1; no upper bound, as the
  schedule lead; `LeadNegative`, < 0, under R79-12a's allow-0), `LeadWithoutDate`. Writers: `SetWarrantyReminder.run(assetId, cmd)` on the `SetHealthPolicy` shape
  (`NoSuchAsset`; `WarrantyReminderValidation`; an equal lead writes nothing; else the asset row alone, `updatedAt`
  moved); and `AssetSettingsCommand.warrantyReminder: WarrantyReminderCommand? = null`, validated against the
  command's own date, written in the same `uow.write` (null keeps the stored lead). **The date rule (R79-12b) lives in
  `Asset.applying`:** a result with `warrantyExpiresOn == null` carries a null lead, so `UpdateAsset` and
  `SaveAssetSettings` cannot diverge and `lead ≠ null ⇒ date ≠ null` holds in every store and archive.
- **C3, the port (AC 14; R79-13).** The fragment; `ProviderId` stays `{ LOCAL }`; a deadline is never `Completed` or `Parked`.
- **C4, bytes that must not move (K2).** `ContentHash.of` appends a separator and `repeat` **only when non-null**, so
  every schedule hash equals `<base>`'s. One tag codec in `DigestPolicy.kt`: `itemTag` writes the shipped
  `<scheduleId>|<hash16>` byte-identically and `<KIND>:<subjectId>|<hash16>` for a deadline; `keyOfTag(tag): SubjectKey?`
  is its only reader. Replacement and nonce clearing act on `Schedule` keys only; the kept set covers both families;
  no `as SubjectKey.Schedule` remains.
- **C5, deadline subjects (AC 3, 4; R79-15).** `BuildDeadlineSubjects(assets).forProvider(provider, today)` in
  `core/…/reminders/`, every provider, sorted by asset id. A subject exists iff the asset is in service —
  `status == AssetStatus.ACTIVE && !isRetired`, the `targetInService` rule, stated in the builder — the date parses,
  the lead is non-null and `today ≤ expiry`: `Deadline(WARRANTY_EXPIRY, assetId.value)`, title "Warranty" (the reused
  word's core home), body "", `dueOn` = expiry, `leadDays` = lead, `Active`, `rule` null, `repeat` ONCE. Otherwise
  **absent** (the withdrawal). It reads neither season nor break: an out-of-season asset still warns (the rule). Its
  KDoc avoids the words `ReminderPortContractTest` bans in `core/…/reminders` (:173-177).
- **C6, composition (AC 3; M4).** `AppGraph`'s `subjectsFor` = schedule subjects then deadline subjects; `ReminderRuns`,
  the six receivers, the alarm and the backstop are unchanged, so recovery needs no receiver and no manifest change.
  `DeadlineDeliveryFacts(assets, today)` → `DeadlineFacts(ownerName, today)`, null if gone. Proven through the real
  graph on device (row 17).
- **C7, LOCAL delivery (AC 3; K1, K3; R79-14).** A new sealed `DigestInput` parents the shipped `DeliveryInput`
  (unchanged, so its 35 constructions compile untouched) and `DeadlineInput(subject, facts: DeadlineFacts?, row:
  DeadlineLocalDelivery?)`; `decide` routes by type; `DigestDecision` keeps its schedule `rows` and gains separate
  `deadlineRows`. The deadline branch (`when` over `repeat`, exhaustive): facts null → forget the row; window =
  `[dueOn − leadDays, dueOn]`; before it → cancel a standing tag of the key, forget the row; inside: tag standing →
  unchanged; the row stamps this hash (and, under R79-14c, this boot) → nothing; channel muted → nothing, no stamp; else
  post and stamp; a moved hash cancels the old tag first. The post: channel id **`warranty_reminders`** (R79-14b;
  permanent once created), title `<asset> — Warranty`, body P79-10, `statusWord` P79-11, actions `[Open]`, the clock
  icon and DUE's accent (`iconFor`/`accentFor`'s shipped `else` branches; R79-17). Never counted in the summary. The
  provider routes inputs through one function (no cast), writes deadline rows, then **deletes every deadline row whose
  key is not an Active subject** (absence forgets); `silence` cancels as today and deletes every deadline row; the
  report counts deadline items as it counts schedules.
- **C8, "Open" (inv. 54, 57).** `QuickActionTarget.scheduleId` moves to the four shipped members, grouped under a
  `ScheduleActionTarget` sub-interface (receivers unchanged); `OpenAsset(assetId)`: an activity `PendingIntent`,
  `FLAG_IMMUTABLE`, `servicetag://asset/<id>` (already routed), no nonce, no write. `QuickActions.forDeadline` = `[Open]`.
- **C9, permission and health (R79-16).** `WARRANTY_NOTIFICATION_RATIONALE` = P79-12 beside the shipped constant; the
  shipped rationale, `health()`, its findings and the two-channel availability check are unchanged.
- **C10, the Warranty section (AC 2; R79-17).** `AssetDetailState.warranty = WarrantyFacts(status, expiresOn, leadDays,
  notes, inService)` from the asset already read and `Today`. `WarrantySection` right after `DetailsSection`, on every
  asset: `SectionHeader` "Warranty"; with a date, badge P79-1/P79-2 and line P79-4/P79-5 (`d MMM yyyy`); without,
  P79-3; P79-6/P79-7 only while IN WARRANTY, with a lead, **in service** (m9); "Warranty notes" when non-blank.
  `DetailsSection` loses both warranty rows.
- **C11, the editor's lead (AC 3, 4; R79-12, R79-16).** Under "Expires on", a number field labelled with the reused
  `REMIND_ME_N_DAYS_EARLY`, supporting text P79-8; blank → `WarrantyReminderCommand(null)`; not a whole number ≥ 1 →
  "Enter the number of days."; a lead with a blank date → P79-9; every save passes the part. After a successful save —
  after #67's copies and the #78 question, before `saved` — when the lead went from null to non-null and the permission
  is not granted: P79-12 with "OK"/"Not now", at most once per editor view-model instance, then `request()`; the asset
  is already written (D-22). The permission is taken from `graph` inside `ui/asset` (as `ScheduleEditViewModel.kt:475`),
  never named under `ui/nav`. A saved date or lead that differs from the loaded one runs the shipped
  `ReminderReconcile` once; an unchanged one never does.
- **C12, API and MCP for Part A (AC 12; M1; R79-18).** `GET /v1/assets/{id}/warranty` → `{warranty: {status, expiresOn,
  leadDays}}` (the `/health` precedent, derived with `Today`); `POST /v1/assets/{id}/warranty-reminder {leadDays}` →
  `{asset, warranty}` (the `season-mode` precedent); family `warranty_reminder_validation` in `ValidationRefusals.kt` (no
  `else`); `command-shapes.json` gains the lowerCamel key `warrantyReminder`; `AssetCommand`'s shape unchanged. The lead rides on
  `core.backup.AssetDto` (canonical); the status never does. MCP `get_warranty`, `set_warranty_reminder`, each refusing a
  phone below schema 11 with nothing sent (a per-tool minimum beside `_MIN_SCHEMA_VERSION = 8`); `ASSET_KEYS` never
  gains the lead; 56 → 58.

**Part B (#79b).**
- **C13, the aggregate (AC 6–9; R79-1).** The fragment; empty text is `""`. `ServiceCaseRepository` (upsert, get,
  forAsset, all, deleteAll, observeForAsset); `ServiceCaseEntryRepository` (insert aborting on a duplicate id, forCase,
  all, deleteAll, observeForCase) — **no entry update or delete exists anywhere**.
- **C14, use cases (AC 6–9, 15; R79-3, R79-5, R79-7–9).** `OpenServiceCase.run(assetId, cmd, incidentEventId: EventId?)`,
  `UpdateServiceCase.run(caseId, cmd)`, `AddServiceCaseEntry.run(caseId, entry)`: one `uow.write` each, every problem
  collected before any write into `ServiceCaseValidation`. `ServiceCaseCommand` = the header's editable fields plus
  `resolutionEventId` **without a default**. Problems: `TitleRequired`, `BadDate`, `OpenedAfterToday`, `NegativeCost`,
  `CostWithoutCurrency`, `BadCurrency`, `ResolutionInvalid` (not a MAINTENANCE/REPLACEMENT of this asset; a stored
  dangling id sent back unchanged passes). Open: `NoSuchAsset`; the Incident null — R79-3 governs the UI only; the use
  case and API accept an Incident-less case — or an INCIDENT of this asset with `scheduleId == null` (the
  `CurrentIncident.kt:32` rule; else `IncidentInvalid`); OPEN, no `closedOn`. Update never moves `assetId`,
  `incidentEventId`, `status`, `closedOn`, `createdAt`; an equal header writes nothing. `CaseEntryCommand(occurredOn,
  occurredTime?, tzId, note, status?)`; problems `EntryEmpty`, `BadDate`, `BadTime`, `EntryAfterToday`. **A note-only
  entry writes the entry alone** (the header, its `updatedAt` included, is untouched); a status entry also sets the
  header's status and `updatedAt`, CLOSED/CANCELLED setting `closedOn` to its date and any other clearing it, in one write.
- **C15, coverage suggestion (R79-6).** Pure `suggestCoverage(expiresOn, incidentOn, openedOn)`: basis = the Incident's
  date, else `openedOn`; no expiry → UNKNOWN; basis ≤ expiry → IN; else OUT. A form default only (AC 7).
- **C16, the write gate (AC 5, 11, 16).** `CrossConceptWriteTest` gains `OpenServiceCase` → {service_case},
  `UpdateServiceCase` → {service_case}, `AddServiceCaseEntry, a note` → {service_case_entry}, `AddServiceCaseEntry, a
  status` → {service_case_entry, service_case}; A1 adds `SetWarrantyReminder` → {asset}; the condition writers unchanged.

**Data (A1 for the lead, B1 for the cases).**
- **C17, schema.** A1: `MIGRATION_10_11` = `ALTER TABLE asset ADD COLUMN warranty_reminder_lead_days INTEGER` (no default,
  no backfill) + `deadline_local_delivery(kind, subject_id, announced_hash, announced_boot, updated_at, PRIMARY KEY(kind,
  subject_id))`, no FK (`announced_boot` only under R79-14c); port `DeadlineLocalDeliveryRepository` (get, upsert, delete,
  all, deleteAll). B1: `MIGRATION_11_12` = `service_case` (`asset_id` FK CASCADE, indexed) + `service_case_entry`
  (`case_id` FK CASCADE, indexed). Each: in `AppGraph`'s list and `MigrationTestSupport`'s chain, `AppDatabase` version,
  `N.json`, `SCHEMA_VERSION`, `VersionAgreementTest`, entities, DAOs, mappers, Room repositories, the in-memory doubles
  (B1's double gains the asset-delete cascade, new behaviour; the Room case is the proof) and `FakeGraph`. No timestamp moved.
- **C18, format.** A1: `FORMAT_VERSION = 11`, `FIRST_LEAD_FORMAT = 11`, `AssetDto.warrantyReminderLeadDays: Int? = null`
  (written as `null`); a `< 11` archive carrying a non-null lead is `BackupCorrupt`. B1: `FORMAT_VERSION = 12`,
  `FIRST_CASE_FORMAT = 12`, `BackupData.serviceCases`, `serviceCaseEntries` (default empty); a `< 12` archive carrying a
  case or entry is `BackupCorrupt` (the `FIRST_ROLE_FORMAT` pattern, `BackupCodec.kt:258-265`); a case's asset and an
  entry's case resolve in the archive; the event ids are soft. `BackupContentCheck` refuses what a command refuses, no
  rule relative to today: C2's and C14's shape problems, `closedOn` non-null iff CLOSED/CANCELLED, an empty entry.
  Replace, export and `StoreIsEmpty` carry what each brief adds.
- **C19, merge (AC 12; M3; R79-11b).** A1: assets in a format-11+ archive compare the lead plainly; below 11, R79-11b.
  B1: `MergeTable` appends `SERVICE_CASES, CASE_ENTRIES` after `CATEGORIES` (no ordinal moves); `MergeWrites`/`MergeSnapshot`
  gain both; apply writes cases after assets and events, entries after cases; **core `MergeReport` (`MergePlan.kt:385-408`)
  gains `serviceCases` and `caseEntries` tallies, filled in `report()` (:457-477), and the API's `MergeReportResponse` and its mapper (`ApiDtos.kt:142-199`)
  the same two keys** — the report is seventeen tables. A case: IDENTICAL, CONFLICT `CONTENT_DIFFERS`, CONFLICT
  `OWNER_NOT_AVAILABLE`, INSERT — no UPDATE; an entry: the conditions precedent (`MergePlanner.kt:775-815`), owned by its case.

**UI for Part B.**
- **C20, Service cases on the detail (AC 6, 11; R79-3).** `AssetDetailState.cases` from one `observeForAsset`.
  `ServiceCasesSection` after `ConditionSection`: P79-15; none → P79-16; P79-17/P79-18 only when at least one is open
  (hidden at zero); rows open first, then closed as history, each by `openedOn` desc then id: title, quiet line
  `<status word> · <coverage word>`, tap → `Route.ServiceCase(id)`. P79-19 **in service only**: `currentIncident`
  non-null → `Route.ServiceCaseEdit(assetId, incidentId = it)`; null → `Route.EventEntry(…, kind = "INCIDENT",
  thenServiceCase = true)`, whose save (after any Workflow B answer) makes the host replace it with
  `ServiceCaseEdit(assetId, incidentId = saved id)`. `thenServiceCase: Boolean = false`; `EventEntryScreen` gains a
  defaulted `onSaved: ((String) -> Unit)? = null`, used instead of `onDone` when set.
- **C21, the case editor (AC 6–8).** `Route.ServiceCaseEdit(assetId, caseId: String? = null, incidentId: String? = null)`;
  title P79-19 or P79-21. New: title = the Incident's, `openedOn` today, coverage = C15 with P79-36, type Warranty service
  when that is IN else Repair, currency = the asset's, else blank. Fields: Title, Type, Opened on, Service provider,
  Phone or contact, Case or RMA number, Coverage, Outbound tracking + Carrier, Return tracking + Carrier, Cost +
  Currency, Notes; "Save case". Cost parsed by `Money.parse` as the price is, else P79-62 (built as
  `"Enter a cost like " + example`, `AssetViewModels.kt:2265`'s form); problems on their fields; an unexpected failure
  P79-61 (logged). `saving` set before the first suspension; Cancel and back write nothing; an edit sends the loaded
  `resolutionEventId`.
- **C22, the case screen (AC 9, 10, 15; R79-2, R79-10).** `Route.ServiceCase(id)`, title P79-21, "Edit". Facts as
  `FieldLabel` + value, Status (P79-37), Closed on (P79-50, when set), Cost via `Money.format`. Link rows Incident (P79-57)
  and Repair record (P79-58): title and date, tap → `Route.EventDetail`; dangling → S24. No repair → P79-59 when a
  candidate exists: a dialog titled P79-59 listing this asset's MAINTENANCE and REPLACEMENT events newest first, and
  "Cancel"; a pick — or "Remove", offered on the Repair record row only — writes the header through
  `UpdateServiceCase` with every other field as loaded and asks nothing. Documents (A1): `AttachmentsSection(owner =
  OfEvent(…))` under each link row. Timeline (P79-53) by `(occurredOn, occurredTime nulls first, createdAt, id)`, no edit
  or delete. P79-54 opens a sheet titled P79-54: Date (today), Time, P79-55, Status chips (none = no change), P79-56
  (enabled iff a note or a status), "Cancel"; refusals "Enter a date as YYYY-MM-DD", "Enter a time as HH:MM", S25; a
  failure "Could not save this entry.". Closing asks nothing.
- **C23, the Incident's detail (AC 15; R79-4).** P79-20 on a non-completion INCIDENT of an **in-service** asset (the one
  rule with C20); `onStartServiceCase` defaults to a no-op; the host pushes `ServiceCaseEdit(assetId, incidentId = id)`.
  `EventDetailViewModel` reads `fun interface CaseLinks { suspend fun linking(eventId: EventId): Boolean }` (read-only,
  built in `AppGraph`); when true the delete confirm adds P79-60 after "Its readings go with it.".

**API, MCP and docs for Part B.**
- **C24, API and MCP (AC 12; R79-18).** Five routes (§5); `ServiceCaseDto`, `ServiceCaseEntryDto`; family
  `service_case_validation`; 404 for a missing asset or case; `/v1/status` counts gain `serviceCases`,
  `serviceCaseEntries`; `command-shapes.json` gains the keys `serviceCase`, `caseEntry`. MCP `list_service_cases`,
  `get_service_case`, `open_service_case`, `update_service_case` (overlay-PATCH as `update_asset`; `clear_fields`; never
  sends status or `closedOn`), `add_case_entry`, each at a schema-12 minimum; 58 → 63.
- **C25, docs.** `v1.md` (A4: the two warranty rows, range 1–11, "11 since #79 (warranty reminders)", the date rule, the
  lead's R79-11b line; B3: the five rows and the 405 row's three path shapes, the case PATCH in the full-replace
  paragraph — status and `closedOn` move only through an entry —, "What has no endpoint", range 1–12, "12 since #79
  (service cases)", seventeen tables). Sub-resource count: sixteen → eighteen (A4) → nineteen (B3).
  `release-proofs.md` per §5. Amendments headed `**Amendment (#79, <the ratification date>)**`, ratified text not
  rewritten, P79 ids plan-local: SPEC12 (A2) §2.5, §5.1, inv. 44 ("from schedule state and the assets' warranty facts
  alone"), inv. 53 (R79-14b), §9.1 quoting P79-10…14; SPEC14 (A4) §10.3, §10.4, §10.7 for Part A's words, (B3) §10.3,
  §10.7 for Part B's.
- **C26, #72 and #77 (AC 13, 14).** #72 later adds `LOAN_DUE_BACK`, `UNTIL_CLEARED`, its builder, delivery columns,
  actions and channel, and the `Completed` pin change. #77 carries the lead, cases and entries with their asset and takes
  a transferred asset out of C5.

## 3. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| **A1** 1. status (C1, K7) | core `WarrantyStatusTest` · `theExpiryDayIsInWarrantyAndTheNextDayIsNot`; `noDateIsNotRecorded`; `anUnparseableDateIsNotRecorded` | `isBefore` for `!isAfter`; read a `Clock` |
| 2. the lead's writer (C2) | core `SetWarrantyReminderTest` · `aLeadIsWrittenOnTheAssetRowAlone`; `anEqualLeadWritesNothing`; `aLeadWithoutAWarrantyDateIsRefused`; `zeroOrLessIsRefusedAndAnyLargerLeadIsAccepted` (under allow-0: 0 accepted, −1 refused); `nullTurnsItOff`; `CrossConceptWriteTest` gains `"SetWarrantyReminder" to setOf("asset")` | drop the date check; accept 0; touch another table |
| 3. the editor's one save (C2) | `SaveAssetSettingsTest` · `aRefusedLeadWritesNoAssetSeasonBreakOrPolicy`; `aNullPartKeepsTheStoredLead`; `aBlankDateWithALeadIsRefused` | the lead in its own write; clear on null |
| 4. full replace (C2, K4) | `AssetUseCasesTest` · `anUpdateKeepsTheLeadWhileTheDateStays`; `anUpdateThatClearsTheDateClearsTheLead`; `assetCommandHasNoLeadProperty` (reflection) | clear on every update; the rule in `UpdateAsset` only |
| 5. schema 11 (C17, AC 1) | `Migration10To11Test` · `everyAssetRowSurvivesFieldForFieldWithANullLead` (warranty date and notes included); `theDeadlineTableArrivesEmptyWithNoForeignKey`; `theMigratedSchemaEqualsAFreshVersion11` | `DEFAULT 0`; drop the chain entry |
| 6. format 11 (C18) | `BackupFormat11Test` (new) · `aLeadRoundTrips`; `anUnsetLeadIsWrittenAsNull`; `aFormat10ArchiveWithANonNullLeadIsRefused`; `…WithAnExplicitNullRestores` | skip the ≤ 10 check |
| 7. content check (C18) | `BackupContentCheckTest` · `aLeadWithoutADate`; `aLeadOfZero` (under allow-0: `aNegativeLead`) | drop each |
| 8. the lead in merge (C19, R79-11b) | `MergePlannerWarrantyLeadTest` (new) · `aFormat10ArchiveIsIdenticalToAnAssetWhoseLeadWasSetThroughSetWarrantyReminder`; `…WithoutALeadComparesAsBefore` (a rename still CONFLICT); `aFormat11ArchiveComparesTheLead` | compare with the lead; always ignore `updatedAt` |
| 9. device-local (C17) | app `DeadlineDeliveryRoomTest` · `aRowForAnUnknownAssetIsAcceptedAndOutlivesItsAsset`; shipped `LocalReminderProviderTest.noDeliveryStateReachesAnExportOrAMerge` widened to `deadline_local_delivery\|DeadlineLocalDelivery` | export the table |
| 10. **A1 retargets** (M2) — the version moves to 11; `MergeTable` stays fifteen | `Format7RestoreContractTest.reExportIsFormat10AndPlansIdentical` (:157-164; format 11, still IDENTICAL); `VersionAgreementTest.statusEchoesTheVersionsTheBuildCarries` (:138-139) and `theSchemaIsTenAndTheFormatIsTen` renamed for 11; `ExportBackupSetTest:31`; `Format7ImportIdentityTest:232`; `BackupCodecTest.aFormat10ArchiveWithoutRolesRestoresAsBefore` (:1136, default encoder); `MaintenanceRoutesTest` :1148, :1373-1374 and `importMergeReadsFormat10AndReportsFifteenTables` (format only); `BackupCodecTest.theFormatIsTenAndTheLegacyBoundaryStaysSeven` (:1033-1036); `BackupFormat6Test:351`; `BackupFormat9Test.theFormatMovedAndTheLegacyBoundaryStaysSeven` (:66-70); `BackupFormat8Test.aFormatPastThisBuildsIsRefusedBeforeAnyRow` (:273-280: 11/10 → 12/11) and :198-203 (the `AssetDto` field list: the lead is appended last, so `takeLast(5)` becomes the five before it and the size 29 → 30); `StageABundleConformanceTest:100-106` (the format-5 bundle emits no lead: a `FORMAT_11_ASSET_FIELDS` set is subtracted; no bundle-tool change) — each keeps every other assertion | — |
| **A2** 11. port pins (C3, AC 14) | `ReminderPortContractTest` · `theDeadlineKindsAreExactlyWarrantyExpiry`; `theRepeatFactsAreExactlyOnce`; `aScheduleSubjectHasNoRepeatAndADeadlineSubjectHasOne`; `nothingInCoreEverBuildsACompletedSubjectOnItsOwn` unchanged | add `LOAN_DUE_BACK` |
| 12. schedule hash (C4, K2) | core `ContentHashTest` (new) · `aScheduleSubjectsHashIsTheBaseValue` (hex computed at `<base>`); `theRepeatFactMovesADeadlinesHash` | the absent marker for null |
| 13. tag codec (C4, K2) | `DigestPolicyTest` · `aScheduleTagIsByteIdenticalToTheBaseForm`; `everyKeyRoundTripsThroughItsTag`; `aDeadlineTagNeverReadsAsASchedule` | prefix schedule tags |
| 14. subjects (C5, R79-15) | core `BuildDeadlineSubjectsTest` · every C5 field through the expiry day; absent with no date, no lead, archived, retired, the day after; `anOutOfSeasonAssetStillWarns`; sorted; `itReadsTodayNeverAClock` | include the day after; ignore retirement |
| 15. mixed sweep (C7, K1) | `LocalReminderProviderTest` · `aMixedListReconcilesAndEachFamilyKeepsItsOwnDelivery` | restore one `(key as SubjectKey.Schedule)` (quote the `ClassCastException`) |
| 16. once (C7, R79-14a) | `DigestPolicyTest` · `aWarrantyIsPostedOnceOnEnteringItsWindow` (lead 30: day −31 none, −30 one, −29 none); `aStandingOrSwipedWarningIsNotReposted`; `aMovedDateOrLeadReplacesIt`; `theRatifiedTitleBodyWordChannelAndOneOpenAction` | re-post when not standing |
| 17. the composition (C6, M4) | device `ReminderPlatformDeviceProofTest` · `theGraphsSweepPostsAWarrantyInItsWindow` (seed an in-service asset inside its window, run `app.graph.reminderRuns.reconcileAll()`, read a `WARRANTY_EXPIRY:<id>\|…` tag back) | drop the deadline builder from `subjectsFor` |
| 18. never maintenance (C7, K3) | `DigestPolicyTest` · `aWarrantyNeverCountsInTheMaintenanceSummary`; `theScheduleBranchNeverCancelsAStandingWarranty` | count it; kept ids from schedules only |
| 19. withdraw, forget, silence (AC 4) | `LocalReminderProviderTest` · `anAbsentWarrantyIsTakenDownAndForgotten` (date, lead cleared; archived; expired); `reEnablingAnnouncesAgain`; `silenceTakesWarningsDownAndForgetsThem` | keep the row |
| 20. muted, restart (R79-14b/c) | · `aMutedWarrantyChannelPostsAndStampsNothing`; `aWarningARestartTookDownIsPostedOnceMore`; `aSwipeInTheSameBootIsFinal`; `anUnreadableBootCountIsTheSameBoot` | stamp when muted; ignore the boot |
| 21. device-local only (AC 5) | · `aWarrantyWarningWritesOneDeviceLocalRowAndNothingElse`; shipped `theSnoozeAndTheNonceWriteOneDeviceLocalRowAndNothingElse` unchanged | upsert a schedule row |
| 22. the action (C8) | `QuickActionsTest` · `aWarrantyOffersOpenOnlyAimedAtItsAssetWithNoNonce` | issue a nonce |
| 23. Android contract (C7, C8) | device `ReminderPlatformDeviceProofTest` · `aWarrantyPostIsReadableBackByItsTagWithOneImmutableOpenAction`; `theBootCountIsReadable` (R79-14c) | a mutable intent |
| 24. health kept (C9) | `ReminderHealthCheckTest`, `ReminderHealthViewModelTest`, `ScheduleDetailViewModelTest` unchanged and green | — |
| 25. **A2 retargets** (M2) | `ReminderPortContractTest.theReminderPortNamesNoAndroidTypeAndNoDeliveryMechanism` (:185-194: `SubjectKey`'s members `["Schedule"]` → `["Schedule", "Deadline"]`; its banned-word checks kept); `NotificationChannelsTest.exactlyTwoChannelsWithTheirRatifiedShape` → three (the shipped two asserted as before, plus `warranty_reminders` P79-13/14 at `IMPORTANCE_DEFAULT`); `ReminderPlatformDeviceProofTest.theTwoChannelsExistAtTheirImportancesAfterAFirstLaunch` → three. Both channel cases unchanged if R79-14b = `maintenance_due` | omit the third |
| **A3** 26. detail state (C10, K7) | app `AssetWarrantyStateTest` (new, FakeGraph) · `theStatusFollowsTodayNotTheClock`; `theReminderLineOnlyWhileInWarrantyWithALeadAndInService` (and n = 1); `theDetailsRowsNoLongerCarryTheWarranty` | the clock; draw on a retired asset |
| 27. editor lead (C11) | app `AssetEditWarrantyReminderTest` (new) · `blankIsOff`; `aLeadIsSavedThroughTheFifthPart`; `aLeadWithABlankDateSaysP79_9`; `zeroOrABadNumberSaysEnterTheNumberOfDays` (under allow-0: 0 saved and P79-63 drawn, −1 refused); `aRenameKeepsTheLead`; `theRationaleThenRequestRunAfterTheWriteWhenALeadIsFirstSet` (a fake `NotificationPermission`: never when granted, once per instance); `aChangedDateOrLeadReconcilesOnceAndAnUnchangedOneNever` | blank → a number; request before the write |
| 28. on device (C10, C11) | device `AssetWarrantySectionTest` (new) · `inOutAndNotRecorded`; `theExpiredSuffixIsGone`; device `AssetEditorWarrantyReminderTest` (new) · `theLeadFieldHelperAndRefusal`; `theRationaleTextAndNotNowWritesNothingMore` (no system dialog) | keep the suffix |
| 29. reads and regressions | `ReadPathsWriteNothingTest` · the detail's and editor's loads; device `AssetDetailConditionHealthSeasonTest`, `AssetDetailKeyDocumentsTest`, `AssetEditorKeyDocumentsTest`, `AssetEditorSeasonAndHealthTest`; `ManifestContractTest` green | upsert on load |
| **A4** 30. warranty API (C12, M1) | app `WarrantyRoutesTest` (new) · `getWarrantyDerivesTheStatusWithToday`; `postWarrantyReminderAnswersAssetAndWarranty`; `theFamilysCodeAndField`; `patchAssetWithoutTheLeadKeepsIt`; `patchAssetClearingTheDateClearsTheLead`; `theBackupAssetDtoCarriesNoStatus` (reflection over `core.backup.AssetDto`); `ApiReadsWriteNothingTest` · the GET | a status on `AssetDto` |
| 31. shapes and MCP (C12) | `CommandShapesGoldenTest.everyRequestDtoMatchesCommandShapesJson`'s literal key list (:39-41) and `command-shapes.json` gain `warrantyReminder`, `asset` unchanged; pytest · `test_the_warranty_tools_refuse_a_schema_10_phone_with_nothing_sent`; `test_update_asset_never_sends_the_lead` | `ASSET_KEYS` gains the lead |
| 32. **A4 retargets** (M2) | `CommandShapesGoldenTest.theContractDocumentNamesFormat10AndFifteenTables` (:80-102: "1–11", "11 since #79 (warranty reminders)", its banned list gains "1–10"; fifteen tables kept); `ReferenceRoutesTest.theApiDocumentAgreesWithTheRouter` (:527-548: range 1–11 with "1–10" banned, sub-resources sixteen → eighteen); the MCP format docstring (`server.py:1039`); pytest's 56 pins (`test_argument_guard.py:51, :203-204, :256-258, :341-342`; `test_tools.py:95-99`, whose `EXPECTED_TOOLS` gains the two names; `test_reference_tools.py:56-57`) → 58 | — |
| **B1** 33. open (C14, R79-3) | core `ServiceCaseUseCasesTest` · `openWritesOneHeaderOpenWithNoClosedOn`; `anIncidentLessCaseIsAccepted`; `theIncidentMustBeANonCompletionIncidentOfThisAsset` | accept any kind; accept a completion |
| 34. header rules (C14, AC 8) | · `titleCostAndCurrencyProblemsAreReportedTogether` (`commits == 0`); `openedAfterTodayIsRefused`; `aZeroCostIsKeptAndANullCostIsNone` | fail fast; null → 0 |
| 35. header update (C14) | · `anUpdateNeverMovesStatusClosedOnIncidentOrAsset`; `theResolutionMustBeAMaintenanceOrReplacementOfThisAsset`; `anUnchangedDanglingResolutionPasses`; `anEqualHeaderWritesNothing` | accept an INCIDENT |
| 36. timeline (C14, AC 9) | · `aNoteOnlyEntryLeavesTheHeaderUntouched` (`updatedAt` equal); `aStatusEntryMovesTheHeader`; `closedOrCancelledSetsClosedOnToTheEntryDate`; `anyOtherStatusClearsClosedOn`; `anEmptyEntryIsRefused`; `anEntryAfterTodayIsRefused`; `aThrowingHeaderWriteLeavesNoEntry` | stamp the header on a note |
| 37. append-only (C13, R79-8) | · `theEntryPortHasNoUpdateOrDelete` (reflection); `aDuplicateEntryIdAborts` (fake and Room) | add an upsert |
| 38. suggestion (C15) | core `CoverageSuggestionTest` · a table: the Incident date on/after expiry, the `openedOn` fallback, no date | use today |
| 39. write gate (C16) | `CrossConceptWriteTest` (amended): the four case entries (the note and the status entry separately); the four-writer message unchanged | write a condition at close |
| 40. schema 12 (C17) | `Migration11To12Test` · `everyRowSurvives`; `theCaseTablesArriveEmpty`; `deletingAnAssetCascadesItsCasesAndTheirEntries`; `theMigratedSchemaEqualsAFreshVersion12` | drop a CASCADE |
| 41. format 12 (C18) | `BackupFormat12Test` (new) · `casesAndEntriesRoundTrip`; a format-11 archive with a case / an entry refused; `…WithEmptyListsRestores`; `aCaseOrEntryWhoseOwnerIsNotInTheArchiveIsCorrupt`; `aDanglingEventLinkIsAccepted`; `anUnknownEnumNameIsRefused`; `BackupContentCheckTest` · blank title, negative cost, cost without currency, `closedOn` iff terminal, an empty entry | check soft links; drop each |
| 42. merge (C19, AC 12, M3) | `MergePlannerServiceCaseTest` (new) · the four case verdicts; entry IDENTICAL/CONFLICT/INSERT; `anEntryWhoseCaseIsAcceptedInThisPlanInserts`; `…NeitherHereNorAcceptedIsOwnerNotAvailable`; `aNoteAddedElsewhereMergesAsEntryInsertWithTheHeaderIdentical`; `ImportBackupMergeTest` · `casesThenEntriesLandAfterAssetsAndEvents`; `MaintenanceRoutesTest` · `aCaseAndAnEntryAreTalliedOnTheWire` | plan entries first; omit a tally |
| 43. replace, export, empty | `ImportBackupReplaceTest`, `ExportBackupSetTest`, `StoreIsEmptyTest` · both tables | forget the entries |
| 44. **B1 retargets** (M2) — the version moves to 12; `MergeTable` grows to seventeen | every row-10 case again, 11 → 12; the literal fifteen-member `MergeTable` lists `MergePlannerMaintenanceTest` :246-267 and `MergePlannerSeasonHealthTest` :123-138 → seventeen; `MergePlannerReferenceTest.the report carries a tally per table in MergeTable order` (:341-368) with the two tallies; `MaintenanceRoutesTest.importMergeReadsFormat10AndReportsFifteenTables` → seventeen tallies and `theMergeReportWireMirrorCarriesEveryTallyInTableOrder` (:1183-1195, `serviceCases`, `caseEntries` after `categories`); `BackupFormat6Test:280` and `BackupFormat7Test:208-211` (`BackupData` 15 → 17 tables); `BackupFormat9Test:96` (`assetCategories` no longer last: the two case lists are); `StageABundleConformanceTest:94-98` (a `FORMAT_12_TABLES` set subtracted); `BackupFormat8Test:273-280` again (13/12) | — |
| **B2** 45. cases on the detail (C20) | app `AssetCasesStateTest` (new) · `noCasesSaysNoServiceCasesYet`; `theOpenCountIsHiddenAtZeroAndSaysOneOrMany`; `openFirstThenClosedAsHistory`; `newServiceCaseOnlyInService` | hide closed cases |
| 46. entry and routes (C20) | `RouteTest` · `theServiceCaseRoutesRoundTrip`; `anEventEntryThenServiceCaseRoundTripsAndAnOldOneDecodes`; device `AssetServiceCasesTest` (new) · `newServiceCaseWithACurrentIncidentOpensTheEditorOnIt`; `withoutOneItOpensAnIncidentEntryFirst` | no Incident first |
| 47. case editor (C21) | app `ServiceCaseEditViewModelTest` (new) · `aNewCaseIsPrefilledFromItsIncident` (blank currency when the asset has none); `refusalsLandOnTheirFields`; `aDoubleTapWritesOnce`; `cancelWritesNothing`; `anEditKeepsStatusClosedOnAndLinks`; `anUnexpectedFailureSaysP79_61` | drop `saving` |
| 48. case screen (C22, AC 9, 10, 15) | app `ServiceCaseViewModelTest` (new) · `theTimelineIsChronological`; `addUpdateWritesOneEntry`; `saveUpdateIsEnabledOnlyWithANoteOrAStatus`; `aBadTimeSaysEnterATimeAsHHMM`; `closingShowsClosedOnAndWritesNoConditionOrEvent`; `thePickerListsOnlyThisAssetsMaintenanceAndReplacement`; `linkingWritesTheHeaderAloneAndAsksNothing`; `removeOnlyOnTheRepairRow`; `aDanglingLinkSaysS24` | list INCIDENTs; call `offersAfter` |
| 49. screens on device | device `ServiceCaseScreensTest` (new) · `theEditorsFieldsChipsAndRefusals`; `addUpdateSheetAndTimeline`; `theLinkedEventsDocumentsAreDrawnThroughTheirOwnSection` (one attachment id) | — |
| 50. Incident detail (C23, R79-4) | app `EventDetailCaseLinksTest` (new) · `startServiceCaseOnlyOnANonCompletionIncidentOfAnInServiceAsset`; `theDeleteConfirmAddsP79_60WhenACaseLinksIt` | offer it out of service |
| 51. silent, reads, regressions (AC 11, 16) | shipped `OperationalOfferTest`, `OffersTest` unchanged; `ReadPathsWriteNothingTest` · the section's, screen's and editor's loads; device `AssetDetailConditionHealthSeasonTest`, `AssetDetailKeyDocumentsTest`, `JournalDeviceProofTest`, `NavigationSmokeTest` | upsert on load |
| **B3** 52. case API (C24) | app `ServiceCaseRoutesTest` (new) · each route's status and body; the family's codes and fields; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` extended; the status counts; `ApiReadsWriteNothingTest` · the two GETs | route a delete |
| 53. shapes and MCP (C24) | the golden key list (:39-41) and `command-shapes.json` gain `serviceCase`, `caseEntry`; pytest · `test_the_case_tools_refuse_a_schema_11_phone_with_nothing_sent`; `test_update_service_case_overlays_and_never_sends_status` | a global minimum only |
| 54. **B3 retargets** (M2) | `CommandShapesGoldenTest` again ("1–12", "12 since #79 (service cases)", "seventeen tables"; banned list gains "1–11" and "fifteen tables"); `ReferenceRoutesTest` again (1–12, seventeen tables, nineteen sub-resources); the MCP docstrings `server.py:1039` (1–12) and `:1053` (seventeen tables); every pytest count pin of row 32 and `EXPECTED_TOOLS` 58 → 63; `ReferenceRoutesTest.statusCarriesTheReferenceCountBesideEveryShippedKey` (:457-477, the key set gains `serviceCases`, `serviceCaseEntries`); `test_command_shapes.py:53-57` (the vendored set gains `serviceCase` when `update_service_case` vendors its keys) | — |

## 4. Lanes, files and collisions

**Split (recommended).** #79a on `issue-79a`: A1 → A2 → A3 → A4; `<base>` of A1 = master at this plan's ratified commit,
each next = the previous accepted tip. #79b on `issue-79b`, branched from master after #79a merges (and after #72 if the
owner orders #72 between — #72 then takes schema/format 12 and #79b renumbers mechanically to 13, every 12 in B1–B3,
rows 40, 41, 44 and the gate reading 13, with no re-review): B1 → B2 → B3; `<base>` of B1 = that branch point, each
next = the previous accepted tip.
**One issue.** One branch `issue-79` from the ratified commit: A1 (+B1's contracts at 11) → A2 → A3 → B2 → A4 (+B3's);
each `<base>` = the previous accepted tip; B1 and B3 do not exist as briefs.
**Files (indicative; each brief's untouched diff fences the rest).** A1: `core/…/warranty/*`, the lead's use cases,
`backup/*`, `merge/MergePlanner.kt`, `data/room/**`, `11.json`. A2: `core/…/reminders/*`, `app/…/reminders/*` but
`ReminderReceivers.kt` and `ReminderHealthCheck.kt`, SPEC12. A3: `ui/asset/{AssetViewModels,AssetDetailScreen,
AssetEditScreen,WarrantyWords}.kt`. A4 and B3: `app/…/api/*`, `docs/api/*`, `tools/servicetag-mcp/**`,
`docs/release-proofs.md`, SPEC14. B1: `model/ServiceCase.kt`, the case use cases, `backup/*`, `merge/*`, `data/room/**`,
`12.json`, `api/ApiDtos.kt` (the report). B2: `ui/asset/*`, new `ui/service/*`, `ui/nav/{Route,ServiceTagRoot}.kt`,
`ui/journal/{EventDetailScreen,EventDetailViewModel,EventEntryScreen}.kt`. `di/AppGraph.kt` in every brief but A3.
**Collisions.** #82 is on master. A3 and B2 both edit `AssetViewModels.kt` and `AssetDetailScreen.kt` (beside #82's flags
and #67's staging) — two lanes under the split; B2 alone edits `Route.kt`, `ServiceTagRoot.kt`, `EventEntryScreen.kt`,
`EventDetailScreen.kt`; `Offers.kt` untouched. **#72 collides totally with A2's files**, with none of Part B's.

## 5. Schema, backup, merge and API implications (summary for the owner)

**Part A** — Room 10 → 11: one nullable asset column, one device-local table; format 11 carries the lead (`null` when
unset); a format ≤ 10 archive carrying a lead is refused; under R79-11b every pre-11 export keeps re-planning IDENTICAL
after a lead is set. API: `GET /v1/assets/{id}/warranty` → `{warranty}`; `POST /v1/assets/{id}/warranty-reminder
{leadDays|null}` → `{asset, warranty}`. MCP 56 → 58. **Part B** — Room 11 → 12: two aggregate tables cascading from the
asset; format 12: two lists, a format ≤ 11 archive carrying a case or entry refused; the merge report grows to
seventeen tables; no UPDATE, so a case whose **status** moved on one phone after the other received it conflicts on
re-merge (a note alone does not). API: `GET /v1/assets/{id}/service-cases`; `POST /v1/service-cases {assetId,
incidentEventId?, …}` → 201 `{serviceCase}`; `GET /v1/service-cases/{id}` → `{serviceCase, entries}`; `PATCH
/v1/service-cases/{id}` (full replace) → `{serviceCase}`; `POST /v1/service-cases/{id}/entries` → 201 `{serviceCase,
entry}`; no delete, no entry amendment. MCP 58 → 63. **Release gate (K6):** `docs/release-proofs.md:88` becomes the
direct **1.4.1 (schema 8) → schema 11** proof (A4), then **→ 12** (B3) if Part B joins the same release, on
`emulator-5554` with #74's and #67's steps as written. **Precondition (m12):** an asset that will carry a lead holds a
warranty date **before** the pre-upgrade export (seeded through 1.4.1's `PATCH /v1/assets/{id}` if the data has none).
Then: the post-upgrade export carries `"warrantyReminderLeadDays": null` on every asset (at 12, also empty case lists);
the pre-upgrade export re-plans applicable with zero INSERT, every asset IDENTICAL; set a lead (at 12, also open a case
with a note and a closing entry) through the API; re-plan the pre-upgrade export again (the same result); prove the
round trip (a fresh export re-plans IDENTICAL and restores by replace and by merge into an install without the rows).

## 6. Strings — for ratification

| id | where | text | treatment |
|---|---|---|---|
| P79-1 | detail Warranty section, badge | `IN WARRANTY` | the condition badge's component (S1–S3); tone per R79-17 |
| P79-2 | same | `OUT OF WARRANTY` | same component; never DOWN's tone |
| P79-3 | same, no date | `Warranty not recorded` | `QuietLine` (S4's precedent) |
| P79-4 / P79-5 | the date line | `Expires <date>` / `Expired <date>` | body line; `d MMM yyyy` |
| P79-6 / P79-7 | the reminder line | `Reminder: <n> days before` / `Reminder: 1 day before` | `QuietLine`; IN WARRANTY, a lead, in service |
| P79-63 C | the reminder line at lead 0 (only if R79-12 allows 0) | `Reminder: on the day it expires` | same |
| P79-8 | editor, under the lead | `Leave blank for no reminder.` | supporting text |
| P79-9 | editor refusal on the lead | `Add the date the warranty expires first.` | field error |
| P79-12 | editor, before the permission request | `ServiceTag needs notification permission to remind you before a warranty expires.` | dialog text; reused "OK" / "Not now" |
| P79-10 | notification body | `Warranty expires <date>.` | body; `DigestPolicy`'s `d MMM uuuu` |
| P79-11 | notification status word | `EXPIRES SOON` | `setSubText`, as DUE / OVERDUE |
| P79-13 C / P79-14 C | channel `warranty_reminders` name / description (R79-14b) | `Warranty reminders` / `Reminders before a warranty expires.` | system settings |
| P79-15 | detail section | `Service cases` | `SectionHeader` (drawn upper-case) |
| P79-16 | its empty state | `No service cases yet` | `QuietLine` |
| P79-17 / P79-18 | open count, hidden at zero | `1 open service case` / `<n> open service cases` | line above the rows |
| P79-19 | detail action; the new-case editor's title | `New service case` | `OutlinedButton`; top-bar title |
| P79-20 | Incident detail menu | `Start service case` | `DropdownMenuItem` after Edit |
| P79-60 C | Incident delete confirm (R79-4) | `A service case links this entry. Its documents go with it.` | second line, after "Its readings go with it." |
| P79-21 | case screen title; the editor's title when editing | `Service case` | top-bar title |
| P79-22, P79-23 | editor fields | `Title`, `Type` | text-field label; label over chips |
| P79-24…26 | type chips | `Warranty service` · `Repair` · `Other service` | chips |
| P79-27…30 | editor fields | `Opened on` · `Service provider` · `Phone or contact` · `Case or RMA number` | labels |
| P79-31 | editor field | `Coverage` | label over chips |
| P79-32…34 | coverage chips; the row's word | `In warranty` · `Out of warranty` · `Unknown` | chips; value text |
| P79-35 C | coverage chip (R79-6a) | `Partly covered` | chip |
| P79-36 | under Coverage, a new case only | `Suggested from the warranty date. Change it if the provider decides otherwise.` | `QuietLine` |
| P79-44…47 | editor fields | `Outbound tracking` · `Return tracking` · `Carrier` (both legs, one home) · `Cost` | labels; Cost beside `Currency` |
| P79-48 / P79-49 | refusals | `A cost needs a currency` / `Cost cannot be negative` | field errors |
| P79-62 | refusal, unparseable cost (planner's addition) | `Enter a cost like <example>` | field error; `"Enter a cost like " + example`, as the price's |
| P79-51 | editor save | `Save case` | `Button` |
| P79-52 | refusal | `Give the case a title` | field error (Title) |
| P79-61 | unexpected save failure (planner's addition) | `Could not save this case.` | snackbar, logged, as "Could not save this asset." |
| P79-37 | case fact; update field | `Status` | `FieldLabel`; label over chips |
| P79-38…43 | status words and chips | `Open` · `Sent out` · `At the service center` · `Returned` · `Closed` · `Cancelled` | value text; chips; the row's word |
| P79-50 | case fact | `Closed on` | `FieldLabel`, when set |
| P79-53 / P79-54 | case section / action and the sheet's title | `Timeline` / `Add update` | `SectionHeader` / `OutlinedButton`, sheet title |
| P79-55 / P79-56 | sheet field / save | `What happened` / `Save update` | label / `Button` |
| P79-57 / P79-58 | link rows | `Incident` / `Repair record` | `FieldLabel` (own homes) |
| P79-59 | case action; the picker's title | `Link repair record` | `TextButton`; dialog title |
| — | reused verbatim (23) | `Warranty` (section header; the notification's `<title>`), `Expires on`, `Warranty notes`, `Remind me N days early` (the ratified label of a number control), `Enter the number of days.`, `Enter a date as YYYY-MM-DD`, `Enter a time as HH:MM`, `Currency`, `Currency is a three-letter code like USD`, `Notes`, `Date`, `Time`, `Cancel`, `OK`, `Not now`, `Edit`, `Remove`, `Could not save this entry.`, `Its readings go with it.`, `Open` (`ACTION_OPEN`), the shape `<asset> — <title>`, S24, **S25 (re)** — also Opened on's and an update's refusal | through their homes |

**63 new ids** (60 from the audit, P79-61/62 close two gaps, P79-63 only if R79-12 allows 0), **5 conditional**
(P79-13, 14, 35, 60, 63), **23 reused**; none accessibility-only; " (expired)" retires under R79-17. Part A owns
P79-1…14 and P79-63; Part B the rest. **Collisions.** No P79 string is `Done`; nothing here completes anything. `Open`
(P79-38) shares `ACTION_OPEN`'s word (anchored count 1 → 2): recommended kept — it pairs with `Closed` and "open service
case", is never a button, and tests find it in its row; alternative `In progress`. `Type` goes 2 → 3; no other P79
literal collides with a shipped anchored home.

## 7. Rulings and owner questions (controller, 2026-09-27)

- **R79-scope — first: one issue or two, decided on coherence, not size.** The facts (confirmed by the review in the
  code). (i) Part A alone still needs schema/format 11 — the lead column, and a device-local table because the shipped
  delivery table's FK is to schedules (`MaintenanceEntities.kt:256-266`); a split avoids no migration, it defers only
  the case tables, entries and their API/MCP surface. (ii) Part A does not sit on the case model: A2 holds no case and
  its `Deadline` seam is shared with #72; Part B depends on #82's Incident and the existing warranty date, not on Part
  A. (iii) Part A commits to the lead column, `deadline_local_delivery`, the port seam and LOCAL's deadline branch, the
  `warranty_reminders` channel, format 11's lead and its merge rule, two routes, two tools, the Warranty section and the
  editor's lead — and to nothing of the case schema, routes, tools, screens, documents model or Incident link. (iv) Part
  B adds its own step and redoes none of A's: `MIGRATION_11_12`, format 12 with `FIRST_CASE_FORMAT = 12`, case tools at
  a schema-12 minimum, the gate paragraph naming 12; landed before the same release, one direct 8 → 12 proof covers
  both. **The split's price (modest):** the version pins of rows 10/32 move twice (rows 44/54); `AssetViewModels.kt` and
  `AssetDetailScreen.kt` are edited in two lanes, with #72 possibly between them; the MCP carries two per-tool schema
  minimums (11 and 12). **Recommended: split** — #79a = A1–A4 at 11, then #79b = B1–B3 at 12 — because the four
  increments of rev 1 were two behind one number: A shares its core with #72, B with #82, and A ships cleanly without
  committing to the case surface. Part B's rulings (R79-1–R79-10, R79-18's case half) may be answered now and carried,
  or deferred to #79b. **Alternative: one issue** — §4's one-issue order and each brief's one-issue paragraph: one
  migration, one format, each pin moved once. **Owner: split or one issue.**
- **R79-1 (recommended: A, the aggregate).** B (events plus references) fails AC 7 (no stored coverage), AC 8 (no money
  on an event) and AC 9 (mutable, deletable NOTE events); a new `EventKind` is itself a format bump. **Owner: A or B.**
- **R79-2 (recommended: A1).** Mid-case paperwork on the Incident, invoice on the resolution event, terms and proof of
  purchase on the asset. A2 (`AttachmentOwner.OfCase`: a table recreate, 16 owner sites, a byte sweep) ≈ +1,500 lines. **Owner.**
- **R79-3 (recommended: every case opens from an Incident in the UI,** so A1 always has a home): C20's chaining; P79-20
  on any in-service Incident; an Incident may carry several cases; the use case and API accept an Incident-less case
  (automation). Alternative: standalone cases in the UI too (their paperwork then needs A2 or the asset). **Owner.**
- **R79-4 (recommended: allow, and say so).** The link dangles (S24); the confirm adds P79-60 (K5). Alternative: refuse. **Owner.**
- **R79-5 (recommended).** (a) Status is a stored column moved only by a status entry; a note-only entry leaves the
  header untouched, so notes never make a case conflict on re-merge; alternative: derived at read from the latest status
  entry by date. (b) The six statuses. (c) CANCELLED distinct from CLOSED. **Owner: (a), (b), (c).**
- **R79-6.** (a) Three coverage values (recommended) or add PARTLY_COVERED (P79-35); (b) the suggestion's basis: the
  Incident's date (recommended — warranty turns on when the unit failed) or the open date.
- **R79-7 (recommended).** Minor units + ISO currency; null = none recorded, 0 = no charge (AC 8); the asset's currency
  by default, else blank; any coverage. Alternative: only when not IN_WARRANTY. **Owner.**
- **R79-8 (recommended: immutable entries;** a correction is a new entry). Alternative: editable with `updatedAt`.
- **R79-9 (recommended).** Close and reopen through a status entry (C14); no condition, completion or event write; no
  case or entry delete in #79 (CANCELLED is the exit). Alternative: also ask "Mark operational?" at close. **Owner.**
- **R79-10 (recommended).** The linked resolution event is the canonical Service Record row; (a) link-only (C22's
  picker) or (b) also "Log repair record" (one more string; `Route.EventEntry.linkCase`; the link lands after the
  event's own save and its Workflow C offer). Alternative: closed cases as Service Record rows (double entry). **Owner.**
- **R79-11.** (a) Storage (recommended): an asset column outside `AssetCommand` (C2); alternatives: on `AssetCommand`
  (K4) or a side table. (b) **Merge (recommended: option B, the #67 shape with its errata):** below format 11 an asset
  compares without the lead, and without `updatedAt` when the local lead is non-null; format 11+ plainly. Option A
  (plain): every pre-11 export stops re-planning IDENTICAL once a lead is set. **Owner: (a); A or B.**
- **R79-12.** Off by default (confirmation: the issue's text). **(a) The range (recommended): whole days ≥ 1, no upper
  bound** (the schedule lead has none, `ScheduleCommands.kt:284`), refused with "Enter the number of days."; no toggle,
  no pre-fill. Alternative: allow 0 (the expiry day) with P79-63. (b) C2's date rule; alternative: a dormant lead
  without a date, which the editor would refuse (P79-9) on an unrelated edit. **Owner: (a), (b).**
- **R79-13 (recommended: C3).** Alternatives: `SubjectKey.Warranty(assetId)` (#72 then adds `Loan(loanId)`), or no
  `repeat` until #72 (which then moves the hash's canonical form again). **Owner.**
- **R79-14 (recommended).** (a) Once per content on entering the window, never in the summary, Open only, the
  device-local table (C7). (b) **Channel `warranty_reminders`** (P79-13/14, `IMPORTANCE_DEFAULT`; amends inv. 53; #72
  then adds its own); alternatives: one shared non-maintenance channel for #79 and #72 (its id and name ratified now to
  fit both), or `maintenance_due` (muting maintenance mutes warranties). (c) **A restart:** the stamp records the
  platform boot count, so a warning a restart took down is posted once more — **and a warning the owner swiped also
  returns once after the next restart inside the window**; an unreadable boot count reads as the same boot (recommended;
  one column, no receiver); alternative: strictly once (a restart ends it like a swipe). **Owner: (a); (b); (c).**
- **R79-15.** Absent when not in service, date or lead cleared, or after expiry (confirmation: the issue's text; C5).
  An in-app save reconciles at once (C11); API writes settle at the next digest (09:00 by default) or the 12-hour
  backstop, as schedule edits do. Out of season it still warns (C5 reads no season or break). Transfer is #77's (C26).
  **Owner: confirm.**
- **R79-16 (recommended).** No health finding; NOTIFICATIONS_BLOCKED keeps its sentence and its two channels; P79-12 is a
  separate rationale; the asset editor becomes the permission's second requester (amending 1.2 decision 23 and SPEC12
  §5.1; without it an owner with no schedules on API 33+ is never asked). **Owner: confirm.**
- **R79-17 (recommended).** C10's section retiring " (expired)"; IN WARRANTY in OPERATIONAL's badge tone, OUT OF WARRANTY
  a neutral outline (never DOWN's); the warranty post's clock icon and DUE's accent (C7); Service cases after Condition;
  P79-19 and P79-20 in service only; no post-save offer. Alternative: badge the Details row in place. **Owner: confirm;
  the tones; the post's icon and accent.**
- **R79-18 (recommended: full parity).** Seven routes (Part A two, Part B five) and seven tools (56 → 58 → 63), the
  derived status in its own response (M1). Alternative: read-only cases (Part B two routes, two tools; automation
  cannot open a case). **Owner.**
- **R79-19 (confirmation).** Out: dashboard, attention and list badges; the scan sheet; a warranty document role; #86;
  #77 (AC 13); the issue's non-goals.
- **R79-20 (recommended).** No `versionName` bump (#67/#82 precedent); master carries 11 (and 12) under 1.4.1, emulator
  only; the vehicle is the next feature-bearing MINOR (one each if #79a ships first). **Owner: confirm.**
- **R79-21.** Ratify P79-1 … P79-63 verbatim with their treatments; the conditional rows follow R79-4, R79-6, R79-12,
  R79-14; choose `Open` or `In progress` for P79-38.
- **Risks.** K1 → C4, C7, rows 15, 17; K2 → C4, rows 12–13; K3 → row 18; K4 → C2, rows 4, 30–31; K5 → R79-2, R79-4;
  K6 → R79-20, §5; K7 → C1, row 26; K8 → C25.

## 8. What this plan does not do, and records

No `LOAN_DUE_BACK`, `UNTIL_CLEARED`, deadline snooze, nonce or "Mark returned", `Completed` producer, deadline dashboard
entry or loan table (#72); no second `ProviderId`; no case or entry delete or amendment; no `AttachmentOwner.OfCase`
(under A1); no warranty document role; no scan-sheet, dashboard, health or list change; no new offer; no successor
(#86); no transfer (#77); no bundle-tool change; no `versionName` bump; no phone. **Record:** the 1.2 master plan's
decision 8 named `Supply` (#15) the port's planned second member (`master-plan.md:434`); it is not edited — this plan
and the SPEC12 §2.5 amendment record `Deadline` as the second. #72's hand-off is C26; A2's tests are its regression base.

## Briefs — common to all seven

Read §1–§8, the issue, the audit and every earlier brief's report. **Gate:**
`./gradlew :core:test :app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`; before
the first connected class `tools/emulator/prepare-emulator.sh`, then one class per run:
`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`;
the brief's anchored greps (`git grep -nE '<pattern>' -- <paths>`, over `app/src/main` unless named) and untouched diff
(`git diff <base> --stat -- <paths>` → empty; `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, expanded in the command); hygiene; gitlink `7e0377a`; `git
status` clean. **The pin rule:** a shipped assertion that pins a schema or format version, a
table, field, route, tool, status-key or sub-resource count or order, or a golden command shape moves with the brief
that bumps it, keeping every other assertion; the retarget rows list the known ones, the implementer confirms the set
with `git grep -nE '\b(10|15|29|56|sixteen|fifteen)\b'` over the test trees before the first commit, and the report
lists every such move, so an unlisted pin is a report obligation, not a gate failure. **Must NOT, always:** delete a
shipped assertion or move one that is neither a retarget-row entry nor under the pin rule; commit
outside the brief's files; use any device but `emulator-5554`; drive a system permission dialog.

## 9. A1 — the lead, the deadline table, format 11 (C1, C2, C17–C19 for the lead)

**Read:** `Asset.kt`, `AssetCommands.kt` (:167), `UpdateAsset.kt`, `SaveAssetSettings.kt`, `SetHealthPolicy.kt`,
`BackupCodec.kt` (:84-95, :244-265), `BackupContentCheck.kt`, `MergePlanner.kt` (:668-680), `Migrations.kt` (:577-587),
`AppGraph.kt` (:198-199, :782), `MigrationTestSupport.kt`, `Migration9To10Test`, every row-10 test; the #67 plan C3–C4,
§13. **Lane:** `issue-79a`; **`<base>`** = its branch point. **Rows:** 1–10. **Needs:** R79-scope, R79-11, R79-12,
R79-14c. **Connected:** `Format7RestoreContractTest`, `AppSmokeTest`. **Greps**, each → 1 over `core/src/main` or its file:
- `'const val FORMAT_VERSION = 11$'`, `'const val LAST_LEGACY_FORMAT: Int = 7$'`, `'const val SCHEMA_VERSION = 11$'` (app);
- `'^\s+version = 11,$'` in `AppDatabase.kt`; `'\bMIGRATION_10_11\b'` → hits in `AppGraph.kt` and `MigrationTestSupport.kt`; `11.json` present;
- `'^    REFERENCES, SEASON_ACTIVATIONS, CONDITIONS, HEALTH_SUBJECTS, CATEGORIES,$'` in `MergePlan.kt` (unchanged);
- `'deadline_local_delivery|DeadlineLocalDelivery'` over `C/backup C/merge` → 0; the `conditions.insert(` pin (exactly `RecordCondition.kt`, `ApplyBackupMergePlan.kt`, `ImportBackupReplace.kt`).

**Untouched:** `A/{ui,reminders,api,share,attachments}`, `C/{reminders,health,condition}`, the manifest, `tools`, `docs`.
**Must NOT:** put the lead on `AssetCommand`; default or backfill the column; add a `MergeTable` member; move
`LAST_LEGACY_FORMAT`; put a derived status on `AssetDto`; name the deadline table in `C/{backup,merge}`. **Size:** about
450 production, 800 test lines.
**One-issue reading:** A1 also carries B1's contracts (C13–C16, C17–C19's case halves, the report tallies) on
`issue-79`, all at 11 (`FIRST_CASE_FORMAT = 11`, one `MIGRATION_10_11`); rows 33–44 join and row 44's pins move with
row 10's, once; the `MergeTable` grep gains the two members and the must-not on adding one lifts; `A/api/ApiDtos.kt`
leaves the untouched list. About 1,300
production, 2,200 test lines.

## 10. A2 — the reminder extension and warranty delivery (C3–C9)

**Read:** the audit §2; `ReminderPort.kt`, `ContentHash.kt`, `BuildReminderSubjects.kt`, `DigestPolicy.kt`,
`LocalReminderProvider.kt`, `QuickActions.kt`, `Notifications.kt`, `NotificationChannels.kt`, `NotificationPermission.kt`,
`PlatformState.kt`, `ScheduleDeliveryFacts.kt`, `AppGraph.kt` (:262-340); SPEC12 §2.5, §5.1, §9.1, inv. 44–61; SPEC14
:409 (the amendment form). **Lane:** after A1; **`<base>`** = A1's accepted tip. **Rows:** 11–25. **The port member,
the builder and the LOCAL generalisation land together (K1): no commit produces a `Deadline` subject before the casts
are gone.** **Connected:** `ReminderPlatformDeviceProofTest`, `QuickActionDeviceProofTest`, `ReminderHealthScreenTest`.
**Greps:**
- over `app/src/main core/src/main`: `'as SubjectKey\.Schedule\b'` → 0; `'\bscheduleIdOfTag\b'` → 0;
- `'^internal fun (itemTag|keyOfTag)\('` → 2;
- over `core/src/main`, each → 1: `'^enum class DeadlineKind \{ WARRANTY_EXPIRY \}$'`, `'^enum class DeadlineRepeat \{ ONCE \}$'`, `'^enum class ProviderId \{ LOCAL \}$'`, `'= "Warranty"$'`;
- `'\b(LOAN_DUE_BACK|UNTIL_CLEARED)\b'` over `core app tools docs/api` → 0;
- each → 1: `'= "EXPIRES SOON"$'`, `'"Warranty expires \$'`, `'= "warranty_reminders"$'`, `'= "ServiceTag needs notification permission to remind you before a warranty expires\."$'`;
- under R79-14b, each → 1: `'name = "Warranty reminders",$'`, `'description = "Reminders before a warranty expires\.",$'`;
- unchanged from `<base>`, each → 1: `'SUMMARY_TITLE_SUFFIX = " maintenance items need attention"$'`, `'"ServiceTag needs notification permission to remind you when maintenance is due\."$'`, `'"Notifications are turned off, so maintenance reminders will not arrive\."'`;
- `'^\*\*Amendment \(#79, '` in SPEC12 → 5 (4 if R79-14b = `maintenance_due`).

**Untouched:** `C/{backup,merge,model,usecase,health,condition,ports,warranty}`, `A/{ui,api,data,share}`,
`A/reminders/{ReminderReceivers,ReminderHealthCheck}.kt`, the manifest, `app/schemas`, `tools`, `docs/api`.
**Must NOT:** change a schedule tag, hash, post, the summary, Done/Snooze or a nonce rule; construct `DeliveryInput`
differently; count a deadline in the summary; add a `ProviderId`, receiver or manifest entry; produce a `Completed` or
`Parked` deadline; give a deadline a nonce or write action; add a health finding or reword a shipped sentence; use a
banned word in `C/reminders`. **Size:** about 500 production, 1,000 test, 30 spec lines.
**One-issue reading:** unchanged; `<base>` = the combined A1's accepted tip.

## 11. A3 — the Warranty section and the editor's lead (C10, C11)

**Read:** `AssetViewModels.kt` (`AssetDetailState` with #82's flags and #67's `purchaseDocument`; the editor and #67's
save orchestration), `AssetDetailScreen.kt` (:341-400, :767-792), `AssetEditScreen.kt` (:582-598), `ScheduleEditScreen.kt`
(:72, :154, :268-276), `ScheduleEditViewModel.kt` (:448-800), `ManifestContractTest` (:229-241). **Lane:** after A2;
**`<base>`** = A2's accepted tip. **Rows:** 26–29. **Connected:** `AssetWarrantySectionTest`,
`AssetEditorWarrantyReminderTest`, `AssetDetailConditionHealthSeasonTest`, `AssetDetailKeyDocumentsTest`,
`AssetEditorKeyDocumentsTest`, `AssetEditorSeasonAndHealthTest`. **Greps:**
- each → 1: `'= "IN WARRANTY"$'`, `'= "OUT OF WARRANTY"$'`, `'= "Warranty not recorded"$'`, `'"Expires \$'`, `'"Expired \$'`, `'"Reminder: \$'`, `'= "Reminder: 1 day before"$'`, `'= "Leave blank for no reminder\."$'`, `'= "Add the date the warranty expires first\."$'` (and P79-63's if ruled in);
- `'\(expired\)|\bwarrantyExpired\b|\bexpiredOn\('` → 0.

**Untouched:** `core/src/main`, `app/schemas`, `A/{api,data,reminders,share}`, `A/ui/{nav,journal,condition,health,dashboard}`,
the manifest, `tools`, `docs`. **Must NOT:** derive a status from `Clock`; request the permission before the write, or
name `NotificationPermission` under `ui/nav`; draw the reminder line out of service; grow `AssetViewModelsTest` beyond
compile fixes. **Size:** about 350 production, 600 test lines.
**One-issue reading:** unchanged; B2 follows it on `issue-79`.

## 12. A4 — the warranty API, MCP, docs and the gate at 11 (C12, C25's Part A)

**Read:** `docs/api/v1.md` (:181-186, :303-311, :492, :531-553, :753-798, the validation families), `command-shapes.json`,
`ApiRouter.kt`, `ApiDtos.kt` (:3, :31-34), `ValidationRefusals.kt`, `SeasonHealthHandlers.kt` (:136, :245, :330),
`CommandShapesGoldenTest` (:80-102), `ReferenceRoutesTest` (:520-548); `server.py` (:130-183, :500-575, :1036-1053),
`command_shapes.py`, `tests/test_argument_guard.py`; `docs/release-proofs.md` (:81, :88); SPEC14 §10.3, §10.4, §10.7.
**Lane:** after A3; **`<base>`** = A3's accepted tip. **Rows:** 30–32. **Connected:** none (were one run, prepare first);
`(cd tools/servicetag-mcp && uv run --frozen pytest)` green at 58 tools. **Greps:**
- ``'^\| `(GET|POST)` \| `/v1/assets/\{id\}/(warranty|warranty-reminder)` \|'`` in `v1.md` → 2;
- `'format \*?\*?1–'` over `docs/api tools/servicetag-mcp/src` → every hit reads `1–11`; `'11 since #79 \(warranty reminders\)'` in `v1.md` → ≥ 1;
- `'\bwarrantyStatus\b'` over `C/backup` → 0; `'warrantyReminderLeadDays|warranty_reminder_lead'` in `command_shapes.py` → 0;
- `'^\*\*Amendment \(#79, '` in SPEC14 → 3; `'schema 8\) → schema 11'` in `docs/release-proofs.md` → 1.

**Untouched:** `core/src/main`, `A/{ui,reminders,data,share}`, `app/schemas`, `tools/servicetag-{bundle,schedules}`,
`docs/versioning.md`, SPEC12. **Must NOT:** put the status on `AssetDto` or the lead on `AssetCommand`/`ASSET_KEYS`; lower
the global MCP minimum; bump `versionName`; rewrite ratified spec text or give P79 ids S-numbers. **Size:** about 200
production, 250 test, 50 docs lines.
**One-issue reading:** A4 also carries B3 (C24, C25's Part B): seven routes, seven tools at one schema-11 minimum (56 →
63), range 1–11, "11 since #79", seventeen tables, nineteen sub-resources, the case steps in the 8 → 11 gate, SPEC14
amendments → 5; rows 52–54 join and row 54's pins move with row 32's, once.

## 13. B1 — the case aggregate, schema 12, format 12, merge (C13–C16, C17–C19's case halves)

**Read:** `Money.kt`, `Condition.kt`, `CurrentIncident.kt` (:32), `DeleteAsset.kt` (:37-40), `Repositories.kt`,
`BackupFormat.kt`, `BackupCodec.kt`, `MergePlan.kt` (:385-408, `report()` :457-477), `MergePlanner.kt` (:775-815), `ApplyBackupMergePlan.kt`,
`ImportBackupReplace.kt`, `ExportBackupSet.kt`, `StoreIsEmpty.kt`, `ApiDtos.kt` (:142-199), `CrossConceptWriteTest`,
`InMemoryRepositories.kt` (:125), every row-44 test. **Lane:** `issue-79b`; **`<base>`** = its branch point. **Rows:**
33–44. **Needs:** R79-1, R79-3, R79-5–R79-9. **Connected:** `Format7RestoreContractTest`, `AppSmokeTest`. **Greps**, each → 1:
- `'const val FORMAT_VERSION = 12$'`, `'const val FIRST_CASE_FORMAT = 12$'` (core); `'const val SCHEMA_VERSION = 12$'`; `'\bSERVICE_CASES, CASE_ENTRIES,'` in `MergePlan.kt`;
- `'^\s+val (serviceCases|caseEntries): MergeTally\b'` over `C/merge` → 2; `'^\s+val (serviceCases|caseEntries): MergeTallyDto\b'` in `ApiDtos.kt` → 2;
- `'\bMIGRATION_11_12\b'` → hits in both lists; `12.json` present; the `conditions.insert(` pin.

**Untouched:** `A/{ui,reminders,share,attachments}`, `A/api` but `ApiDtos.kt`, `C/{reminders,health,condition,warranty}`,
the manifest, `tools`, `docs`. **Must NOT:** add an UPDATE verdict; check a soft event link; give the entry port an update
or delete; stamp the header on a note-only entry; let a case writer touch `asset_event`, `asset_condition` or
`schedule_state`; move a shipped `MergeTable` ordinal. **Size:** about 900 production, 1,500 test lines.
**One-issue reading:** folded into A1 (no `MIGRATION_11_12`, no format 12, no second retarget).

## 14. B2 — the case screens (C20–C23)

**Read:** `AssetViewModels.kt`, `AssetDetailScreen.kt` (`ConditionSection`), `Route.kt`, `ServiceTagRoot.kt`,
`EventEntryScreen.kt` (:83, :103), `EventDetailScreen.kt` (:279-290), `DocumentsSection.kt` (`AttachmentsSection`),
`ChangeConditionSheet.kt` (the sheet pattern), `RouteTest.kt`. **Lane:** after B1; **`<base>`** = B1's accepted tip.
**Rows:** 45–51. **Connected:** `AssetServiceCasesTest`, `ServiceCaseScreensTest`, `AssetDetailConditionHealthSeasonTest`,
`AssetDetailKeyDocumentsTest`, `JournalDeviceProofTest`, `NavigationSmokeTest`. **Greps:**
- every Part B P79 literal → exactly 1 in its anchored form (`'= "<text>"$'`, regex-escaped; a template by its opening, e.g. `' open service cases"$'`; P79-62 as `'"Enter a cost like " \+'`); P79-35, P79-60 only when ruled in;
- except `'= "Open"$'` → 2 (the base's `ACTION_OPEN` plus P79-38; under `In progress`, 1 and `'= "In progress"$'` → 1) and `'= "Type"$'` → 3;
- each → 1: `'thenServiceCase: Boolean = false'`, `'^fun interface CaseLinks\b'`, `'^\s+onStartServiceCase = '` in `ServiceTagRoot.kt`;
- unchanged from `<base>`: the `conditions.insert(` pin and `'\boffersAfter\('`.

**Untouched:** `core/src/main`, `app/schemas`, `A/{api,data,reminders,share}`, `A/ui/{health,dashboard,condition}`,
`A/ui/journal/EventEntryViewModel.kt`, the manifest, `tools`, `docs`; `EventEntryScreen.kt`'s diff is the defaulted
`onSaved` and its use only. **Must NOT:** write a condition, event or schedule from a case surface; call `offersAfter`
or add an offer; fan out per case; offer an entry edit or delete; own an attachment by a case; show P79-19 or P79-20
out of service. **Size:** about 950 production, 1,100 test lines.
**One-issue reading:** runs after A3 on `issue-79`; `<base>` = A3's accepted tip.

## 15. B3 — the case API, MCP, docs and the gate at 12 (C24, C25's Part B)

**Read:** as A4, plus A4's report. **Lane:** after B2; **`<base>`** = B2's accepted tip. **Rows:** 52–54. **Connected:**
none; pytest green at 63 tools. **Greps:**
- ``'^\| `(GET|POST|PATCH)` \| `/v1/(assets/\{id\}/service-cases|service-cases)'`` in `v1.md` → 5;
- every `'format \*?\*?1–'` hit reads `1–12`; `'12 since #79 \(service cases\)'` in `v1.md` → ≥ 1;
- `'fifteen tables'` over `docs/api tools/servicetag-mcp/src` → 0; `'seventeen tables'` → ≥ 1 in `v1.md` and in `server.py`;
- `'^\*\*Amendment \(#79, '` in SPEC14 → 5; `'schema 8\) → schema 12'` in `docs/release-proofs.md` → 1.

**Untouched:** `core/src/main`, `A/{ui,reminders,data,share}`, `app/schemas`, `tools/servicetag-{bundle,schedules}`,
`docs/versioning.md`, SPEC12. **Must NOT:** route a delete or an entry amendment; let `update_service_case` send status
or `closedOn`; lower the global MCP minimum; bump `versionName`. **Size:** about 300 production, 400 test, 50 docs lines.
**One-issue reading:** folded into A4.

## 16. Owner rulings (2026-09-27; binding on every brief)

- **R79-scope = SPLIT**, on coherence: #79a = A1 → A4 on schema/format 11, then #79b = B1 → B3 on
  schema/format 12, **back to back** — #72 is NOT inserted between them (the split is an implementation
  boundary, not a roadmap reorder); no release between them unless separately ruled; both stay under
  `versionName` 1.4.1 and ship together in the next feature-bearing MINOR; the final release proof is the
  direct production 1.4.1 (schema 8) → current schema.
- **R79-1** the aggregate (`service_case` + append-only `service_case_entry`). **R79-2** documents stay on
  the Asset, the originating Incident and the resolution event (no `AttachmentOwner.OfCase`). **R79-3**
  phone-UI cases originate from an Incident; the core/API accept an Incident-less case. **R79-4** Incident
  deletion allowed: a readable dangling link + P79-60 in the warning. **R79-5** stored status moved only by
  immutable status entries; six statuses; CANCELLED distinct from CLOSED; note-only entries never touch
  the header. **R79-6 = FOUR coverage values: IN_WARRANTY, OUT_OF_WARRANTY, UNKNOWN, PARTLY_COVERED**
  (P79-35 active); the suggestion uses the Incident's date. **R79-7** minor units + ISO currency, the
  asset's currency by default, any coverage, null = none, 0 = no charge. **R79-8** immutable entries.
  **R79-9** close/reopen by status entry; no condition/event/completion write; no delete (CANCELLED is
  the exit). **R79-10 link-only**: the resolution event is canonical; no "Log repair record" in #79.
  **R79-11** the lead as an asset column outside `AssetCommand`; merge option B on the #67 shape with
  its stamp rule. **R79-12** off by default; whole days ≥ 1, no upper bound; clearing the date clears the
  lead; zero refused — **P79-63 does not ship**. **R79-13** `SubjectKey.Deadline(kind, subjectId)`,
  `DeadlineKind.WARRANTY_EXPIRY`, `DeadlineRepeat.ONCE`. **R79-14** (a)(b)(c) as recommended: once per
  content on entering the window, never in the maintenance summary, Open only, the device-local table,
  the dedicated `warranty_reminders` channel (P79-13/14 active), the boot-count behaviour with its
  disclosed swipe-then-restart repeat. **R79-15** confirmed; **an out-of-season asset still warns**.
  **R79-16** no health finding; the asset editor is the second permission requester (decision 23 / SPEC12
  §5.1 amended). **R79-17** the Warranty section and notification treatments as recommended. **R79-18**
  full parity: seven routes and seven tools; the derived status in its own response, never on the backup
  DTO. **R79-19** exclusions confirmed. **R79-20** no `versionName` bump in #79a or #79b; the vehicle is
  the next feature-bearing MINOR, number deferred. **R79-21** strings ratified verbatim; P79-13/14, P79-35
  and P79-60 ACTIVE; P79-63 OMITTED; **P79-38 = `Open`**.
- Rev 2.1 is the implementation contract; the scoped re-review's APPROVE stands; the PARTLY_COVERED path is
  the plan's conditional branch, so no further planning or review cycle.

## 17. Errata after implementation (#79a; controller, 2026-09-27 onward)

- **A lead on a retired or archived asset is accepted** (`SetWarrantyReminder` does not refuse it): the lead is a
  setting, and R79-15's subject rule already suppresses the reminder for an asset not in service; refusing would
  have needed a new sentence. (A1 concern; controller ruling.)
- **`DeadlineLocalDelivery.kind` is a `String`** holding the `DeadlineKind` name, because the enum belongs to A2's
  reminders package and A1 may not touch it; A2 maps it at the boundary.
- **The docs lag A1 by design:** asset GET responses carry `warrantyReminderLeadDays` from A1 (the API reuses
  `AssetDto`) while `docs/api/v1.md` and the MCP docstring are A4's; the 8 → 11 release-gate paragraph is A4's too.
- **Replace leaves the device-local deadline table alone** (it has no foreign key); A2's provider forgets any row
  whose key is no longer an Active subject.
- **Unlisted pins moved under the pin rule** (A1's report itemises them): `BackupFormat8Test:216`; the
  asset-column filters in `Migration3To4Test`, `Migration7To8Test`, `Migration8To9Test`, `Migration9To10Test`;
  `ReferenceMigrationTest` (asset columns and the `V11_TABLES` subtraction).
- **A2, recorded shapes:** a schedule id literally beginning `WARRANTY_EXPIRY:` would be read as a warranty
  tag — generated ids are UUIDs, so only a hand-edited archive could reach it; no tag namespace change now (KDoc
  says so at the parse site). A replace restore with identical warranty content keeps its delivery stamp, so it
  is not warned again in the same boot; a changed date or lead re-announces (R79-14 a). `LocalReminderProvider`'s
  two new constructor parameters default to no-op posting; `AppGraph`, the only production caller, passes the
  real facts and stamps; `DeliveryInput` implements `DigestInput` with a derived key that rejects a non-schedule
  subject. The SPEC12 §5.1 amendment describes the editor's permission request before A3 built it (the plan's
  assignment).
- **A3, recorded shapes:** the Warranty section shows on every asset (C10); only its reminder line requires the
  asset in service. The in-app sweep runs once, right after the save (C11): with notifications not yet allowed,
  a warranty already inside its window is posted at the next digest or the 12-hour backstop after the grant. A
  save that sets no lead never asks for notifications (pinned by `aSaveThatSetsNoLeadNeverAsks`). The warranty
  badges carry no glyph (§6's treatment names none). The shipped `calendarSeasonLines` device case scrolls to
  its break lines now that the section sits above them (the standing scroll rule). The editor's two new ports
  are nullable defaults; `AppGraph` passes the real ones.
- **A4, recorded shapes:** `get_warranty` refuses a phone below schema 11 although it is a read (C12: the route
  does not exist there); the API's warranty answer is not gated on the asset being in service (C12), while the
  phone warns only in service; a lead set through the API on a retired or archived asset is accepted (the
  standing ruling); the MCP README's stale `import_merge` paragraph now says format 1–11 and the current fifteen
  tables (Part B moves it to seventeen); the release gate says the pre-upgrade format-8 export re-plans with
  every ROW IDENTICAL (stronger than #67's attachments-only wording) — the emulator gate proves it; the permitted
  `NotificationPermission.kt` KDoc edit is two sentences on four wrapped lines, no code.
- **From the whole-branch review:** the editor compares the saved date and lead against the pair it LAST SWEPT,
  not the loaded pair (a retry restoring the loaded value still sweeps — stricter and correct); it sweeps on any
  change to the date, lead or no lead (C11 read literally; harmless); the app refuses a lead without a date (P79-9)
  while the `UpdateAsset` / API PATCH path clears the lead silently (R79-12); in-app retire, archive and delete run
  no sweep — a standing warning stays until the next digest or backstop, as schedules do (`v1.md`'s "taken down
  when … retired or archived" means at the next sweep); the stamp precedes the post; a rename does not re-title
  a standing warning; the lead survives the expiry date and applies again if the date is extended; the editor's
  two ports are nullable defaults, their production wiring proven at the branch gate by the editor → sweep device
  case; the release gate's in-app step answers the notification question with "Not now" and seeds the date
  through an overlay write; the unlisted pins A2 and A4 moved are itemised in their reports.
