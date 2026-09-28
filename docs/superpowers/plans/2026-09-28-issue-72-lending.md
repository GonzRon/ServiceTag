# #72 — lent-out assets, Android contacts and due-back reminders: plan and briefs (rev 2.1, 2026-09-28)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> Four briefs (§9–§12) on one branch `issue-72`, strictly sequential, each with one task review, one batched fix round,
> at most one scoped re-review; one whole-branch review. Planned read-only from issue #72 and the audit (both under
> `.superpowers/sdd/2026-09-28-issue-72/`) on master 59b8b55b (#79 complete: schema/format 12 unreleased under 1.4.1,
> MCP 63 tools). **Nothing is dispatched until every string in §6 and every ruling in §7 is answered.**
>
> **Rev 2** folds in the brief review (`brief-review.md`: REJECT on rev 1 — 0 blocker, 5 major, 17 minor, 10 notes;
> the design confirmed): M1 the grant grep rescoped; M2 `_require_loan_schema`; M3 a pure lookup-URI codec with one
> shared rule and a device equality row; M4 a loan's day anchored to the digest hour; M5 a loan's Once strictly once;
> m1 the warranty's `once` untouched (K9 gone); m5 a fifth route and an overlay `update_loan` (68 tools); m11 a
> foreign-UID grant proof from `:share-test-sender`; the other minors and N1–N10 applied; P72-46 added.
> **Rev 2.1** applies the re-review's C1–C5 (`brief-re-review.md`); nothing else moved.

**Goal:** the owner lends an asset to a person or an organisation picked from Android Contacts, with no contacts
permission; the plate, a Lending section, the Assets list and, once overdue, the Dashboard say so; "Mark returned" keeps
the loan as history; an optional Once or Until-returned reminder travels the one reminder pipeline as a third deadline
shape and a return withdraws it; backup, merge, API and MCP carry loans, and a link that does not open degrades to its
name snapshot. Nothing writes a condition, event, schedule, completion or health value.

**Spec:** issue #72 (AC 1–14); SPEC12 = `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`
(amended by B2); SPEC14 = `docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md` (§10.2
RATIFIED; amended by B4); P79 = the #79 plan (C3–C9, C17–C19, C26, §17–§18); the #71 plan; the planning policy.

## Global constraints

- **The condition writers stay closed; lending is an eighth concept, not a maintenance fact.** No loan write touches
  `asset_event`, `asset_condition`, `schedule_state`, a schedule, a closure or a health subject; the four writers, §5.4,
  inv. 81, inv. 119, `core/{health,condition,schedule}/**` and `ui/{health,condition}/**` are untouched; nothing joins
  `offersAfter`. An overdue loan is never `DueStatus.OVERDUE`, never in the maintenance summary, never an attention
  CONDITION or HEALTH row, and never takes the bare status word `OVERDUE` (`WORD_OVERDUE`), its tone, channel or icon
  (AC 13).
- **The closed schedule-reminder semantics are untouched except the shared seam (C8–C14)** — statuses, summary, counts,
  Done/Snooze, nonces, schedule tags and hashes, the maintenance channels, receivers and health findings. **The warranty
  warning is byte-identical:** its `once`, window end, restart rule and post are not edited (C11, C14).
- **No contacts permission for ServiceTag, no ServiceTag manifest change (AC 12):** `AndroidManifest.xml` and
  `ManifestContractTest` (:358 permissions, :187 `<queries>`) do not move; no `READ_CONTACTS` in `app/src/main`; a
  picked contact is read once, in the result callback. Only the test-only sender holds contacts permissions (C25).
- **Schema 13 / format 13** on the #79 precedent, stacked on the unreleased 12; `LAST_LEGACY_FORMAT` stays 7;
  `deadline_local_delivery` never exported, merged or named in `core/{backup,merge}` and gains no column; the open
  marker never leaves the Room layer; `docs/versioning.md` and `versionName` untouched (R72-21). Nothing of #77 or #86.
- **Strings.** §6 verbatim, ratified before dispatch; one home per P72 literal (a `const val … = "…"` or a template
  whose line ends in its closing quote), `Due back` excepted (core title and form label, the #79 "Warranty" precedent);
  reused strings through their homes, or inline where none exists (§6).
- **Tests.** JVM first; the device and gate rules are the briefs' common block; one connected class per run; no UI
  harness. The one new OS boundary, a one-shot contact grant from another UID, is `ContactGrantBoundaryTest` (C25);
  everything else is in process. Every RED is a real mutation run with `--no-build-cache --rerun-tasks`, its failing
  assertion quoted. One-line commits, the tombstone rule, gitlink `7e0377a`, **no personal data**: fictional fixtures
  only ("Example Rentals Ltd", "Sample Borrower", "Example Drill"), never a real name, number or lookup key.

## 1. Audit additions (the audit is the base; these are the planner's and the review's findings)

(1) The subject id `<assetId>/<loanId>` lets "Open" find the asset with no read (`forDeadline` is plain,
`QuickActions.kt:136-139`); an asset-only key would carry a returned loan's stamp into an identical re-lend when no
sweep runs between. (2) `DateChangedReceiver` sweeps at each local midnight (`ReminderReceivers.kt:109, :195-198`;
manifest :164), and a loan's Once has no window end, so a date-anchored cadence would ring at 00:00 and R79-14c's
restart rule would be unbounded: C11 anchors a loan to the digest hour (`AppPrefs.kt:49-50`) and keeps its Once strictly
once. (3) App JVM tests run on the stub `android.jar` with no Robolectric (`app/build.gradle.kts:98`), so the codec is
pure Kotlin on `LinkLauncher.kt:43-51`'s split. (4) "Two open loans for one asset" is a cross-row unique-index guard:
beside `uniqueIds` in the graph check (`BackupCodec.kt:375-378`), outside the content check's scope
(`BackupContentCheck.kt:48-53`). (5) The borrower rides in the facts, never the hash, so a relink cannot post a Once
twice (AC 6). (6) `LocalReminderProvider.kt` needs no change (`:218`, `:228-256`); `= "None"$` already has two homes;
without `lentOn` editable while open a mistyped lent date has no correction short of a return.

## 2. Behaviour (the briefs' contract)

```kotlin
// core/…/model/AssetLoan.kt (B1)
enum class LoanReminderMode { NONE, ONCE, UNTIL_RETURNED }
data class AssetLoan(
    val id: AssetLoanId, val assetId: AssetId,
    val borrowerName: String,          // the display-name snapshot; trimmed, never blank
    val contactLookupUri: String?,     // null = name only (every API loan); else matches CONTACT_LOOKUP_URI
    val lentOn: String, val dueOn: String?, val returnedOn: String?,   // ISO dates; open iff returnedOn == null
    val reminderMode: LoanReminderMode, val notes: String,
    val createdAt: Long, val updatedAt: Long,
)
enum class LoanStanding { LENT_OUT, OVERDUE }   // derived at read, never stored
val CONTACT_LOOKUP_URI = Regex("""^content://com\.android\.contacts/contacts/lookup/[^/?#]+(/[0-9]+)?$""")

// core/…/reminders/ReminderPort.kt (B2) — one member each and nothing else
enum class DeadlineKind { WARRANTY_EXPIRY, LOAN_DUE_BACK }
enum class DeadlineRepeat { ONCE, UNTIL_CLEARED }

// app/…/reminders/DigestPolicy.kt (B2) — the three new fields are set for a loan and null for a warranty
data class DeadlineFacts(
    val ownerName: String, val today: LocalDate,
    val borrower: String? = null,      // the loan's snapshot
    val opensAt: Long? = null,         // epoch millis: the due day at the digest hour, device zone
    val cadenceSince: Long? = null,    // epoch millis: the latest digest-hour instant at or before now
)
```

**Data (B1).**
- **C1, the aggregate (AC 3, 5; R72-1).** The fragment; `AssetLoanId` on the `ServiceCaseId` shape. Port
  `AssetLoanRepository`: upsert, get, forAsset (`lent_on` desc, id), openFor(assetId), open(), all, deleteAll,
  observeForAsset, observeOpen. Pure `AssetLoan.standingOn(today)`: returned → null; `today > dueOn` → OVERDUE (the due
  day is LENT_OUT); else LENT_OUT — never stored, on a DTO or mapped to `DueStatus` or health; `AssetLoan.kt` imports
  nothing from `core.schedule` or `core.health`. `CONTACT_LOOKUP_URI` is the one link rule, used by C3, C6 and C15.
- **C2, one open loan per asset (AC 4; R72-2).** (i) `LendAsset` refuses `AssetAlreadyLent` inside its `uow.write` (409
  `asset_already_lent`, P72-37); (ii) Room: `open_marker INTEGER`, 1 while open and NULL once returned, set by the
  mapper, under `UNIQUE(asset_id, open_marker)` (distinct NULLs, `Journal.kt:66-70`) — the database's last word, never
  mapped: the use-case check runs in the same write transaction (`RoomRepositories.kt:128`), so neither phone nor API
  reaches it; (iii) the graph check (C6); (iv) merge (C7).
- **C3, use cases (AC 1, 4, 5, 8; R72-3, 5, 11, 17–19).** One `uow.write` each; every problem collected first into
  `LoanValidation`; `Today` from the graph. `LoanTerms(lentOn, dueOn?, reminderMode, notes)`; problems
  `BorrowerRequired`, `BadDate(field)`, `LentAfterToday` (S25), `DueBeforeLent`, `ReminderWithoutDueDate` (a refusal,
  never a silent reset — stricter than the asset lead's clear, SPEC14 :862; documented, N10), `ContactLinkInvalid`
  (fails the rule). `LendAsset.run(assetId, borrowerName, terms, contactLookupUri: String? = null)`: `NoSuchAsset`,
  `AssetAlreadyLent`; any lifecycle. `UpdateLoan.run(loanId, terms)`: full replace of the four terms, never the asset,
  borrower, link or `returnedOn`; `NoSuchLoan`, `LoanReturned` (frozen, R72-18); equal → no write.
  `ReturnLoan.run(loanId, returnedOn)`: `NoSuchLoan`, `LoanReturned`, `BadDate`, `ReturnedBeforeLent`,
  `ReturnedAfterToday` (S25); the row stays. `RelinkLoanContact.run(loanId, lookupUri, displayName)` (phone only): link
  **and** snapshot in one write; `NoSuchLoan`, `LoanReturned`, `BorrowerRequired`, `ContactLinkInvalid`; equal → no
  write. No loan delete exists (R72-17); an asset delete cascades its loans.
- **C4, the write gate.** `CrossConceptWriteTest` gains the four writers → `{asset_loan}`.
- **C5, schema 13.** `MIGRATION_12_13` = `CREATE TABLE asset_loan` (C1's columns plus `open_marker`; FK → `asset(id)` ON
  DELETE CASCADE), `index_asset_loan_asset_id` (redundant, kept on the house style) and unique
  `index_asset_loan_asset_id_open_marker`, nothing else; wired on #79's path (P79 C17: list, chain, `AppDatabase` 13,
  `13.json`, `SCHEMA_VERSION`, `VersionAgreementTest`, entity, DAO — update-then-insert, `AssetDao.kt:14-28` —, mapper,
  repository, the in-memory double with the cascade, `FakeGraph`). The stamp port's KDoc (`Repositories.kt:250-256`)
  names a loan's `<assetId>/<loanId>`.
- **C6, format 13.** `FORMAT_VERSION = 13`, `FIRST_LOAN_FORMAT = 13`; `BackupData.assetLoans = emptyList()` by default
  (every C1 field; no marker, no standing), written sorted by id (`BackupCodec.kt:153`) so equal content is equal bytes;
  manifest `counts` gain `assetLoans` (23 → 24 keys); a `< 13` archive carrying a loan is `BackupCorrupt` after the
  strict decode (`:312-321`'s pattern); an unknown mode is refused. **The graph check** (`validateGraph`, beside
  `uniqueIds`) refuses a loan whose asset is not in the archive and two open loans for one asset (the index would
  otherwise abort a replace). **The content check** (per row; a loan bullet in its KDoc, its scope unchanged) refuses
  what a command refuses with no today-relative rule (P79 C18): a blank borrower, unparseable dates, due < lent,
  returned < lent, a mode without a due date, a link failing the rule, a loan id holding `/` (C9). Replace, export and
  merge apply carry the list; `StoreIsEmpty` unchanged (a loan cannot outlive its asset).
- **C7, merge (AC 11; K3).** `MergeTable` appends `LOANS` after `CASE_ENTRIES`; `MergeWrites`/`MergeSnapshot` gain
  loans; apply writes them after assets; core `MergeReport` and the API's `MergeReportResponse` gain `loans` after
  `caseEntries` — **eighteen tables**. A loan by id: present → IDENTICAL (every field equal) or CONFLICT
  `CONTENT_DIFFERS`; absent → `OWNER_NOT_AVAILABLE` when its asset is neither here nor inserted by this plan; else
  **`ASSET_ALREADY_LENT`** (new, appended) when it is open and its asset holds a different open loan **here** — the
  graph check keeps an archive to one open loan per asset, so every planner case is reachable through the real decode;
  else INSERT. **No UPDATE:** a return, re-date, mode change, relink or note on one phone after the other received the
  loan conflicts on re-merge (P79 §18), said in `v1.md` and the `import_merge` docstring.

**The reminder seam (B2).**
- **C8, the port (AC 10; R72-20).** The fragment's two members, nothing else: no `SubjectKey` member, `ProviderId` or
  `Completed` producer — a returned loan is **absent**, so C26's "Completed pin change" is declined and
  `nothingInCoreEverBuildsACompletedSubjectOnItsOwn` stays. `ContentHash.of` is unchanged (`ONCE` bytes do not move).
- **C9, loan subjects.** `BuildLoanSubjects(loans).forProvider(provider, today)` in `core/…/reminders/`: every **open**
  loan with a due date and mode ≠ NONE, by loan id → `Deadline(LOAN_DUE_BACK, LoanSubjectId.of(assetId, loanId))`, title
  "Due back" (P72-23's core home), body "", `dueOn`, `leadDays` 0, `Active`, `rule` null, `repeat` ONCE or
  UNTIL_CLEARED; otherwise absent. No lifecycle gate (R72-10), season, break, asset read, clock or end while open.
  `LoanSubjectId`: `assetOf` = before the **last** `/`, `loanOf` = after it, null with no `/` or an empty side; a loan
  id never holds `/` (C6), so any asset id round-trips; `itemTag`/`keyOfTag` unchanged. KDoc avoids
  `ReminderPortContractTest`'s banned words (:177).
- **C10, composition and facts.** `subjectsFor` = schedule + deadline + loan subjects; `ReminderRuns`, receivers, alarm
  and backstop unchanged. `DeadlineDeliveryFacts` takes the loan port, the clock, its own `zone = {
  ZoneId.systemDefault() }` and `digestHour = { prefs.digestHour }`; for a loan → null when the loan or asset is gone or
  it is returned, else the asset name, `today`, the borrower, `opensAt` (the due date at the digest hour) and
  `cadenceSince` (the latest digest-hour instant ≤ now), both in the zone (a DST gap moves forward); the warranty branch
  is unchanged.
- **C11, LOCAL delivery (AC 6–9, 13; R72-6, R72-7; K1, K5).** `deadlineStep` routes by kind, then repeat:
  WARRANTY_EXPIRY → the shipped `once`, **unedited** (its end at `:346`, R79-14c); LOAN_DUE_BACK + ONCE → new
  `loanOnce`; + UNTIL_CLEARED → new `untilCleared`; other pairings → Forget. Both loan steps Forget on null facts, a
  non-Active subject or `nowMillis < opensAt`, and have no end (a return makes the subject absent).
  - **`loanOnce` (R72-6 a):** standing → Standing; a stamp with this hash **from any boot** → Quiet (strictly once per
    due occurrence; `announced_boot` written and ignored, no column); muted → Quiet, no stamp; else post and stamp. A
    re-date or mode change is a new occurrence; a NONE → ONCE round trip or `silence` forgets the stamp and re-arms it
    (disclosed).
  - **`untilCleared` (R72-7):** *announced this period* = a stamp with this hash, `sameBoot`, `updatedAt ≥ cadenceSince`
    → Standing if the tag stands, else Quiet; otherwise muted → Standing if the tag stands (a mute never takes a post
    down), else Quiet, no stamp; else post and stamp, a standing tag **re-posted in place, re-alerting**
    (`setOnlyAlertOnce(false)`, `Notifications.kt:82`) as a new `DeadlineStep.Realert` counted *unchanged*. The midnight
    sweep is quiet.
  - **The post:** WARRANTY_EXPIRY as shipped (its midnight entry disclosed, unchanged). LOAN_DUE_BACK →
    `loan_reminders`, title `<asset> — Due back`, body P72-40 through the due day and P72-41 after it, word P72-42 /
    P72-43 alike (`d MMM uuuu`), `meter` false, `[Open]`, the shipped `else` icon and accent; never counted in the
    summary, snoozed or nonced; the kept set protects it.
  - **Withdrawal (AC 8, 9):** absence (a return, a cleared date, NONE, the asset deleted) cancels the tag and forgets
    the stamp; a moved date or mode is new content, its old tag cancelled first. The facts' clock read and `nowMillis`
    differ by milliseconds, so one extra re-alert at a boundary is possible (N6). `LocalReminderProvider.kt` does not
    change.
- **C12, "Open" (inv. 54, 57; R72-8).** `forDeadline` stays plain: LOAN_DUE_BACK → `[Open →
  OpenAsset(LoanSubjectId.assetOf(subjectId))]` (null → none; unreachable); no nonce, write or "Returned".
- **C13, channel and permission (R72-9).** `NotificationChannels.LOANS = "loan_reminders"`, P72-38/39,
  `IMPORTANCE_DEFAULT`, permanent once created; inv. 53 → four. `LOAN_NOTIFICATION_RATIONALE` = P72-33 beside the
  warranty's; the lend form is the **third requester** (SPEC12 §5.1 amended); no health finding
  (`notificationsAvailable` keeps its two channels).
- **C14, warranty byte-identity (K1, K2).** B2's first commit records at `<base>`, before any production edit, the
  warranty day matrix — expiry −31 … +2 × {nothing standing, standing, stamped this boot, another boot, muted} — through
  the real builder and `decide`: every post's tag, title, body, word, channel and actions, every stamp and forget. B2's
  tip reproduces it; the warranty's path is unedited, so the claim holds for every sweep.

**The phone (B3).**
- **C15, contacts (AC 1–3, 11, 12; R72-3, R72-4).** New `A/contacts/`. **Pure (no `android.*` import):**
  `ContactLink.lookupUriOf(id: Long, lookupKey: String): String?` →
  `content://com.android.contacts/contacts/lookup/<key>/<id>`, the key appended **verbatim**, as `Contacts.getLookupUri`
  does; null when the key is empty or holds `/`, `?`, `#`, whitespace or non-ASCII, so any result matches
  `CONTACT_LOOKUP_URI`; `PickedContactReader(query: ContactRowQuery).read(uri: String)` → `Picked(lookupUri,
  displayName)`, `NoName` (blank name → P72-30) or `Unreadable` (no row, a `SecurityException`, a key the codec refuses
  → P72-46, logged); `ContactOpenPolicy.decide(uri)` allows only what the rule matches. **Boundary:** `ContactRowQuery`
  returns `_ID`, `LOOKUP_KEY`, `DISPLAY_NAME` as primitives; `ContactPick` launches an `ActivityResultContract<Void?,
  Uri?>` defaulting to `PickContact()` (C25's seam) and reads the result's string in the callback
  (`ActivityNotFoundException` → P72-45); `ContactOpener` builds `Intent(ACTION_VIEW, …)` only after the policy, a
  missing handler or `SecurityException` → P72-20 via a `LinkLauncher`-style `noHandler` split. A company-only contact's
  name is its organisation (AC 2); nothing else is kept; no persistable grant; an unresolvable link is Contacts' own
  "not found", undetectable (R72-4).
- **C16, plate and Lending section (AC 1, 3, 13, 14; R72-11, 12, 22).** `AssetDetailState.loans = LoanFacts(open,
  standing, history, inService)` from one `observeForAsset` and `Today`; `PlateFact.Lent(overdue)` → `StatusBadge` P72-1
  / P72-2 with the loan glyph, after Retired and Archived, before the season badge (condition first, inv. 119).
  `LendingSection` after `WarrantySection`, before `ConditionSection`, when the asset is in service or holds any loan:
  P72-3; the open block — badge, P72-4, P72-5, then P72-6, or P72-7 with P72-2, or P72-8; P72-9 / P72-10 with a date and
  a mode; the notes; "Open contact" (P72-11) with P72-19, or P72-17; "Choose from Contacts" (P72-18, relink); "Mark
  returned" (P72-12); "Edit loan" (P72-13). No open loan, in service: "Lend out" (P72-14) → `Route.LoanEdit(assetId)`.
  "Past loans" (P72-15), by `returnedOn` desc then id: P72-4, P72-5, P72-16, the notes, no action. The open block works
  on every lifecycle; the overflow is unchanged.
- **C17, the editor (AC 1, 2, 5).** `Route.LoanEdit(assetId: String, loanId: String? = null)`; title P72-14 / P72-13.
  New: Borrower (P72-21) = the picked name beside "Choose from Contacts" (P72-18), no typed name; Lent on (P72-22,
  today); Due back (P72-23, optional); Reminder (P72-24): `None` (reused) / Once (P72-25) / Until returned (P72-26),
  enabled only with a due date — else P72-27, reset to None; Notes; "Save loan" (P72-28). Edit: the borrower as text, no
  picker. Field refusals P72-29, P72-30, P72-46, "Enter a date as YYYY-MM-DD", S25, P72-31; snackbars P72-37
  (`AssetAlreadyLent`) and P72-32 (`LoanReturned`, an unexpected failure, logged). `saving` before the first suspension;
  Cancel and back write nothing. After a save whose mode went from NONE or absent to another, permission not granted:
  P72-33 with "OK"/"Not now", once per view-model instance; `request()` only after "OK", "Not now" requests nothing
  (SPEC12 :928); then `ReminderReconcile` once when the loan is new or its date or mode moved (never for notes or the
  lent date); then `saved`.
- **C18, return and relink (AC 8, 11).** "Mark returned" → a dialog titled P72-34: "Returned on" (P72-35, today), "Mark
  returned" / "Cancel"; refusals "Enter a date as YYYY-MM-DD", P72-36, S25; success → `ReturnLoan`, then
  `ReminderReconcile` once. "Choose from Contacts" on an open loan → the picker → `RelinkLoanContact`, no form; P72-30,
  P72-45, P72-46 as snackbars, anything else P72-32; no sweep (the hash does not move).
- **C19, the Assets list (AC 14; #71).** One `observeOpen` read joined by asset id; `StatusBadge` P72-1 or P72-2 with a
  new drawable (`ic_outbox`, `ServiceTagIcons.Outbox`) and `contentDescription = label`; in the #71 `FlowRow` after
  Retired, Out of season and the archived label, before condition and health; the row's guard (`:339`) admits a
  loan-only row; tones per R72-22; no filter chip.
- **C20, the Dashboard (R72-14 b).** After ATTENTION's four ratified tiers: one row per open **overdue** loan of an
  **in-service** asset, by `dueOn`, asset name, id — the asset name, P72-2 with the glyph, P72-44; tap → the detail.
  Hidden under any status or condition chip; the category filter applies. A retired or archived asset's overdue loan
  notifies (R72-10) but never appears here (R72-14). `AttentionKind`, `AttentionReadModel` and `/v1/attention`
  unchanged; the §10.2 amendment is additive.

**API, MCP and docs (B4).**
- **C21, API (R72-11, 15, 16).** `GET /v1/assets/{id}/loans` → `{loans}` (repository order; the twentieth sub-resource);
  `GET /v1/loans/{id}` → `{loan}`; `POST /v1/loans {assetId, borrowerName, lentOn, dueOn, reminderMode, notes}` → 201
  `{loan}`; `PATCH /v1/loans/{id} {lentOn, dueOn, reminderMode, notes}` (full replace) → `{loan}`; `POST
  /v1/loans/{id}/return {returnedOn}` → `{loan}`. `LoanDto {id, assetId, borrowerName, contactLinked, lentOn, dueOn,
  reminderMode, returnedOn, notes, createdAt, updatedAt}`: no loan route returns or takes the URI (archives carrying it
  still cross `/v1/import-merge/*` as data, N7). One family `loan_validation` on the house shape
  (`ValidationRefusals.kt:186-189`: `problems` by domain name, `message` and `field` of the first; rows in C23),
  `ContactLinkInvalid` an unreachable arm (no `else`, `:25-27`); 409 `asset_already_lent`, 409 `loan_returned`; 404
  `no_such_loan`; an unknown `reminderMode` → 400. `/v1/status` counts gain `assetLoans`; `command-shapes.json` gains
  `loan` and `loanReturn`. Handlers never reconcile, delete, relink or touch contacts.
- **C22, MCP (63 → 68).** `list_loans(asset_id)`, `get_loan(loan_id)`, `lend_asset(asset_id, borrower_name, lent_on,
  due_on=None, reminder_mode="NONE", notes="")`, `return_loan(loan_id, returned_on)` and `update_loan(loan_id,
  lent_on=None, due_on=None, reminder_mode=None, notes=None, clear_fields=None)` — an overlay on the
  `update_service_case` pattern: `_read_for_write` through `GET /v1/loans/{id}`, the arguments laid over the vendored
  `loan` keys less `assetId` and `borrowerName`, omitted or `null` preserving (`README.md:195-199`), `clear_fields` out
  of band (`due_on`, `notes`), one full-replace PATCH. `_MIN_LOAN_SCHEMA_VERSION = 13` with `_require_loan_schema(tool)`
  beside the warranty and case helpers (`server.py:156, :162, :229-236`), called by all five by name;
  `_MIN_SCHEMA_VERSION` stays 8. `command_shapes.py` vendors `loan`; the `import_merge` docstring and README say
  "formats 1–13", "eighteen tables"; bundle and schedules tools untouched.
- **C23, docs.** `v1.md`: the five rows; the 405 row gains `/v1/loans`, `/v1/loans/{id}`, `/v1/loans/{id}/return`; "What
  has no endpoint" gains `DELETE /v1/loans/{id}`, relinking and any contact read; the full-replace paragraph
  (`returnedOn` only through `/return`, borrower and link never, a mode without `dueOn` refused — N10); the
  settle-at-next-sweep sentence; an overdue loan is not an attention row; `ASSET_ALREADY_LENT` in the reason table and a
  loan paragraph beside the case one (`:1334-1338`); 1–13, "13 since #72 (loans)", eighteen tables, twenty
  sub-resources; **the `loan_validation` table** (422), ten rows: `BorrowerRequired` (`borrowerName`), `BadDate` for
  each of `lentOn`, `dueOn`, `returnedOn`, `LentAfterToday` (`lentOn`), `DueBeforeLent` (`dueOn`),
  `ReminderWithoutDueDate` (`reminderMode`), `ReturnedBeforeLent` and `ReturnedAfterToday` (`returnedOn`),
  `ContactLinkInvalid` (none, unreachable), messages in the case family's words ("lentOn may not be later than today").
  Amendments `**Amendment (#72, <the ratification date>)**`, P72 ids plan-local: SPEC12 (B2) §2.5, §5.1, inv. 44 ("… and
  the open loans' due dates"), inv. 53 (four), §9.1 (quoting P72-23, 33, 38–43); SPEC14 (B4) §2 (the eighth concept,
  **Custody** — who has it, due back when — "—" against C1–C7), §10.2 (C20), §10.3 (C16), §10.4 (C17, C18), §10.7 (the
  P72 strings).
- **C24, #77 and #86.** #77 decides what an open loan does to a transfer, takes a transferred asset out of
  `BuildLoanSubjects` (C26's shape) and never reuses a loan row; #86's successor inherits no loan.

**The grant proof (B3; R72-24).**
- **C25, one foreign-UID boundary.** `:share-test-sender` gains `PickerActivity` (exported, explicit-only) and
  `uses-permission` `READ_CONTACTS`, `WRITE_CONTACTS` (its "holds no permission" comment and its `README.md`'s "What it
  must not become" (:80) and command list rewritten by B3), with `pick_contact` (seed its fixture — an
  organisation-only, account-less raw contact "Example Rentals Ltd", by `ContentResolver.applyBatch` from its own UID —
  then `RESULT_OK` with the lookup URI and `FLAG_GRANT_READ_URI_PERMISSION`), `pick_contact_no_grant` (no flag) and
  `forget_contact`. `ContactGrantBoundaryTest` grants the sender its permissions by
  `UiAutomation.grantRuntimePermission` (no shell string carries a name), asserts ServiceTag's UID holds no
  `READ_CONTACTS`, aims C15's seam at `PickerActivity` in an activity of ServiceTag's process and calls `launch()` —
  nothing chosen, typed or pressed. **The boundary:** another UID's one-shot provider grant suffices to read the name.
  Case 1, first (ShareBoundaryTest's ordering): no grant → `Unreadable`; case 2: `Picked("Example Rentals Ltd", the
  codec's URI)`; `@After` `forget_contact`. Feasible on emulator-5554: the platform provider needs no account and
  declares `grantUriPermissions`; the grant covers `com.android.contacts` only, so ShareBoundaryTest's no-grant case
  stays valid. If the platform refuses, B3 reports it and the boundary stays unproven — no UI-driven substitute. R4
  names both classes (five cases, ≤ 100 s).

## 3. Test matrix (hazards; the briefs fix names)

| hazard | test (class · case) | RED mutation |
|---|---|---|
| **B1** 1. standing (C1, AC 13) | core `AssetLoanTest` · `theDueDayIsLentOutAndTheNextDayIsOverdue`; `aReturnedLoanHasNoStanding`; `noDueDateIsNeverOverdue`; `theLoanModelImportsNoScheduleOrHealth` (source scan) | `>=` for `>`; map OVERDUE to `DueStatus` |
| 2. lend (C2 i, C3) | core `LoanUseCasesTest` · `lendWritesOneOpenRow`; `aSecondOpenLoanIsAssetAlreadyLent` (`commits == 0`); `aReturnedLoanDoesNotBlockTheNext`; `problemsAreReportedTogether`; `aModeWithoutADueDateIsRefused`; `aRetiredOrArchivedAssetIsAccepted`; `aLinkFailingTheRuleIsRefused`; core `ContactLookupUriTest` · the rule takes `…/lookup/<key>` and `…/lookup/<key>/<id>`, refuses another authority, `/contacts/<id>`, a query, a fragment, a `/` in the key | drop the open check; fail fast; accept `content://media/…` |
| 3. update, return, relink (C3, AC 8) | · `anUpdateNeverMovesAssetBorrowerLinkOrReturnedOn`; `anEqualCommandWritesNothing`; `returnKeepsTheRowWithItsDate`; `returnedBeforeLentOrAfterTodayIsRefused`; `aReturnedLoanRefusesUpdateReturnAndRelink`; `relinkReplacesLinkAndSnapshotTogether`; `anEqualRelinkWritesNothing` | delete on return; keep the old snapshot |
| 4. write gate (C4) | `CrossConceptWriteTest` + four entries; `LoanUseCasesTest.noLoanWriterTouchesEventConditionScheduleOrHealth` | write an event on return |
| 5. the index (C2 ii, C5) | app `AssetLoanRoomTest` (new) · `aSecondOpenRowForOneAssetAbortsAtTheIndex`; `returnedRowsForOneAssetCoexist`; `theMarkerIsOneOpenAndNullReturned`; `deletingTheAssetCascadesItsLoans` | marker 0 when returned; drop `UNIQUE` |
| 6. schema 13 (C5) | `Migration12To13Test` · `everyRowSurvives`; `theLoanTableArrivesEmpty`; `theDeadlineStampTableIsUnchanged`; `theMigratedSchemaEqualsAFreshVersion13` | drop the CASCADE; drop the chain entry |
| 7. format 13 (C6) | `BackupFormat13Test` (new) · `openAndReturnedLoansRoundTripFieldForField` (link null and set); `theManifestCountsCarryAssetLoans`; `aShuffledArchiveEncodesToTheSameBytes`; `aFormat12ArchiveWithALoanIsRefused`; `…WithEmptyListsRestores`; `anUnknownModeIsRefused`; `theArchiveCarriesNoMarkerOrStanding` | skip the ≤ 12 check; leave the list unsorted |
| 8. graph and content checks (C2 iii, C6) | `BackupCodecTest` · `twoOpenLoansForOneAssetAreCorrupt`; `oneOpenAndManyReturnedLoansDecode`; `aLoanWhoseAssetIsNotInTheArchiveIsCorrupt`; `BackupContentCheckTest` · blank borrower; due < lent; returned < lent; a mode without a due date; a link failing the rule; `aLoanIdHoldingASlash` | drop each |
| 9. merge (C7, AC 11; K3) | core `MergePlannerLoanTest` (new; every case from a decoded archive) · `anEqualLoanIsIdentical`; `returnedHereOpenThereConflicts` (both directions); `aRedatedOrRelinkedLoanConflicts`; `anIncomingOpenLoanMeetingADifferentLocalOpenLoanIsAssetAlreadyLent`; `aReturnedIncomingLoanInsertsBesideALocalOpenOne`; `anUnavailableAssetIsOwnerNotAvailable`; `anAssetThisPlanInsertsCarriesItsLoan`; `theReportTalliesLoans`; `ImportBackupMergeTest` · `loansLandAfterAssets`; `MaintenanceRoutesTest` · `aLoanIsTalliedOnTheWire` | an UPDATE path; compare with the archive instead of the store |
| 10. replace, export, empty | `ImportBackupReplaceTest`, `ExportBackupSetTest`, `StoreIsEmptyTest` · loans carried; the empty rule unchanged | forget the list |
| 11. **B1 retargets** — 12 → 13, `MergeTable` 17 → 18, counts 23 → 24 | `Format7RestoreContractTest.reExportIsFormat12AndPlansIdentical` (:157; 13, still IDENTICAL); `VersionAgreementTest.theSchemaIsTwelveAndTheFormatIsTwelve` (:78, renamed) and `statusEchoesTheVersionsTheBuildCarries` (:114); `MaintenanceRoutesTest.statusReports12And12AndTheNewCounts` (:1418, versions only), `importMergeReadsFormat12AndReportsSeventeenTables` (:1450) and the wire-mirror order (:1269, `takeLast`; `loans` after `caseEntries`); `BackupCodecTest.theFormatIsTwelveAndTheLegacyBoundaryStaysSeven` (:1041); `BackupFormat8Test`'s format-past-this-build case (found/supported 13/12 → 14/13, :277-280) and `counts.size` (:308, 23 → 24); `BackupFormat7Test` (:212, 17 → 18 tables); `MergePlannerSeasonHealthTest.theSeventeenTablesAreInTheirPinnedOrder` (:132) and `MergePlannerMaintenanceTest`'s literal list → eighteen; `MergePlannerReferenceTest`'s report-order case; `ExportBackupSetTest`, `Format7ImportIdentityTest`, `ReferenceMigrationTest`'s table subtraction, `StageABundleConformanceTest` (:85, "fourteen of its seventeen" → eighteen; the format-5 bundle emits no loans) — each keeping every other assertion | — |
| **B2** 12. port pins (C8) | `ReminderPortContractTest` · `theDeadlineKindsAreExactlyWarrantyExpiryAndLoanDueBack`; `theRepeatFactsAreExactlyOnceAndUntilCleared`; `aScheduleSubjectHasNoRepeatAndADeadlineSubjectHasOne` (both repeats); unchanged: `theReminderPortNamesNoAndroidTypeAndNoDeliveryMechanism` (members `[Schedule, Deadline]`), `nothingInCoreEverBuildsACompletedSubjectOnItsOwn` | add a `SubjectKey` member; build a Completed loan |
| 13. hashes, tags, codec (K2, K6) | `ContentHashTest` · `aWarrantySubjectsHashIsTheBaseValue` (hex at `<base>`); `theModeMovesALoansHash`; `DigestPolicyTest` · `everyKeyRoundTripsThroughItsTag` + a loan key; `aLoanTagNeverReadsAsAWarrantyOrASchedule`; `LoanSubjectIdTest` · round trip; an asset id holding `/` round-trips; no `/` or an empty side → null | split at the first `/` |
| 14. subjects (C9) | core `BuildLoanSubjectsTest` (new) · every field for Once and Until returned; `absentWhenReturnedWithoutADueDateOrNone`; `aRetiredOrArchivedAssetsOpenLoanIsStillASubject`; `aLoanLongPastItsDueDateIsStillASubject`; `sortedByLoanId`; `itReadsNoClockAndNoAsset` | gate on in service; end at the due date |
| 15. facts (C10; M4) | `DeadlineDeliveryFactsTest` · `aLoanFactNamesItsAssetBorrowerAndItsDigestHourInstants` (a fixed non-UTC zone; digest hours 9 and 7; digest hour 2 on a spring-forward day in `America/New_York`, whose 02:00–03:00 gap moves both instants to 03:00); `aReturnedOrGoneLoanHasNoFacts`; the warranty case unchanged | midnight for the digest hour; the instants in UTC |
| 16. Once (C11, AC 6; R72-6) | `DigestPolicyTest` · `aLoanOnceWaitsForTheDigestHourOnItsDueDay` (due day 00:05 none, 09:00 one); `aFirstSweepDaysLaterPostsIt`; `aLoanOnceStillStandsAfterItsDueDay`; `aSwipedLoanOnceNeverReturnsAfterARestart`; `aLoanOnceARestartTookDownStaysDown` (under R72-6 a) | route a loan through the warranty's `once` (+1: its tag leaves the kept set); apply R79-14c's boot rule to a loan |
| 17. Until returned (C11, AC 7; R72-7; K5) | · `untilReturnedRealertsOnceAPeriodAtTheDigestHour` (sweeps at 00:05, 09:00, 15:00 from due −1 to due +3: one post a day, each at 09:00); `aSweepAt0005IsQuietAndThe0900SweepRealerts`; `yesterdaysStandingPostIsRepostedAndCountedUnchanged`; `aSwipeIsQuietUntilTheNextDigestHour`; `aMutedChannelPostsAndStampsNothingAndKeepsAStandingPost`; `anotherBootRepostsOnceInThePeriod` | a calendar-day gate (00:05 re-alerts); a 24 h millis gate; count a re-alert as posted; drop a standing post when muted |
| 18. withdraw, re-date (AC 8, 9) | `LocalReminderProviderTest` · `aReturnedLoanIsTakenDownAndForgotten`; `aMovedDueDateOrModeCancelsTheOldTagFirst`; `silenceTakesLoanPostsDownAndForgetsThem`; `aLoanPostWritesOneDeviceLocalRowAndNothingElse` | keep the stamp after return |
| 19. words, never maintenance (AC 13) | `DigestPolicyTest` · `theLoanTitleBodyWordChannelAndOneOpenAction` (P72-40/42 through the due day, P72-41/43 after); `aLoanNeverCountsInTheMaintenanceSummary`; `theScheduleBranchNeverCancelsAStandingLoanPost`; `aLoanWordIsNeverTheBareOverdue` | use `WORD_OVERDUE`; count it |
| 20. Open (C12) | `QuickActionsTest` · `aLoanOffersOpenOnlyAimedAtItsAssetWithNoNonce` (label for label with `ItemPost.actions`) | aim at the loan id |
| 21. channels (C13) | `NotificationChannelsTest.exactlyThreeChannelsWithTheirRatifiedShape` → four (the three as before, plus `loan_reminders` P72-38/39 at `IMPORTANCE_DEFAULT`) | omit it |
| 22. warranty identity (C14) | `DigestPolicyTest` · `theWarrantyDayMatrixIsTheBaseMatrix`; every shipped warranty case unchanged | put the warranty on the loan's open-ended step |
| 23. Android contract (C10, C11) | device `ReminderPlatformDeviceProofTest` · `theGraphsSweepPostsALoanPastItsDueDay` (a loan due yesterday seeded through the repository, so any digest hour has passed; `app.graph.reminderRuns.reconcileAll()`; a `LOAN_DUE_BACK:<asset>/<loan>\|…` tag read back); `aLoanPostHasOneImmutableOpenActionAimedAtItsAsset`; `returningTheLoanThenSweepingTakesItDown`; the channels case → four | drop the loan builder from `subjectsFor` |
| 24. **B2 retargets**; health and manifest kept | row 21; the device channels case `theThreeChannelsExistAtTheirImportancesAfterAFirstLaunch` (:62) → four; row 12's two pins; `DeadlineDeliveryFactsTest`'s constructions (C10); `ReminderHealthCheckTest`, `ReminderHealthViewModelTest`, `ManifestContractTest` unchanged and green | — |
| **B3** 25. codec (C15; M3) | app `ContactLinkTest` · `lookupUriOfAppendsTheKeyVerbatim` (a table: `0r1-ABC`, `a.b` and `x%2Fy` appear unchanged; `a/b`, `a b` and a non-ASCII key → null; an empty key → null); `theOutputMatchesTheRule` | encode the key (the `x%2Fy` row catches it); use `_ID` alone |
| 26. reader, opener (C15) | app `PickedContactReaderTest` (fake seam) · `aPersonsNameAndLookupUri`; `aCompanyOnlyContactReadsItsOrganisation` ("Example Rentals Ltd"); `aBlankNameIsNoName`; `noRowASecurityExceptionOrABlankKeyIsUnreadable`; `ContactOpenPolicyTest` · `onlyARuleMatchingUriIsLaunched`; `noHandlerSaysP72_20` | map a `SecurityException` to `NoName`; launch any URI |
| 27. codec on device (C15) | device `ContactLinkContractTest` (new, framework contract, no permission) · `theCodecEqualsGetLookupUri` (only the row-25 keys the codec accepts, plus the seeded contact's real key read through the sender, against `Contacts.getLookupUri(id, key).toString()`; no blank row is compared, since `getLookupUri` does not treat a blank key as null) | encode the key |
| 28. grant boundary (C25, R72-24) | device `ContactGrantBoundaryTest` (new) · `aPickWithoutAGrantIsUnreadable` (first); `aPickFromAnotherUidIsReadThroughItsOneShotGrant` | query `…/contacts/<id>`, which no grant covers |
| 29. section state (C16) | app `AssetLoansStateTest` (new) · `noLoanInServiceOffersLendOut`; `noLoanOutOfServiceDrawsNoSection`; `theOpenBlockFollowsTheStanding` (due day, +1, no date); `theReminderLineOnlyWithADateAndAMode`; `aNameOnlyLoanSaysNoContactLinked`; `aRetiredAssetsLoanCanStillBeReturned`; `historyNewestReturnedFirstWithNoActions`; `thePlateCarriesLentOrOverdueAfterTheLifecycleFacts` | `>=`; hide the block out of service |
| 30. editor (C17) | app `LoanEditViewModelTest` (new) · `aNewLoanNeedsAPickedBorrower`; `aNamelessPickSaysP72_30`; `aReadFailureSaysP72_46`; `chipsNeedADueDateAndClearingItResetsToNone`; `refusalsLandOnTheirFields`; `aStaleFormSaysP72_37`; `aDoubleTapWritesOnce`; `cancelWritesNothing`; `theRationaleThenRequestRunAfterTheWrite` (fake permission: never when granted, never for None, once per instance); `notNowRequestsNothing`; `aNewLoanOrMovedDueOrModeSweepsOnceAndNotesNever` | request before the write or after "Not now"; sweep on notes |
| 31. return, relink (C18) | app `LoanReturnTest` (new) · `returnWritesThenSweepsOnce`; `refusalsOnReturnedOn`; `relinkReplacesNameAndLinkWithNoSweep`; `relinkOnlyOnTheOpenLoan`; `aRelinkReadFailureSaysP72_46` | no sweep after return |
| 32. on device (C15–C18) | device `LendingSectionDeviceTest` (new) · `lendThroughAFakePickerShowsTheOpenBlockAndPlate` (a fake `ActivityResultRegistry` via `LocalActivityResultRegistryOwner`, a fake reader seam); `markReturnedMovesItToPastLoans`; `theRationaleTextAndNotNowWritesNothingMore` (no system dialog) | — |
| 33. list, dashboard (C19, C20, AC 14) | device `AssetsIndicatorsTest` · `aLentAndAnOverdueAssetShowTheirWords` (the rendered text and the description per R72-23, not colour); `theLoanBadgeFollowsTheLifecycleBadgesAndPrecedesConditionAndHealth`; `aLoanOnlyRowDrawsItsBadge`; `aNarrowLargeFontRowWraps` (the #71 frames, :87-106); app `DashboardFiltersTest` · `loanRowsFollowTheRatifiedTiers`; `hiddenUnderAnyStatusOrConditionChip`; `theCategoryChipFiltersThem`; `onlyOverdueLoansOfInServiceAssets`; device `DashboardAttentionTest` · `anOverdueLoanRowAfterTheRatifiedTiers` | put it before DOWN; show it under a status chip; drop the guard |
| 34. routes, reads, scroll (K4) | `RouteTest` · `theLoanEditRouteRoundTrips`; `ReadPathsWriteNothingTest` · the section's, list's, dashboard's and editor's loads; the scroll-rule re-runs (B3) | upsert on load |
| 35. **B3 retargets** | `ReleaseProofPolicyTest.theExternalBoundaryRowIsShareBoundaryTestAlone` → the R4 row names exactly `{ShareBoundaryTest, ContactGrantBoundaryTest}` (renamed; its class KDoc point 2 updated) | — |
| **B4** 36. API (C21) | app `LoanRoutesTest` (new) · each of the five routes' status and body; `theResponseSaysContactLinkedAndNeverCarriesTheUri` (reflection over `LoanDto` + the body text); the family's problems, messages and fields (C23's table); `aSecondOpenLoanIs409`; `writesToAReturnedLoanAre409LoanReturned`; `aModeWithoutADueDateIs422`; `anUnknownModeIs400`; `aMissingLoanIs404`; `aRetiredAssetCanBeLent`; `noLoanHandlerReconciles`; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` + `DELETE /v1/loans/{id}`; `ApiReadsWriteNothingTest` · both GETs; `ValidationRefusalsTest` · every loan problem | echo the URI; route a delete |
| 37. shapes, MCP (C22) | `CommandShapesGoldenTest.everyRequestDtoMatchesCommandShapesJson` + `loan`, `loanReturn` (update = `loan` − `assetId` − `borrowerName`); pytest `test_loan_tools.py` · `test_the_loan_tools_refuse_a_schema_12_phone_with_nothing_sent` (all five); `test_update_loan_overlays_and_null_preserves`; `test_update_loan_submits_exactly_the_vendored_keys`; `test_clear_fields_takes_due_on_and_notes_only`; `test_no_loan_tool_takes_or_returns_a_lookup_uri` | gate the loan tools on `_MIN_SCHEMA_VERSION` (8), so a schema-12 phone is accepted; `null` clears |
| 38. **B4 retargets** | `CommandShapesGoldenTest.theContractDocumentNamesFormat12AndSeventeenTables` (:99; 1–13, "13 since #72 (loans)", eighteen; banned "1–12", "seventeen tables"); `ReferenceRoutesTest.theApiDocumentAgreesWithTheRouter` (1–13, eighteen tables, twenty sub-resources, :556) and `statusCarriesTheReferenceCountBesideEveryShippedKey` (+ `assetLoans`); `MaintenanceRoutesTest.statusReports…` (the counts); the MCP docstrings; pytest 63 → 68 (`test_argument_guard.py:8, :46, :51, :182, :203-204, :207, :257-258, :326, :341-342`; `test_tools.py:103-107` with `EXPECTED_TOOLS`; `test_reference_tools.py:27, :52-57`; `test_maintenance_tools.py:43`); `test_command_shapes.py:57` (the vendored set + `loan`) | — |

## 4. Lanes, files and collisions

One branch `issue-72` from this plan's ratified commit: B1 → B2 → B3 → B4, each `<base>` the previous accepted tip.
**Files** (indicative; each brief's untouched diff fences the rest): B1 `C/model/AssetLoan.kt`, the loan use cases,
`C/ports/Repositories.kt`, `C/{backup,merge}/*`, the replace/export/apply use cases, `A/data/room/**`, `13.json`,
`A/api/ApiDtos.kt` (the report), `A/di/AppGraph.kt`; B2 `C/reminders/{ReminderPort,BuildLoanSubjects}.kt`, five
`A/reminders/` files, `AppGraph.kt`, SPEC12; B3 new `A/contacts/*` and `A/ui/loan/*`,
`A/ui/asset/{AssetViewModels,AssetDetailScreen,AssetsScreen}.kt`, `A/ui/dashboard/*`,
`A/ui/nav/{Route,ServiceTagRoot}.kt`, `ServiceTagIcons.kt` + `ic_outbox.xml`, `AppGraph.kt`, `share-test-sender/**`, the
androidTest sender helper, `ReleaseProofPolicyTest`, the R4 row of `docs/release-proofs.md`; B4 new
`A/api/{LoanDtos,LoanHandlers}.kt`, the router, DTOs, refusals and status handler, `docs/api/*`,
`tools/servicetag-mcp/**`, the gate paragraph, SPEC14. **Collisions.** #79 is merged; no lane is live. B2 edits #79a
A2's seam files; B3 edits `AssetDetailScreen.kt` (1,386 lines), `AssetViewModels.kt` (2,545), `Route.kt` and
`ServiceTagRoot.kt` beside #79's and #82's work; `AppGraph.kt` in B1–B3; `docs/release-proofs.md` in B3 (R4) and B4 (the
gate). "Lend out" sits above Condition on every in-service asset, so it moves shipped device taps below the fold on
almost every fixture (K4, P79 §18): B3 re-runs every detail device class. **#77 (future)** collides with
`BuildLoanSubjects`, the lifecycle use cases, the overflow and backup/merge — it reads C24 first; **#86** touches only
the detail screen; #84's tap sweep takes B3's findings.

## 5. Schema, backup, merge and API implications (summary for the owner)

Room 12 → 13, one cascading table (C5); format 13 carries `assetLoans`, the lookup URI included (C6, R72-4); eighteen
merge tables, `ASSET_ALREADY_LENT`, **no UPDATE** (C7, R72-18); five name-only routes, no URI, no delete, settling at
the next sweep (C21); MCP 63 → 68. **Release gate (R72-21):** `docs/release-proofs.md:88` becomes the direct **1.4.1
(schema 8) → schema 13** proof on `emulator-5554`: #74's, #67's and #79's steps as written with these 12s moved to 13
and "#72's loans at schema 13" beside the kept "service cases at schema 12" (the title's "schema 12 / backup format 12",
"`schemaVersion` 12, `backupFormatVersion` 12", "the format-12 round trip", "Master builds carry schema 12"), plus:
`counts` gain `assetLoans: 0`; the post-upgrade export carries `"assetLoans": []`; the pre-upgrade export re-plans
applicable, zero INSERT, every row IDENTICAL; through the API, lend asset A with a due date and Once, lend asset B then
return it; the pre-upgrade export re-plans the same again; a fresh format-13 export re-plans IDENTICAL and restores by
replace and by merge into an install without the loans (`loans` insert 2). No contact is picked in the gate.

## 6. Strings — for ratification

| id | where | text | treatment |
|---|---|---|---|
| P72-1 | plate, list row, open block | `Lent out` | `StatusBadge` (drawn upper-case; letter case per R72-23) + loan glyph |
| P72-2 | same after the due day; dashboard row | `Loan overdue` | same component; never the bare `OVERDUE`, its tone or icon |
| P72-3 | detail section | `Lending` | `SectionHeader` |
| P72-4 | open block; history row; opens P72-44 | `Lent to <name>` | body line; one home, `lentTo(name)` |
| P72-5 | open block; history row | `Lent <date>` | `QuietLine`; `d MMM yyyy` |
| P72-6 / P72-7 | open block | `Due back <date>` / `Was due back <date>` (with P72-2) | body line |
| P72-8 / P72-9 | open block: no due date / Once | `No due date` / `Reminder: when it is due back` | `QuietLine`; P72-9 on P79-6's shape |
| P72-10 C | open block, Until returned (R72-7) | `Reminder: every day until returned` | `QuietLine` |
| P72-11 / P72-17 | open block, with a link / name-only | `Open contact` / `No contact linked` | `OutlinedButton` / `QuietLine` |
| P72-19 C | under Open contact (R72-4) | `If the contact does not open, choose it again.` | `QuietLine` |
| P72-18 | editor Borrower; open block relink | `Choose from Contacts` | `OutlinedButton`; `TextButton` |
| P72-12 | open block; return dialog confirm | `Mark returned` | `Button` |
| P72-13 | open block; editor title (edit) | `Edit loan` | `TextButton`; top bar |
| P72-14 | section action; editor title (new) | `Lend out` | `OutlinedButton`; top bar |
| P72-15 / P72-16 | history label / row | `Past loans` / `Returned <date>` | `FieldLabel` / `QuietLine` |
| P72-20 / P72-45 | open failure / no picker (P72-45 the planner's) | `No app can open this contact` / `No app can pick a contact` | snackbars (`LinkLauncher`; `NO_APP_CAN_PICK_FILES`'s form) |
| P72-21…24 | editor labels | `Borrower` · `Lent on` · `Due back` · `Reminder` | labels; `Due back` is also the notification `<title>` |
| P72-25, 26 | reminder chips | `Once` · `Until returned` | chips after the reused `None` |
| P72-27 / P72-28 | under the chips, no due date / save | `Add a due date to get a reminder.` / `Save loan` | supporting text, chips disabled / `Button` |
| P72-29 / P72-30 | refusals: new loan / nameless pick | `Choose a borrower` / `This contact has no name to show.` | field errors; snackbars on relink |
| P72-46 | refusal, the pick could not be read (review) | `Could not read this contact.` | field error on a new loan; snackbar on relink |
| P72-31 / P72-36 | refusals | `The due date cannot be before the day it was lent.` / `The return date cannot be before the day it was lent.` | field errors |
| P72-32 / P72-37 | save failure (logged) / stale form | `Could not save this loan.` / `This asset is already lent out.` | snackbars |
| P72-33 | after a save that first sets a reminder | `ServiceTag needs notification permission to remind you when a loan is due back.` | dialog; reused "OK" / "Not now" |
| P72-34 / P72-35 | return dialog | `Mark returned?` / `Returned on` | title / date label |
| P72-38 / P72-39 | channel `loan_reminders` | `Loan reminders` / `Reminders when a lent item is due back.` | system settings |
| P72-40 / P72-41 C | notification body through / after the due day (R72-7) | `Lent to <name>. Due back <date>.` / `Lent to <name>. Was due back <date>.` | body; `d MMM uuuu`; homes in `DigestPolicy.kt` |
| P72-42 / P72-43 C | status word through / after the due day (R72-7) | `DUE BACK` / `NOT RETURNED` | `setSubText` |
| P72-44 C | dashboard row (R72-14 b) | `Lent to <name> · due back <date>` | line under the asset name, with P72-2; composed as `lentTo(name)` + `" · due back "` + date |
| — | reused verbatim (10) | through homes: `Not now` (`NOT_NOW`, `MaintenanceSheetViewModel.kt:56`), S25 `The date cannot be later than today.` (`DATE_NOT_LATER_THAN_TODAY`, `ChangeConditionViewModel.kt:33`; Lent on, Returned on), `None` (`NONE`, `ScheduleEditScreen.kt:86`), `Open` (`ACTION_OPEN`, `DigestPolicy.kt:40`); inline on the #79 editors' precedent: `Enter a date as YYYY-MM-DD`, `Notes`, `OK`, `Cancel`; and the shape `<asset> — <title>`, the display date `d MMM yyyy` / `d MMM uuuu` | — |

**46 new ids** (44 from the audit, P72-45 the planner's, P72-46 the review's), **5 conditional** (P72-10, 19, 41, 43,
44), **10 reused**; none accessibility-only. **Collisions:** no bare `Returned` (P79-41); `Open`, `None` and `Due back`
keep two anchored homes each; `"Lent to $` opens one literal under `A/ui` (P72-44 composes it) and two in
`DigestPolicy.kt`; `Lent out` and `Lend out` differ by a letter; `Reminder` and `Reminder: …` anchor differently.

## 7. Rulings and owner questions (controller, 2026-09-28)

- **R72-1 (recommended: A, `asset_loan`).** B (events + references) has no due date, mode, return date or one-open rule
  (`Journal.kt:55-75`; no lending `EventKind`; a lookup URI is no reference scheme, `LinkLaunchPolicy.kt:51`) — R79-1's
  verdict. **Owner: A or B** (B voids these briefs).
- **R72-2 (recommended: four layers, C2);** alternative the use case only. **Owner.** **R72-5 (recommended).** Relink
  replaces link and snapshot together; returned loans frozen. **Owner.**
- **R72-3 (recommended).** The phone requires a picked contact; its display name is the snapshot, not editable; API
  loans are name-only, linkable later on the phone. Alternatives: a typed name; an editable snapshot. **Owner.**
- **R72-4 (recommended: the canonical `contact_lookup_uri`, exported).** A synced contact may open on another phone; a
  local-only one falls to Contacts' "not found"; the snapshot always renders and "Choose from Contacts" relinks (AC 11);
  "unavailable" is undetectable without `READ_CONTACTS`, so P72-19 sits under "Open contact" (recommended active).
  Disclosure: a lookup key can embed a raw-contact id and a normalised name — in exports, never on a loan route.
  Alternatives: a device-local link table (every link lost on restore) or a hybrid. **Owner: storage; P72-19.**
- **R72-6 (recommended: a).** A loan's Once has no window end while open, so R79-14c's restart re-post would bring a
  swiped Once back after **every** restart — monthly OS updates included — for the life of the loan. **(a) Strictly once
  per due occurrence:** one post at the first sweep at or after the digest hour on the due day (or later, if the phone
  was off), standing until swiped or returned; a swiped Once never returns and one a restart took down stays down, while
  the plate, list and Dashboard still say "Loan overdue"; AC 6 read literally; the shipped stamp suffices
  (`announced_hash`; `announced_boot` ignored), no column; re-dating re-creates it. **(b) Bounded re-post:** a restart
  re-posts once, only within the due day's digest period. The warranty keeps R79-14c either way. **Owner: a or b.**
- **R72-7 (recommended: the digest-hour period).** `DateChangedReceiver` sweeps at each local midnight, so a
  date-anchored loan would post Once, and audibly re-alert Until returned every night, at about 00:00 on an
  `IMPORTANCE_DEFAULT` channel. Recommended: a loan's day runs from the owner's digest hour (09:00 by default) — Once at
  the first sweep at or after the digest hour on the due day; Until returned re-posting, and re-alerting in place while
  the last post stands (the OVERDUE precedent), at the first sweep at or after each later digest hour and once more
  after a restart within a period; computed in the facts, no column; after the due day the body and word switch to
  P72-41 / P72-43. The warranty keeps its shipped midnight entry (disclosed). Alternatives: calendar days (the 00:00
  cost above); every three days (D-5); re-alert only after a swipe; one body and word throughout (P72-41, P72-43 out).
  **Owner: the period; re-alert; post-due forms.**
- **R72-8 (recommended: "Open" only).** "Returned" needs nonces and a receiver branch and dates the return today.
  **Owner.** **R72-9 (confirm).** Its own `loan_reminders`, default importance, inv. 53 → four — foreseen by R79-14b
  ("#72 then adds its own"). **Owner: confirm.**
- **R72-10 (recommended).** Reminders continue for a retired or archived asset while its loan is open — custody is
  independent of service. Alternative: in service only (the warranty precedent). **Owner.** **R72-11 (recommended).**
  "Lend out" in service only on the phone; the API accepts any lifecycle (R79-3's reading). Alternative: refuse
  everywhere (a 409 and a sentence). **Owner.**
- **R72-12 (recommended).** Archive, retire and delete allowed with an open loan; it stays open, shown and returnable;
  delete cascades under the unchanged confirm. Alternative: refuse retire/archive while lent. **Owner.** **R72-13
  (recommended: none).** Lending is the eighth concept, "—" against C1–C7: a lent asset keeps schedules, reminders,
  condition, health and season; an overdue loan is never maintenance OVERDUE (AC 13). **Owner: confirm.**
- **R72-14 (recommended: b).** (a) `AttentionKind.LOAN` in the projection and `/v1/attention` re-opens the ratified
  order and changes an enum clients branch on (K8); (b) a dashboard-only row after the four ratified tiers (C20), hidden
  under any status or condition chip, `/v1/attention` unchanged — an additive §10.2 amendment, P72-44 active; **with
  R72-10, a retired or archived asset's overdue loan notifies but never reaches the Dashboard** (§10.2 shows in-service
  rows only); (c) no entry, leaving "eligible for attention" unmet. **Owner.**
- **R72-15 (recommended).** API writes settle at the next digest or the 12-hour backstop (R79-15); in-app lend, edit and
  return sweep at once, so AC 8's "immediately" holds on the phone. Alternative: the loan handlers reconcile (a new
  precedent). **Owner.**
- **R72-16 (recommended).** Five routes (`GET /v1/loans/{id}` included), five tools (63 → 68), `LoanDto` with
  `contactLinked` and never the URI, `update_loan` an overlay honouring the README's null rule. Alternatives: four
  routes and a whole-state `update_loan` (a strict client sending `null` for every unset argument clears a due date on a
  notes edit); the archive DTO (echoes the URI); read-only. **Owner.**
- **R72-17 (recommended).** "Mark returned" is the only exit, no delete (R79-9); "Edit loan" corrects an open loan's
  lent date, due date, mode and notes. Alternative: a phone-only "Delete loan" (merge cannot carry a deletion).
  **Owner.**
- **R72-18 (recommended).** Returned loans are frozen, notes included. **Acknowledged (K3):** every routine return,
  re-date or relink on one phone makes the other phone's archive non-applicable at the next dev↔production merge
  (`MergePlan.kt:445`) until resolved on the phone. **Owner: confirm, with the acknowledgement.** **R72-19
  (recommended).** Dates only: `lent_on` ≤ today, `due_on` ≥ `lent_on`, `lent_on` ≤ `returned_on` ≤ today; alternative a
  return time. **Owner.**
- **R72-20 (recommended).** A returned loan is absent; the `Completed` pin stays; C26's pin change is declined. **Owner:
  confirm.** **R72-21 (recommended).** No `versionName` bump; 13 rides master under 1.4.1, emulator only; the vehicle is
  the next feature-bearing MINOR with #79; the gate is the direct 8 → 13 proof (§5). **Owner: confirm.**
- **R72-22 (recommended).** The plate badge and the Lending section right after Warranty, before Condition; "Lend out"
  in the section; the badge after the lifecycle badges (C16, C19); both badges word + loan glyph in the neutral
  `seasonInactive` tone, never OVERDUE's or DEGRADED's. Alternatives: after Service cases; "Lend out" in the overflow;
  an amber "Loan overdue". **Owner: placement; tones.**
- **R72-23.** Ratify P72-1 … P72-46 with their treatments; conditionals per R72-4 (P72-19), R72-7 (P72-10, 41, 43),
  R72-14 (P72-44). (i) **Letter case:** `StatusBadge` draws `label.uppercase()` and describes the literal
  (`StatusBadge.kt:40, :46`) — mixed-case `Lent out` / `Loan overdue` like `RETIRED` (recommended: LENT OUT on screen,
  "Lent out" to TalkBack) or upper-case like `IN_WARRANTY_WORD`. (ii) **Overdue vocabulary:** "Loan overdue" (badges),
  "Was due back" (lines), "NOT RETURNED" (notification) — recommended three, one per surface; or `LOAN OVERDUE` as the
  word. (iii) P72-45 `No app can pick a contact` (recommended, the shipped "No app can pick files" form) or `No app can
  choose a contact`.
- **R72-24 (recommended).** The contact boundary is proven once, at level 4: `ContactGrantBoundaryTest` (C25), a seeded
  contact handed over by another UID with a one-shot grant and read without `READ_CONTACTS`; everything else in process
  (rows 25–27, 30–32); Contacts' UI never driven. Cost: contacts permissions on the test-only sender and a second R4
  class (the policy pin moves); the testing hierarchy (`planning-policy.md:74-76`) amended by the controller at
  ratification. Alternatives: fakes only; plus an owner-run release row. **Owner.**
- **Risks.** K1 → C11, rows 16, 22; K2 → C8, rows 13, 22; K3 → C7, row 9, R72-18; K4 → §4, B3's re-runs; K5 → R72-7, row
  17; K6 → row 13; K7 → C13, R72-9; K8 → R72-14.

## 8. What this plan does not do, and records

No `SubjectKey` member, `ProviderId`, `Completed` producer, stamp column, deadline nonce, snooze or write action; no
loan delete, typed borrower, `READ_CONTACTS` in ServiceTag, `<queries>` entry or contact enumeration; no `AttentionKind`
member, `/v1/attention`, filter-chip, scan-sheet, health or warranty-delivery change; no transfer (#77), successor
(#86), `versionName` bump or phone. **Records:** C26's delivery columns and Completed pin change are not built.

## Briefs — common to all four

Read §1–§8, the issue, the audit, the brief review and every earlier brief's report. **Gate:** `./gradlew :core:test
:app:testDebugUnitTest --rerun` zero failures/skips; `:app:compileDebugAndroidTestKotlin`; before the first connected
class `tools/emulator/prepare-emulator.sh`, then one class per run: `ANDROID_SERIAL=emulator-5554 ./gradlew
:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`; the brief's anchored greps
(`git grep -nE '<pattern>' -- <paths>`, over `app/src/main` unless named) and untouched diff (`git diff <base> --stat --
<paths>` → empty; `A/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `C/` =
`core/src/main/kotlin/com/loosecannon/servicetag/core/`, expanded in the command); hygiene; gitlink `7e0377a`; `git
status` clean. **The pin rule:** a shipped assertion that pins a schema or format version, a table, field, route, tool,
channel, count key, status-key or sub-resource count or order, a golden command shape or the R4 row moves with the brief
that bumps it, keeping every other assertion; the retarget rows list the known ones; the implementer confirms the set
with `git grep -nE '\b(12|17|19|23|63)\b|Twelve|[Ss]eventeen|[Nn]ineteen|ThreeChannels|three channels'` over the test
trees before the first commit, and the report lists every move, so an unlisted pin is a report obligation, not a gate
failure. **Must NOT, always:** delete a shipped assertion or move one outside a retarget row or the pin rule; commit
outside the brief's files; use any device but `emulator-5554`; drive a system permission dialog or the Contacts app's
UI; touch ServiceTag's manifest or `ManifestContractTest`; put a real name, number or lookup key in any fixture, report
or transcript.

## 9. B1 — the loan aggregate, schema 13, format 13, merge (C1–C7)

**Read:** audit §2, §4; #79b's B1 files as its plan lists them (P79 §13), `validateGraph` (`BackupCodec.kt:375`),
`AssetDao.kt` (:14-28), `Repositories.kt` (:250-277), every row-11 test. **Lane:** `issue-72`; **`<base>`** = the
ratified commit. **Rows:** 1–11. **Needs:** R72-1–5, 11, 12, 17–19, 21. **Connected:** `Format7RestoreContractTest`,
`AppSmokeTest`. **Greps**, each → 1 unless stated:
- `'const val FORMAT_VERSION = 13$'`, `'const val FIRST_LOAN_FORMAT = 13$'`, `'const val LAST_LEGACY_FORMAT: Int = 7$'`, `'^val CONTACT_LOOKUP_URI = Regex\('` over `core/src/main`; `'const val SCHEMA_VERSION = 13$'`; `'^\s+version = 13,$'` in `AppDatabase.kt`; `'"assetLoans" to sorted\.assetLoans\.size,$'` in `BackupCodec.kt`;
- `'\bMIGRATION_12_13\b'` → hits in `Migrations.kt`, `AppGraph.kt` and `MigrationTestSupport.kt`; `13.json` present;
- `'\bCASE_ENTRIES, LOANS,'` and `'^\s+ASSET_ALREADY_LENT,?$'` in `MergePlan.kt`; `'^\s+val loans: MergeTally\b'` over `C/merge`; `'^\s+val loans: MergeTallyDto\b'` in `ApiDtos.kt`;
- `'open_?[mM]arker'` over `core/src/main` → 0; `'\b(LOAN_DUE_BACK|UNTIL_CLEARED)\b'` over `core/src/main app/src/main` → 0; `'deadline_local_delivery|DeadlineLocalDelivery'` over `C/backup C/merge` → 0;
- the `conditions.insert(` pin: exactly `RecordCondition.kt`, `ApplyBackupMergePlan.kt`, `ImportBackupReplace.kt`.

**Untouched:** `A/{ui,reminders,share,attachments,links,contacts}`, `A/api` but `ApiDtos.kt`,
`C/{reminders,health,condition,warranty,schedule}`, `app/src/main/AndroidManifest.xml`, `share-test-sender`, `tools`,
`docs`. **Must NOT:** add an UPDATE verdict or a loan delete; put the marker or a standing on a domain type or DTO; let
a loan writer touch another table; move a `MergeTable` ordinal or `LAST_LEGACY_FORMAT`; put a cross-row rule in the
content check; reset a mode silently. **Size:** about 1,150 production, 1,800 test lines (#79b ran 1.7× its estimate).

## 10. B2 — the reminder seam and loan delivery (C8–C14)

**Read:** audit §1; #79a A2's files (P79 §10), `ReminderReceivers.kt` (:109, :195-198, :236-239), `AppPrefs.kt`
(:40-52); SPEC12 §2.5, §5.1, §9.1, inv. 44, 53 with their #79 amendments; P79 §17's A2 errata. **Lane:** after B1;
**`<base>`** = B1's accepted tip. **Rows:** 12–24. **The first commit is C14's base matrix and its test, before any
production edit; the members, the builder and the two loan steps then land together — no commit produces a loan subject
before `loanOnce`, `untilCleared` and the post by kind exist.** **Needs:** R72-6–10, R72-20, R72-23 (P72-23, 33, 38–43).
**Connected:** `ReminderPlatformDeviceProofTest`, `QuickActionDeviceProofTest`, `ReminderHealthScreenTest`. **Greps:**
- over `core/src/main`, each → 1: `'^enum class DeadlineKind \{ WARRANTY_EXPIRY, LOAN_DUE_BACK \}$'`, `'^enum class DeadlineRepeat \{ ONCE, UNTIL_CLEARED \}$'`, `'^enum class ProviderId \{ LOCAL \}$'`, `'= "Due back"$'`;
- unchanged from `<base>`, each → 1 in `DigestPolicy.kt`: `'facts\.today\.isBefore\(opens\) \|\| facts\.today\.isAfter\(dueOn\)'`, `'"Warranty expires \$'`; `'suspend fun forDeadline'` → 0; `'\bdigestHour\b'` in `DeadlineDelivery.kt` → ≥ 1;
- each → 1: `'= "loan_reminders"$'`, `'name = "Loan reminders",$'`, `'description = "Reminders when a lent item is due back\.",$'`, `'= "DUE BACK"$'`, `'= "ServiceTag needs notification permission to remind you when a loan is due back\."$'`; in `DigestPolicy.kt` `'"Lent to \$[^"]*\. Due back \$'`; under R72-7's post-due forms, each → 1: `'= "NOT RETURNED"$'`, `'"Lent to \$[^"]*\. Was due back \$'`;
- unchanged from `<base>`, each → 1: `'SUMMARY_TITLE_SUFFIX = " maintenance items need attention"$'`, `'= "EXPIRES SOON"$'`, `'= "warranty_reminders"$'`, `'"ServiceTag needs notification permission to remind you when maintenance is due\."$'`, `'"Notifications are turned off, so maintenance reminders will not arrive\."'`;
- `'^\*\*Amendment \(#72, '` in SPEC12 → 5.

**Untouched:** `C/{backup,merge,model,usecase,health,condition,ports,warranty,schedule}`,
`A/{ui,api,data,share,contacts,links,prefs}`,
`A/reminders/{LocalReminderProvider,Notifications,ReminderReceivers,ReminderHealthCheck,QuickActionReceiver,BackstopWorker}.kt`,
the manifest, `app/schemas`, `share-test-sender`, `tools`, `docs/api`, SPEC14 (a needed edit to an untouched file is a
report item and a controller ruling, never silent). **Must NOT:** edit the warranty's `once`, post or window, or any
schedule tag, hash, post or stamp; add a `SubjectKey` member, `ProviderId`, receiver, alarm, worker, column or manifest
entry; count, snooze or nonce a loan; make `forDeadline` suspend; drop a standing post on a muted channel; re-alert at
the midnight sweep; add a health finding or reword a shipped sentence. **Size:** about 500 production, 1,100 test, 35
spec lines.

## 11. B3 — the phone: contacts, the Lending section, the editor, list, Dashboard and the grant proof (C15–C20, C25)

**Read:** audit §3, §5; #79b's B2 files (P79 §14), `plateFacts`/`plateBadges` (`AssetViewModels.kt:1230`,
`AssetDetailScreen.kt:757-770`), `AssetsScreen.kt` (:330-426), `StatusBadge.kt`, the dashboard files, `LinkLauncher.kt`
(:30-51), `AssetEditorWarrantyReminderTest`, `share-test-sender/**`, `TestSender.kt`, `ShareBoundaryTest`,
`ReleaseProofPolicyTest`, `docs/release-proofs.md` (R4). **Lane:** after B2; **`<base>`** = B2's accepted tip. **Rows:**
25–35. **Needs:** R72-3, 4, 5, 11, 12, 14, 17, 18, 22–24. **Connected:** `ContactLinkContractTest`,
`ContactGrantBoundaryTest`, `LendingSectionDeviceTest`, `AssetsIndicatorsTest`, `DashboardAttentionTest`,
`ShareBoundaryTest` (unchanged, re-run beside the new sender); then the scroll-rule re-runs —
`AssetDetailConditionHealthSeasonTest`, `AssetDetailKeyDocumentsTest`, `AssetWarrantySectionTest`,
`AssetServiceCasesTest`, `ServiceCaseScreensTest`, `AssetModelDeviceProofTest`, `JournalDeviceProofTest`,
`JournalSmokeTest`, `IncidentOfferDialogTest`, `AppSmokeTest`, `NavigationSmokeTest` (the implementer confirms the set
by `git grep` on the detail route and screen; a tap the new section pushes off screen is fixed test-only with
`performScrollTo()`, the #79 §18 precedent, and reported). **Greps:**
- every P72 literal B3 owns (P72-1…22, 24…32, 34…37, 44…46) → exactly 1 in its anchored form (`'= "<text>"$'`, regex-escaped; `'"Lent to \$'` over `A/ui` → 1, P72-44 composing it; `'" · due back "'` → 1 when ruled in); P72-10, 19, 44 only when ruled in;
- `'= "Due back"$'` over `core/src/main app/src/main` → 2; `'= "None"$'` → 2 and `'= "Open"$'` → 2 (unchanged);
- `'READ_CONTACTS|WRITE_CONTACTS|lookupContact'` over `app/src/main` → 0; `'takePersistableUriPermission'` over `A/contacts A/ui/loan` → 0, and over `app/src/main` → 1 (the shipped `A/ui/settings/SettingsScreen.kt:144`, unchanged); `'^import android\.'` in `A/contacts/ContactLink.kt` → 0; `'PickContact\(\)'` → 1;
- `'^enum class AttentionKind \{ CONDITION, HEALTH \}$'` → 1 (unchanged); `'NotificationPermission'` over `A/ui/nav` → 0;
- in `docs/release-proofs.md`: the R4 row names exactly `ShareBoundaryTest` and `ContactGrantBoundaryTest`.

**Untouched:** `core/src/main`, `app/schemas`, `A/{api,data,reminders,share,prefs}`,
`A/ui/{health,condition,maintenance,journal,service,settings}`, `app/src/main/AndroidManifest.xml`,
`app/src/test/kotlin/com/loosecannon/servicetag/reminders/ManifestContractTest.kt`, `tools`, `docs` but the R4 row and
"R4 in full". **Must NOT:** read a contact outside the pick callback or keep more than the URI and the name; launch a
URI the rule refuses; offer a typed borrower, a loan delete, an action on a returned loan or "Lend out" out of service;
request the permission before the write or after "Not now"; carry an indicator by colour alone; type, press or choose in
a boundary case; give the sender more than C25's three commands. **Size:** about 1,200 production, 1,700 test lines.

## 12. B4 — the loan API, MCP, docs and the gate at 13 (C21–C23)

**Read:** #79b's B3 files (P79 §15); `v1.md` (:1176-1197, :1288-1300, :1334-1338), `ValidationRefusals.kt` (:20-30,
:175-200), `server.py` (:150-165, :210-260, :2465-2530), the MCP `README.md` (:190-200, :250-280),
`tests/test_command_shapes.py`; `docs/release-proofs.md` (:81-88); SPEC14 §2, §10.2–§10.4, §10.7. **Lane:** after B3;
**`<base>`** = B3's accepted tip. **Rows:** 36–38. **Needs:** R72-11, 13–16, 21. **Connected:** none; `(cd
tools/servicetag-mcp && uv run --frozen pytest)` green at 68 tools. **Greps:**
- ``'^\| `(GET|POST|PATCH)` \| `/v1/(assets/\{id\}/loans|loans)'`` in `v1.md` → 5; `'^\| 422 \| `loan_validation`'` in `v1.md` → 10; `'ASSET_ALREADY_LENT'` in `v1.md` → ≥ 1;
- every `'format \*?\*?1–'` hit over `docs/api tools/servicetag-mcp/src tools/servicetag-mcp/README.md` reads `1–13`; `'13 since #72 \(loans\)'` in `v1.md` → ≥ 1;
- `'seventeen tables'` over `docs/api tools/servicetag-mcp/src` → 0; `'\*\*seventeen\*\*'` in `tools/servicetag-mcp/README.md` → 0; `'eighteen tables'` → ≥ 1 in `v1.md` and in `server.py`;
- `'contactLookupUri|contact_lookup_uri|lookupUri|lookup_uri'` over `A/api tools/servicetag-mcp/src docs/api/command-shapes.json` → 0;
- in `server.py`: `'^_MIN_LOAN_SCHEMA_VERSION = 13$'` → 1; `'^def _require_loan_schema\(tool: str\) -> None:$'` → 1; `'_require_loan_schema\("(list_loans|get_loan|lend_asset|update_loan|return_loan)"\)'` → 5; `'^_MIN_SCHEMA_VERSION = 8$'` → 1;
- in `docs/release-proofs.md`, → 0: ``'Room schema 12 / backup format 12|`schemaVersion` 12|`backupFormatVersion` 12|format-12 round trip|carry schema 12 under'``; `'service cases at schema 12'` → 1 (the title's true attribution stays, "#72's loans at schema 13" added beside it); `'schema 8\) → schema 13'` → 1;
- `'^\*\*Amendment \(#72, '` in SPEC14 → 5 (4 if R72-14 is not b).

**Untouched:** `core/src/main`, `A/{ui,reminders,data,share,contacts}`, `app/schemas`,
`tools/servicetag-{bundle,schedules}`, `share-test-sender`, `docs/versioning.md`, SPEC12, the manifest. **Must NOT:**
carry a lookup URI on a loan route or tool; route a delete, a relink or a contact query; reconcile from a handler; let
`null` clear in `update_loan`; lower the global MCP minimum; bump `versionName`; rewrite ratified spec text. **Size:**
about 550 production and 300 Python, 850 test, 110 docs lines.

## 13. Owner rulings (2026-09-28; binding on every brief)

The owner passed the gate on rev 2.1 as written: no further planning review. Every ruling is the recommendation above
unless stated.

- **R72-1** A, the `asset_loan` aggregate. **R72-2** all four enforcement layers (use case, the unique
  `(asset_id, open_marker)` index, the archive graph check, the merge conflict) — the invariant is canonical.
- **R72-3** a phone loan requires a picked contact; its display name is the snapshot, not directly editable; API loans
  may be name-only and linked later on the phone. **R72-4** the canonical `contact_lookup_uri`, exported beside the
  snapshot in backup and merge, degrading gracefully where it does not resolve, never exposed by the loan API;
  **P72-19 ACTIVE.** **R72-5** a relink on an open loan replaces link and snapshot together; returned loans frozen.
- **R72-6 (a) strictly once** per due occurrence: a swiped Once never returns after a restart.
- **R72-7** the digest-hour period; Until returned daily, re-alerting in place; the post-due forms **P72-10, P72-41,
  P72-43 ACTIVE**; no alert around midnight because `ACTION_DATE_CHANGED` ran.
- **R72-8** "Open" only; no notification-side Returned. **R72-9** the dedicated `loan_reminders` channel, default
  importance (four channels). **R72-10** reminders continue while a loan is open on a retired or archived asset.
  **R72-11** the phone offers "Lend out" in service only; the API accepts any lifecycle. **R72-12** archive and retire
  allowed while lent (the loan stays visible and returnable); delete follows the existing destructive confirmation and
  cascades the loan. **R72-13** no maintenance, condition or health coupling; an overdue loan is never maintenance
  OVERDUE.
- **R72-14 (b)** the Dashboard-only loan row after the ratified ATTENTION tiers; `AttentionKind` and `/v1/attention`
  untouched; **P72-44 ACTIVE**; accepted: a retired or archived overdue loan notifies but is absent from that in-service
  projection.
- **R72-15** phone writes reconcile at once; API and MCP writes settle at the next digest or backstop — no handler becomes
  a reminder reconciler. **R72-16** five routes and five tools (63 → 68) with `GET /v1/loans/{id}` and the overlay
  `update_loan` honouring null-preserves; `LoanDto` carries `contactLinked`, never the lookup URI.
- **R72-17** "Mark returned" is the only exit; no loan delete; "Edit loan" corrects an open loan. **R72-18** returned
  loans frozen; accepted: a return, re-date or relink on one phone conflicts with a stale copy on another — no UPDATE
  merge path for loans. **R72-19** dates only, with the stated ordering rules. **R72-20** a returned loan is absent from
  the subjects; the `Completed` reservation untouched. **R72-21** no version bump; schema/format 13 rides unreleased
  under 1.4.1 into the next feature-bearing MINOR with #79; the release proof is the direct schema 8 → 13 path.
- **R72-22** Lending right after Warranty, before Condition, "Lend out" inside it; both loan badges word + glyph in the
  neutral tone, "Loan overdue" included — never maintenance-overdue or degraded semantics.
- **R72-23** P72-1 … P72-46 **ratified verbatim**: the literals `Lent out` and `Loan overdue` stay mixed-case
  (`StatusBadge` draws LENT OUT / LOAN OVERDUE; TalkBack reads the phrase); the three surface-specific overdue
  expressions stay ("Loan overdue", "Was due back", "NOT RETURNED"); P72-45 is `No app can pick a contact`; the
  conditionals P72-10, 19, 41, 43, 44 are all ACTIVE; the single-home `lentTo(name)` composition for P72-4/P72-44 stands.
- **R72-24** the foreign-UID grant proof `ContactGrantBoundaryTest` from the test-only sender, never Contacts UI
  automation; the testing hierarchy in `docs/superpowers/planning-policy.md` amended at ratification (committed with
  this section).

**Addendum (owner, 2026-09-28, after B2's task review):**
- **R72-B2 departure approved:** add a per-post `onlyAlertOnce` flag to the notification post model and builder,
  default **false**. Only the silent post-due refresh of an already-standing **Once** loan reminder sets it **true**. No
  existing maintenance, warranty, Until-returned or ordinary loan announcement changes behaviour. This crosses §10's
  `Notifications.kt` fence by that one flag only; `LocalReminderProvider.kt` stays untouched ("still standing" is the
  platform's active-notification list the provider already passes in).
- **Conditions:** the flag defaults to false on every path; only the standing Once → post-due wording refresh sets it;
  the pre-digest hold rule applies before **all** loan-post decisions (first post, Until-returned cadence, restart
  re-post), an already-standing notification simply left alone before the day's digest hour; tests prove the three owner
  boundaries — a standing Once refreshes silently, a swiped Once stays gone after the due day and after a restart,
  Until-returned and warranty behaviour unchanged — plus a device read-back of the alert flag and a negative unit
  assertion that ordinary announcements and warranty posts never carry it; the review's lifecycle and zone-change minors
  land in the same batched fix round; one scoped re-review of the fix before B2 is accepted and B3 dispatched.
- This resolves the R72-6 / R72-7 tension by option (a): a standing Once turns to "Was due back … / NOT RETURNED"
  silently after the due day; a dismissed one never returns.
