# #113 + #114 — Home Assistant publishing: equipment status and the Season Start service: master plan (rev 4, 2026-10-23)

> **Rev 4 (2026-10-23)** is a focused correction pass over the external review of rev 3 the owner forwarded
> (`external-review-rev3.md`; the controller's rulings in brackets are binding here): the architecture stands, the
> contracts move. **The principle (G22):** seasonality is configured per asset — season mode, window, inbound binding,
> boundary helper — and no equipment type, template or name ever decides it; **§1a Stories** states the three
> independent facts and four stories, each pointing at rows, and adds rows 102–106. **A** a returning asset keeps its
> local role, `roleSinceOn` and its own events' provenance through the return path (`ReturnCarry`; C20, §4 A3's fence
> and the untouched list widened, I31, row 49c). **B** rev 3's schedule-level evidence fence is gone everywhere: an
> `imported` marker on the event row (schema 22 column, format 21 key), set only when a Transfer Pack brings the event
> in, never cleared by an edit or a role change; qualifying ⇔ not imported; no clock in the rule; O-20 (C4, C7, C9,
> C10, C12, C13, C18–C20, O-8, limit 14, I24, K16, rows 3b, 21a, 49b). **C** the projector observes only work-arrived
> inputs, never a delivery write; a durable `reconcile_pending` flag cleared only by a completed read; drain selects it
> and the one-time work's bounded backoff covers it (C4.3, C17, C30–C32, the table, I28, I29, rows 57c, 57d). **D**
> boundary authority per asset — a `CALENDAR` asset's window, never HA's date; HA's season-ended helper only for a
> `MANUAL` asset whose season an enabled #16 binding drives, named on its date binding; fresh = a read at the current
> revision within max(7 days, the cadence), else **unverified**: the later of the cached date and the phone's END,
> "unverified since" on the card and the API, publication proceeds (C9, C9a, C25, C29, C33, I25, rows 3a, 3c, 99, 99a,
> 99b, 101b). **E** §8 and O-17 split `in_season` (edge for edge) from the `season_ended` stamp (once per heating
> year); the June cold snap worked through (row 100a). **F** `config_generation` a counter column; `validate()` returns
> both results; the observation seam and the runner/projector/dispatch dependencies declared; row 57a proves the
> single transaction serializes; the third-helper step marked simulated; `boundarySource` and `cutoffOn` as two API
> fields and two kinds of line (C14, C25, C28–C32, the interfaces table, rows 57a, 60a, 80, 101a, §5, §7). Strings:
> P114-44/45 reworded, P114-46 amended, P114-50…53 new (88 and one plural), all PENDING; §6 keeps the advisor's rulings
> pending the owner's word.

> **Rev 3 (2026-10-22)** folds three inputs, nothing else. **(1) The operator's privacy note (binding):** no personal
> Home Assistant or ServiceTag configuration in this public document — every real asset, schedule and group name, every
> fixture helper id and every production-phone fact is gone; the fictional "Example Heater" (a pellet-type appliance),
> "Example Boiler" and the group schedule "Example pre-season check" stand in, the owner's real state lives in the
> private ledger, and §8 describes the HA side generically (G19, §6, §7, §8). **(2) The external design audit of rev 2.1**
> (`external-audit-rev2.1/audit-findings.json`, A01–A11, CHANGES REQUIRED) under the controller's rulings:
> **A01** one immutable `ImportContext(importDay, importAt)` carried through preview, the accepted plan and the
> transaction-time replan, covered by the fingerprint; A3 edits `BuildBackupMergePlan`, `ApplyBackupMergePlan` and
> `ImportTransferPack` narrowly (C19, C20, §4 A3, rows 49, 49a). **A02** the projection is one write transaction with no
> HTTP, fenced by the desired revision it read; the sender's snapshot re-checks asset eligibility (C17, C30, C31, I20,
> rows 57a, 57b). **A03** a delivery-work signal apart from source change: every publishing control commit ends in
> `requestDelivery()`, tombstones are observed inputs (C31, C32, I21, row 70a). **A04** `error_binding_revision` /
> `error_desired_revision` persisted, and every pair-changing write clears the error (C4.3, C5, C14, C17, C25, C28, C31,
> the delivery table, I22, rows 42a, 42b). **A05** `needs_delivery`, set by a write-ahead attempt record and by a
> contradicting read, cleared only by a verified read-back (C4.3, C17, C30, the table, I23, row 38a). **A06** an event a
> Transfer Pack brought never qualifies for the recipient: a schedule-level evidence fence (replaced in rev 4, B, by
> the event's `imported` marker). **A07** the HA boundary reconciliation: a date binding reads HA's `season_ended` helper in the same
> reconcile and readiness uses it while fresh, the phone's END only as the fallback, and says which (C9, C9a, C30, C33,
> O-19, I25, rows 3a, 98–101). **A08** validation never activates (C25, C27, I26, row 69). **A09** a typed
> `ValidationResult` with connection provenance, `STALE_READ` defined on it (C14, C25, C28, C30, row 60a). **A10**
> known-not-sent only before transmission; a failure while writing or closing and every 5xx are `OUTCOME_UNKNOWN` (C14,
> C22, C23, I27, rows 54, 54a). **A11** `HaServiceAction` in core, B1's; D1's one KDoc-only `.kt` edit; G1 and row 23 name
> `claim_entity_id` (G1, C14a, §4, rows 23, 44). **(3) An advisor's recommended rulings, forwarded by the owner tonight:**
> recorded in §6 as **the plan's position, recommended to the owner 2026-10-22, pending the owner's word** — Q2 (b) (§8
> updated), C1 yes, O-2 yes by explicit election, O-17 the heating-year latch stated precisely, O-18 MANUAL on the shared
> inbound helper with separate evidence and **two** outbound date helpers, the vehicle, and P114-4/4g/9 reworded (§5).
> Five new strings (P114-44…48) are PENDING.

> **Rev 2.1 (2026-10-22)** folds the scoped re-review (`plan-review-rev2-scoped.md`), nothing else: **R-1** a
> tombstone only when the stored binding was enabled; Disable of a disabled binding is a no-op; invariant I19 (no
> PENDING tombstone on a pair an enabled binding claims) (C25, C31, C34, the delivery and tombstone tables, rows 96–97).
> **R-2** `drain()` skips a NEEDS_OWNER error at the current pair, and the midnight tick and the worker skip a WAIT one;
> reconcile mode (start, resume, Publish now) and any pair move retry both (C17, C30, C31, row 42a). **R-3** the
> delivery table's Disable row is Disable only; Change target goes to PENDING. **N-1** the resume limit counts from the
> last completed read-back (C31). **N-2** Revalidate on the same helper keeps a true "Published" line; a new enabled
> claim supersedes a PENDING or UNDELIVERABLE tombstone on its pair (C25, the tables). Also C34: a tombstone record
> deletes by its id, never by the pair.

> **Rev 2 (2026-10-22)** folds the independent plan review (`plan-review-rev1.md`, APPROVE WITH CORRECTIONS, F-1…F-28)
> under the controller's rulings, nothing else:
> **F-1** verified/published binding-revision columns; `Done` on the `(binding revision, desired revision)` pair;
> Enable, Change target and Revalidate reset the delivery; re-enable after a tombstone re-publishes (C4.3, C14, C17,
> C25, C30, the delivery table, rows 45a, 93, 94). **F-2** a token or network change keeps bindings validated and
> pending invalidations moving (C14, C28, the table, row 87). **F-3** start and resume run a read-back reconcile, resume
> rate-limited to 60 min, FOREGROUND_ONLY included (C31, the table, row 66). **F-4** B3 owns the three exhaustive
> `when` arms and P113-42/43 (C26, C28, §4 B3, §5). **F-5** B6 after B5 (§4 waves). **F-6** trailing defaulted
> collaborators keep `CT/seasonsync` byte-identical (G3, C26, C28, R6, row 95). **F-7** the gate carries no timeout;
> after-write timeout is `OUTCOME_UNKNOWN` (C21, C23, c13, row 51). **F-8** future-dated completions never qualify,
> `FUTURE_DATED`, P114-29, O-16 (C9, row 4, §6). **F-9** a claim column with `UNIQUE(connection_id, claim_entity_id)`
> over enabled bindings; disabled orphans retired (C4.2, C5, C25, C31, row 63). **F-10** tombstones for date bindings
> only (C25, the tombstone table). **F-11** the stamp fires `from: "on"` `to: "off"` only (§8). **F-12** owner question
> Q2, both positions in §8 (§6, §8). **F-13** the C1-refused fallback (§6). **F-14** the second asset needs a season first,
> O-18 (§6, §7 step 2b). **F-15** P113-41 a plural (C36, §5). **F-16** every state and refusal has an id; the
> "may still allow starts" rule on enabled date bindings; glossary terms (C33, C34, §5, row 88). **F-17** B4b, B6 and A5
> split: eighteen dispatches, eleven waves (§2 headings, §4, the interfaces table). **F-18** R-N7 (§6). **F-19**
> alternatives for R-0, O-10, O-13, O-14, R-N6 (§6). **F-20** R6 catches `doOutput = true`, `openConnection(`, other
> verbs, and lists every `else ->` (§7). **F-21** `ReadinessBoundary`/`ReadinessBoundaryKind` (C1, C9). **F-22** a
> FORM → QUICK edit of a role schedule re-asks the election, `QUICK_NEEDS_ELECTION`, P114-42/43 (C11, O-2, row 20).
> **F-23** the real-HA proof names step 1's writes (§7). **F-24** only the serviced-flag action leaves the 1 August
> reset (§8, §7). **F-25** a tombstone carries the binding's installation id (C25, the table, row 69). **F-26**
> `APPEND_OR_REPLACE` (C32). **F-27** a window edit or mode switch republishes (row 62). **F-28** a role on an archived
> group reads `SCHEDULE_ARCHIVED` (C9, row 10). Reviewer Q3 → **O-17** (the in-season latch, §6, §8); Q5 → **R-N8**
> (§6). The four wrong citations and c13 are fixed (§10's closing paragraph).

> **Rev 1 (2026-10-22).** The first master plan for the programme the owner started on 2026-10-22 ("we should start the
> work for 113 and 114"). It folds the owner's two rulings of that day — **C2 RATIFIED** (in-season and
> ready-for-the-season are two helpers in two directions) and the heater's inbound binding following HA's own
> flag — and carries **C1 PROPOSED** (the `SEASON_START` role on a group schedule) and O-1…O-14 plus R-N1…R-N6 to the
> owner gate (§6). Every user-visible string is in §5's **PENDING** block. Briefs (per-component dispatch texts) are not
> in this document: they follow the plan review.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget
> (`docs/superpowers/planning-policy.md`, "Review budget"): one task review per brief, at most one bounded fix round,
> mechanical fixes by controller inspection, one whole-branch review.
> **Ledger:** `.superpowers/sdd/2026-10-22-issues-113-114/progress.md` (the controller's; implementers never write it).
> **Audit (inventory of record):** `.superpowers/sdd/2026-10-22-issues-113-114/audit.md`, read on `62e39670`. This
> planner re-read on the same base every site this plan cites and **corrects the audit in eighteen places** (§10; rev 2 fixes c8 and c13 and four of its own citations).
> **Worktree / branch:** `../noteNFC-wt-113`, branch `issue-113-114-ha-publishing`.
> **Base:** master `62e39670` (1.8.0 / code 21, Room schema 21 / backup format 20, MCP 90 tools, gitlink `7e0377a`,
> 78 connected classes in 76 files / 430 tests). **Eighteen dispatches** in eleven waves on two lanes (§4, rev 2); each brief's
> `<base>` is the previous accepted tip of its lane.

**Goal:** a fictional "Example Heater" (a pellet-type appliance, top-level, `MANUAL` season, its inbound #16 binding following
the HA-owned `input_boolean.example_heater_in_season`) gets two **outbound**, owner-enabled publications to helpers
its Home Assistant administrator created. **#113:** its recorded operational condition, the current row of
`ConditionHistory`, is mirrored to `input_select.example_heater_equipment_status` as exactly `UP`, `DEGRADED` or
`DOWN`, through the one allowlisted service action `input_select/select_option`, and nothing is sent while no
condition is recorded. **#114:** one of its schedules, "Example annual service", carries the passive role
`SEASON_START`, set on a day that becomes its commissioning boundary; the asset reads **Ready for the season** exactly
when that schedule has a qualifying completion of this asset dated on or after the later of that boundary and the day
its season last ended — for this `MANUAL` asset whose season HA drives, HA's own `season_ended` date read fresh (C9a);
for a `CALENDAR` asset, its own window (G22) — and recorded on this phone, never brought by a Transfer Pack (C9); the qualifying date, or `1970-01-01` whenever it is not ready, is mirrored to the date-only
`input_datetime.example_heater_service_done` through `input_datetime/set_datetime`. HA's own
`input_datetime.example_heater_season_ended` and its start gate decide whether anything may start. Both publications
share one delivery layer: a device-local latest-desired-state record per binding, recomputed after every committed
change by an app-scope projector and by reconcile scans at start, resume, local midnight, the worker and Publish now;
one serialized, generation-fenced sender that reads the helper back; no inheritance by backup, merge, Transfer Pack or
a platform restore; and an invalid date published, and shown when it cannot be, before ServiceTag ever lets go of a
date helper. ServiceTag answers "was it serviced since its season ended?"; Home Assistant answers "may I start it
now?". Neither `UP` nor being in season stands in for the other.

**Inputs:** issues #113 and #114 with #114's review and scenarios (`113.md`, `114.md`), the ledger and its owner
rulings of 2026-10-22, the audit, the owner's HA inventory (`ha-inventory.md`), all under
`.superpowers/sdd/2026-10-22-issues-113-114/`; the #16 plan (`docs/superpowers/plans/2026-10-02-issue-16-home-assistant-season-sync.md`)
as the structural model and the precedent for every reused HA piece; `docs/api/v1.md`,
`docs/home-assistant-season-sync.md`, `docs/versioning.md`, `docs/release-proofs.md`, `docs/localization.md` and the
planning policy. **Paths:** `C/`, `CT/` = core main and test under `com/loosecannon/servicetag/core/`; `A/`, `T/`,
`AT/` = app main, test and androidTest under `com/loosecannon/servicetag/`; `M/` = `tools/servicetag-mcp/`; `S/` =
`tools/servicetag-schedules/`; `B/` = `tools/servicetag-bundle/`. Every `file:line` was read on `62e39670`.

## Global constraints

Each line is a sentence a reviewer can test against the diff.

1. **One writer per helper.** Within one installation at most one enabled outbound binding names a given
   `(connection, entity)` pair (`UNIQUE(connection_id, claim_entity_id)`, the claim null while disabled, C4.2, F-9, plus
   a command refusal, C25); no binding of either kind ever names an entity an inbound #16 binding reads, no inbound
   binding ever names an entity an outbound one writes (G9), and a date binding's boundary helper (C9a) is read, never
   written.
2. **Allowlisted service actions only.** The app's only write to Home Assistant is
   `POST <base>/api/services/input_select/select_option` with `{"entity_id", "option"}` or
   `POST <base>/api/services/input_datetime/set_datetime` with `{"entity_id", "date"}`, chosen by a two-member enum
   (`HaServiceAction`, core, B1's, C14a), never by a string parameter. Never `POST /api/states`, never `climate`, `switch`,
   `script`, `rest_command`, `light`, `lock`, `input_boolean`, `input_text`, `set_options` or any generic service
   caller; never a helper created, renamed or re-optioned.
3. **#16's GET-only contract is untouched.** `HomeAssistantStateClient`'s requests, headers, caps, mapping and its
   tests (`T/seasonsync/HomeAssistantStateClientTest.kt`, `SeasonSyncScenarioTest.kt`, `SeasonSyncRunnerTest.kt`,
   `SeasonSyncWorkerBodyTest.kt`, `CT/seasonsync/*`) are byte-identical at the tip but for the one extraction B2 makes
   (C21), which moves code without changing an assertion; the inbound runner, worker, work and cadence are unchanged. The four
   #16 commands C26/C28 extend (`LinkSeasonSync`, `EditSeasonSyncEntity`, `SaveHaConnection`, `ForgetHaConnection`)
   take their new collaborator as a **trailing parameter defaulted to `OutboundTargets.NONE`**, so every shipped
   positional construction (`CT/seasonsync/LinkSeasonSyncTest.kt:80-89` and the rest) compiles unchanged and
   `CT/seasonsync` stays byte-identical; production never uses the default (R6, F-6).
4. **The token is never data** (#16's R16-5, unchanged): never a column, field, `toString`, exception message, log line,
   view-model state, API or MCP byte, archive byte or fixture value that looks real (`fictional-token-1`).
5. **Nothing active travels.** Outbound bindings, delivery records, decommission tombstones, desired values, cursors,
   generations and activation stamps are device-local Room rows named by no export, merge, Transfer Pack, replace
   restore or `MergeTable`; a platform (Auto Backup) restore that brings the rows back cannot make the copy a writer
   (the installation fence, C27).
6. **The passive role travels.** `role` and `roleSinceOn` on a schedule are ordinary data in backup format 21, merged
   and packed under C18–C20; legitimate service history travels as it does today, each event with its `imported`
   marker, which only a Transfer Pack import sets (B, C20).
7. **Device-local tables are in no archive.** `ha_publish_binding`, `ha_publish_delivery` and
   `ha_publish_decommission` appear in no `BackupData` field, no codec, no planner, no transfer class;
   `SeasonSyncNeverTravelsTest`'s shape is extended to them (row 12).
8. **Condition is independent of maintenance.** The equipment-status value is a function of `ConditionHistory.current`
   alone; no health score, band, due date, completion, season, readiness, reminder or child state reaches it, and
   readiness never reads a condition.
9. **The C2 cross-check runs both ways on exact ids** (C26): linking or editing an inbound entity refuses an id an
   outbound binding writes on the same connection, and enabling or retargeting an outbound binding refuses an id an
   inbound binding reads; indirect loops through HA templates are documented, not inferred.
10. **Decommission publishes `1970-01-01` first.** Every way ServiceTag stops writing a date helper — disable, target
    change, the asset archived, retired, transferred out or deleted, the role removed or its schedule archived, Forget
    connection — first makes `1970-01-01` the desired value or writes a tombstone for it in the same transaction, and
    an invalidation that has not been confirmed by readback is drawn to the owner (P114-36/37); silence never claims a
    revocation.
11. **Exhaustive `when`, never `else`,** over every new closed set: `PublishKind`, `PublishErrorKind`, `PublishState`,
    `NotReadyReason`, `BoundarySource`, `HaBoundary` (sealed), `PublishRefusal`, `SeasonStartRefusal`, `SeasonStartStrandCause`,
    `HaServiceAction`, `ScheduleRole`, and each new
    API arm (#47 E-18: a new exception never reaches `ApiJson`'s `else` → 500).
12. **Every user-visible string is a resource in all ten packs** (`values` + `values-{b+zh+Hans,de,es,fr,hi,it,ja,pt,ru}`),
    ratified in §5 before its brief runs, read through getters, held by `LocalizationCoverageTest` and
    `UiLiteralGuardTest`; developer log lines are §5's G-list with `// l10n-ok`.
13. **The gitlink and the tombstones stay.** `libs/nfc-tag-core` stays `7e0377a`; `git diff <base> -- . ':!app/schemas'
    | grep -cE '^[-+].*(external_link|externalLinks)'` → 0.
14. **One schema step, one format step.** Room 21 → 22 (S1) and backup format 20 → 21 (A3), forward-only; `21.json`
    and every format literal below 21 are never edited; no `versionName`/`versionCode` change (MINOR by
    `docs/versioning.md:24-25`, unreleased on master, emulator only, the owner picks the vehicle).
15. **HTTP stays outside domain transactions.** No use case awaits the network inside `uow.write`; the projector reads
    its sources and writes the desired state in one `uow.write` with no HTTP (A02, C31); the sender reads one `uow.read`
    snapshot, records a write-ahead attempt before any POST (A05) and a reconcile write-ahead before a reconcile's
    GETs (C), performs at most two GETs (the boundary, C9a, and the
    helper), one POST and one read-back GET, then records with a revision compare-and-set (C17, C30). `RecordCondition` keeps "one immutable row, and nothing else" (`C/usecase/RecordCondition.kt:14-19`); no
    condition, event, schedule or activation writer gains an outbox write.
16. **No new permission, component, receiver or foreground work.** The manifest changes by its `:12` comment only
    (D1); no expedited work (an expedited `CoroutineWorker` before API 31 needs `ForegroundInfo`), no foreground
    service, no broadcast receiver, no network callback, no dependency.
17. **The fence (out, everywhere):** an inbound listener, webhook, WebSocket, SSE or MQTT; a generic REST source (#100);
    publishing maintenance health, bands, due dates or readiness as a condition; a fourth equipment value; a release
    or revoke workflow beyond completing and un-completing the schedule; a cycle identifier; Assets-tab indicators
    (#116); any API or MCP **write** for publishing or the role (O-7); the base URL, token or Wi-Fi name over API or MCP.
18. **The API adds codes, never sentences:** additive, version 1; `ignoreUnknownKeys = false` stays
    (`A/api/ApiJson.kt:104`), so the role keys appear only in responses and a request body carrying them is the
    shipped 400.
19. **No personal data (operator note, 2026-10-22, binding).** Fixtures, docs and this plan use the fictional names of
    the Goal ("Example Heater", "Example Boiler", "Example pre-season check"), `ha.example`, `http://192.168.0.10:8123`,
    `ExampleHomeWifi`; the owner's asset, schedule and group names, entity ids, fixture helper ids, address, Wi-Fi, host
    name, token, HA automation names and production-phone state never enter the repository (§8 names two HA-side places
    the owner's ruling allows, and only in this plan; the private ledger holds the rest).
20. **AppGraph and FakeGraph** are edited only by S1, A2, A3, B3, B4a, B4b1, B4b2 and B5, never by two concurrent
    briefs; B5 is the last of all (§4).
21. **Standing rules.** Commits: one casual lowercase subject line, no body, no trailer, no tool name; implementers
    never write ledgers; JVM first; every counted RED a real mutation (`--no-build-cache --rerun-tasks`), quoted,
    reverted; no rerun-until-green; time boxes 1 h target, 2 h hard, fix rounds 45 min.
22. **Seasonality is configured per asset (owner principle, 2026-10-23, binding).** Whether an asset has a season,
    when it ended and who says so are that asset's own settings — its season mode, its window, its inbound #16 binding
    and the boundary helper named on its date binding (C8, C9a) — and nothing else: no equipment type, category,
    template, schedule title or asset name decides whether an asset has a season, when it ended or whether it is
    ready, in readiness, the projections or any command (names only label sentences; rows 104, 106), and this plan
    states no heater-, pool- or tub-specific rule. Two assets' seasons, cutoffs and readiness are
    independent (row 104). §8's dates are one owner's Home Assistant configuration for one appliance, never a
    ServiceTag rule.

## 1. Scope

**R-0, the scope sentence (proposed for ratification):** *#113 + #114 = two optional, per-asset, owner-enabled
outbound publications from ServiceTag to administrator-created Home Assistant helpers on the existing #16 connection —
the current operational condition to an `input_select` with exactly `UP`/`DOWN`/`DEGRADED`, and the Season Start
service date (or `1970-01-01`) to a date-only `input_datetime` — through two allowlisted service actions, one
serialized latest-desired-state sender with generation fencing and readback, post-commit projection and reconcile
scans, installation-fenced activation and no inheritance, and, for a date binding on a `MANUAL` asset whose season HA
drives, one read of HA's season-ended helper as its readiness boundary; plus a passive `SEASON_START` role on one schedule per
eligible asset (or, under C1, per group), a commissioning boundary, a pure readiness rule, its phone lines and
read-only API/MCP projections. Nothing more.*

**In scope — #113's acceptance lines (AC113-n) and #114's (AC114-n), each on its contracts and rows:**

| AC | the issue's words (abridged) | contracts · rows |
|---|---|---|
| AC113-1 | an administrator-created helper with exactly the three options is selected or entered, validated, enabled for one asset; later changes publish automatically | C14, C16, C22, C25, C31 · rows 30–34, 52, 60, 63 |
| AC113-2 | exact mapping; initial publication needs a known source; no-condition and not-yet-published are honest; a helper's existing value is never our push | C15, C17 · rows 25–29, 37, 38 |
| AC113-3 | UI, offers, incident write, API/MCP and merge use one post-commit projection; rolled-back, duplicate, backdated non-current writes publish nothing | C31 · rows 61–66, 57a, 70a |
| AC113-4 | UP → DEGRADED → DOWN reaches the helper; HA observes the changes; ServiceTag sets only that field | C30, C22 · rows 58, 85 (real HA) |
| AC113-5 | offline transitions coalesce to the latest, survive process death, recover under the network policy; nothing is relabelled DOWN | C17, C30, C32 · rows 36, 57, 59, 67 |
| AC113-6 | missing or wrong-domain helper, changed options, refused token, TLS/redirect/network failure, ambiguous completion are bounded; edits and disable mid-request cannot falsely succeed | C17, C23, C30 · rows 39–44, 53–56 |
| AC113-7 | initial publication, Publish now and reconciliation repair a stale or reset helper | C17, C31 · rows 38, 59, 85 |
| AC113-8 | no writer inherited by backup, restore or transfer; same-install merges and lifecycle covered; two assets cannot claim one helper | C6, C25, C27 · rows 12, 46–48, 64, 69 |
| AC113-9 | #16's season semantics and network/secret guarantees and #105's picker stay correct | C21, G3 · rows 50, 51, 86 |
| AC113-10 | docs: helper, mapping, initial/manual/restored values, one writer, examples, current-state semantics, Android limits; "reads, and only reads" restated | C38 · D1 |
| AC114-1 | a top-level or parent asset with seasons can mark one schedule `SEASON_START`, which sets its boundary; leaf child, `YEAR_ROUND`, group (as amended by C1) and a second role are refused with a sentence | C7, C10, C11 · rows 13–20 |
| AC114-2 | ready only with a boundary and a qualifying completion on or after it; the not-ready list; a new END; the same-day case | C8, C9, C9a · rows 1–11, 3a–3c, 98–101b, 102–104 |
| AC114-3 | edit/delete of the completion, backdated END, window change re-derive; condition, health, in-season, reminders never change it | C9, C31 · rows 5–8, 62, 68 |
| AC114-4 | the card's state, date and reason; the row's role; the completion line; every pack | C33 · rows 75–79 |
| AC114-5 | with #113's layer, the opt-in publishes the date or `1970-01-01` through `set_datetime` only, under every #113 rule | C15, C22, C30 · rows 26, 34, 58 |
| AC114-6 | every way ServiceTag stops writing publishes `1970-01-01` first; an undeliverable invalidation is shown | C34 · rows 70–74 |
| AC114-7 | docs: role, boundary, rule, invalid date, HA pair and gate with the freeze override, the interim, no instant revocation, same day | C38, §8 · D1 |

**Out of scope** (the fence, G17, and): #116's Assets-tab indicators; maintenance-health publication (#113's separate
optional work); `input_boolean`/`input_text` publication and the cycle-identifier model of #113's addendum (superseded
by #114's date design); a Season End role; more than one required schedule per asset; checklist revisions; readiness
on a leaf child or inherited by children; any change to #16's inbound decision, cadence or guard; any change to #105's
`input_boolean` scope other than adding two scopes beside it; #100; any HA-repository change (§8 is the owner's work).

**Boundaries with neighbouring issues.** **#16:** reused, not changed — the connection, endpoint policy, secret store,
network eligibility, the gate (extracted, C21) and the re-bind command `EditSeasonSyncEntity`
(`C/seasonsync/SeasonSyncCommands.kt:114-143`), which gains only the C2 refusal. **#105:** `EntityScope` gains
`INPUT_SELECTS` and `INPUT_DATETIMES` beside `INPUT_BOOLEANS` (`C/seasonsync/EntityPicker.kt:7-9`); the browser is
reused with a scope parameter; the inbound sheet keeps `INPUT_BOOLEANS`. **#100:** untouched; when it lands, C26's
cross-check is the place its source joins. **#116:** draws nothing of this plan's on the Assets tab.

**Recorded limits (stated, not fixed):**

1. **Current state, not an event log.** DOWN → DEGRADED → UP while offline publishes UP once (#113).
2. **Delivery is best effort** (K1): WorkManager may defer; a stopped phone cannot send; "home Wi-Fi only" with
   background checks Off sends only at start, resume, midnight-in-process and Publish now. HA's own `season_ended`
   comparison is the backstop for a late invalidation (§8).
3. **Revocation is not instantaneous.** A completion deleted on an offline phone reaches HA when it can be delivered;
   the card says so (P114-36).
4. **An HTTP call HA accepted cannot be recalled;** a stale reply never marks a newer generation delivered, but HA may
   briefly hold a value an edit has already superseded until the next send.
5. **The token is not read-only** (K2): HA has no such token; a successful Test connection proves no write right; the
   first 401/403 says which (C23).
6. **The local date decides** (K4): readiness compares local dates, a completion on the END day counts, a CALENDAR
   season "ends" on the first out-of-season day (O-1), and a time-zone move can shift a boundary by a day.
7. **`detailsPending` is the shipped flag** (K3): QUICK and group completions never set it, and any edit of the event
   clears it (`C/usecase/EventCommands.kt:293`); a qualifying completion is one whose flag is false (O-15).
8. **One HA connection per installation** (#16 R16-10): every binding of either kind uses it.
9. **A second phone or an HA automation can still write the helper:** one writer is enforced within this installation
   only; a copied installation is fenced (C27), an independently configured phone is not.
10. **A merge or replace that changes a bound asset's history republishes from the new history,** the same projection
    as any other change (#113: "a merge into the original installation can legitimately change the source").
11. **Indirect feedback loops** (a template reading the published helper and driving the inbound boolean) cannot be
    inferred and are documented (K6).
12. **A date set by hand in HA during the interim** (§8) is "matches" or "differs" to ServiceTag, never "published".
13. **Time-only changes while the process is dead** (K14): a CALENDAR window end passing while the app is closed is
    applied at the next start, resume, periodic reconcile or worker run; HA's own boundary already refuses the date.
    A boundary's move from fresh to unverified is time-only too (C9a).
14. **Service a Transfer Pack brought never counts** (B, O-8, O-20): an event a pack import inserts is marked
    `imported` and never qualifies — not on the import day, not when its date arrives, not after an edit, not after the
    role is cleared and set again; the recipient records its own service. A returning asset's own earlier events keep
    their local marker and count again (A, C20); a borrower's events come back `imported`. A backup merge or replace of
    the owner's own archive marks nothing (O-20).
15. **The season boundary is decided per asset** (D, O-19): a `CALENDAR` asset's is its window; a `MANUAL` asset with an
    enabled #16 binding and a boundary helper uses HA's `season_ended` while fresh (read at the binding's current
    revision within max(7 days, the cadence), and not before the asset's newest activation); otherwise it is
    **unverified**: readiness uses the later of the cached HA date and the phone's latest END, the card and the API
    say "unverified since" (P114-45), and publication proceeds — HA's own comparison stays the backstop for starts.
    Every other `MANUAL` asset uses the phone's END, which #16 dates the day the phone observes it.
16. **Unverified is conservative** (D, E): while the HA boundary is unverified, an END the phone recorded for an
    early-spring warm spell can withhold the date (`1970-01-01`) until the next successful read — a start refused,
    never one allowed (§8).
17. **A boundary helper is named at Enable** (C25): a date binding enabled before its asset's #16 link names none and
    reads the phone's END (P114-51) until Stop publishing and Enable name one.

## 1a. Stories

Three facts, kept apart on every asset (G8, G22): **in season** (the asset's own season — mode, window or inbound
binding — which wakes and sleeps its in-service schedules), **ready for the season** (its Season Start service recorded
on this phone on or after the cutoff, C9) and **condition** (`UP` / `DEGRADED` / `DOWN`, C15). None is derived from
another. The names are fictional; the stories carry no equipment-specific rule (G22).

| story | what it must show · rows |
|---|---|
| **1. A pellet-type appliance** — `MANUAL`, its season driven by HA through #16, a status binding and a date binding | serviced once a season, it is ready (3, 5); readiness rests on evidence recorded here, never on a seller's or a borrower's record (3b, 21a, 49b, 49c); a fault is its condition, published `DOWN`, never a readiness reason (25–28, 58); warm spells record phone END/START rows that leave readiness alone while HA's boundary is fresh (3c, 100a); the season's closure — HA's stamp — revokes last season's service (98, 99, 99a); a completion deleted while offline shows "not yet cleared" until delivered (72, 88) |
| **2. A winter hot tub** — `CALENDAR` 11-01…03-31, its own opening-service schedule | closed is out of season, never `DOWN` (28, 102); the opening service in October makes it ready before it opens and keeps it ready across New Year (102); its closing tasks — another schedule — never count as the opening service (8, 102); 1 April, the first day out of season, ends the season (2, 102) |
| **3. A summer pool** — `CALENDAR` 06-01…08-31 | the spring service makes it ready; 1 September ends it (103); its season, cutoff and readiness are independent of the hot tub's and the appliance's (104) |
| **4. A year-round indoor pool** — `YEAR_ROUND` | no season, so no Season Start role (14, 105); its condition publishes like any asset's (105) |

Out of scope: the hot tub's and the pool's Home Assistant rules (§8 describes one heating appliance's HA side only).

## 2. Contracts

### Common (C1–C3)

- **C1, the words.** Core package `C/hapublish/` (the outbound domain: kinds, binding, delivery, tombstone, error
  kinds, projections, mapper, validators, the state machine, commands, ports) and `C/season/SeasonReadiness.kt` (the
  rule); app package `A/hapublish/` (client, runner, worker, work, dispatch, projector, Room adapters); UI in
  `A/ui/asset/` (the card line, the two publishing blocks, the setup sheets) and `A/ui/maintenance/` (the role).
  Tables `ha_publish_binding`, `ha_publish_delivery`, `ha_publish_decommission`; columns `maintenance_schedule.role`,
  `maintenance_schedule.role_since_on`, `asset_event.imported` (B), `ha_connection.config_generation` (F),
  `ha_publish_delivery.reconcile_pending` (C). Domain: `PublishKind { EQUIPMENT_STATUS, SEASON_SERVICE_DATE }`,
  `PublishBinding`, `PublishDelivery`, `DecommissionTombstone`, `PublishErrorKind`, `PublishState`, `HaServiceAction`,
  `RemoteEntity`, `ScheduleRole { NONE, SEASON_START }`, `Readiness`, `ReadinessBoundary`, `BoundarySource`,
  `NotReadyReason`, `OutboundTargets`, `SeasonStartRefusal`,
  `SeasonStartStrandCause`, `HaBoundary` (A1, C9a), `haBoundaryOf` (B1, C9a), `ImportContext` and `ReturnCarry` (A3, C20),
  `ValidationResult` (B1, C14), the app seams `DesiredStateReconciler` (B4a), `PublishInputs` and `DeliveryRequester`
  (B4b1) (C30–C32) and the
  app-layer `requestDelivery()` (B4b2, C32). Wire values: the options `UP`, `DEGRADED`, `DOWN`; the invalid date
  **`1970-01-01`**, declared once as `const val INVALID_SERVICE_DATE = "1970-01-01"` in `C/hapublish/`. WorkManager
  unique names `"ha-publish"` (one-time) and `"ha-publish-reconcile"` (periodic). Routes
  `GET /v1/assets/{id}/season-readiness` (A4) and `GET /v1/assets/{id}/ha-publishing` (B5); MCP tools
  `get_season_readiness`, `get_ha_publishing`; constant `_MIN_HA_PUBLISHING_SCHEMA_VERSION = 22`. **Never** the words
  "release", "authorize", "permission to start" or "safe" for what ServiceTag publishes (HA authorizes; ServiceTag
  reports), and never "read-only token".
- **C2, the codes (API).** New, each its own `is` arm beside `SEASON_SYNC_ENABLED`'s in `A/api/ApiJson.kt`, never the
  mapper's `else`:
  - **409 `SEASON_START_STRANDED`** from `SeasonStartStrands(assetId, cause, schedules)` (C11), raised by the four hooked
    shipped commands when they would leave a role where it may not stand; the body names `cause` (a
    `SeasonStartStrandCause` name) and the role schedules by id and title, like `SEASON_MODE_STRANDS_POLICY`.
  - **409 `SEASON_START_REFUSED`** from `SeasonStartRefused(reason, …)` (C10): unreachable over `/v1` while O-7 holds
    (the role command is phone-only), mapped anyway so a later route or a hook-reached path can never 500.
  - Reused unchanged: `no_such_asset` (404) on both new reads; every shipped schedule, season and group code.
- **C3, the wire shapes.** C29 (readiness, schedule role keys) and C35 (publishing); `GET /v1/status` moves
  `schemaVersion` to 22 and `backupFormatVersion` to 21, nothing else.

### S1 — Room schema 22 (C4–C6)

- **C4, the DDL (`MIGRATION_21_22`, statements copied verbatim from the exported `22.json`, the
  `A/data/room/Migrations.kt:960-985` precedent).** In one step:
  1. `ALTER TABLE maintenance_schedule ADD COLUMN role TEXT NOT NULL DEFAULT 'NONE'`,
     `ALTER TABLE maintenance_schedule ADD COLUMN role_since_on TEXT` (entity
     `A/data/room/entities/MaintenanceEntities.kt:95-155` gains the two columns with the same defaults, so a fresh v22
     and a migrated v22 agree; no index — coverage is a use-case rule, C10);
     **`ALTER TABLE asset_event ADD COLUMN imported INTEGER NOT NULL DEFAULT 0`** (B: true only on an event a Transfer
     Pack import inserted, C20; `AssetEventEntity`, `A/data/room/entities/JournalEntities.kt:198-215`, gains
     `imported` with `defaultValue = "0"`, the `details_pending` shape at `:214`; no index);
     **`ALTER TABLE ha_connection ADD COLUMN config_generation INTEGER NOT NULL DEFAULT 0`** (F: a monotonic counter
     that every `SaveHaConnection` moves by one, C28; `HaConnectionEntity` gains it last with the same default).
  2. **`ha_publish_binding`** — `id TEXT PK`; `asset_id TEXT NOT NULL` (**a soft link, no foreign key**: deleting or
     replace-wiping an asset leaves the row for the projector to decommission, C34 — the `eventId` / `healthPrimarySubjectId`
     soft-link precedent); `connection_id TEXT NOT NULL` → `ha_connection(id)` **ON DELETE CASCADE** (Forget writes
     tombstones first, C28); `kind TEXT NOT NULL`; `entity_id TEXT NOT NULL`; `enabled INTEGER NOT NULL`;
     `revision INTEGER NOT NULL`; `activated_installation_id TEXT` (null while never enabled); `validated_at INTEGER`;
     `validated_revision INTEGER`; **`claim_entity_id TEXT`** (= `entity_id` while enabled, null while disabled: the
     claim); **`boundary_entity_id TEXT`** (A07: the date kind's HA `season_ended` helper, read never written, C9a;
     null for the status kind); `created_at INTEGER NOT NULL`; `updated_at INTEGER NOT NULL`. Indices: **`UNIQUE(asset_id, kind)`**,
     **`UNIQUE(connection_id, claim_entity_id)`** — SQLite's UNIQUE treats nulls as distinct, so this is the partial
     "unique among **enabled** bindings" index Room can declare (F-9) — and `INDEX(connection_id)`.
  3. **`ha_publish_delivery`** — `binding_id TEXT PK` → `ha_publish_binding(id)` ON DELETE CASCADE;
     `desired_value TEXT` (null = nothing to send); `desired_revision INTEGER NOT NULL`; `desired_at INTEGER`;
     `last_published_value TEXT`; `last_published_at INTEGER`; `published_desired_revision INTEGER`;
     `published_binding_revision INTEGER`; `last_verified_value TEXT`; `last_verified_at INTEGER`;
     `verified_binding_revision INTEGER`; `verified_desired_revision INTEGER` (F-1: verification is of a
     `(binding revision, desired revision)` pair); `remote_value TEXT` (the last value read, bounded);
     `remote_read_at INTEGER`; `error_kind TEXT`; `error_detail TEXT`; `error_at INTEGER`;
     **`error_binding_revision INTEGER`**, **`error_desired_revision INTEGER`** (A04: the pair the error was recorded at;
     both null exactly when `error_kind` is); `outcome_unknown_since INTEGER`; **`needs_delivery INTEGER NOT NULL
     DEFAULT 0`** (A05: an outstanding delivery or repair, C17); `attempts_at_revision INTEGER NOT NULL DEFAULT 0`;
     `last_attempt_at INTEGER`; **`boundary_value TEXT`**, **`boundary_read_at INTEGER`**,
     **`boundary_binding_revision INTEGER`** (A07: the last valid read of the boundary helper, C9a);
     **`reconcile_pending INTEGER NOT NULL DEFAULT 0`** (C: a reconcile begun and not yet completed by a read, C17).
  4. **`ha_publish_decommission`** — `id TEXT PK`; `connection_id TEXT NOT NULL` (**no foreign key**: it outlives a
     Forget, C34); `entity_id TEXT NOT NULL`; `asset_id TEXT` (display only, soft); `installation_id TEXT NOT NULL`;
     `state TEXT NOT NULL` (`PENDING` | `UNDELIVERABLE`); `requested_at INTEGER NOT NULL`; `error_kind TEXT`;
     `error_at INTEGER`; `revision INTEGER NOT NULL`. Index `UNIQUE(connection_id, entity_id)` (one obligation per
     helper; a second is merged into the first, C34).
  No other table, column, index or row moves; `21.json` is unchanged; `AppGraph.SCHEMA_VERSION` 21 → 22 and the
  migration list gains the step; `MigrationTestSupport` its twin.
- **C5, the DAOs.** One DAO per table with: on bindings `observeAll()` and **`observeControls()`** (C: the control
  projection `(id, assetId, kind, enabled, revision, entityId, boundaryEntityId, activatedInstallationId)`, the
  projector's only binding input, C31); on tombstones **`observeObligations()`** (C: `(id, revision)` of the PENDING
  rows; a tombstone's `revision` moves only when an obligation is written or merged — the drain's state and error
  writes are a compare-and-set on it that never moves it); **no delivery flow is offered to the projector**; a binding update
  that is a **compare-and-set on `revision`** (writes only when the stored row's revision is `next.revision - 1`,
  answers whether it wrote — `C/seasonsync/SeasonSyncPorts.kt:20-25`'s contract); a delivery update that is a
  compare-and-set on **both** the binding's revision and `desired_revision` (C17's record step); a desired-state write
  that is a compare-and-set on the `desired_revision` the projector read **in the same transaction** (A02, C31); **every
  write that moves the binding revision or the desired revision sets `error_kind`, `error_detail`, `error_at` and both
  error revisions to null in the same statement** (A04: the clearing rule; the persisted pair is the second guard); tombstone insert-or-
  merge by `(connection_id, entity_id)`; deletes by id. Every binding write sets `claim_entity_id`
  from `enabled` in the same statement. No DAO query reads a token column (there is none).
- **C6, never travels.** No `BackupData`, DTO, `MergeTable`, `TransferTableClass`, manifest-count map or codec names
  any of the three tables; `T/backup/SeasonSyncNeverTravelsTest.kt` gains the three tables and the key words
  `ha_publish`, `desired_value`, `activated_installation_id`, `reconcile_pending` and an example entity id (row 12).
  The two role columns and the event's `imported` column **do** travel (C18); `config_generation` never does
  (`ha_connection` is device-local, #16 R16-Q-E).

### A1 — the role and the readiness rule (C7–C9; core JVM)

- **C7, `ScheduleRole`.** `enum class ScheduleRole { NONE, SEASON_START }` and, on `MaintenanceSchedule`
  (`C/model/Maintenance.kt:100-133`), two fields appended after `providers` with defaults `role = ScheduleRole.NONE`
  and `roleSinceOn: String? = null` (ISO date); on `AssetEvent` (`C/model/Journal.kt:64-83`) one field appended after
  `detailsPending`: **`imported: Boolean = false`** (B: true only on an event a Transfer Pack import inserted, C20; no
  command sets it and none clears it, C12). **Invariant R1:** `roleSinceOn != null` ⇔ `role == SEASON_START`
  (asserted by the mapper and the codec, C13, C18). The role changes no rule, due date, reminder or policy: the engine,
  `RecomputeSchedules` and every evaluator ignore it (row 21 pins a recompute before and after a role change equal).
- **C8, `SeasonContext.lastSeasonEndOnOrBefore(d: LocalDate): LocalDate?`** (public, pure, beside `seasonEndAt`,
  `C/schedule/SeasonContext.kt:101-107`):
  - **MANUAL:** the `occurredOn` of the latest END activation dated on or before `d` (rows already ordered
    `(occurredOn, createdAt, id)`, `:257-261`); null when none. An END following an END (a merge) still counts: the
    later boundary is the conservative one.
  - **CALENDAR (O-1, plan position):** the **first out-of-season day** after the latest window end strictly before `d`:
    `latestEndOnOrBefore(d.minusDays(1)).plusDays(1)` over the private `YearlyWindow` (`:225-226`, exposed to the class,
    not made public); null when the window is missing or malformed (the context is total, `:36-46`). Symmetric with
    MANUAL's "out of season from its own day". A window crossing New Year (11-01…03-31) and a summer one
    (06-01…08-31) are the same arithmetic (rows 2, 102, 103).
  - **YEAR_ROUND:** null.
  - Activations are still read only in MANUAL (inv. 90, `:247`): a MANUAL → CALENDAR switch hides the END history and the
    window decides; a CALENDAR → MANUAL switch writes its switch row dated today (`SetSeasonMode`), which a MANUAL END
    then reads as a boundary — a deliberate consequence (row 9).
- **C9, the rule — one pure function in `C/season/SeasonReadiness.kt`:**

```kotlin
sealed interface Readiness {
    data class Ready(val serviceOn: LocalDate, val scheduleId: ScheduleId, val boundary: ReadinessBoundary) : Readiness
    data class NotReady(val reason: NotReadyReason, val scheduleId: ScheduleId?, val boundary: ReadinessBoundary?) : Readiness
}
data class ReadinessBoundary(
    val cutoffOn: LocalDate,            // services dated on or after it count (API `cutoffOn`, F)
    val source: BoundarySource,         // where cutoffOn came from (API `boundarySource`, F)
    val checkedAt: Long? = null,        // HA_SEASON_ENDED only: the read's time
    val unverifiedSince: Long? = null,  // set whenever the HA boundary is Unverified, whatever won (D)
)
enum class BoundarySource { CALENDAR_WINDOW, PHONE_SEASON_END, HA_SEASON_ENDED, HA_UNVERIFIED, COMMISSIONING }
sealed interface HaBoundary {           // C9a; built by B1's pure haBoundaryOf, never inside C9
    data object NotUsed : HaBoundary
    data class Fresh(val on: LocalDate, val readAt: Long) : HaBoundary
    data class Unverified(val cachedOn: LocalDate?, val since: Long) : HaBoundary
}
enum class NotReadyReason {
    NO_ROLE, ROLE_NOT_ALLOWED, TWO_ROLES, SCHEDULE_ARCHIVED, NOT_DONE_SINCE_SEASON_END,
    NOT_DONE_SINCE_COMMISSIONING, DETAILS_PENDING, FUTURE_DATED, INHERITED_SERVICE_ONLY,
}
fun seasonReadiness(
    asset: Asset, assets: Collection<Asset>, schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>, events: Collection<AssetEvent>,   // this asset's events
    activations: List<SeasonActivation>, today: LocalDate,
    haBoundary: HaBoundary = HaBoundary.NotUsed,
): Readiness
```

  **Coverage.** The role schedules covering `asset` are the `SEASON_START` schedules that are not ARCHIVED (PAUSED
  counts as live) and either target it (`AssetTarget(asset.id)`) or target a non-archived group of which it is an
  **open** member (`removedAt == null`, `C/usecase/GroupCommands.kt:188`). Then, in this order (the first that holds
  answers): none covering and an archived role schedule targets it or its group, **or a role schedule targets an
  archived group of which it is an open member** (F-28) → `SCHEDULE_ARCHIVED`; none → `NO_ROLE`;
  two or more → `TWO_ROLES`; the asset is `YEAR_ROUND`, or has a parent and no child row (`AssetTree.children`,
  `C/model/AssetTree.kt:44`; O-5: any child row counts) → `ROLE_NOT_ALLOWED`. Otherwise with the one role schedule `s`:
  - **season end, per asset (D, G22):** `CALENDAR` → `SeasonContext.of(asset.seasonInputs(activations))
    .lastSeasonEndOnOrBefore(today)` (the window, `CALENDAR_WINDOW`); **`haBoundary` is ignored for a `CALENDAR`
    asset even when `Fresh`** (row 99b). `MANUAL` with `Fresh` → its date (`HA_SEASON_ENDED`, `checkedAt = readAt`);
    with `Unverified` → the later of `cachedOn` and the phone's END (`HA_UNVERIFIED`, `unverifiedSince = since`);
    with `NotUsed` → the phone's END (`PHONE_SEASON_END`);
  - **cutoff** = the later of `s.roleSinceOn` and that season end; `COMMISSIONING` when `roleSinceOn` is later or no
    season end exists; on a tie the season end's source; `roleSinceOn` is never null here (R1; a row that breaks R1
    reads `NO_ROLE`, defensively);
  - **evidence (B, O-8):** an event whose `imported` is true never counts — a Transfer Pack brought it (C20). No clock,
    no `createdAt` and no role field takes part, so clearing and setting the role again re-admits nothing (row 21a);
  - **qualifying** = an event with `scheduleId == s.id`, `assetId == asset.id`, `!detailsPending`, `!imported`, and
    `cutoffOn <= LocalDate.parse(occurredOn) <= today` (inclusive: a completion on the END day counts — #114 §2;
    a completion dated after today never qualifies until its day, F-8, O-16);
  - any qualifying → `Ready(max qualifying occurredOn, s.id, boundary)` (O-3: the latest);
  - else any non-pending, not imported event of `s` on this asset dated after today → `FUTURE_DATED`;
  - else any event of `s` on this asset on or after the cutoff with `detailsPending` → `DETAILS_PENDING`;
  - else any non-pending event of `s` on this asset in `[cutoffOn, today]` that is `imported` →
    `INHERITED_SERVICE_ONLY` (P114-46);
  - else `NOT_DONE_SINCE_COMMISSIONING` when the source is `COMMISSIONING`, otherwise `NOT_DONE_SINCE_SEASON_END`.
  **Never read:** condition, health, `SeasonPhase` today, reminders, closures (a closed round is not a completion),
  any other schedule's events, a parent's or child's events, `occurrenceOn`, `createdAt`, and the asset's name,
  category or type or any schedule's title (G22, row 106). **Pure:** no clock; `today` is a parameter; freshness is
  decided by the caller through `haBoundaryOf` (C9a); a malformed `occurredOn` is skipped, never thrown. Under C1 the
  same function runs per member, reading that member's own events of the group schedule (and, under O-18, that
  member's own date binding's `HaBoundary`).
- **C9a, the boundary authority and its freshness (A07 as corrected by D; plan position, O-19).** #16's applier dates an
  END the day the phone **observes** OFF (`C/seasonsync/RecordSeasonSyncResult.kt:49-51`, `:116-127`: `occurredOn =
  today`), and observes a phase, not a history: a late observation moves the phone's boundary later than HA's, and a
  missed OFF → ON cycle leaves none. Authority is decided **per asset, at each projection, from its current settings**
  (G22; the #16 applier, its guard and its tests are untouched, G3):
  - **`CALENDAR`:** the window decides. No boundary helper is read for it and HA's date never overrides it.
  - **`MANUAL` with an enabled #16 binding and a boundary helper named on its date binding** (`boundary_entity_id`,
    named explicitly at Enable or Change target and required there for such an asset, C25): HA's `season_ended`
    decides — `Fresh` or `Unverified` below.
  - **Every other `MANUAL` asset** (no #16 binding, a disabled one, or a date binding naming no boundary helper,
    limit 17): the phone's END decides (`NotUsed`).
  - **The helper:** a date-only `input_datetime`, validated with the target (C25); never equal to the target; read,
    never written; two bindings may read one (O-18 — a read is not a claim).
  - **The read:** one GET in the same reconcile that publishes the date (C30's boundary step, through
    `HomeAssistantPublishClient.read`, #16's gate, headers and caps). A valid `YYYY-MM-DD` state is cached on the
    delivery (`boundary_value`, `boundary_read_at`, `boundary_binding_revision`) by its own CAS'd write; Enable,
    Change target and Revalidate replace the cache with their own boundary `ValidationResult`'s date (when valid) at
    the new revision; a failed or malformed read records nothing and keeps the old cache; a validation failure clears
    `validatedRevision` like the target's (C17).
  - **`Fresh`** ⇔ the cache was read at the binding's **current revision**, `now − boundary_read_at ≤ max(7 days,
    the connection's cadence)` (`SyncCadence.hours`: 12 h, 24 h and a week give 7 days; a month gives 30), and the
    read is not older than the `createdAt` of the asset's newest activation row (an observation after the read may
    mean HA stamped a newer date). The pure `haBoundaryOf(cache, binding, assetMode, inboundEnabled, cadence,
    newestActivationCreatedAt, now): HaBoundary` (B1, `C/hapublish/BoundaryFreshness.kt`) decides; its callers — the
    projector, the card's view model, the API handler — pass `now` from the shipped clock, so C9 stays clock-free.
  - **`Unverified`** (every other case for such an asset): `since` = the last valid read's `boundary_read_at`, else the
    binding's `validatedAt`; readiness uses **the later** of the cached date and the phone's latest END (a later
    boundary can only withhold); the card draws P114-45 and the API `boundaryUnverifiedSince`; **publication
    proceeds** — HA's own comparison of its own stamp is the backstop. Time alone moves `Fresh` to `Unverified`, so
    the midnight tick re-projects (C31), and the drain reads the boundary whenever it is not fresh (C30).
  - **A missed whole OFF/ON cycle with failing reads:** within the window the card shows the old service Ready with
    its check date (P114-44) and HA, comparing its newer stamp, refuses the start; past the window the boundary is
    `Unverified` (P114-45) and publication proceeds the same way; the next valid read makes it NotReady and publishes
    `1970-01-01` (rows 99, 99a).
  - **O-19's alternative:** no boundary read; document the limitation and give the owner a reconciliation workflow
    (backdate the END on the phone to HA's date by hand). §6.

### A2 — the role command and the refusals (C10–C13; core JVM, one app mapper)

- **C10, `SetScheduleRole` (`C/usecase/SetScheduleRole.kt`, new).**
  `suspend fun run(id: ScheduleId, role: ScheduleRole, sinceOn: String? = null): MaintenanceSchedule`, one
  `uow.write`. `NONE` clears both fields (no refusal but `NoSuchSchedule`; allowed on an archived schedule; clearing a
  `NONE` is a no-op that writes nothing). `SEASON_START` — `sinceOn` defaults to today, must parse (`BadDate`, 422 shape
  of `ConditionProblem`'s) and be ≤ today (a bad date is P114-28); re-setting a role schedule with a new date moves only `roleSinceOn`.
  No role write touches an event, so clearing and setting the role again never re-admits an imported service (B,
  row 21a).
  Refusals, each `SeasonStartRefused(reason)` with one sentence (§5, P114-16…23):

| `SeasonStartRefusal` | when | sentence |
|---|---|---|
| `SCHEDULE_ARCHIVED` | `s.status == ARCHIVED` | P114-19 |
| `NOT_MAINTAINED_HERE(assetId)` | the target asset (or an open member) fails `maintainedHere(held)` (`C/model/Asset.kt:60`) | P114-18 |
| `YEAR_ROUND(assetId)` | the asset (or an open member) is `YEAR_ROUND` | P114-17 |
| `LEAF_CHILD(assetId)` | it has a parent and no child row | P114-16 |
| `ALREADY_COVERED(assetId, scheduleId, title)` | another live `SEASON_START` covers it (C9's coverage, excluding `s`) | P114-20 |
| `GROUP_ARCHIVED` | the group target is archived | P114-22 |
| `GROUP_HAS_NO_MEMBERS` | the group has no open member | P114-22 |
| `DATE_IN_FUTURE` | `sinceOn` after today | P114-23 |

  For a group target every **open** member is checked; the first failing member, by name then id, is named (P114-21
  wraps the member sentence). The write moves `role`, `roleSinceOn` and `updatedAt` only — never `ruleChangedAt`,
  `postponedDueOn` or a provider row — and calls no recompute (the role changes no rule; row 21). **No API or MCP
  route reaches it in V1 (O-7).**
- **C11, the hooks on shipped commands — `SeasonStartStrands(assetId, cause, schedules)`, 409, phone sentences
  P114-24…27.** One shared pure helper `seasonStartStrandedBy(before, after, …)` in `C/usecase/SeasonCommands.kt`
  beside `strandedBy` (`:265-272`), called **after** the strands rule so a PRE_SERVICE refusal keeps priority:
  - `YEAR_ROUND` (cause `NEEDS_SEASONS`): `SetSeasonMode.setInTransaction` (`C/usecase/SetSeasonMode.kt:81`),
    `UpdateAsset`'s legacy pair (`C/usecase/UpdateAsset.kt:70`) and `SaveAssetSettings` (`:145`) — when the asset is
    covered (asset or group role) and the mode after is `YEAR_ROUND`. `SetMaintenanceBreak` (`:46`) cannot change the
    mode and gets no hook.
  - **Re-parent** (cause `WOULD_BE_LEAF_CHILD`): in `UpdateAsset` and in `SaveAssetSettings` when it edits an existing
    asset's parent (the brief confirms the callers of `validateAsset` with a non-null id by `git grep -n
    'validateAsset('`): refused when, after the change, a covered asset would have a parent and no child — the moved
    asset itself, or its **old parent** losing its last child. `validateAsset` (`C/usecase/AssetCommands.kt:84-121`)
    stays pure and unchanged.
  - **Retarget** (cause `ROLE_SCHEDULE_RETARGET`; O-6): `SaveSchedule.commit` refuses an edit whose `target` differs
    from the stored one while the stored role is `SEASON_START`.
    - **QUICK election** (cause `QUICK_NEEDS_ELECTION`; O-2, F-22): `SaveSchedule.commit` refuses an edit that
    changes a role schedule's `completionMode` from FORM to QUICK unless a new defaulted parameter
    `seasonStartQuickElected: Boolean = false` (beside `unlinkHealthSubject`, `C/usecase/SaveSchedule.kt:81`) is true;
    the phone's editor asks P114-5 and passes it; `PATCH /v1/schedules` and the MCP's `update_schedule` cannot, and
    answer `SEASON_START_STRANDED` (P114-43 on the phone).
  - **Group membership** (cause `MEMBER_NOT_ELIGIBLE` / `MEMBER_ALREADY_COVERED`): `SaveGroup.run`
    (`C/usecase/SaveGroup.kt:43-106`) refuses an added member of a group a live role schedule targets when the member
    fails C10's asset rule or is covered by another role.
  - **Not hooked (indirect, read as `ROLE_NOT_ALLOWED` / `TWO_ROLES` / `NO_ROLE` by C9):** asset delete, archive,
    retire, Replace asset, transfer out, a child deleted or replaced, a merge or replace restore bringing a second role.
- **C12, the full-replace save keeps the role.** `SaveSchedule.prepare` builds `candidate` wholly from the command
  (`C/usecase/SaveSchedule.kt:153-180`): it gains `role = existing?.role ?: NONE`,
  and `roleSinceOn = existing?.roleSinceOn` (`buildEvent`'s `occurrenceOn` precedent, `C/usecase/EventCommands.kt:289-291`).
  **Likewise the event (B):** `buildEvent` (`C/usecase/EventCommands.kt:271-295`, the one path of `LogEvent` and
  `UpdateEvent`, which already keeps `createdAt` at `:283`) gains `imported = existing?.imported ?: false`, so an edit
  never clears the marker (row 21a); no other event writer changes — a completion, a group completion and a log entry
  are new rows, `false` by default.
  `ScheduleCommand`, `ScheduleForms` (`A/api/ScheduleForms.kt`) and the MCP's `update_schedule` body never carry a role
  (K11). `createInTransaction` (`:90-91`), which `ReplaceAsset` uses to copy schedules (`C/usecase/ReplaceAsset.kt:362`),
  therefore creates `NONE`: a successor never inherits a predecessor's role or boundary (#114 point 6).
- **C13, the mapper.** `A/data/room/MaintenanceMappers.kt` (or wherever `MaintenanceScheduleEntity` maps — the brief
  confirms by `git grep -n 'MaintenanceScheduleEntity('`) maps `role` by enum name and `role_since_on` verbatim (and `A/data/room/JournalMappers.kt`, `:191` and its
  entity-to-domain twin, maps `imported`), and
  throws on a stored row that breaks R1 only in debug assertions — a read model never crashes on a merged row (it reads
  `NO_ROLE`).

### B1 — the outbound core (C14–C17; core JVM)

- **C14, the model (`C/hapublish/PublishModel.kt`).**
  - `PublishBinding(id, assetId, connectionId, kind: PublishKind, entityId, enabled, revision: Long,
    activatedInstallationId: String?, validatedAt: Long?, validatedRevision: Long?, boundaryEntityId: String?,
    createdAt, updatedAt)` — the columns of C4.2 under Kotlin names. `revision` is the **generation**: +1 on enable,
    disable, target change, re-validation and connection re-authorisation (C28).
  - `PublishDelivery(bindingId, desiredValue: String?, desiredRevision: Long, desiredAt: Long?, lastPublishedValue,
    lastPublishedAt, publishedDesiredRevision, lastVerifiedValue, lastVerifiedAt, remoteValue, remoteReadAt,
    errorKind: PublishErrorKind?, errorDetail, errorAt, errorBindingRevision: Long?, errorDesiredRevision: Long?,
    outcomeUnknownSince, needsDelivery: Boolean, reconcilePending: Boolean, attemptsAtRevision, lastAttemptAt,
    boundaryValue, boundaryReadAt, boundaryBindingRevision)`. **A04:** an error is **current** only when `(errorBindingRevision,
    errorDesiredRevision)` equals the binding's `(revision, desiredRevision)`; every pair-changing write also clears it
    (C5), so the two rules agree after any restart; no error history is kept (the block draws the current error only,
    and a repeat at the new pair records itself again).
    **Three facts kept apart (#113):** `lastPublishedAt` moves only when **our** POST at the current `(revision, desiredRevision)` was
    accepted **and** its readback matched (it records `publishedBindingRevision`, `publishedDesiredRevision`);
    `lastVerifiedAt` moves whenever a readback equals the desired value (with or without our POST) and records
    `verifiedBindingRevision`, `verifiedDesiredRevision`; `remoteValue` is whatever the last read saw. **F-1:** the
    delivery is current only when `lastVerifiedValue == desiredValue`, the verified pair equals the binding's
    current `(revision, desiredRevision)` **and** `needsDelivery` and `reconcilePending` are false (A05, C). **F-2:** the connection's re-authorisation bump (C28) moves `revision` and,
    when `validatedRevision` equalled the old revision, `validatedRevision` with it — a fence against in-flight
    replies, never a demand to re-validate; a pending `1970-01-01` keeps going.
  - `DecommissionTombstone(id, connectionId, entityId, assetId?, installationId, state: PENDING|UNDELIVERABLE,
    requestedAt, errorKind?, errorAt?, revision)`.
  - **`ValidationResult(target: String, remote: RemoteEntity, connectionId: String, configGeneration: Long,
    bindingRevision: Long, readAt: Long)`** (A09): what a fresh read proves and **where it was read**: the exact target,
    the connection's id and its **`configGeneration`** at the read (F: the counter column every `SaveHaConnection`
    moves by one, C28 — never `updatedAt`, which two saves in one clock millisecond would share; Forget + recreate
    changes the id), the binding's revision at the read (0 when none exists) and the read time. Built only by the
    runner's `validate` (C30), which answers the target's result and, for a date binding, the boundary helper's from
    **one** snapshot; C25 verifies both in the command transaction.
- **C14a, the allowlist (A11; `C/hapublish/HaServiceAction.kt`, B1's, core):**
  `enum class HaServiceAction(val domain: String, val service: String, val valueKey: String) { SELECT_OPTION("input_select",
  "select_option", "option"), SET_DATE("input_datetime", "set_datetime", "date") }` and the pure exhaustive
  `actionOf(kind: PublishKind)` (`EQUIPMENT_STATUS → SELECT_OPTION`, `SEASON_SERVICE_DATE → SET_DATE`). B2's client
  consumes it; row 44 runs in B1's own gate.
  - `enum class PublishErrorKind` — the reused transport kinds `ENDPOINT_REFUSED, DENIED, UNREACHABLE, TIMED_OUT,
    AUTH_REFUSED, REDIRECTED, HTTP_ERROR, MALFORMED, NEEDS_TOKEN, NOT_ON_LOCAL_NETWORK, NAME_NOT_LOCAL, TLS_FAILED,
    ENTITY_NOT_FOUND` and the new `WRITE_FORBIDDEN, WRONG_DOMAIN, OPTIONS_MISMATCH, NOT_DATE_ONLY, SERVICE_REFUSED,
    OUTCOME_UNKNOWN, REMOTE_DIFFERS, COPIED_INSTALLATION`. A **separate** enum: #16's `SyncErrorKind`
    (`C/seasonsync/SeasonSyncModel.kt:108-126`) and its ratified `when`s are untouched; a pure
    `publishKindOf(SyncErrorKind)` maps the shared transport members (exhaustive; the season-only members
    `UNSUPPORTED_STATE`, `NOT_MAINTAINED_HERE`, `NOT_MANUAL`, `DATE_BEFORE_HISTORY` map to `MALFORMED` /
    unreachable arms the brief documents).
  - **Retry class** (pure, exhaustive): `TRANSIENT` = `UNREACHABLE, TIMED_OUT, HTTP_ERROR` (a GET's 5xx only; a 4xx
    detail is permanent; a POST's 5xx is `OUTCOME_UNKNOWN`, C23, A10), `OUTCOME_UNKNOWN`; `WAIT` = `NOT_ON_LOCAL_NETWORK, DENIED` (no retry; the next start, resume or Publish
    now); every other kind `NEEDS_OWNER` (no retry until a command changes the binding or Publish now).
    - `PublishState` (derived for display and the API, never stored; every member has a sentence, §5): `NOT_ENABLED`,
    `COPIED_INSTALLATION`, `NEEDS_VALIDATION` (P113-30), `NEEDS_TOKEN`, `WAITING_FOR_SOURCE` (status kind, no condition), `NOT_MAINTAINED_HERE` (status
    kind: stopped, remote kept), `PENDING`, `OUTCOME_UNKNOWN`, `ERROR`, `PUBLISHED`, `MATCHES` (verified, never by our
    push), `INVALIDATED_INELIGIBLE` (date kind: `1970-01-01` verified while ineligible; quiet).
- **C15, the two projections (pure, `C/hapublish/Projections.kt`).**
  - `equipmentStatusOf(history: ConditionHistory): String?` = `OPERATIONAL → "UP"`, `DEGRADED → "DEGRADED"`,
    `DOWN → "DOWN"`, no row → **null** (never a fabricated `UP`); reads `history.current` only — a new reason with the
    same enum, a duplicate or a backdated non-current row leaves it unchanged by construction
    (`C/condition/ConditionHistory.kt:17-35`).
  - `seasonDateOf(readiness: Readiness): String` = `Ready.serviceOn.toString()` or `INVALID_SERVICE_DATE`.
  - `desiredValueOf(binding, asset?, maintainedHere, history, readiness): Desired` — `Desired.Send(value)` or
    `Desired.Nothing(state)`: asset missing → status `Nothing(NOT_MAINTAINED_HERE)` (the binding is retired by C34),
    date `Send(INVALID_SERVICE_DATE)`; not maintained here → status `Nothing(NOT_MAINTAINED_HERE)` (stop, keep the
    remote; #113, K12), date `Send(INVALID_SERVICE_DATE)` (decommission first; K12); else status
    `equipmentStatusOf` (null → `Nothing(WAITING_FOR_SOURCE)`), date `Send(seasonDateOf(readiness))`.
- **C16, the raw-entity mapper and the validators (`C/hapublish/RemoteEntityMapper.kt`).** Beside, never inside,
  `HaStateMapper` (`C/seasonsync/HaStateMapper.kt:42-62`), reusing its public `parseObject`, `isJsonMediaType` and the
  bounded-text helper: a 200 JSON object whose `entity_id` equals the requested id → `RemoteEntity(entityId, state:
  String (bounded 64), options: List<String>? (attributes.options, at most 64 strings, each bounded), hasDate:
  Boolean?, hasTime: Boolean?)`; on this **GET**, 401 and 403 are `AUTH_REFUSED` (#16's rule; the write's 403 is
  C23's `WRITE_FORBIDDEN`); 404 `ENTITY_NOT_FOUND`; 3xx `REDIRECTED`; other `HTTP_ERROR(code)`; non-JSON, truncated, another
  entity's answer, a non-string state → `MALFORMED`. Validators, pure:
  - `validateStatusTarget(entityId, remote)`: domain before the dot is `input_select` else `WRONG_DOMAIN` (checked
    before any read, from the id alone); `options` as a **set** equals `{UP, DOWN, DEGRADED}` exactly (case-sensitive,
    no extra, order immaterial) else `OPTIONS_MISMATCH`.
  - `validateDateTarget(entityId, remote)`: domain `input_datetime` else `WRONG_DOMAIN`; `hasDate == true &&
    hasTime == false` else `NOT_DATE_ONLY`. The same validator checks a boundary helper (C9a); `boundaryDateOf(remote)`
    answers the state as a `LocalDate` when it is exactly `YYYY-MM-DD`, else null (`unknown`, `unavailable`).
  - `remoteMatches(kind, remote, desired)`: exact string equality of `state` with the desired option, or with the
    desired `YYYY-MM-DD` (HA's date-only state).
- **C17, the delivery state machine (pure, `C/hapublish/DeliverySteps.kt`).** Inputs: one snapshot
  `Snap(binding, delivery, connectionPresent, hasToken, installationId)`; outputs a plan and, after I/O, a record.
  - **Gate:** not enabled, `activatedInstallationId != installationId` (`COPIED_INSTALLATION`), no token
    (`NEEDS_TOKEN`), `validatedRevision != revision` (`NEEDS_VALIDATION`), `desiredValue == null` → `Skip(state)`.
    **Per-send eligibility (A02, #113):** the snapshot carries the asset row and the held ids; an asset missing or not
    `maintainedHere(held)` → the status kind `Skip(NOT_MAINTAINED_HERE)` (no POST, the remote kept), the date kind sends
    only `1970-01-01` (any other desired value → `Skip(PENDING)` and a projector pass, which writes `1970-01-01`).
  - **Plan:** `ReadFirst` when `outcomeUnknownSince != null`, `needsDelivery` **or** the verified pair is not the
    current `(revision, desiredRevision)` (initial publication, re-enable, target change and reconcile compare first,
    #113); `Post(value)` when the read differs; **`Done` ⇔ `lastVerifiedValue == desiredValue`, the verified pair equals
    the current pair and `needsDelivery` and `reconcilePending` are false** (F-1, A05, C), and the caller is not a
    reconcile. `drain()` selects every enabled binding whose verified pair differs from its current pair, **or whose
    `needsDelivery` is set, or whose `outcomeUnknownSince` is set, or whose `reconcilePending` is set** (A05, C:
    outstanding work is selected regardless of an earlier verification; a `reconcilePending` binding is processed in
    reconcile mode), **except
    (R-2)** one whose **current** error (A04: `(errorBindingRevision, errorDesiredRevision) == (rev, dRev)`, persisted)
    has class NEEDS_OWNER (`AUTH_REFUSED`, `WRITE_FORBIDDEN`, `SERVICE_REFUSED`, `REMOTE_DIFFERS`, the
    validation kinds); the midnight tick and the worker, which are not eligibility changes, also skip one whose error
    at the current pair is WAIT. Reconcile mode (start, resume, Publish now) retries both — the plan's choice, so an
    owner who fixed HA's side sees it repaired at the next open — and so does any move of the pair (a command, a new
    desired value, a re-authorisation).
  - **Reconcile write-ahead (C):** reconcile mode first records `reconcilePending := true` (CAS on `(rev, dRev)`)
    before its GET(s); a **completed read** of the helper — and, for a date binding whose boundary is HA's (C9a), of the
    boundary helper too — at the current pair clears it **in the same write** that records the read (equal: verified;
    different: `needsDelivery := true`, then `Post`), so the work moves from one flag to the other and is never
    dropped between them; a GET that fails, is cancelled or dies with the process leaves it set, so an ordinary
    `drain()` — the worker's, `requestDelivery`'s — retries the read (row 57d). Nothing else clears it but Enable or
    Change target (C25), which owe nothing to the old helper.
  - **Steps:** `ReadFirst` → read; equal → record **verified** (`lastVerifiedAt`, `remoteValue`; not published;
    `needsDelivery := false`, `outcomeUnknownSince := null`) and stop; different → `Post`. **Before every POST, a
    write-ahead record** (A05, A10; CAS on `(rev, dRev)`; a failed CAS abandons the attempt): `needsDelivery := true`,
    `outcomeUnknownSince := now`, `lastAttemptAt := now`, `remoteValue` := the contradicting read — so a cancellation, a
    process death or a lost reply leaves the work visible to the next drain and makes it read first. `Post` → 200 →
    read back → equal → record **published + verified** (`needsDelivery := false`, `outcomeUnknownSince := null`); read
    back differs → `REMOTE_DIFFERS` (NEEDS_OWNER at this pair); POST `Refused` (known not to have reached HA, C23) → that
    kind, `outcomeUnknownSince := null`, `needsDelivery` kept; POST `Unknown` → kind `OUTCOME_UNKNOWN` (TRANSIENT;
    `outcomeUnknownSince` kept; the next attempt reads first, never re-POSTs blind); a read-back GET that fails → kind
    `OUTCOME_UNKNOWN` likewise. A readback whose validator fails → that validation kind, and `validatedRevision`
    cleared (re-validation needed). **`needsDelivery` is cleared only by a verified read-back equal to the desired
    value at the current pair** (or by C25's reset of a new target, which owes nothing to the old one).
  - **Record:** a compare-and-set on `(binding.revision, delivery.desiredRevision)` as snapshotted: a moved generation
    or a newer desired value **drops** the result entirely (no field moves, the sender loops); an error is recorded
    **with its pair** (`errorBindingRevision`, `errorDesiredRevision` := the snapshot's, A04); `attemptsAtRevision`
    counts per desired revision and resets when it moves. **Coalescing** is structural: only the latest desired value
    exists to send.
  - **Never:** a transport failure, a missing helper or an unavailable HA recorded as a source change or mapped to
    `DOWN`; `lastPublishedAt` moved by a read alone.

### A3 — format 21, the merge and the Transfer Pack (C18–C20; core JVM)

- **C18, format 21.** `MaintenanceScheduleDto` (`C/backup/BackupFormat.kt:341-368`) gains `role: String = "NONE"` and
  `roleSinceOn: String? = null` **after `providers`**, and `AssetEventDto` (`:275-297`) gains **`imported: Boolean =
  false`** after `detailsPending` (B), all written as explicit values (the format-17 reference-role precedent,
  `C/backup/BackupCodec.kt:139-145`); `toDto` (`:1258`) and `toDomain` (`:1296`) carry the role fields, the event's
  (`:1094`, `:1115`) the marker; `toDomain` refuses an unknown role name and a row breaking R1 (`BackupCorrupt`).
  `FORMAT_VERSION` 20 → **21** (`BackupCodec.kt:191`); `internal const val FIRST_SCHEDULE_ROLE_FORMAT = 21` beside
  `FIRST_RESOURCE_OWNER_FORMAT` (`:247`); a format ≤20 archive whose schedule carries a non-`NONE` role, or whose event
  carries `imported = true`, is refused as hand-built (the `:462-469` shape). Forward-only: 1.8.0 refuses a format-21
  archive with `BackupNewerFormat` and a format-21 pack with `NewerPack`. `VersionAgreementTest`
  (`T/VersionAgreementTest.kt:86-88`, `:158-159`) moves to 22/21; the `StageABundleConformanceTest` pin and `B/`'s
  format pin are checked and moved only as twins.
- **C19, the merge.** In `C/merge/MergePlanner.kt`'s schedules section (`:752-797`):
  - **Compare:** `rolesCompared = formatVersion >= FIRST_SCHEDULE_ROLE_FORMAT` (`:1062`'s shape); against an older
    archive a local row with a role compares without `role`, `roleSinceOn` and the `updatedAt` the role write moved
    (`sameReference`'s three arms, `:1063-1067`), so re-merging this phone's own pre-role export stays `IDENTICAL`.
  - **Events (B):** `sameEvent` (`:647-650`) compares **without `imported` at every format** — a matched row keeps
    the local marker: provenance belongs to the installation, never to the archive (a re-imported pack stays
    `IDENTICAL`).
  - **One coverage per asset (O-9, plan position: refuse):** an INSERT of a `SEASON_START` schedule whose coverage
    (C9's rule over the destination plus this plan's accepted groups and members, with a return's carried values,
    C20) meets an asset a destination role covers → `CONFLICT`, new reason **`SEASON_START_HELD_BY_A_LOCAL_ROW`**
    (detail: the local schedule id); one another accepted archive row already claimed → `CONFLICT`,
    **`SEASON_START_DUPLICATED_IN_ARCHIVE`** (the health-subject pair, `:1168-1201`, as conflicts). `docs/api/v1.md`'s
    merge tables name both (A3 edits only those rows).
  - Applying a merge writes the role and the marker as part of the inserted rows through the shipped
    `schedules.upsert` and event writes; the apply's edits are C20's (the context and the return carry), nothing else.
- **C20, the Transfer Pack (O-8, plan position; A01, A, B).** The role travels in the pack as data
  (`C/transfer/TransferGraph.kt:202` unchanged). **One immutable context (A01):** `data class ImportContext(val
  importDay: LocalDate)` in `C/merge/` (rev 3's `importAt` left with the schedule-level fence, B), captured **once** by
  `ImportTransferPack.preview` (a new trailing `today: Today` collaborator) and carried, never re-derived; its presence
  is what says "a Transfer Pack" to the planner:
  - `mergePlanOf(backup, snapshot, returning, context: ImportContext? = null, carry: ReturnCarry = ReturnCarry.NONE)`
    (`C/merge/MergePlanner.kt:285`) and `BuildBackupMergePlan.run(backup, incoming, returning, context:
    ImportContext? = null)` (`:97-114`) pass them;
  - `MergePlan` keeps the context (`val context: ImportContext?`, beside `backup`, `C/merge/MergePlan.kt:567-573`),
    and `fingerprintOf` (`:639-650`) hashes one `context|<importDay>` line when it is non-null and one
    `carry|<scheduleId>|<role>|<roleSinceOn>` line per carried role row, so a plan built with another context or
    carry is named stale;
  - `ApplyBackupMergePlan.runReturning`'s replan (`C/usecase/ApplyBackupMergePlan.kt:181`) becomes
    `mergePlanOf(plan.backup, scope.snapshot, returning, plan.context, scope.carry)`;
  - an import accepted after local midnight therefore writes the **preview's** day, the day the owner saw.
  - **The marker (B):** every event the plan INSERTs **under a context** is written `imported = true` (MergePlanner's
    event insert, `:965`) unless the return carry names it; a backup merge (no context) and a replace restore write
    the archive's value verbatim — the same owner's history counts as recorded (scenario 7; O-20). Nothing ever sets
    it back to false: `sameEvent` keeps the local value (C19), `buildEvent` keeps it (C12), and no role write touches
    an event (C10).
  - **The role on insert:** every incoming `SEASON_START` row the plan INSERTs (`:791`) gets `roleSinceOn =
    importDay` unless the return carry names it.
  - **The return (A).** `ReturnScope.of` (`C/usecase/ApplyBackupMergePlan.kt:280-380`) already takes a returning
    asset's schedules (its own and its groups', `:288-293`) and its events (`:294`) out of the snapshot, so the pack's
    rows plan as **inserts** (`:319-321`) — which, unamended, would re-date the role to the import day and mark the
    owner's own earlier service imported. It now also builds **`ReturnCarry(roles: Map<ScheduleId, Pair<ScheduleRole,
    String?>>, eventImported: Map<EventId, Boolean>)`** from exactly the rows it removes, and exposes it as
    `scope.carry`. In the planner, an inserted schedule the carry names takes the **local** `role` and `roleSinceOn`
    (never re-dated), whatever the pack row says; an inserted event the carry names keeps its **local** `imported`
    (an event recorded here before the asset went out counts again); every other pack event is `imported` (a
    borrower's record never counts, limit 14). `BuildBackupMergePlan.run` (`:114`) passes `ReturnScope.of(snapshot,
    returning)`'s carry beside its snapshot; both sides read it from their own snapshot, as they already read the
    scope. `ReturnCarry.NONE` for every other merge.
  A row matched to a local id (a re-import) compares without the role fields and the marker and keeps the local
  values. No pack key, class or manifest map moves; `C/transfer/**` is untouched.

### B2 — the POST client and the shared gate (C21–C23; app JVM)

- **C21, the gate, extracted.** The pre-request checks of `HomeAssistantStateClient` — endpoint classify and the
  INTERNET probe (`exchange`, `A/seasonsync/HomeAssistantStateClient.kt:136-140`) and `checkedExchange` (`:145-178`:
  the home-network check with its causes, private-only resolution of a name, the URL build and the same-host check) —
  move unchanged into one internal class `A/seasonsync/HaRequestGate.kt` that both clients call. It answers
  `Allowed(url) | Refused(SyncErrorKind, detail)` and **carries no request timeout** (F-7): the state client keeps its
  own `withTimeoutOrNull(CALL_MILLIS)` (`:141-142`) around gate + GET, so its behaviour is identical, and the
  publish client owns its timeout and its phase (C23). **No behaviour change:** every shipped test of
  the state client passes unmodified (G3; row 50 is the pin); the state client keeps `requestMethod = "GET"`, its four
  headers, caps and mapping.
- **C22, `HomeAssistantPublishClient` (`A/hapublish/HomeAssistantPublishClient.kt`, new; the third
  `HttpURLConnection` class).** Same constructor seams as the state client (`settings`, `networkPermissionGranted`,
  `currentNetwork`, `resolver`, `io`, `parse`, `open = { it.openConnection(Proxy.NO_PROXY) }`, `:69-79`). Two
  operations, each first through `HaRequestGate`:
  - `suspend fun read(connection, entityId, token): RemoteRead` — one `GET <base>/api/states/<entityId>` with #16's
    four headers, no redirect, no cache, 64 KiB cap, C16's mapper;
  - `suspend fun call(connection, action: HaServiceAction, entityId, value, token): CallOutcome` — one
    `POST <base>/api/services/<action.domain>/<action.service>`, `instanceFollowRedirects = false`, `useCaches =
    false`, `doOutput = true`, `setFixedLengthStreamingMode(body.size)`, headers exactly `Authorization: Bearer`,
    `Accept: application/json`, `Accept-Encoding: identity`, `Content-Type: application/json`, `User-Agent`
    (`DocumentTransport.USER_AGENT`); the body built by kotlinx-serialization from a two-key object (never string
    concatenation; the value already validated: an option in the three, or `^\d{4}-\d{2}-\d{2}$`); timeouts
    10 s / 15 s / 30 s whole call; a 200 body read and discarded under the 64 KiB cap.

```kotlin
// HaServiceAction is core's (C14a, B1); B2 consumes it.
sealed interface CallOutcome {
    data object Accepted : CallOutcome                         // a 200: accepted, never "verified"
    data class Refused(val kind: PublishErrorKind, val detail: String?) : CallOutcome   // never reached HA, or HA refused it explicitly (C23)
    data class Unknown(val detail: String?) : CallOutcome      // the body may have reached HA and may have acted
}
```

  The action is B1's `actionOf(kind)` (C14a); the path is built from the enum only. **Never** a log line, message or `toString`
  with the URL, host, entity, body, Wi-Fi name or token.
- **C23, the error mapping (POST; GET is C16; A10).** Two phases, split at **the start of transmission** — the moment
  `getOutputStream()` returns a stream (fixed-length streaming sends bytes as they are written, without buffering, so
  closing the stream is not a commit boundary): **before** it (gate refusal, DNS, connect, TLS handshake, the
  connection's own refusal) the request is **known not to have reached HA** → `Refused`; **from** it on (writing any
  byte, `close()`, waiting for the status line, reading the body, any timeout or reset) HA **may have received and
  acted on it** → `Unknown`, read back before any retry (C17). A status line is an answer, mapped by the table: an
  explicit refusal HA's API gives before acting (400, 401, 403, 404 on the service path, 3xx not followed, other 4xx)
  stays `Refused`; **every 5xx is `Unknown`** — HTTP gives a 500 no transactional meaning (RFC 9110 §15.6.1), and a
  service may have changed the helper before failing. The publish client's whole-call bound (30 s, wrapping gate +
  request) answers `Refused(TIMED_OUT)` before transmission starts and `Unknown` after (F-7). Cancellation propagates
  (disconnects); the write-ahead record (C17, A05) has already marked the attempt outstanding, so a cancelled attempt is
  never invisible to the next drain.

| what happens | outcome | `PublishErrorKind` | sentence |
|---|---|---|---|
| gate: endpoint refused / INTERNET denied / not on home network / name not local | `Refused` | `ENDPOINT_REFUSED` / `DENIED` / `NOT_ON_LOCAL_NETWORK` (+ #16's cause detail) / `NAME_NOT_LOCAL` | P16-12 / P16-15 / P16-50 (+ P16-68…81 by cause) / #16's sentence (reused) |
| DNS or connect fails, refused, unreachable, before transmission | `Refused` | `UNREACHABLE` / `TIMED_OUT` | P16-13 / P16-14 (reused) |
| TLS failure (any `SSLException` in the cause chain) before transmission | `Refused` | `TLS_FAILED` | #16's (reused) |
| 200 | `Accepted` | — | — |
| 400 | `Refused` | `SERVICE_REFUSED` (detail: the value) | P113-17 |
| 401 | `Refused` | `AUTH_REFUSED` | P16-16 (reused) |
| 403 | `Refused` | `WRITE_FORBIDDEN` | P113-18 |
| 404 | `Refused` | `HTTP_ERROR` (detail `404`) — a missing **entity** is found by the readback, not the service status (§10 c6) | P113-27 |
| 300–399 | `Refused` | `REDIRECTED` | #16's (reused) |
| other 4xx | `Refused` | `HTTP_ERROR` (detail the code) | P113-27 |
| 500–599 (A10) | `Unknown` | `OUTCOME_UNKNOWN` (detail the code) | P113-19 |
| any failure while writing a byte or in `close()`; read timeout, reset or EOF after transmission started; the 30 s whole-call bound after it (A10) | `Unknown` | `OUTCOME_UNKNOWN` | P113-19 |
| cancellation | propagates (disconnects); the write-ahead record keeps the attempt outstanding (A05) | — | — |

  The 401/403 split exists because a write may be refused for a user whose token reads fine (#113: "no assumption
  that a successful connection test proves write permission").

### B3 — ports, Room adapters and the commands (C24–C28; core JVM + app adapters)

- **C24, the ports (`C/hapublish/PublishPorts.kt`) and their Room adapters (`A/data/room/HaPublishRepositories.kt`).**
  `PublishBindingRepository` (`get(id)`, `forAsset(assetId)`, `byTarget(connectionId, entityId)`, `all()`,
  `observeAll()`, `insert`, CAS `update(binding): Boolean`, `delete(id)`), `PublishDeliveryRepository` (`get`,
  `upsert`, CAS `record(bindingRevision, delivery): Boolean`), `DecommissionRepository` (`all`, `observeAll`,
  `upsertMerging`, `delete`, CAS `update`). In-memory doubles in `CT/testing/InMemoryHaPublish.kt` with rollback
  (`InMemorySeasonSync.kt:28-150`'s shape) and a `ScriptedPublishTransport` beside `ScriptedHaStateReader` (`:157`).
  `AppGraph`/`FakeGraph` construct the three repositories and the commands (B3's AppGraph edit).
- **C25, the commands (`C/hapublish/PublishCommands.kt`; one `uow.write` each, no HTTP inside).** The app layer reads
  the helper **before** the command through the runner's `validate` (C30), which answers a `ValidationResult` (C14,
  A09); the command re-validates its `remote` (C16) **and verifies its provenance in the same transaction**:
  **`STALE_READ`** ⇔ the result's `target` is not the command's entity id, **or** its `connectionId` is not the stored
  connection's id (Forget + recreate), **or** its `configGeneration` is not the stored connection's `configGeneration`
  (F: the counter, so any connection edit since the read — even one in the same clock millisecond — C28), **or** its `bindingRevision` is not the stored binding's revision (0 when none:
  another setup, a target change or a revalidation since the read), **or** `readAt` is more than 10 minutes before the
  command's clock. The same rule applies to a date binding's boundary result (C9a). Nothing here claims resistance to
  a forged internal object; the provenance check is what makes "the owner validated this destination" true:
  - `EnablePublishing(assetId, kind, entityId, fresh: ValidationResult, boundary: ValidationResult?, installationId)`
    (D: `boundary` required for the date kind **exactly when the asset is `MANUAL` with an enabled #16 binding** —
    missing → `BAD_ENTITY_ID` (P113-29) at the boundary field; its id ≠ `entityId`, else
    `PublishRefusal.BOUNDARY_IS_TARGET`; for any other asset the sheet offers no field and the command stores none,
    C9a; null for the status kind): refusals
        `PublishRefusal.{BAD_ENTITY_ID, WRONG_DOMAIN, OPTIONS_MISMATCH, NOT_DATE_ONLY, NO_CONNECTION, NOT_MAINTAINED_HERE,
    TARGET_IN_USE, INBOUND_SOURCE, STALE_READ, BOUNDARY_IS_TARGET}` (P113-29, P113-16/P114-34, P113-15, P114-32, P16-10
    reused, P113-32, P113-22, P113-23, P113-31, P114-48); creates or updates the asset's binding of that
    kind, `enabled = true`, revision +1, `validatedRevision = revision`, `activatedInstallationId = installationId`; a
    PENDING **or UNDELIVERABLE** tombstone for the same `(connection, entity)` is deleted in the same write —
    superseded: the new owner writes its own value, and its block now draws that helper's state, so no separate dismiss
    or line is owed for the old obligation (N-2). **F-1:** Enable and Change target clear `lastPublished*`,
    `lastVerified*`, the verified and published pairs, `remote*`, `error*` with its pair (A04), `outcomeUnknownSince`,
    `needsDelivery` and `reconcilePending` (C) in the same write, and replace the boundary cache with the boundary
    result's date (when valid) at the new revision (D), so a re-enable after a decommission, or a new target, reads
    and publishes again and never shows an inherited "Published" line. **N-2:** Revalidate on the **same** helper
    clears only `error*` with its pair and re-seeds the boundary cache from its boundary result: its pair move already
    forces a read-back, `outcomeUnknownSince`, `needsDelivery` and `reconcilePending` stay (an outstanding attempt is
    still outstanding, A05, C), and its "Published" line is a true fact about our own push to that helper, kept. **F-9:**
    the claim is per enabled binding: another asset's **disabled** binding on the same pair does not block Enable (its
    `claim_entity_id` is null); re-enabling that disabled binding is then `TARGET_IN_USE`, naming the holder. Then the
    projector computes the first desired value (C31): **initial publication requires a known source** (status:
    `WAITING_FOR_SOURCE` sends nothing).
  - `ChangePublishTarget(assetId, kind, newEntityId, fresh, boundary, installationId)`: as Enable for the new id,
    including Enable's destination-specific confirm (P113-5 / P114-33 naming the new helper) — the **only** other
    command that stamps `activatedInstallationId` (A08); for the date
    kind a tombstone for the **old** `(connection, entity)` in the same write **only when the stored binding was
    enabled** (it held the claim; R-1) (C34); for the status kind the old
    helper is let go silently (#113: preserve the last reported value).
    - `DisablePublishing(assetId, kind)`: `enabled = false`, revision +1; for the date kind a tombstone in the same write;
    **disabling a disabled binding is a no-op** — no revision bump, no tombstone (R-1);
    the status kind writes **no** tombstone (#113: the remote value is preserved, F-10). A tombstone always carries the
    binding's `activatedInstallationId`, never the running one, so a copied installation draws it but never drains it
    (F-25).
    - `RevalidatePublishing(assetId, kind, fresh, boundary)`: "Check helper" — sets `validatedRevision` (revision +1)
    when the fresh read validates (and its provenance holds), else records the validation kind (N-2: the "Published"
    line is kept). **It never writes `activatedInstallationId` and never sets `enabled` (A08):** a copied row stays
    `COPIED_INSTALLATION` after a Check helper until Enable on this phone.
  - **Invariant I19 (R-1):** no PENDING tombstone coexists with an enabled binding whose claim names the same
    `(connection, entity)`. Every tombstone insert-or-merge (Disable, Change target, the projector's orphan retirement,
    Forget) first checks the claim in the same write and is **skipped** when an enabled binding holds the pair.
  - `DismissDecommission(id)`: deletes one tombstone at the owner's word ("I cleared it in Home Assistant", P114-38).
  - `RequestPublishNow(assetId?)` is not a command: the runner's entry (C30).
  - **Status kind on a not-maintained asset:** Enable is refused (`NOT_MAINTAINED_HERE`); an enabled binding whose asset
    becomes not maintained stops sending (C15) and resumes on its own when the asset is maintained here again (#16's
    R16-18 shape; #113 "re-evaluate on return/unarchive").
- **C26, the C2 cross-check (both directions, exact ids, one connection).** `EnablePublishing` and
  `ChangePublishTarget` refuse an `entityId` any `season_sync_binding` row names **or any publish binding names as its
  boundary helper** (`INBOUND_SOURCE`, P113-23), and a boundary id an enabled binding claims or a PENDING tombstone
  names (`BOUNDARY_IS_TARGET`, P114-48);
  `LinkSeasonSync.run` (`C/seasonsync/LinkSeasonSync.kt:83`) and `EditSeasonSyncEntity.run`
  (`C/seasonsync/SeasonSyncCommands.kt:120`) refuse an id any `ha_publish_binding` names, enabled or not, and any
  PENDING tombstone (new `SeasonSyncLinkRefusal.OUTBOUND_TARGET`, `C/seasonsync/LinkSeasonSync.kt:23`; P113-43). The
  domains are disjoint today (`input_boolean` in, `input_select`/`input_datetime` out — audit headline 9); the check
  is the guard for #100 and any later kind. The new collaborator reaches the two #16 commands as a trailing
  parameter `outbound: OutboundTargets = OutboundTargets.NONE` (G3, F-6). **B3 owns every exhaustive `when` the new
  member reaches (F-4):** `A/ui/asset/SeasonSyncStrings.kt:161-166` and `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296`
  (`OUTBOUND_TARGET` → P113-43), with P113-43 ratified before B3.
- **C27, the installation fence (A08).** `activatedInstallationId` is written only by **Enable**, and by **Change
  target** with Enable's destination-specific confirm — **never by Revalidate** — from `InstallationIdentity.id()` (`A/prefs/InstallationIdentity.kt:28`, the no-backup directory, created per
  installation). The sender, the projector and the tombstone drain compare it with the running installation and treat
  a mismatch as `COPIED_INSTALLATION`: nothing is sent, nothing is invalidated, the block says so (P113-21) and only
  an explicit Enable on this phone (after a Check helper, which alone activates nothing) re-stamps it; a restored row
  that arrived with `enabled = true` stays inert through any number of Check helpers. A token re-entered after a platform restore (#16's
  NEEDS_TOKEN path) therefore never re-activates a copied writer (audit headline 7, K7). Tombstones carry
  `installation_id` under the same rule.
- **C28, the shipped connection commands.** `SaveHaConnection` (`C/seasonsync/SeasonSyncCommands.kt:231-311`): its
  `reauthorized` branch (`:284-289`) also moves every publish binding's revision (CAS) and clears its error with the
  pair in the same write (A04), so a send built before the change is dropped and an `AUTH_REFUSED` is retried; **every**
  save adds one to the connection's **`configGeneration`** (F; `HaConnection.configGeneration: Long = 0`, appended
  last and defaulted in `C/seasonsync/SeasonSyncModel.kt:23-32`, so #16's constructions at
  `SeasonSyncCommands.kt:272` and `A/ui/homeassistant/HomeAssistantViewModel.kt:353` and every `CT/seasonsync` file
  compile unchanged; `A/data/room/SeasonSyncRepositories.kt:71`, `:82` map it), which makes every outstanding
  `ValidationResult` `STALE_READ` (A09); the app
  layer calls `requestDelivery()` after the commit (A03, C32); **O-12 (plan position): an address change is refused** with new
  `SaveHaConnectionRefusal.PUBLISHING_BLOCKS_ADDRESS_CHANGE` (P113-42) while any date binding is enabled or any
  tombstone of this connection is PENDING — the old HA must hear `1970-01-01` first. A token or network change is
  allowed and, per F-2, keeps every binding validated: pending invalidations are delivered, not parked in
  NEEDS_VALIDATION. B3 adds the `PUBLISHING_BLOCKS_ADDRESS_CHANGE` arm to
  `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462` (P113-42, ratified before B3); the new collaborators of
  `SaveHaConnection` and `ForgetHaConnection` are trailing defaulted parameters (F-6). `ForgetHaConnection` (`:318-332`): in the same write that deletes the connection, each enabled date
  binding and each PENDING tombstone of it becomes an **UNDELIVERABLE** tombstone (no foreign key, so it survives);
  the app layer runs the drain first (C34), as a `requestDelivery`-class foreground drain (A03). Status bindings go
  with the CASCADE.

### A4 — the readiness and role reads over API and MCP (C29; app JVM, pytest, docs)

- **C29.** `GET /v1/assets/{id}/season-readiness` → 200, computed by C9 from one `uow.read` snapshot inside the handler
  (no `AppGraph` edit; the handler builds from the graph's existing repositories):

| key | value |
|---|---|
| `assetId` | the path's id |
| `readyForSeason` | bool |
| `seasonServiceDoneOn` | `"YYYY-MM-DD"` (the qualifying date) or `null` |
| `reason` | `null` when ready, else a `NotReadyReason` name |
| `roleScheduleId` | the covering role schedule's id, or `null` |
| `roleTarget` | `"ASSET"` \| `"GROUP"` \| `null` |
| `roleSinceOn` | the commissioning boundary, or `null` |
| `cutoffOn` | `"YYYY-MM-DD"`: services dated on or after it count (F), or `null` without a role |
| `boundarySource` | `"CALENDAR_WINDOW"` \| `"PHONE_SEASON_END"` \| `"HA_SEASON_ENDED"` \| `"HA_UNVERIFIED"` \| `"COMMISSIONING"` (F), or `null` |
| `boundaryCheckedAt` | epoch millis of the HA read for `HA_SEASON_ENDED`, else `null` |
| `boundaryUnverifiedSince` | epoch millis whenever the asset's HA boundary is unverified (D), whatever won the cutoff, else `null` |

  A4's handler passes `HaBoundary.NotUsed` (A4 runs beside B3, and no date binding can exist before B6a — the gaps of
  §4); **B5 replaces that one argument** in `A/api/SeasonHealthHandlers.kt` with `haBoundaryOf(…)` (C9a) over the date
  binding's cache read in the same snapshot, `now` from the shipped clock. "Publication not verified"
  is **not** a readiness reason: it is the publishing projection's `state` (C35). Every
  schedule read (`ScheduleRowResponse`, `A/api/ScheduleRowResponse.kt:25-58`, and the MCP's `get_schedule`,
  `list_schedules`, `server.py:1591`, `:1609`) gains `role` (`"NONE"` \| `"SEASON_START"`) and `roleSinceOn`, response
  only. MCP `get_season_readiness` appended as a "#114" block (the #16 tail-pin precedent, R16-17), gated by
  `_MIN_HA_PUBLISHING_SCHEMA_VERSION = 22`; `update_schedule` (`:1805`) never forwards the role keys and a pytest pins
  that an update of a role schedule leaves it a role schedule. Router counts and the v1.md sub-resource sentence move
  as twins; `ApiReadsWriteNothingTest` covers the route.

### B4a — the runner (C30; app JVM)

- **C30, `PublishRunner` (`A/hapublish/PublishRunner.kt`).** One per graph, one `Mutex` (the one connection makes
  "one serialized sender per destination" one lock). Entry points: `publishNow(assetId: AssetId? = null)` (waits for
  the lock; Publish now, and reconcile mode: always reads the helper, so an HA reset or restart is repaired, #113);
  `drain()` (the worker's, the projector's and `requestDelivery`'s: C17's selection — a verified pair not current, or
  `needsDelivery`, or `outcomeUnknownSince` (A05), or `reconcilePending` (C) — less C17's R-2 skips, plus PENDING
  tombstones; returns whether TRANSIENT work remains: an error of that class, an unknown outcome, or a flag a transient
  failure left); `drainBeforeForget(): List<TombstoneOutcome>` (C34); **`validate(assetId, kind, entityId,
  boundaryEntityId?): Validated(target: ValidationResult, boundary: ValidationResult?) | refusal`** (A09, F: under the
  lock, **one** snapshot of the connection's id and `configGeneration` and the binding's revision, then the target's
  GET and, when a boundary id is given, the boundary helper's GET, then both results from that one snapshot — the only
  producer of a `ValidationResult`). Each binding: snapshot in one `uow.read` (binding, delivery, connection, **the asset row and the
  held ids** (A02's per-send eligibility, C17), `SecretStore.get(connection.id)` — the #16 key — installation id);
  **the boundary step** for a date binding (C9a: in reconcile mode always, otherwise when the cache is not fresh — one
  GET of `boundaryEntityId`, a valid date recorded by CAS, then the **`DesiredStateReconciler`** before this pass sends
  — an interface B4a declares in `A/hapublish/PublishSeams.kt` (`suspend fun reconcileDesired(today: LocalDate):
  Boolean`), the runner's only dependency on the projector, which implements it in B4b1);
  C17's plan; the write-ahead record before a POST (A05); at most one GET + one POST + one GET; then C17's CAS record
  in one `uow.write`; a dropped record loops at most twice per call (a third newer desired value waits for the next
  trigger). Never holds a
  transaction across I/O; never throws a transport failure (values only); a Room failure surfaces as
  `PublishDatabaseFailed` for the worker's retry (#16's `SeasonSyncDatabaseFailed` shape). The schedule rule is
  #16's `seasonSyncScheduleOf` (`A/seasonsync/SeasonSyncRunner.kt:46-55`) over "any publish binding enabled or any
  tombstone pending", reused, not copied.

### B4b1 / B4b2 — the projector and triggers (C31), the worker and the wiring (C32); app JVM

- **C31, `PublishProjector` (`A/hapublish/PublishProjector.kt`) and the reconcile.**
  - `suspend fun reconcileDesired(today: LocalDate): Boolean` — **one `uow.write` (A02), no HTTP inside:** it reads the
    enabled bindings, their deliveries (the `desiredRevision` and the boundary cache), the connection's cadence, the
    #16 bindings, assets, `transferRecords` held ids, conditions, schedules, groups, events and activations of the
    bound assets, runs C15 (and C9 with `haBoundaryOf`'s `HaBoundary`, `now` from the shipped clock, C9a) per binding, purely, and, per binding whose desired value **differs** from the stored one, writes it
    with `desiredRevision + 1`, `desiredAt` and the error cleared (A04) — a compare-and-set on the binding revision
    **and on the `desiredRevision` it read in this same transaction**. **The source version is that
    `desired_revision`** (chosen over the max of the observed rows' `updatedAt`, which a delete lowers): Room
    serialises write transactions, so a projection's read and write see one state, and a computation that read an
    older revision can never commit over a newer one (row 57a's barrier interleaving); a binding whose asset row is missing is
    retired as C34 says; so is a **disabled** binding whose asset is missing (F-9: its row is deleted, and no tombstone is written for it —
    R-1: it holds no claim; any obligation was written at Disable). Answers whether anything changed. **Idempotent:** a second call over the same database writes
    nothing (row 61). Duplicate, rolled-back and backdated-non-current writes change no projection and so write nothing.
  - **Observation — work arrived, never bookkeeping (C; prompt, not correctness).** On `appScope`, the declared seam
    **`PublishInputs.workArrived(): Flow<Unit>`** (B4b1, `A/hapublish/PublishInputs.kt`) combines exactly: the source
    rows — `conditions.observeAll()` (`C/ports/Repositories.kt:449`), `assets.observeAll()` (`:61`),
    `schedules.observeAll()` (`:193`), `groups.observeAll()` (`:168`), `transferRecords.observeHeldIds()` (`:552`)
    and, per enabled binding's asset, `events.observeForAsset` (`:123`) and `activations.observeForAsset` (`:434`);
    the bindings' **control fields** (`bindings.observeControls()`, C5, distinct until changed); the #16 bindings'
    `(assetId, enabled)` and the connection's cadence, distinct until changed (D: they decide the boundary's authority
    and its freshness window, C9a); and **new
    obligations** (`decommissions.observeObligations()`, emitting only when an `(id, revision)` appears that it has not
    seen, A03). **No delivery flow and no tombstone state or error write is an input:** the runner's attempt
    bookkeeping — the write-ahead, an error, a verified or published record, the boundary cache, `reconcilePending`,
    a cleared `validatedRevision`, a tombstone's error or state, a deletion — never triggers a projection, so a failure
    can never trigger the next attempt (row 57c); the boundary cache reaches the projection through the runner's own
    `DesiredStateReconciler` call (C30). Debounced 300 ms →
    `reconcileDesired(today)` → then **the delivery-work signal (A03), computed apart from its answer:** any enabled
    binding C17 would select (a generation not verified, `needsDelivery`, an unknown outcome, `reconcilePending`) or any
    PENDING tombstone
    → `requestDelivery()` (C32). A source change, a generation move (Enable, Change target, Revalidate,
    re-authorisation) and a new tombstone are therefore each enough on their own. Every
    writer of audit §1.2 (UI, offers, incident write, API/MCP, merge, Transfer Pack, replace restore) reaches Room, so
    the projector covers them with no use case touched.
  - **Triggers that do not depend on a flow (the crash gap and time-only change, K14):** `ServiceTagApp.onCreate`
    after `startSeasonSync` (`A/ServiceTagApp.kt:86-91`); the resume hook — `ResumeRefresh`
    (`A/seasonsync/ResumeRefresh.kt`, called at `A/ui/nav/ServiceTagRoot.kt:119-120`) gains the publish reconcile in
    the graph's lambda, `ServiceTagRoot` itself untouched; a **local-midnight tick** on `appScope` (delay until the
    next local midnight, re-armed; no receiver, no alarm — it also carries a boundary's time-only move from `Fresh` to
    `Unverified`, C9a); the worker's start; Publish now. **F-3:** start and resume run
    `reconcileDesired(today)` then **reconcile mode** (`publishNow(null)`: every enabled binding read back, so an HA
    restart or reset is repaired at the next open even under FOREGROUND_ONLY); resume is rate-limited to once per
    60 minutes of process time since the last **completed** read-back — a pass refused by the gate (WAIT,
    NEEDS_TOKEN, COPIED_INSTALLATION) does not start the window (N-1); start always runs it; the midnight tick and the worker
    run `reconcileDesired` then `drain()`; Publish now is reconcile mode.
- **C32, the work.** `PublishWorker : CoroutineWorker` reached through `HaPublishDispatch` (assigned synchronously in
  `ServiceTagApp.onCreate` beside `SeasonSyncDispatch.runner` at `:55`). Two unique works:
  - **`"ha-publish"`**, one-time, `CONNECTED`, `BackoffPolicy.EXPONENTIAL` from 30 s,
        `ExistingWorkPolicy.APPEND_OR_REPLACE` (F-26: a run whose snapshot predates a new desired value is followed by
    another, never dropped), **not expedited** (G16): body = reconcile + drain; `retry()` while `drain()` answers
    that TRANSIENT work remains — including `needsDelivery` or `reconcilePending` left by a transient failure (C) — and
    `runAttemptCount < 10`, else `success()` (bounded: WorkManager's exponential backoff from 30 s, capped by the
    platform; the periodic work, start, resume or Publish now resumes it); `retry()` on `PublishDatabaseFailed`; cancellation propagates.
  - **`"ha-publish-reconcile"`**, periodic at the connection's cadence (`SyncCadence.hours`), `CONNECTED`, `UPDATE`
    policy: body = reconcile in **reconcile mode** (every enabled binding read back) + drain, always `success()`.
  - Both enqueued only when C30's schedule rule answers `PERIODIC`; `FOREGROUND_ONLY` and `PAUSED` enqueue nothing and
    pending state waits for start, resume, midnight-in-process, Publish now **or the next control commit** (#113:
    background retries honour the connection's setting and its Android grants, `A/ui/homeassistant/LocationAsks.kt`).
    `ensure`/`cancel` after every C25/C28/C34 commit that changes "any enabled", the cadence or the eligibility.
  - **`requestDelivery()` (A03; `A/hapublish/PublishDispatch.kt`, B4b2).** One post-commit call, idempotent and cheap:
    while the process runs it launches `runner.drain()` on `appScope` (foreground, single-flight behind the runner's
    lock) when the connection's network rule allows a request now, and otherwise, when C30's rule answers `PERIODIC`,
    `PublishWork.enqueueOnce()`. **Every publishing control commit ends with it**: Enable, Change target, Revalidate,
    Disable (a tombstone or not), `SaveHaConnection` (token, network, cadence), `DismissDecommission` and Forget's
    drain — through one app-layer facade `PublishControls` (B4b2) that runs the core command and then calls it, so no
    view model can call a bare command and forget; the observer (C31) is the backstop, never the only path. WorkManager
    setup alone never replaces the foreground dispatch. **No self-trigger (C):** an in-process drain that leaves
    TRANSIENT work hands it, when C30's rule answers `PERIODIC`, to `PublishWork.enqueueOnce()` and its backoff; it
    never re-launches a drain itself, and nothing a drain writes calls `requestDelivery()` (under `FOREGROUND_ONLY` the
    work waits for start, resume, Publish now or the next control commit — the resume window does not start until a
    read completes, N-1).
  - **The seams and their direction (F; the interfaces table):** `DeliveryRequester` (`fun requestDelivery()`) is
    declared by B4b1 beside `PublishInputs`; the projector's observer and `PublishControls` depend on it, never on the
    dispatch; `PublishDispatch` (B4b2) implements it and depends on the runner (`drain`) and `PublishWork`; the runner
    depends on `DesiredStateReconciler` (C30), which the projector implements; the worker calls the reconciler, then
    `drain()`. No cycle: runner → reconciler ← projector → requester ← dispatch → runner is broken at the two
    interfaces, each wired in `AppGraph` by the brief that implements it.
    - **Wiring:** B4a wires the runner with a `DesiredStateReconciler` that B4b1 replaces with the projector; B4b1 the
    projector, `PublishInputs`, an in-process `DeliveryRequester` (launch `drain()` on `appScope`), the resume lambda
    (`AppGraph.kt:901`) and the midnight tick; B4b2 replaces the requester with `PublishDispatch` and adds the work
    seam; `FakeGraph` the scripted transport and a recording work seam.

### A5a / A5b — the phone: readiness and completion lines (A5a), the role and the strand sentences (A5b) (C33; app JVM)

- **C33.** Drawn from the existing read models; no use case added here.
  - **The season card** (`A/ui/asset/AssetDetailScreen.kt:477-488`, `SeasonSection` `:1278`): one readiness line under
    the season, **only when a role covers the asset or a date binding exists** (R-N1): `Ready` → P114-7 with the date;
    `NotReady` → P114-8…13, P114-29 or P114-46 by reason (`NO_ROLE` drawn only when a date binding exists: P114-40).
    Under it, **two lines (F)**: the **source line** by `BoundarySource`, exhaustive — P114-52 (`CALENDAR_WINDOW`),
    P114-51 (`PHONE_SEASON_END`), P114-44 with the date and its check date (`HA_SEASON_ENDED`), P114-45 with its since
    date (`HA_UNVERIFIED`), P114-53 (`COMMISSIONING`) — and the **cutoff line** P114-50 with `cutoffOn`. The view model
    reads C9 with `haBoundaryOf(…)` (C9a: the binding's cache, the asset's mode and #16 binding, the cadence and `now`
    from the shipped clock) and `today` from the shipped `Today`, and recomputes on resume (no cached `val`).
  - **The schedule row and detail** (`A/ui/maintenance/SchedulesSection.kt:25-…`, `ScheduleDetailScreen.kt`,
    `ScheduleDetailViewModel.kt`): the role label P114-1 with "from %s" (P114-41); the action "Use as Season Start
    service" (P114-2) / "Stop using as Season Start service" (P114-3), **its own action, never in the editor's save**
    (K11); the confirm P114-4 (asset) or P114-4g (group) with the date field (P114-6, default today, ≤ today) and, on a
        QUICK schedule, the note P114-5 (O-2); refusals P114-16…23 and P114-28 by `SeasonStartRefusal`, exhaustive.
  - **Glossary first (F-16):** A5a adds **Season Start service** and **ready for the season**, B6a **helper** and
    **publish**, to `docs/localization-glossary.md` in every shipped language before the strings that use them.
  - **The completion line** (`A/ui/maintenance/CompletionFlow.kt:183-210`, and the FORM route at `:196`): when the
    schedule completed is a role schedule, P114-14 (asset; the asset's name) or P114-15 (group) on the sheet before the
    write; nothing else in the flow changes.
  - **The hook refusals** drawn where the shipped command is called: the asset editor and settings save
    (`AssetViewModels.kt`, the season-mode and parent paths), the schedule editor (retarget), the group editor (member
        add; the FORM → QUICK election, P114-5 then P114-43) — P114-24…27 and P114-43 by `SeasonStartStrandCause`, exhaustive, beside the shipped strands sentence handling.

### B5 — decommission and the publishing read (C34–C35; core + app JVM, API, MCP)

- **C34, decommission — every exit, in order.** The obligation is "`1970-01-01` delivered and read back before
  ServiceTag stops writing a date helper":

| exit | what is written in the same transaction | then |
|---|---|---|
| role removed, role schedule archived, asset leaves the group, completion deleted or edited, new END, window change | nothing extra: the projector's desired value becomes `1970-01-01` (C15) | ordinary delivery; the binding stays enabled and **keeps owning** the helper |
| asset archived, retired, transferred out | nothing extra: desired `1970-01-01` (C15) | once verified, the binding reads `INVALIDATED_INELIGIBLE` and sends nothing more; it resumes on its own when maintained here again |
| asset deleted (`DeleteAsset`), replace restore wiping assets (`ImportBackupReplace.kt:196`) | nothing in the shipped use case: the binding row survives (soft link, C4.2) | the projector sees the asset missing and, in one write, converts the date binding into a PENDING tombstone and deletes the binding (status: deletes it, no tombstone, the remote value preserved — F-10) |
| Disable, Change target (C25), date kind | a PENDING tombstone for the old helper, carrying the binding's installation id (F-25); the status kind writes none (F-10) | drained like a binding |
| Forget connection (C28) | first `drainBeforeForget()` (one attempt per date binding and tombstone, bounded by C22's timeouts), then the command, which turns every undelivered one into UNDELIVERABLE | shown until dismissed |

  A tombstone drain is C17 with `desired = 1970-01-01`: readback equal → the row is deleted **by its id**, never by the
  `(connection, entity)` pair (so an Enable landing mid-drain, which deleted that row, is never touched); a NEEDS_OWNER kind or a
  missing connection or token → `UNDELIVERABLE`; TRANSIENT/WAIT → stays PENDING with its error. A PENDING tombstone
  is drawn on the asset's date block (or, when the asset is gone, on the HA screen) with P114-36; an UNDELIVERABLE one
  with P114-37 and "I cleared it in Home Assistant" (P114-38, `DismissDecommission`). **The same rule for an enabled
  date binding (F-16):** while its desired value is `1970-01-01` and its verified pair is not current, the block draws
  P114-36 (or P114-37 when the error is NEEDS_OWNER) **beside** its error sentence. **Never** a silent drop of an
  undelivered obligation.
- **C35, `GET /v1/assets/{id}/ha-publishing`** → 200 `{"assetId", "bindings": [ … ]}`, at most one per kind, each
  `{"kind": "EQUIPMENT_STATUS"|"SEASON_SERVICE_DATE", "entityId", "boundaryEntityId" (date kind; else null),
  "enabled", "state": PublishState name,
  "desiredValue", "lastPublished": null|{"value", "at"}, "lastVerified": null|{"value", "at"}, "lastError":
  null|{"kind", "detail", "at"}}` plus `"pendingDecommissions": [{"entityId", "state", "requestedAt"}]` for tombstones
  of this asset; times epoch millis; **never** the base URL, Wi-Fi name, token or installation id. MCP
  `get_ha_publishing` in the same "#113/#114" block. `ApiReadsWriteNothingTest` covers it.

### B6a / B6b — the phone, publishing: status block, sheet and HA screen (B6a), date block and tombstones (B6b) (C36; app JVM)

- **C36.**
  - **Two blocks on asset detail** (beside `SeasonSyncBlock`, `A/ui/asset/SeasonSyncBlock.kt:41-127`, and the
    condition card): "Publish equipment status to Home Assistant" (P113-1) and "Publish Season Start date to Home
    Assistant" (P114-30). Each draws: the destination entity id; the mapping (status) or the invalid-date meaning
    (date); the `PublishState` by an exhaustive `when` (P113-7…11, P113-15…24, P114-39, reused #16 sentences per C23);
    the last published and last verified lines kept apart; **Publish now** (P113-12), **Check helper** (P113-25),
    **Stop publishing** (P113-13) with its confirm (P113-14 status / P114-35 date); pending and undeliverable
    tombstones (P114-36…38); the delivery limit line (P113-26).
  - **The setup sheet** (a new `LinkPublishSheet` reusing `LinkSeasonSyncSheet`'s browser, `A/ui/asset/LinkSeasonSyncSheet.kt:215`,
    with an `EntityScope` parameter; `EntityScope` gains `INPUT_SELECTS("input_select")` and
    `INPUT_DATETIMES("input_datetime")`): pick or type the exact id (and, for the date kind on a `MANUAL` asset with an enabled
    #16 binding — only then, D — the season-ended helper, P114-47), a fresh read through `validate` (C30), the validation answer, then the confirm naming the exact
    destination and the mapping (P113-5 / P114-33), Enable — every control through `PublishControls` (C32).
  - **The Home Assistant screen** (`A/ui/homeassistant/HomeAssistantScreen.kt`, `HomeAssistantViewModel.kt`): the
        write disclosure P113-40 while any publish binding exists; the Forget warning P113-41 — an Android `<plurals>` in
    `values/plurals.xml` read with `localizedPlural` (`docs/localization.md:17-18`, `:39`; F-15) — with the count of
    date helpers before the shipped P16-9 confirm; the address-change refusal P113-42; orphan tombstones (asset gone) listed.
    - **The inbound sheet's `OUTBOUND_TARGET` arm is B3's** (F-4), not B6's.


### B7 — drawn and platform (C37; androidTest sources)

- **C37.** `AT/hapublish/HaPublishScreensTest.kt` (new Compose class: the readiness line in each state, the role
  confirm and a refusal, the completion line, both blocks in WAITING / PENDING / PUBLISHED / MATCHES / ERROR /
  COPIED_INSTALLATION / tombstone states, the setup sheet's validation refusals, the HA screen disclosure and Forget
  warning) and `AT/hapublish/HaPublishPlatformProofTest.kt` (new device class: the real `HomeAssistantPublishClient`
  POSTs to an in-process fake HA on the device — method, path, headers, body, no redirect followed; the readback; the
  `"ha-publish"` unique work enqueued once with `CONNECTED`; the periodic work's period; a deleted Keystore key reads
  `NEEDS_TOKEN`; the installation id file lives under `noBackupFilesDir`). No UI driving (#62).

### D1 — the documents (C38; docs only)

- **C38.** `docs/home-assistant-season-sync.md`: the two directions stated apart; ":14 It reads, and only reads"
  restated (reads for the season, writes only to the helpers you enable); helper setup for both kinds (the TODAY trap,
  restore behaviour, no `initial:`), the exact mapping, the invalid date, commissioning, the single-writer
  expectation, current-state and coalescing semantics, Android limits, revocation not instantaneous, the same-day
  limitation, the HA gate and freeze-override example (§8, fictional names), the interim. `docs/api/v1.md`: `:38`'s
  outbound inventory and `:39-42`'s "two `HttpURLConnection` classes" → three (the routes' rows are A4's and B5's).
  `app/src/main/AndroidManifest.xml:12` comment (the one manifest change); `A/fetch/UrlConnectionTransport.kt:20-22`
  KDoc (comment-only, the one `.kt` edit D1 may make, A11); the boundary helper and its fallback line (C9a); `docs/design/09-security-privacy.md`, `docs/local-first-and-permissions.md`,
  `docs/capabilities.md` (one paragraph each); `docs/release-proofs.md` the schema-22 / format-21 R7 paragraph beside
  `:139`'s. No release row in `docs/versioning.md` (the owner picks the vehicle).

### The two state machines, as tables (C17, C34)

**Delivery, per enabled binding.** Stored: the binding's `revision` (generation) and the delivery's `desiredRevision`,
`desiredValue`, `lastVerifiedValue`, `outcomeUnknownSince`, `needsDelivery`, `errorKind` with its pair. The snapshot is
`(rev, dRev)`; every record is a compare-and-set on both. **One rule for errors (A04):** an error is current only at its
recorded pair, and every row below that moves `rev` or `dRev` clears it in the same write. **Every control row ends in
`requestDelivery()` (A03).**

| from (derived `PublishState`) | event | guard | action | to |
|---|---|---|---|---|
| any | projector: desired value changes | binding enabled; CAS on the `dRev` read in the same transaction (A02) | `desiredValue := v`, `dRev += 1`, `attemptsAtRevision := 0`, error cleared (one write) | `PENDING` |
| enabled | Disable | — | `rev += 1`, error cleared; a tombstone for the date kind (R-1: only because the stored binding was enabled) | `NOT_ENABLED` |
| `NOT_ENABLED` | Disable | — | nothing (no-op, R-1) | `NOT_ENABLED` |
| any | `SaveHaConnection` re-authorises (token, network) | — | `rev += 1`, error cleared; `validatedRevision += 1` when it equalled the old `rev` (F-2) | unchanged (still validated; pending `1970-01-01` keeps going); in-flight results dropped; the verified pair is no longer current, so the next drain reads back |
| any | Enable, Change target | fresh read validates and its provenance holds (A09) | `rev += 1`; `activatedInstallationId := this` (A08); delivery's published, verified, remote, error, `outcomeUnknownSince`, `needsDelivery` and `reconcilePending` cleared (F-1, C); the boundary cache replaced by the boundary result's date at the new `rev` (D); Change target of an enabled date binding tombstones the old pair (R-1) | `PENDING` (reads first, then publishes) |
| any | Revalidate (same helper) | fresh read validates and its provenance holds (A09) | `rev += 1`; `error*` cleared; the boundary cache re-seeded (D); `outcomeUnknownSince`, `needsDelivery`, `reconcilePending` and the published line kept (N-2, A05, C); **`activatedInstallationId` and `enabled` untouched** (A08) | `PENDING` (reads back), or `COPIED_INSTALLATION` / `NOT_ENABLED` unchanged |
| `ERROR` (NEEDS_OWNER, current per its persisted pair, A04) | `drain()` (projector, midnight, worker, `requestDelivery`) | — | skipped (R-2) | unchanged until reconcile mode or a pair move |
| `ERROR` (WAIT, current per its persisted pair) | midnight tick, worker | — | skipped (R-2) | unchanged until reconcile mode, a projector change, a control commit or a pair move |
| `PENDING`, or any with `needsDelivery` / `outcomeUnknownSince` (A05) | drain | gate passes (C17, including per-send eligibility, A02) | snapshot `(rev, dRev)`; `ReadFirst` or `Post` | (I/O) |
| (I/O) | read equals desired, no POST made | CAS holds | `lastVerified* := v, now`, verified pair `:= (rev, dRev)`; `needsDelivery := false`, `outcomeUnknownSince := null`; error cleared | `MATCHES` |
| (I/O) | read differs; about to POST | CAS holds (else abandon) | **write-ahead (A05):** `needsDelivery := true`, `outcomeUnknownSince := now`, `lastAttemptAt := now`, `remoteValue := read` | (I/O, POST) |
| (I/O) | POST 200, readback equals desired | CAS holds | `lastPublished* := v, now`, published pair `:= (rev, dRev)`; `lastVerified* := v, now`, verified pair `:= (rev, dRev)`; `needsDelivery := false`, `outcomeUnknownSince := null`; error cleared | `PUBLISHED` |
| (I/O) | POST 200, readback differs | CAS holds | `errorKind := REMOTE_DIFFERS` at `(rev, dRev)`, `remoteValue := read`; `needsDelivery` kept | `ERROR` (NEEDS_OWNER) |
| (I/O) | POST `Refused(kind)` (never reached HA, or an explicit 4xx refusal; A10) | CAS holds | `errorKind := kind` at `(rev, dRev)`; `outcomeUnknownSince := null`; `needsDelivery` kept; `attemptsAtRevision += 1` | `ERROR` (by kind's retry class) |
| (I/O) | POST `Unknown` (transmission started, any 5xx, A10), or the read-back GET fails | CAS holds | `errorKind := OUTCOME_UNKNOWN` at `(rev, dRev)`; `outcomeUnknownSince`, `needsDelivery` kept from the write-ahead | `OUTCOME_UNKNOWN` |
| (I/O) | cancelled or the process dies after the write-ahead | — | nothing more written; the write-ahead stands | `OUTCOME_UNKNOWN` (drain reads first) |
| `OUTCOME_UNKNOWN` | drain | gate passes | `ReadFirst`: equal → as "read equals" (`outcomeUnknownSince := null`, `needsDelivery := false`, **not** published); differs → write-ahead, `Post` | `MATCHES` / (I/O) |
| (I/O) | a read or POST validator fails | CAS holds | `errorKind := WRONG_DOMAIN`/`OPTIONS_MISMATCH`/`NOT_DATE_ONLY`; `validatedRevision := null` | `NEEDS_VALIDATION` |
| (I/O) | any outcome | CAS **fails** (rev or dRev moved) | nothing written | the newer state; the runner loops (≤ 2) |
| any enabled | reconcile mode (Publish now, periodic, start, resume — F-3), or a drain selecting `reconcilePending` | gate passes; CAS holds | **reconcile write-ahead (C):** `reconcilePending := true`; then `ReadFirst` (and the boundary read, C9a) | (I/O) |
| (I/O) | the reconcile's read(s) complete | CAS holds | `reconcilePending := false` **in the same write** as the read's record: equal → as "read equals"; differs → `needsDelivery := true` (A05), then the POST write-ahead | `MATCHES` / (I/O, POST) |
| (I/O) | a reconcile GET fails, is cancelled, or the process dies | — | an error at `(rev, dRev)` for a failure, nothing for a cancellation; `reconcilePending` stays | unchanged; the next ordinary drain (worker, `requestDelivery`, a control commit) reads again (row 57d); never a self-trigger (C31) |
| any | asset not maintained here (status) | — | projector: `desiredValue := null` | `NOT_MAINTAINED_HERE` (sends nothing; remote kept) |
| any | asset not maintained here (date) | — | projector: `desiredValue := 1970-01-01` | `PENDING` → … → `INVALIDATED_INELIGIBLE` when verified |
| any | installation id differs | — | none (gate) | `COPIED_INSTALLATION` |

**Decommission tombstone, per `(connection, entity)` let go.**

| from | event | action | to |
|---|---|---|---|
| — | Disable or Change target of an **enabled** date binding (R-1), an enabled date binding whose asset row is missing (projector) | insert-or-merge `PENDING` with the binding's `activatedInstallationId` (merged: `revision += 1`, `requestedAt` kept) — **skipped** when an enabled binding claims the pair (I19) | `PENDING` |
| `PENDING` | drain: read equals `1970-01-01` (with or without our POST) | delete the row **by id** | (gone) |
| `PENDING` | drain: TRANSIENT or WAIT kind | `errorKind`, `errorAt` | `PENDING` (drawn with P114-36) |
| `PENDING` | drain: NEEDS_OWNER kind (auth, forbidden, entity gone, wrong domain, not date-only) | `state := UNDELIVERABLE` | `UNDELIVERABLE` (P114-37) |
| `PENDING` | Forget connection (after its drain) | `state := UNDELIVERABLE` | `UNDELIVERABLE` |
| `PENDING` or `UNDELIVERABLE` | a new binding enables on the same `(connection, entity)` | delete the row in the same write — superseded (N-2) | (gone) |
| `UNDELIVERABLE` | the owner taps P114-38 | delete the row | (gone) |
| any | installation id differs | none (fenced) | unchanged, drawn as P113-21 |

### Cross-brief invariants (each named by at least one row)

| I | invariant | rows |
|---|---|---|
| I1 | `roleSinceOn != null` ⇔ `role == SEASON_START`, in Room, the domain and the archive | 17, 46 |
| I2 | at most one live `SEASON_START` covers an asset, enforced by commands; a second from a merge is a conflict; one met anyway reads `TWO_ROLES` | 15, 48, 10 |
| I3 | readiness is a pure function of the asset tree, schedules, groups, this asset's events, its activations, `today` and its `HaBoundary` (computed outside it, C9a); no clock | 1–11, 3a–3c |
| I4 | the equipment value is a pure function of `ConditionHistory.current`; null means nothing is sent | 25–28 |
| I5 | the date value is the qualifying date or `1970-01-01`, never absent while a date binding is enabled and its asset exists | 26, 29, 70 |
| I6 | `lastPublishedAt` moves only for our accepted POST whose readback matched at the current `(rev, dRev)` | 35, 39, 41 |
| I7 | a result recorded at a moved `rev` or `dRev` writes nothing | 40, 57 |
| I8 | no transaction is open across an HTTP call | 57 |
| I9 | one POST at a time per installation | 57 |
| I10 | the only POST paths are the two of `HaServiceAction` | 44, 52, R6 |
| I11 | no binding writes an entity an inbound binding reads, and the reverse | 86 |
| I12 | a binding activated on another installation sends nothing and invalidates nothing | 69 |
| I13 | no `ha_publish` row or byte is in any archive, merge or pack; the role is | 12, 46 |
| I14 | every exit from a date helper leaves `1970-01-01` delivered or an obligation drawn | 70–73 |
| I15 | a transport failure, missing helper or unavailable HA never changes a source fact or reads as DOWN | 42 |
| I16 | a full-replace schedule save, an API PATCH and the MCP update keep the stored role | 21, 81, 84 |
| I17 | #16's GET client and its tests are unchanged | 50, R6 |
| I18 | the projector is idempotent and is re-run at start, resume, midnight, the worker and Publish now | 61, 66, 68 |
| I19 | no PENDING tombstone coexists with an enabled binding claiming the same pair (R-1) | 96, 97 |
| I20 | a projection's source read and desired write are one transaction; a computation over an older `desiredRevision` never commits; a send re-checks eligibility (A02) | 57a, 57b |
| I21 | every publishing control commit is followed by `requestDelivery()`; no delivery waits for an unrelated trigger (A03) | 70a |
| I22 | an error is current only at its persisted pair, and every pair move clears it (A04) | 42a, 42b |
| I23 | `needsDelivery` is set before any POST and by any contradicting read, and only a verified read-back clears it (A05) | 38a |
| I24 | an event is `imported` exactly when a Transfer Pack import inserted it (or it came back carrying that local value); an `imported` event never qualifies; no edit, role change or backup merge clears the marker (B) | 3b, 21a, 49b, 49c |
| I25 | the boundary is decided per asset: a `CALENDAR` window is never overridden by HA; HA's `season_ended` only for a `MANUAL` asset with an enabled #16 binding and a named boundary helper, and only while fresh; otherwise unverified (the later of cached and phone END), reported as such (D) | 3a, 3c, 98–101b |
| I26 | no command but Enable (and Change target with its confirm) stamps `activatedInstallationId` (A08) | 69 |
| I27 | `Refused` only before transmission or for an explicit 4xx refusal; everything else after transmission started is `Unknown` (A10) | 54, 54a |
| I28 | the projector observes only work-arrived inputs; no delivery write and no tombstone state or error write triggers a projection or a drain (C) | 57c |
| I29 | `reconcilePending` is set before a reconcile's GET and cleared only by a completed read, in the same write that hands any difference to `needsDelivery` (C) | 57d |
| I30 | readiness and the projections read no equipment type, category, template, title or name; each asset's season, cutoff and readiness are its own (G22) | 102–106 |
| I31 | a returning asset keeps its local role, `roleSinceOn` and each of its own events' marker; only rows new to it take the insert rules (A) | 49c |
| I32 | `configGeneration` moves by one on every connection save; a `ValidationResult` from before any save is `STALE_READ` (F) | 60a, 87 |

### Interfaces between briefs (consumes → produces)

| brief | consumes | produces (the boundary later briefs code against) |
|---|---|---|
| S1 | — | the v22 entities and DAOs (C4, C5), including `observeControls()` and `observeObligations()` and the `imported`, `config_generation` and `reconcile_pending` columns; `MIGRATION_21_22`; `22.json` |
| A1 | `SeasonContext`, `AssetTree`, the model | `ScheduleRole`, the two role fields, `AssetEvent.imported`; `lastSeasonEndOnOrBefore`; `HaBoundary`, `BoundarySource`, `ReadinessBoundary`; `seasonReadiness(…, haBoundary) : Readiness` with the `!imported` rule |
| B1 | `ConditionHistory`, `Readiness` (A1), `HaStateMapper`'s public helpers | `PublishKind`, `PublishBinding`, `PublishDelivery`, `DecommissionTombstone`, `ValidationResult`, **`HaServiceAction` + `actionOf`** (C14a, A11), `PublishErrorKind` + retry class, `PublishState`, `equipmentStatusOf`, `seasonDateOf`, `desiredValueOf`, `RemoteEntity` + mapper, validators, `boundaryDateOf`, **`haBoundaryOf`** (C9a, D), `DeliverySteps` with `reconcilePending` |
| A2 | A1's model; S1's columns | `SetScheduleRole`, `SeasonStartRefused`, `SeasonStartStrands`, the role mapping, the two API codes |
| A3 | A1's model | format 21 (the role fields, the event marker); `FIRST_SCHEDULE_ROLE_FORMAT`; the two `MergeReason`s; `ImportContext` through preview, plan, fingerprint and apply (A01); `ReturnCarry` from `ReturnScope` into the planner (A); the marker on pack inserts (B) |
| B2 | B1's kinds, mapper and `HaServiceAction`; #16's gate | `HaRequestGate`; `HomeAssistantPublishClient.read/call`; `CallOutcome` |
| B3 | S1's DAOs; B1's model and validators; `InstallationIdentity`; ratified P113-42/43 | the three ports and Room adapters; the three new `when` arms; `EnablePublishing`, `ChangePublishTarget`, `DisablePublishing`, `RevalidatePublishing`, `DismissDecommission`; the C26/C28 refusals; the in-memory doubles |
| A4 | A1's rule; A2's mapping (the HA boundary argument arrives with B5) | `GET …/season-readiness`; the schedule `role` keys; `get_season_readiness` |
| B4a | B2's client; B3's ports; `SecretStore` | `PublishRunner.publishNow/drain/drainBeforeForget/validate` (`Validated(target, boundary)`, F); the boundary step; the write-ahead and reconcile write-ahead records; **`DesiredStateReconciler`** (declared; the runner's only edge to the projector); `PublishDatabaseFailed` |
| B4b1 | B4a's runner and `DesiredStateReconciler`; the source repositories' flows, `observeControls()`, `observeObligations()` — **never a delivery flow** | `PublishProjector` implementing `DesiredStateReconciler`; **`PublishInputs.workArrived()`** and **`DeliveryRequester`** (declared; an in-process implementation); the observer; the midnight tick; the start and resume read-back reconcile |
| B4b2 | B4b1's `DeliveryRequester`; B4a's `drain()` | `PublishDispatch` implementing `DeliveryRequester` (in-process drain, else `enqueueOnce`, never self-triggered); `PublishWorker`, `PublishWork` (both unique works), `HaPublishDispatch`, `PublishControls` (command, then `requestDelivery()`) |
| A5a | A1; B1's `haBoundaryOf`; B3's ports (read) | the readiness line, the source and cutoff lines (F), the completion line; `ReadinessStrings`; two glossary terms |
| A5b | A2, A5a | the role action and dialogs, the refusal and strand sentences, the editor's QUICK election |
| B5 | B4a, B4b1, B4b2, B3; B1's `haBoundaryOf` | the readiness route's `HaBoundary` argument (C29); the decommission paths; `GET …/ha-publishing`; `get_ha_publishing`; the Forget orchestration |
| B6a | B3's commands, B4a's runner, B5's read model and `drainBeforeForget`, A5a's strings | the status block, the blocks' frame, the setup sheet, the scopes, the HA screen lines, two glossary terms |
| B6b | B6a, B5 | the date block, the tombstone lines and the F-16 rule |
| B7 | everything drawn; B2, B4b2 | two androidTest classes |
| D1 | everything | the documents and the R7 paragraph |

## 3. Test matrix

Rows: id · layer (**JVM** core or app; **Compose**; **contract** = API route or pytest; **platform** = device class;
**real-HA** = §7's controller proof, never a gate) · class · cases · the behaviour proven · **the mechanism by which it
fails without the change** (the counted RED is that mutation, `--no-build-cache --rerun-tasks`, quoted, reverted). Case
names are the implementer's; what each proves is not. "pin" = a shipped assertion re-run unchanged, no RED.

### A1 — the rule (rows 1–11, 3c, 102–104, 106; `CT/season/SeasonReadinessTest`, `CT/season/SeasonStoriesTest`, `CT/schedule/SeasonContextLastEndTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 1 | JVM | `SeasonContextLastEndTest` · `manualAnswersTheLatestEndOnOrBefore`; `manualStartOnlyAnswersNull`; `anEndAfterAnEndAnswersTheLater`; `yearRoundAnswersNull` | C8 MANUAL / YEAR_ROUND | the filter on `action == END` removed (a START is answered) |
| 2 | JVM | same · `calendarAnswersTheFirstOutOfSeasonDayOfTheLatestFinishedWindow` (window 10-01…04-20, d = 2026-04-21 → 04-21; d = 04-20 → 2025-04-21); `aWrappingWindowAndFeb29Resolve`; `aMalformedWindowAnswersNull` | C8 CALENDAR (O-1), K4 | `latestEndOnOrBefore(d)` used instead of `(d − 1) + 1` (04-20 answered on 04-20) |
| 3 | JVM | `SeasonReadinessTest` · `aQualifyingCompletionAfterTheEndIsReady` (scenario 1: END 04-20, service 09-18 → Ready 09-18); `theLatestOfSeveralQualifyingIsPublished` (O-3) | C9 core, AC114-2 | `>=` written `>` against the boundary **and** `max` replaced by `first` — two counted |
| 3a | JVM | same · `aFreshHaBoundaryWinsOverALaterPhoneEnd` (MANUAL; `Fresh` 05-10, phone END 10-22, service 09-18 → Ready, `HA_SEASON_ENDED`, `checkedAt`); `anUnverifiedBoundaryUsesTheLaterOfTheCacheAndThePhoneEnd` (cached 05-10, END 10-22 → cutoff 10-22, NotReady, `HA_UNVERIFIED`, `unverifiedSince` set); `notUsedUsesThePhoneEnd` (`PHONE_SEASON_END`); `theCutoffAndItsSourceAreReported` (`COMMISSIONING` when `roleSinceOn` is later) | C9, C9a, D, I25 | the `Unverified` arm reading the cached date alone (a stale cache passes as HA's word) |
| 3b | JVM | same · `anImportedEventNeverQualifies` (dated the import day → `INHERITED_SERVICE_ONLY`); `anImportedFutureDatedEventNeverQualifiesOnItsDay`; `theRecipientsOwnServiceQualifies`; `noClockOrCreatedAtTakesPart` (the same events with `createdAt` varied answer alike) | C9 evidence rule, B, I24 | the `!imported` filter removed (the seller's service makes the buyer Ready) |
| 3c | JVM | same · `aWarmSpellEndIsHarmlessWhileTheHaBoundaryIsFresh` (story 1: `Fresh` 05-10 last year; phone END 03-10 and START 03-20 this year; service 09-18 last year → Ready, `HA_SEASON_ENDED`); `theSameSpellWithholdsWhileUnverified` (→ NotReady, `HA_UNVERIFIED`, limit 16) | D, E, story 1 | a `MANUAL` asset with a `Fresh` boundary taking the later of HA's date and the phone's END (the warm spell revokes) |
| 4 | JVM | same · `aCompletionOnTheEndDayCounts` (END and completion both 04-20); `aCompletionTheDayBeforeTheEndDoesNot`; `aFutureDatedCompletionCountsOnlyFromItsDay` (F-8: `FUTURE_DATED` today, Ready on its day) | the same-day pin (#114 §2, K4); O-16 | the comparison made strict; **and** the `<= today` filter removed — two counted |
| 5 | JVM | same · `aCompletionBeforeTheCommissioningBoundaryNeverCounts` (role set 10-22, completion 10-21 → `NOT_DONE_SINCE_COMMISSIONING`); `theLaterOfEndAndCommissioningWins` | commissioning boundary (#114 review 2, scenario 1 setup) | the boundary computed from the END alone |
| 6 | JVM | same · `aDetailsPendingCompletionIsNotReadyWithItsReason`; `filledInDetailsMakeItReady` (the same event with the flag cleared); `anOlderQualifyingPlusANewerPendingIsReadyFromTheOlder` | K3, scenario 3 | the `!detailsPending` filter removed |
| 7 | JVM | same · `aNewEndAfterTheServiceMakesItNotReady`; `aBackdatedEndBeforeTheServiceKeepsItReady`; `aBackdatedEndAfterTheServiceRevokesIt` | AC114-3 re-derivation | `lastSeasonEndOnOrBefore(today)` replaced by the END cached at role time |
| 8 | JVM | same · `deletingOrRepointingTheCompletionReDerives` (an event re-pointed to another schedule of the asset via `UpdateEvent`'s `scheduleId`, `EventCommands.kt:289`); `anotherSchedulesCompletionNeverCounts`; `aChildsOrParentsCompletionNeverCounts` | AC114-3, "never inferred" | the `scheduleId == s.id` or `assetId == asset.id` filter removed |
| 9 | JVM | same · `aCalendarToManualSwitchRowIsABoundary` (the switch END dated today revokes an earlier service — pinned and documented); `aManualToCalendarSwitchReadsTheWindow` | inv. 90 consequences (C8) | activations read in CALENDAR (the window ignored) |
| 10 | JVM | same · `noRoleIsNoRole`; `anArchivedRoleScheduleIsScheduleArchived`; `aPausedRoleScheduleIsLive`; `twoRolesAreTwoRoles`; `aLeafChildOrYearRoundIsRoleNotAllowed`; `aParentWithAnArchivedChildIsAllowed` (O-5); `aRoleOnAnArchivedGroupIsScheduleArchived` (F-28) | C9's reason order | the `status != ARCHIVED` filter removed (an archived schedule's completion reads Ready) |
| 11 | JVM | same · `aGroupRoleRunsPerMemberOnItsOwnEvents` (C1: "Example Boiler" done, "Example Heater" not → the heater NotReady, the boiler Ready); `aRemovedMemberIsNoRole`; `conditionHealthPhaseAndRemindersChangeNothing` (the same inputs with each varied) | C1, AC114-3 "never" | the `assetId` filter dropped for group schedules (the boiler's event makes the heater Ready) |
| 102 | JVM | `SeasonStoriesTest` (story 2) · a fictional winter hot tub, `CALENDAR` 11-01…03-31, an opening-service role schedule and a separate closing schedule: the opening service 2026-10-20 → Ready on 10-20, 11-01 and **2027-01-15 (across New Year)**, `CALENDAR_WINDOW`, cutoff 2026-04-01; on 2027-04-01 → NotReady `NOT_DONE_SINCE_SEASON_END`; a closing-schedule completion 2027-04-05 never counts; its condition stays as recorded (out of season is never `DOWN`, row 28) | G22, C8, C9, I30 | the window's end resolved in today's year without stepping back (on 2027-01-15 the cutoff becomes 2027-04-01 and the October service is refused) |
| 103 | JVM | same (story 3) · a fictional summer pool, `CALENDAR` 06-01…08-31: a 2026-05-20 service → Ready (cutoff 2025-09-01); 2026-09-01 → NotReady; a 2027-05-15 service → Ready | G22, C8 | `seasonEndAt(today)` used instead (null out of season: the cutoff falls back to commissioning and 09-01 ends nothing) |
| 104 | JVM | same · `twoAssetsKeepIndependentSeasonsAndReadiness`: the hot tub (`CALENDAR` winter), the pool (`CALENDAR` summer) and a `MANUAL` appliance, each with its own role schedule — each one's service, END, cutoff and source move only its own readiness (each varied in turn, the others equal) | G22, I30 | readiness reading the activations or events of every asset passed (one asset's END revokes another's service) |
| 106 | JVM | same · `nameCategoryTitleAndTypeNeverDecideTheSeason`: the same inputs with the asset's name ("Example Hot Tub", "Example Heater"), category and the schedule's title varied answer alike; a `CALENDAR` asset named like a heater reads its window | G22, I30 | none: tripwire (C9's signature carries no such input); R6's grep is the guard |

### S1 — schema 22 (rows 12, 22–24 and pins; `T/data/room/Migration21To22Test`, `HaPublishDaoTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 12 | JVM | `T/backup/SeasonSyncNeverTravelsTest` (+4) · `anExportCarriesNoPublishBindingDeliveryTombstoneOrEntityByte`; `aMergeLeavesThemAndTheirRevisions`; `aTransferPackCarriesNone`; `aReplaceRestoreLeavesThemForTheProjector` | G5, G7, AC113-8 | none: tripwires (row 22's RED covers the shape) |
| 22 | JVM | `Migration21To22Test` · `theRoleColumnsDefaultToNoneAndNull`; `theEventMarkerAndTheConnectionGenerationDefaultToZero` (B, F); `theThreeTablesExistEmptyWithTheirIndicesAndKeys`; `everyOtherV21TableIsUnchanged` (the #47 snapshot shape; `maintenance_schedule`, `asset_event` and `ha_connection` differ by their added columns only); `theMigratedSchemaEqualsAFreshVersion22` | C4 | the binding's `asset_id` written with a CASCADE key (the schema compare fails) |
| 23 | JVM | `HaPublishDaoTest` · `oneBindingPerAssetAndKind`; `twoEnabledBindingsCannotClaimOneEntity` (`claim_entity_id`); `disabledBindingsMayShareATarget` (claims null, A11/F-9); `deletingTheConnectionTakesItsBindingsButNoTombstone`; `deletingTheAssetLeavesTheBinding` | C4, G1 | `UNIQUE(connection_id, claim_entity_id)` dropped **and**, separately, the index put on `entity_id` (disabled reuse refused) — two counted |
| 24 | JVM | same · `aStaleRevisionUpdateAnswersFalse`; `aDeliveryRecordAtAMovedDesiredRevisionAnswersFalse`; `aPairMovingWriteClearsTheErrorAndItsPair` (A04); `observeControlsIgnoresBookkeepingWrites` (a cleared `validatedRevision` emits nothing new, C); `anObligationsRevisionMovesOnlyOnInsertOrMerge` (C) | C5 CAS, C5 clearing, C5 flows | the delivery CAS reads only the binding revision |

### A2 — the role command and hooks (rows 13–21, 21a; `CT/usecase/SetScheduleRoleTest`, `SeasonStartStrandsTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 13 | JVM | `SetScheduleRoleTest` · `aTopLevelManualAssetTakesTheRoleDatedToday`; `aParentCalendarAssetTakesIt`; `aMidLevelAssetQualifiesAsAParent` (#114 point 1); `aChosenPastDateIsKept`; `aFutureDateIsRefused` | C10, AC114-1, O-4 | `sinceOn` ignored (always today) |
| 14 | JVM | same · each refusal of C10's table with nothing written (counting doubles) | C10 refusals | the leaf-child check reads "has a parent" only |
| 15 | JVM | same · `aSecondRoleOnTheAssetIsAlreadyCovered`; `aGroupRoleOverACoveredMemberIsAlreadyCovered`; `anAssetRoleOverAGroupCoveredAssetIsAlreadyCovered` | one role per asset across targets (K5) | coverage reads asset targets only |
| 16 | JVM | same · `aGroupWhoseOpenMembersAllQualifyTakesIt` (C1); `anIneligibleOpenMemberIsNamed`; `aRemovedMemberIsNotChecked`; `aGroupWithNoOpenMemberIsRefused` | C1 eligibility | `openMembers()` replaced by `members` (a removed member refuses) |
| 17 | JVM | same · `clearingWritesNoneAndNull`; `clearingAnArchivedScheduleIsAllowed`; `theWriteMovesRoleSinceOnAndUpdatedAtOnly` (rule fields, `ruleChangedAt`, `postponedDueOn`, providers equal) | C10 write | `ruleChangedAt` stamped (the pin's floor moves) |
| 18 | JVM | `SeasonStartStrandsTest` · `yearRoundIsRefusedOnACoveredAssetBySetSeasonModeUpdateAssetAndSaveAssetSettings` (three writers); `aPreServiceStrandStillWinsFirst` | C11 `NEEDS_SEASONS` | the `UpdateAsset` hook omitted (the legacy pair reaches YEAR_ROUND; §10 c3) |
| 19 | JVM | same · `reparentingARoleAssetIntoALeafChildIsRefused`; `movingTheLastChildAwayFromAChildRoleAssetIsRefused`; `reparentingAnUncoveredAssetIsUnchanged` | C11 `WOULD_BE_LEAF_CHILD` | the old-parent half of the check removed |
| 20 | JVM | same · `retargetingARoleScheduleIsRefused` (O-6); `addingAnIneligibleOrCoveredMemberToARoleGroupIsRefused`; `anIndirectBreakIsReadNotRefused` (delete the only child → `ROLE_NOT_ALLOWED`); `aFormToQuickEditOfARoleScheduleNeedsTheElection` (refused without `seasonStartQuickElected`, accepted with it; F-22) | C11 retarget, group, indirect | the `SaveSchedule` hook compares `title` instead of `target` |
| 21 | JVM | `CT/usecase/ScheduleRoleSurvivesTest` · `aFullReplaceSaveKeepsTheRole` (K11); `aReplaceAssetCopyHasNoRole` (C12); `theRoleChangesNoComputedDue` (recompute before/after equal) | C7, C12 | `candidate` built without `existing.role` (the save wipes it) |
| 21a | JVM | same · `anEditOfAnImportedEventKeepsTheMarker` (`UpdateEvent` through `buildEvent`); `clearingAndSettingTheRoleAgainNeverReAdmitsAnImportedService` (NONE → SEASON_START: still `INHERITED_SERVICE_ONLY`); `aNewCompletionIsNotImported` | B, C10, C12, I24 | `buildEvent` without `existing?.imported` (the edit clears the marker and the seller's service counts) |

### B1 — the outbound core (rows 25–45; `CT/hapublish/*Test`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 25 | JVM | `ProjectionsTest` · `operationalDegradedDownMapExactly`; `noConditionIsNullNeverUp` | C15, AC113-2 | `null → "UP"` |
| 26 | JVM | same · `readyIsTheDateNotReadyIs1970` | C15 date, AC114-5 | `NotReady` mapped to the last ready date |
| 27 | JVM | same · `aNewReasonWithTheSameEnumIsNoChange`; `aBackdatedNonCurrentRowIsNoChange`; `aDuplicateRowIdIsNoChange` | AC113-3 by construction | `ordered.last()` replaced by "the newest `createdAt`" (the backdated row wins) |
| 28 | JVM | same · `healthBandsDueDatesSeasonAndReadinessNeverReachStatus` (vary each) | G8 | none: tripwire over the signature (the function takes only `ConditionHistory`) |
| 29 | JVM | same · `notMaintainedHereStopsStatusAndInvalidatesDate`; `aMissingAssetRetiresStatusAndInvalidatesDate` | K12 asymmetry | the date kind follows the status kind's "Nothing" |
| 30 | JVM | `RemoteEntityMapperTest` · `aSelectsOptionsAndADatetimesFlagsAreRead`; `anotherEntitysAnswerIsMalformed`; `boundsHold` (65 options, a 10 000-char state) | C16 | the `entity_id` compare removed |
| 31 | JVM | same · `401And403AreAuthRefusedOnAGet`; `404IsEntityNotFound`; `3xxIsRedirected`; `otherIsHttpError` | C16 statuses | 404 → `HTTP_ERROR` |
| 32 | JVM | `ValidatorsTest` · `exactlyUpDownDegradedInAnyOrderPasses`; `aMissingExtraOrCaseVariantOptionIsOptionsMismatch` (`Up`, `UNKNOWN` added, `DOWN` missing) | C16, AC113-1 | the set compare made "contains all" |
| 33 | JVM | same · `aWrongDomainIdIsRefusedBeforeAnyRead` (`input_boolean.example_heater_in_season` as a status target) | C16, WRONG_DOMAIN | the domain check moved after the read (the scripted transport records a GET) |
| 34 | JVM | same · `aDateOnlyHelperPasses`; `aHelperWithATimeOrWithoutADateIsNotDateOnly` | C16 date, AC114-5 | `hasTime` ignored |
| 35 | JVM | `DeliveryStepsTest` · `anInitialPublicationReadsFirst`; `aMatchingHelperIsVerifiedNotPublished` (`lastPublishedAt` null, `lastVerifiedAt` set) | #113 "a match is not a push" | the verified-without-POST arm writes `lastPublishedAt` |
| 36 | JVM | same · `downDegradedUpBeforeDeliverySendsUpOnce` (three desired revisions, one POST) | coalescing (AC113-5, K1) | the record keeps a queue (three POSTs) |
| 37 | JVM | same · `waitingForSourceSendsNothing` (status, no condition); `notEnabledCopiedNoTokenOrUnvalidatedSkip` | gate, AC113-2 | the `desiredValue == null` gate removed (a POST with no option) |
| 38 | JVM | same · `aResetHelperIsRepairedInReconcileModeWithoutASourceChange` (verified UP, remote reads DOWN → POST UP) | AC113-7, K10 | `Done` answered from the stored verification without a read |
| 38a | JVM | `T/hapublish/RepairStaysVisibleTest` (B4a, Room-backed `FakeGraph`) · from a verified UP, the fake HA reset to DOWN, Publish now's repair POST fails before transmission → **only an ordinary drain** repairs it; the same with an `Unknown` POST, a failing read-back GET, and a cancellation after the write-ahead **followed by a graph rebuild** | A05, I23 | `needsDelivery` not consulted by `drain()`'s selection (the helper stays DOWN) |
| 39 | JVM | same · `outcomeUnknownReadsBeforeResending` (remote already UP → verified, no second POST); `outcomeUnknownWithADifferentRemotePosts` | ambiguous completion (AC113-6) | `ReadFirst` skipped after `Unknown` (a blind re-POST) |
| 40 | JVM | same · `aStaleReplyAtAMovedGenerationRecordsNothing` (disable/target change between snapshot and record); `aReplyForAnOlderDesiredRevisionRecordsNothing` | generation fencing (AC113-6) | the CAS compares only the desired revision |
| 41 | JVM | same · `aReadbackThatDiffersAfterA200IsRemoteDiffers`; `aReadbackThatFailsValidationClearsTheValidation` (options changed in HA) | C17, "changed options" | the readback skipped after 200 (`Accepted` recorded as published) |
| 42 | JVM | same · `eachKindHasItsRetryClass` (exhaustive table); `aTransportFailureIsNeverDown` (the desired value and the source untouched) | K1, "never relabelled DOWN" | `UNREACHABLE` classed `NEEDS_OWNER` |
| 43 | JVM | `PublishModelTest` · `noModelTypeHasASecretField` (reflection over `C/hapublish`); `everyKindMapsFromSyncErrorKindExhaustively` | G4, C14 | a `token` field on `PublishBinding` |
| 44 | JVM | same (B1, core; A11) · `theAllowlistHasExactlyTwoActions` (`HaServiceAction.entries` names, domains, services); `actionOfIsExhaustive` | G2, C14a | a third member added |
| 42a | JVM | `DeliveryStepsTest` · `aNeedsOwnerErrorIsNotRetriedByDrainUntilThePairMovesOrPublishNow` (also: a WAIT error skipped by the tick and the worker, retried at resume) — keyed to the persisted error pair (A04) | R-2, the retry class | `drain()` without the skip (a `WRITE_FORBIDDEN` helper re-POSTed on every projector change) |
| 42b | JVM | `T/hapublish/ErrorPairRoomTest` (B3, Room) · history A (`WRITE_FORBIDDEN` at (1,7), then re-authorised to (2,7)) and history B (re-authorised first, then `WRITE_FORBIDDEN` at (2,7)) round-tripped through Room **with a graph rebuild** before `drain()`: A retries, B skips; also a desired-value change, a revalidation and two same-pair failures | A04, I22 | the error pair not persisted (both histories read alike after the rebuild) |
| 45 | JVM | same · `invalidDateIsDeclaredOnce` (the constant equals `"1970-01-01"`) | C1 | none: pin |
| 45a | JVM | `DeliveryStepsTest` · `aVerifiedValueAtAnOlderBindingRevisionIsNotDone`; `drainSelectsByThePairNotTheValue` | F-1 | `Done` answered from `lastVerifiedValue` alone |

### A3 — format 21 (rows 46–49, 49a–49c; `CT/backup/BackupFormat21Test`, `CT/merge/ScheduleRoleMergeTest`, `CT/usecase/{TransferPackRoleTest,TransferRoundTripRoleTest}`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 46 | JVM | `BackupFormat21Test` · `theRoleRoundTrips`; `aFormat20ArchiveDecodesAsNone`; `aFormat20ArchiveCarryingARoleIsRefused`; `anUnknownRoleOrABrokenR1IsCorrupt`; `aFormat22ArchiveIsNewer`; `theEventsImportedMarkerRoundTrips`; `aFormat20EventMarkedImportedIsRefused` | C18, G6, B | the older-format refusal removed |
| 47 | JVM | `ScheduleRoleMergeTest` · `thisPhonesPreRoleExportReMergesIdentical` (local row gained a role); `aFormat21ArchiveComparesTheRole`; `aMatchedEventComparesWithoutItsMarkerAndKeepsTheLocalOne` (B) | C19 compare | `rolesCompared` always true (CONTENT_DIFFERS on the phone's own export) |
| 48 | JVM | same · `aSecondCoverageIsHeldByALocalRow`; `twoArchiveRolesOverOneAssetAreDuplicated`; `noApplyCodeMoved` (the apply writes the row as planned) | C19 one coverage, O-9 | the claim map not seeded from the destination |
| 49 | JVM | `TransferPackRoleTest` · `anInsertedRoleScheduleIsRecommissionedOnTheImportDay` (scenario 8); `aReturnCarryKeepsTheLocalRoleFields` (the planner, a hand-built carry); `aBackupMergeKeepsTheArchivesBoundary` (scenario 7) | C20, #114 point 6, O-8 | `importDay` ignored (the predecessor's boundary makes the recipient Ready) |
| 49a | JVM | `TransferPackRoleTest` (A01) · through the **real** `ImportTransferPack.preview` → `import` → `ApplyBackupMergePlan.runReturning`, reading the persisted schedule: `previewBeforeMidnightImportAfterWritesThePreviewsDay` (a fake `Today`/`Clock` moved between the calls); `reImportIsIdentical`; `aReturningPackKeepsTheLocalRoleFields`; `anOrdinaryBackupMergeKeepsTheArchivesValues`; `aPlanWithAnotherContextOrCarryIsStale` (fingerprint) | C20, A01 | the replan's `plan.context` dropped (the apply writes the archive's 2025 boundary); **and** the context left out of the fingerprint — two counted |
| 49b | JVM | same · `aPackInsertedEventIsImported` (end to end: a pack with a service dated the import day → persisted `imported`, `INHERITED_SERVICE_ONLY`); `aFutureDatedPackServiceNeverCounts` (clock advanced past its day); `aBackupMergeOfTheOwnersArchiveMarksNothing` and `aReplaceRestoreKeepsEachMarkerVerbatim` (O-20) | B, O-8, O-20 | the planner not marking pack inserts |
| 49c | JVM | `TransferRoundTripRoleTest` (A; two `FakeGraph`-style core graphs on the real `CreateTransferPack` → `MarkTransferredOut` → `ImportTransferPack` path both ways) · an asset with a role from 2025-10-01 and its own service 2025-10-05 goes out to graph B; B records its own service; B packs it back; A imports the return on a later day → A's **persisted** schedule is `SEASON_START` from 2025-10-01 (not the import day), A's own 10-05 event is `imported = false` and qualifies, B's event is `imported = true`; the card's readiness is Ready from 10-05 | A, B, I31 | the carry's roles dropped (the persisted `roleSinceOn` is the import day); **and** the carry's events dropped (A's own service reads `INHERITED_SERVICE_ONLY`) — two counted |

### B2 — the client (rows 50–56; `T/hapublish/HomeAssistantPublishClientTest`, `T/seasonsync/HaRequestGateTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 50 | JVM | `HomeAssistantStateClientTest`, `CurrentNetworkReaderTest`, `NetworkSecurityConfigTest`, `SeasonSyncRunnerTest`, `SeasonSyncScenarioTest`, `SeasonSyncWorkerBodyTest` green **unchanged** (`git diff <base>` empty on each) | G3 after C21's extraction | none: pins |
| 51 | JVM | `HaRequestGateTest` · each gate refusal opens nothing for both clients (endpoint, INTERNET, home network with causes, private resolution, same host); `theGateCarriesNoRequestTimeout` (F-7) | C21 shared | the publish client calling `open` before the gate |
| 52 | JVM | `HomeAssistantPublishClientTest` · `postsExactlyThePathBodyAndFiveHeaders` (`methodAtHead == "POST"`, path `/api/services/input_select/select_option`, body `{"entity_id":"input_select.example_heater_equipment_status","option":"UP"}`); `theDateActionPostsADateBody`; `noRedirectNoCacheNoProxy` | C22, G2 | `instanceFollowRedirects` left true |
| 53 | JVM | same · each row of C23's table → its outcome (400, 401, 403, 404, 302, 500) | C23, AC113-6 | 403 mapped to `AUTH_REFUSED` |
| 54 | JVM | same · `aConnectFailureBeforeTransmissionIsRefused`; `aReadTimeoutAfterTheWriteIsUnknown`; `theWholeCallBoundAfterTransmissionIsUnknown`; `every5xxIsUnknown` (A10) | C23 phases | the after-write timeout mapped to `TIMED_OUT` (a resend without readback); **and** 500 mapped to `Refused` — two counted |
| 54a | JVM | same (A10) · scripted failures `afterTheLastByteBeforeCloseReturns`, `afterAPartialWrite`, `a500AfterTheServerChangedTheHelper` (the fake HA mutates, then answers 500), `cancellationAroundCompletion` → each `Unknown`, and (with B4a's runner) the next attempt reads back before any POST | C23, A10, I27 | a failure inside `close()` classed as before transmission (`Refused`; a blind resend) |
| 55 | JVM | same · `tlsFailureIsTlsFailed`; `a200BodyIsCappedAndDiscarded` | C22 caps | the cap removed (a 65 KiB body read whole) |
| 56 | JVM | same · `noLogNoMessageNoToStringCarriesUrlEntityBodyOrToken` (a captured log seam; every exception message) | G4 | an exception message with the URL |

### B3 — commands (rows 60, 60a, 63, 64, 69, 86, 87, 93–97; `CT/hapublish/PublishCommandsTest`, `CT/seasonsync/CrossCheckTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 60 | JVM | `PublishCommandsTest` · `enableValidatesTheFreshReadAndStampsTheInstallation`; `aStaleOrForeignReadIsRefused`; `enableWithNoConditionWaits` | C25, AC113-1/2 | the command trusting the UI's "validated" flag (no re-validation) |
| 60a | JVM | same (A09) · a `ValidationResult` raced by an address change, by Forget + recreate, by a target change, by another setup with the same entity id, and one older than 10 min → each `STALE_READ` before any writer is enabled; `twoSavesInOneClockMillisecondStillMakeItStale` (F: the counter, not `updatedAt`); `anHaDrivenManualAssetNeedsABoundaryHelperOtherThanItsTarget` (`BAD_ENTITY_ID`, `BOUNDARY_IS_TARGET`, `NOT_DATE_ONLY`); `aCalendarAssetStoresNoBoundaryHelper` (D) | C25, C9a, F, I32 | the provenance compare dropped (a read of connection A enables a writer on B) |
| 63 | JVM | same · `aSecondAssetOnTheSameHelperIsTargetInUse`; `onePerAssetAndKind`; `enableOverAPendingTombstoneSupersedesIt`; `anotherAssetsDisabledBindingDoesNotBlockAndReEnablingItIsTargetInUse`; `aDisabledBindingWhoseAssetIsDeletedIsRetired` (F-9) | G1, AC113-8 | the pre-check removed (the unique index throws a 500-shaped error instead of the refusal) |
| 64 | JVM | same · `disableAndChangeTargetMoveTheGeneration`; `theDateKindWritesATombstoneInTheSameWrite`; `theStatusKindLetsGoSilently` | C25, K12 | the tombstone written after the commit (a crash between loses it: the double's rollback shows it absent) |
| 69 | JVM | `InstallationFenceTest` · `aCopiedBindingSendsNothingEvenWithAToken` (Room rows restored, a new installation id, a token re-entered); **`revalidatingACopiedEnabledRowLeavesItInertUntilEnable`** (A08: zero POSTs after Check helper, also after a graph rebuild; then Enable re-stamps and it sends); `aCopiedBindingsTombstoneIsDrawnNeverDrained` (F-25) | C27, K7, AC113-8, A08 | the fence compares nothing (the copy POSTs); **and** Revalidate stamping the installation — two counted |
| 93 | JVM | `PublishCommandsTest` · `reEnableAfterDecommissionSendsAgain` (disable → tombstone drained to `1970-01-01` → re-enable → the date is POSTed and read back) | F-1, AC114-6 | `Done` answered from `lastVerifiedValue` alone (HA keeps `1970-01-01`, the block reads MATCHES) |
| 94 | JVM | same · `aTargetChangePublishesToTheNewHelper`; `aNewTargetShowsNoInheritedPublishedLine` | F-1, #113 "never attributed" | the delivery fields not cleared by Change target |
| 95 | JVM | `CT/hapublish/DefaultedCollaboratorTest` · `theFourCommandsCompileWithTheirShippedPositionalArguments`; `theDefaultRefusesNothing` | F-6, G3 | none: pin (R6's `OutboundTargets.NONE` grep is the guard) |
| 96 | JVM | `PublishCommandsTest` · `aDisabledBindingsTargetChangeNeverTombstonesAPairAnotherAssetClaims` (A disabled on H, B enabled on H, A changes target to H2: no tombstone for H, B's date stands) | R-1, I19 | the tombstone inserted unconditionally (HA receives `1970-01-01` over B's date) |
| 97 | JVM | same · `disablingADisabledBindingWritesNothing` (no revision bump, no tombstone) | R-1 | the tombstone inserted unconditionally |
| 86 | JVM | `CrossCheckTest` · `linkAndEditRefuseAnOutboundTarget`; `enableAndChangeTargetRefuseAnInboundSource`; `aPendingTombstonesEntityIsOutboundToo` | C26, G9, K6 | the inbound refusal checks enabled bindings only |
| 87 | JVM | `ConnectionHooksTest` · `anAddressChangeIsRefusedWhileADateBindingOrPendingTombstoneExists` (O-12); `aTokenChangeBumpsEveryPublishRevision`; `forgetTurnsUndeliveredDateObligationsIntoUndeliverableTombstones` (O-11); `aTokenChangeKeepsBindingsValidatedAndDeliversPendingInvalidations` (F-2); `everySaveAddsOneToConfigGeneration` (F) | C28, K9, I32 | the reauthorised branch bumps only season bindings; **and** `validatedRevision` left behind (pending invalidations park in NEEDS_VALIDATION) — two counted |

### B4a/B4b1/B4b2 — runner, projector, worker (rows 57–59, 57a–57d, 61–62, 65–68, 98–101b, 105; `T/hapublish/PublishRunnerTest`, `PublishProjectorTest`, `PublishWorkerBodyTest`, `PublishScenarioTest`, `ReconcilePendingTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 57 | JVM | `PublishRunnerTest` · `twoConcurrentDrainsPostOnce` (single flight); `aDisableDuringTheRequestRecordsNothing`; `noTransactionSpansTheRequest` (the scripted transport asserts no open write) | C30, AC113-6, G15 | the `Mutex` removed (two POSTs) |
| 57a | JVM | `PublishProjectorTest` (B4b1, Room) · **serialization (F):** projection A holds its write transaction on a barrier placed after its read; a completion delete is launched on another coroutine and is observed **not committed** while A is paused (no interleaving inside A's transaction); A commits, then the delete commits, and the observer's next projection writes `1970-01-01`; the final desired value and the scripted HA's last write are `1970-01-01`, never the old date after it | A02, I20 | the projector split into a `uow.read` and a `uow.write` (the delete is observed committed while A is paused) |
| 57c | JVM | `PublishProjectorTest` (B4b1, Room, counting seams) · `aTransientFailureDoesNotReTriggerItself`: the scripted HA unreachable; one source change → exactly one projection and one drain; the drain's write-ahead, error and `reconcilePending` writes, a tombstone's error and a cleared `validatedRevision` produce **no** further projection or drain across ten debounce windows; one later source change → exactly one more | C, I28 | the observer combining `deliveries.observeAll()` (each failure's write triggers the next drain: the count grows) |
| 57d | JVM | `T/hapublish/ReconcilePendingTest` (B4b2, Room-backed `FakeGraph`, the worker body) · from a verified helper, start's reconcile GET fails (`UNREACHABLE`) → `reconcilePending` set, `needsDelivery` false; HA reset meanwhile; an **ordinary** worker run (`reconcileDesired` + `drain()`, not reconcile mode) reads, repairs and clears it; `retry()` while the GET keeps failing, `success()` at the tenth attempt; the same after a cancellation mid-GET and a graph rebuild | C, I29 | `drain()`'s selection without `reconcilePending` (the reset helper stays wrong until the next start) |
| 57b | JVM | `PublishRunnerTest` · `aQueuedStatusSendForAnAssetArchivedBeforeTheRequestPostsNothing` (and transferred out); `aDateSendForAnIneligibleAssetSendsOnly1970` | A02 per-send eligibility, #113 | the snapshot without the asset row (the stale UP is POSTed) |
| 58 | JVM | `PublishScenarioTest` · `upDegradedDownEachReachTheHelperInOrder` (scripted HA records three POSTs and serves readbacks); `aServiceDateThenADeletionPublishesTheDateThen1970` | AC113-4, AC114-5 | the projector not triggering a drain on change |
| 59 | JVM | same · `offlineTransitionsSurviveProcessDeathAndCoalesce` (desired written, the graph rebuilt, `UNREACHABLE` then reachable → one POST of the latest); `homeWifiOnlyWithBackgroundOffWaitsForResume` | AC113-5, K1 | the desired value held in memory only |
| 61 | JVM | `PublishProjectorTest` · `reconcileIsIdempotent` (second call writes nothing); `aChangeBumpsTheDesiredRevisionOnce` | C31 | `desiredRevision` bumped on every call |
| 62 | JVM | same · `eachWriterReachesTheProjection`: `RecordCondition`, `AcceptOperationalOffer`, `AcceptImpairmentOffer`, `RecordConditionWithIncident`, the API's `POST …/conditions`, a merge apply, a Transfer Pack import, a replace restore — each through the Room-backed `FakeGraph`; `aWindowEditOrModeSwitchRepublishes` (a CALENDAR window edit and a MANUAL → CALENDAR switch move the date, F-27) | AC113-3 (one seam) | the projector observing only `conditions.observeForAsset` of the UI's asset |
| 65 | JVM | same · `aRolledBackWriteChangesNothing` (a `RecordConditionWithIncident` whose event half fails) | AC113-3 | the projector reading uncommitted state (none: Room; RED = the projector called inside the use case) |
| 66 | JVM | same · `startResumeMidnightAndWorkerEachReconcile` (the crash gap: a desired value never written before death is written at the next start); `startAndResumeReadBackAVerifiedHelper` (an HA reset found at the next open, FOREGROUND_ONLY included; resume at most once per 60 min; F-3) | C31 triggers, #113 crash gap and comparison | the start-up reconcile omitted; **and** start calling `drain()` instead of reconcile mode — two counted |
| 67 | JVM | `PublishWorkerBodyTest` · `transientRetriesBoundedByTenAttempts`; `needsOwnerSucceeds`; `aDatabaseFailureRetries`; `cancellationPropagates`; `anUnassignedDispatchSucceeds` | C32 | `retry()` on every failure (unbounded) |
| 68 | JVM | same · `aCalendarWindowEndPassingAtMidnightPublishes1970` (no database write, the tick only) | K14, AC114-3 | the midnight tick omitted |
| 70a | JVM | `T/hapublish/ControlDispatchTest` (B4b2; B5 adds the Forget case) · through `PublishControls` only — **never calling `drain()` by hand**: re-enable, target change, Check helper, a token update, and Disable of one date binding while another stays enabled, each with unchanged source values, background checks Off and no lifecycle or time trigger → the scripted HA receives the read and the write | A03, I21 | `requestDelivery()` removed from the facade (each waits for an unrelated trigger) |
| 98 | JVM | `T/hapublish/BoundaryScenarioTest` (B4b2; a scripted HA that also models HA's gate: released ⇔ `service_done != 1970-01-01 && service_done >= season_ended`) · `aDelayedOffAfterANewerServiceAgrees`: HA ends 05-10 with the phone offline, service on the phone 09-18, the phone observes OFF on 10-22 → phone Ready (HA boundary), `service_done = 09-18`, HA released | C9a, A07 | the boundary step omitted (the phone's 10-22 END publishes `1970-01-01`; HA would have released) |
| 99 | JVM | same · `aMissedWholeOffOnCycleWithinTheWindow`: a `MANUAL` asset with an enabled #16 binding misses an OFF → ON (no END), last season's service; the boundary read succeeds → NotReady (`HA_SEASON_ENDED`), `1970-01-01`, HA not released; it fails two days after the last good read → Ready **with P114-44 and its check date**, `service_done` = the old date, HA not released (its own stamp) | C9a, I25 | the HA boundary ignored when no activation moved |
| 99a | JVM | same · `pastTheWindowItIsUnverified`: the same failing reads eight days after the last good one (DAILY) → `HA_UNVERIFIED`, **P114-45 with its since date reachable**, readiness on the later of the cached date and the phone's END, the scripted HA still receives the date (publication proceeds), HA not released; at MONTHLY the same is `Fresh` at day 8 and `Unverified` at day 31; the next good read → NotReady, `1970-01-01` | D, I25 | the time window removed from `haBoundaryOf` (the stale cache stays `Fresh` forever and the label never appears) |
| 99b | JVM | same · `aCalendarAssetNeverUsesHaDate`: a `CALENDAR` asset's date binding with a (test-stored) boundary helper whose state is a fresh, later date → `CALENDAR_WINDOW`, no boundary GET in the scripted log; a `MANUAL` asset whose #16 binding is disabled → `PHONE_SEASON_END`, no boundary GET | D, G22 | `haBoundaryOf` ignoring the asset's mode (HA's date overrides the window) |
| 100 | JVM | same · `aPhaseAlreadyMatchingAtResumeReadsTheBoundary` (no transition, reconcile mode reads `season_ended` and re-projects) | C9a, C31 | the boundary read only on a new activation |
| 100a | JVM | same · `aJuneColdSnapAfterTheMayStamp` (E; the scripted HA models §8: `in_season` edge for edge, `season_ended` stamped once per heating year at the first OFF in its 1 May–31 July window): OFF 05-15 → stamp 05-15, phone NotReady on last autumn's service, `1970-01-01`; ON 06-10 (a START row) → still NotReady, HA's override can act because `in_season` is ON; a 06-12 service → Ready, released; OFF 06-20 → no new stamp, still Ready; a 09-20 service → Ready; an October warm spell's END/START rows → still Ready (`HA_SEASON_ENDED`) | E, D, story 1 | a `MANUAL` asset with a `Fresh` boundary falling back to the phone's END (the October warm spell revokes the September service) |
| 101 | JVM | `PublishRunnerTest` · `theBoundaryReadIsCachedAtTheBindingRevisionAndAFailedReadKeepsTheOldCache`; `aBoundaryHelperFailingValidationClearsValidation` | C9a, C30 | the cache written without the binding-revision CAS |
| 101a | JVM | `PublishRunnerTest` · `validateReturnsTheTargetAndBoundaryResultsFromOneSnapshot` (both carry one connection id, `configGeneration` and binding revision, even with a connection save racing between the two GETs); `aStatusValidationHasNoBoundaryResult` | F, C30 | the boundary result built from a second snapshot (mixed provenance passes) |
| 101b | JVM | `CT/hapublish/BoundaryFreshnessTest` (B1) · `freshWithinSevenDays`; `aLongerCadenceWidensTheWindow` (MONTHLY 30 d); `anOlderRevisionIsUnverified`; `aNewerActivationIsUnverified`; `neverReadIsUnverifiedSinceValidation`; `calendarNoInboundOrNoHelperIsNotUsed` | D, C9a | the cadence term dropped (MONTHLY reads `Unverified` at day 8) |
| 105 | JVM | `PublishScenarioTest` (B4b2; story 4) · a fictional `YEAR_ROUND` indoor pool: `SetScheduleRole` refused `YEAR_ROUND`; its status binding enabled, DOWN then UP recorded → the scripted HA receives `DOWN` then `UP`; no date binding is offered a boundary | G22, G8 | the status projection gated on a season (a year-round asset publishes nothing) |

### A4, B5 — API and MCP (rows 80–84; contract)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 80 | contract | `T/api/SeasonReadinessRoutesTest` (new) · each `NotReadyReason` and Ready as keys; `noSuchAsset404`; `aGroupRoleNamesGroup`; `boundarySourceAndCutoffAreTwoFields` (each `BoundarySource`); `anUnverifiedBoundaryReportsItsSince` (F, D; B5's case, with the argument it wires) | C29 | `reason` serialized from `toString` |
| 81 | contract | `T/api/MaintenanceCommandShapeTest` (+3) · `everyScheduleReadCarriesRoleAndRoleSinceOn`; `aPatchWithoutRoleKeepsIt`; `aBodyCarryingRoleIs400` (`ignoreUnknownKeys = false`) | K11, G18 | the row mapping omits `role` |
| 82 | contract | `T/api/HaPublishingRoutesTest` (new) · `bindingsAndTombstonesAsKeys`; `noAddressTokenWifiOrInstallationId` (byte scan) | C35, G4 | the connection's base URL added |
| 83 | contract | `T/api/ApiReadsWriteNothingTest` (+2 routes) | read-only | the handler calling `reconcileDesired` (it writes) |
| 84 | contract | `M/tests/test_ha_publishing_tools.py` (new) · the two tools' shapes; the schema gate below 22; `update_schedule_keeps_the_role` | C29, C35, K11 | the tool forwarding `role` in the PATCH body (400) |

### B5 — decommission (rows 70–74; `CT/hapublish/DecommissionTest`, `T/hapublish/DecommissionScenarioTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 70 | JVM | `DecommissionScenarioTest` · `disableTargetChangeArchiveRetireTransferOutRoleRemovalAndScheduleArchiveEachDeliver1970First` (one case each, readback before quiescence) | AC114-6, G10 | the binding quiesced on ineligibility before `1970-01-01` is verified |
| 71 | JVM | same · `anAssetDeleteLeavesAnObligationTheProjectorDelivers`; `aReplaceRestoreWithTheAssetGoneDoesTheSame` | soft link (C4.2, C34), K8 | the binding's `asset_id` given a CASCADE key in the double (the obligation vanishes) |
| 72 | JVM | same · `anUndeliverableInvalidationIsShownNeverDropped` (no network → PENDING with the error; token gone → UNDELIVERABLE); `dismissIsTheOnlyWayOut` | AC114-6 "never silent" | the drain deleting a tombstone on a NEEDS_OWNER failure |
| 73 | JVM | same · `forgetDrainsFirstThenKeepsTheRest` (one reachable, one not) | O-11 | the command deleting before the drain |
| 74 | JVM | same · `anUnarchivedAssetsDateBindingResumes` (INVALIDATED_INELIGIBLE → Ready date sent) | K12 return path | the quiescence stored as a terminal state |

### A5a/A5b, B6a/B6b — the phone (rows 75–79, 88–90; app JVM view models; Compose in B7)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 75 | JVM | `T/ui/asset/ReadinessLineTest` (new) · each state's sentence id; `noLineWithoutARoleOrDateBinding` (R-N1); `theLineRecomputesOnResume` | C33, AC114-4 | the line cached in a `val` at construction |
| 76 | JVM | `T/ui/maintenance/ScheduleRoleViewModelTest` (new) · `theActionIsNotInTheEditorsSave`; `confirmCarriesTheDateAndQuickNote`; `eachRefusalHasItsSentence` | C33, K11, O-2 | the QUICK note shown on FORM |
| 77 | JVM | `T/ui/maintenance/CompletionFlowTest` (+2) · `aRoleScheduleShowsTheLine`; `anOrdinaryScheduleDoesNot` | the completion line | the line keyed on `servicePolicy` instead of `role` |
| 78 | JVM | `T/ui/asset/AssetEditViewModelTest` (+2), `ScheduleEditViewModelTest` (+1), `GroupEditViewModelTest` (+1) · each strand cause's sentence | C11 drawn | an `else` arm (UiLiteralGuard / the exhaustive-when check) |
| 79 | JVM | `LocalizationCoverageTest`, `UiLiteralGuardTest` pins with the new resources | G12 | none: pins |
| 88 | JVM | `T/ui/asset/PublishBlockViewModelTest` (new) · each `PublishState` and `PublishErrorKind` → its sentence id (exhaustive); `publishedAndVerifiedAreTwoLines`; `aTombstoneIsDrawnOnItsAssetOrTheHaScreen`; `anEnabledDateBindingFailingItsInvalidationDrawsMayStillAllowStarts` (P114-36/37 beside the error, F-16); `everyPublishStateAndRefusalHasASentence` | C36 | `MATCHES` drawn as `PUBLISHED` |
| 89 | JVM | `T/ui/asset/LinkPublishViewModelTest` (new) · `theScopeListsOnlyItsDomain`; `theConfirmNamesTheExactDestinationAndMapping`; `aValidationRefusalBlocksEnable` | C36, AC113-1 | the sheet passing `INPUT_BOOLEANS` |
| 90 | JVM | `T/ui/homeassistant/HomeAssistantViewModelTest` (+3) · `theWriteDisclosureAppearsWithABinding`; `forgetWarnsWithTheCountAsAPlural` (F-15); `anAddressChangeRefusalHasItsSentence` | C36, K2 | the disclosure keyed on season bindings |

### B7 — drawn and platform (rows 91–92)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 91 | Compose | `AT/hapublish/HaPublishScreensTest` (new, ~14 cases): C37's list | AC114-4 drawn; AC113 blocks drawn | a block hidden behind a missing state (`assertExists` fails) |
| 92 | platform | `AT/hapublish/HaPublishPlatformProofTest` (new, ~6 cases): C37's list, against an in-process fake HA on the device | the real POST and work on Android (tiers 3–4, #62) | none counted: first run at the merged-tip gate; the class asserts method, path, body and the unique work |

### Real HA (row 85; §7, controller, never a gate)

| # | layer | case | proves |
|---|---|---|---|
| 85 | real-HA | §7's ten steps | AC113-4/7 and AC114-5/6 against a real Home Assistant; the 404-versus-`[]` behaviour of a service call naming a missing entity (§10 c6) |

### Hazard coverage (K1–K14)

| K | hazard | rows |
|---|---|---|
| K1 | delivery latency, coalescing | 36, 42, 59, 67, limit 2 |
| K2 | the token's write right | 53 (401/403 split), 90 (disclosure) |
| K3 | `detailsPending` weak | 6, O-15; future-dated completions 4, O-16 |
| K4 | same day; CALENDAR end | 2, 4 |
| K5 | the group role | 11, 15, 16, 20 |
| K6 | the C2 loop | 86, 33 |
| K7 | Auto Backup copies | 69 |
| K8 | CASCADE versus decommission | 23, 71 |
| K9 | address change | 87 |
| K10 | HA restart / reset | 38, 85 step 5 |
| K11 | the role in full-replace bodies | 21, 76, 81, 84 |
| K12 | lifecycle asymmetry | 29, 64, 70, 74 |
| K13 | the GET-pinned contract | 50, R6 greps |
| K14 | time-only transitions | 68, 66 |
| K15 | the phone's END dated at observation versus HA's stamp (A07, D) | 3a, 3c, 98–101b |
| K16 | inherited evidence through a Transfer Pack, either way (B, A) | 3b, 21a, 49b, 49c |
| K17 | a lost, cancelled or 5xx-answered POST (A05, A10) | 38a, 54, 54a |
| K18 | a failure triggering the next attempt; a lost first reconcile read (C) | 57c, 57d |
| K19 | HA's date overriding a `CALENDAR` season; a stale boundary passing as fresh (D) | 99a, 99b, 101b |
| K20 | seasonality inferred from type, template or name (G22) | 102–106 |

## 4. Files, fences, order and the gate budget

| brief | wave · lane | touches | never touches |
|---|---|---|---|
| **S1** schema 22 | 1 · L1 | `A/data/room/entities/MaintenanceEntities.kt` (two columns, the entity `:95-155`), `JournalEntities.kt` (`imported`, `:198-215`), `HaConnectionEntity.kt` (`config_generation`); `A/data/room/entities/{HaPublishBindingEntity,HaPublishDeliveryEntity,HaPublishDecommissionEntity}.kt`, `A/data/room/dao/{HaPublishBindingDao,HaPublishDeliveryDao,HaPublishDecommissionDao}.kt` (new); `A/data/room/{Migrations,AppDatabase}.kt` (`MIGRATION_21_22`, version 22); `A/di/AppGraph.kt` (`SCHEMA_VERSION`, the migration list **only**); `T/testing/FakeGraph.kt`, `MigrationTestSupport` (twins); `app/schemas/…/22.json` (generated); `T/data/room/{Migration21To22Test,HaPublishDaoTest}.kt` (new); `T/backup/SeasonSyncNeverTravelsTest.kt` (+4); `T/VersionAgreementTest.kt` (schema only: `:87`, `:158`) — rows 12, 22–24 (the error pair, `needs_delivery`, `reconcile_pending`, `boundary_*`, `imported` and `config_generation` columns and the `observeControls` / `observeObligations` queries included) | `C/**`, `A/api`, `A/ui`, `A/seasonsync`, `21.json`, `docs`, `tools` |
| **A1** rule | 1 · L2 | `C/model/Maintenance.kt` (`ScheduleRole`, two fields); `C/model/Journal.kt` (`AssetEvent.imported`, appended and defaulted); `C/schedule/SeasonContext.kt` (`lastSeasonEndOnOrBefore`, `YearlyWindow` made class-visible); `C/season/SeasonReadiness.kt` (new); `CT/season/{SeasonReadinessTest,SeasonStoriesTest}.kt`, `CT/schedule/SeasonContextLastEndTest.kt` (new) — rows 1–11, 3a–3c, 102–104, 106 | `A/**`, `C/usecase`, `C/backup`, `C/merge`, `C/transfer`, every other `C/schedule` line, `docs`, `tools` |
| **A2** role command | 2 · L2 | `C/usecase/SetScheduleRole.kt` (new); `C/usecase/{SeasonCommands,SetSeasonMode,UpdateAsset,SaveAssetSettings,SaveSchedule,SaveGroup}.kt` (the exceptions, the helper, one call each, `candidate`'s two fields, the QUICK-election parameter); `C/usecase/EventCommands.kt` (`buildEvent`'s one `imported` line, B); `A/data/room/MaintenanceMappers.kt` (role mapping) and `A/data/room/JournalMappers.kt` (`imported`, B); `A/api/ApiJson.kt` (the two `is` arms only); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (`setScheduleRole`, constructor arguments); `CT/usecase/{SetScheduleRoleTest,SeasonStartStrandsTest,ScheduleRoleSurvivesTest}.kt` (new) — rows 13–21, 21a | `C/backup`, `C/merge`, `C/transfer`, `C/schedule`, `C/hapublish`, `A/ui`, `A/hapublish`, every other `A/api` file, `docs`, `tools` |
| **B1** outbound core | 2 · L1 | `C/hapublish/{PublishModel,HaServiceAction,Projections,RemoteEntityMapper,Validators,DeliverySteps,BoundaryFreshness}.kt` (new; `HaServiceAction` and `ValidationResult` are B1's, A11, A09); `CT/hapublish/*Test.kt` (new) — rows 25–45, 42a, 45a, 101b | every shipped file; `A/**`; `C/seasonsync` (read-only use of its public helpers) |
| **A3** format 21 | 3 · L2 | `C/backup/{BackupFormat,BackupCodec}.kt`; `C/merge/{MergePlanner,MergePlan}.kt` (the compare, the claim, two `MergeReason`s, `ImportContext`, the plan's `context` and the fingerprint line); **`C/usecase/BuildBackupMergePlan.kt` (the `context` parameter and `ReturnScope`'s carry passed through, only) and `C/usecase/ApplyBackupMergePlan.kt` (`ReturnScope` builds `ReturnCarry` from the rows it already removes, and `:181`'s replan passes `plan.context` and `scope.carry`, only)** (A01 and rev 4's A, controller rulings); `C/usecase/ImportTransferPack.kt` (the context captured once in `preview`, a trailing `today: Today`); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (`ImportTransferPack`'s `today` argument); `T/VersionAgreementTest.kt` (format: `:88`, `:159`); `docs/api/v1.md` (the merge tables' two reasons, the format line); `CT/backup/BackupFormat21Test.kt`, `CT/merge/ScheduleRoleMergeTest.kt`, `CT/usecase/{TransferPackRoleTest,TransferRoundTripRoleTest}.kt` (new); the twins `git grep -n 'FORMAT_VERSION\|formatVersion = 20'` names, read first — rows 46–49, 49a–49c | every other line of `ApplyBackupMergePlan.kt` and `BuildBackupMergePlan.kt`, `C/transfer/**`, `A/ui`, `A/hapublish`, `A/seasonsync`, `tools` (but a format pin twin in `B/` if one exists) |
| **B2** client + gate | 3 · L1 | `A/seasonsync/HaRequestGate.kt` (new, the moved checks, no timeout); `A/seasonsync/HomeAssistantStateClient.kt` (calls the gate inside its own unchanged `withTimeoutOrNull`; nothing else); `A/hapublish/HomeAssistantPublishClient.kt` (new; consumes B1's `HaServiceAction`); `T/seasonsync/HaRequestGateTest.kt`, `T/hapublish/HomeAssistantPublishClientTest.kt` (new) — rows 50–56, 54a (its client half) | `A/di`, `FakeGraph`, every shipped test file, `C/**`, `A/fetch`, the manifest, `docs`, `tools` |
| **B3** commands | 4 · L1 | `C/hapublish/{PublishPorts,PublishCommands}.kt` (new); `C/seasonsync/{LinkSeasonSync,SeasonSyncCommands,SeasonSyncModel}.kt` (C26's refusal; C28's bump, refusal, tombstones and `configGeneration` increment; the model's one appended, defaulted field, F; each new collaborator a **trailing defaulted** parameter, F-6); `A/data/room/SeasonSyncRepositories.kt` (`:71`, `:82`: that field mapped, only); `A/data/room/HaPublishRepositories.kt` (new); **the three exhaustive `when`s the two new enum members reach (F-4):** `A/ui/asset/SeasonSyncStrings.kt:161-166` (`OUTBOUND_TARGET`), `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296` (`OUTBOUND_TARGET` → P113-43 on the entity line), `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462` (`PUBLISHING_BLOCKS_ADDRESS_CHANGE` → P113-42) — those arms only, with P113-42/43 in all ten packs; `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (repositories, commands, `DismissDecommission`); `CT/testing/InMemoryHaPublish.kt` (new); `CT/hapublish/PublishCommandsTest.kt`, `CT/hapublish/{CrossCheckTest,ConnectionHooksTest}.kt`, `T/hapublish/{InstallationFenceTest,ErrorPairRoomTest}.kt` (new; **not** in `CT/seasonsync`) — rows 24's A04 case with S1's DAO, 42b, 60, 60a, 63, 64, 69, 86, 87 | every shipped `CT/seasonsync` test file (byte-identical, G3); every other `A/ui` line; `A/api`; `C/usecase`, `C/backup`, `C/merge`, `docs`, `tools` |
| **A4** API/MCP read | 4 · L2 | `A/api/{ApiRouter,SeasonHealthHandlers,ScheduleRowResponse,SeasonReadinessDtos}.kt` (the route, the handler, the keys, a new DTO file); `docs/api/v1.md` (the route, the keys, the counts); `M/src/servicetag_mcp/server.py`, `M/README.md`; `T/api/{SeasonReadinessRoutesTest (new),MaintenanceCommandShapeTest,ApiReadsWriteNothingTest}.kt`; `M/tests/test_ha_publishing_tools.py` (new, the readiness half) — rows 80, 81, 83, 84 (half) | `A/di`, `FakeGraph`, `C/**`, `A/ui`, `A/hapublish`, `S/`, `B/` |
| **B4a** runner | 5 · L1 | `A/hapublish/{PublishRunner,PublishSeams}.kt` (new: `validate` with both results, the boundary step, the write-ahead and reconcile write-ahead records, per-send eligibility; `DesiredStateReconciler`); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (client, runner); `T/hapublish/{PublishRunnerTest,RepairStaysVisibleTest}.kt` (new) — rows 38a, 54a (its runner half), 57, 57b, 101, 101a | `A/ui`, `A/api`, `C/**`, `ServiceTagApp.kt`, `ServiceTagRoot.kt` |
| **A5a** readiness + completion lines | 5 · L2 | `A/ui/asset/{AssetDetailScreen,ReadinessStrings}.kt` (the line, the source line and the cutoff line; `ReadinessStrings.kt` new), the readiness view-model state beside the season card, `A/ui/maintenance/CompletionFlow.kt` (the line); `docs/localization-glossary.md` (**Season Start service**, **ready for the season**, in every shipped language, first); `app/src/main/res/values*/strings_season_start.xml` (new, ten files: P114-7…15, P114-29, P114-40, P114-44…46, P114-50…53) — rows 75, 77, 79 (its half) | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `A/ui/homeassistant`, `SeasonSyncBlock.kt`, `A/ui/maintenance` but `CompletionFlow.kt` |
| **B4b1** projector + triggers | 6 · L1 | `A/hapublish/{PublishProjector,PublishInputs,MidnightTick}.kt` (new; `PublishInputs`, `DeliveryRequester` and its in-process implementation); `A/ServiceTagApp.kt` (the start reconcile); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (projector, the resume lambda at `AppGraph.kt:901`, the tick); `T/hapublish/PublishProjectorTest.kt` (new; the single-transaction projector, the work-arrived inputs only, the delivery-work signal) — rows 57a, 57c, 61, 62, 65, 66, 68 | `A/ui/**` (`ServiceTagRoot.kt` untouched), `A/api`, `C/**`, the manifest |
| **A5b** the role on the phone | 6 · L2 | `A/ui/maintenance/{SchedulesSection,ScheduleDetailScreen,ScheduleDetailViewModel,ScheduleEditViewModel,GroupEditViewModel}.kt`, `A/ui/asset/AssetViewModels.kt` (the editor's strand causes), `A/ui/asset/ReadinessStrings.kt` (its rows); `strings_season_start.xml` ×10 (P114-1…6, P114-16…28, P114-41…43) — rows 76, 78, 79 (its half) | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `AssetDetailScreen.kt`, `CompletionFlow.kt` |
| **B4b2** worker + wiring | 7 · L1 | `A/hapublish/{PublishWorker,PublishWork,HaPublishDispatch,PublishDispatch,PublishControls}.kt` (new; `requestDelivery()` and the facade, A03); `A/ServiceTagApp.kt` (the dispatch beside `:55`); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (work seam, dispatch, facade); `T/hapublish/{PublishWorkerBodyTest,PublishScenarioTest,ControlDispatchTest,BoundaryScenarioTest,ReconcilePendingTest}.kt` (new) — rows 57d, 58, 59, 67, 70a, 98–100, 99a, 99b, 100a, 105 | `A/ui/**`, `A/api`, `C/**`, the manifest |
| **B5** decommission + read | 8 · L1 | `C/hapublish/Decommission.kt` (new); `A/hapublish/{PublishRunner,PublishProjector}.kt` (drain, `drainBeforeForget`, orphan retirement); `A/api/{ApiRouter,HaPublishingHandlers,HaPublishingDtos}.kt`, `A/api/SeasonHealthHandlers.kt` (the readiness handler's `HaBoundary` argument only, C29); `docs/api/v1.md` (the route); `M/src/servicetag_mcp/server.py`, `M/README.md`, `M/tests/test_ha_publishing_tools.py` (the publishing half); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the Forget orchestration, the tombstone read model) — **the last edit of either**; `CT/hapublish/DecommissionTest.kt`, `T/hapublish/DecommissionScenarioTest.kt`, `T/api/HaPublishingRoutesTest.kt` (new) — rows 70–74, 82, 84 (half) | `A/ui/**`, `C/usecase`, `C/backup`, `C/merge`, `S/`, `B/` |
| **B6a** status block, sheet, HA screen | 9 · L1 | `A/ui/asset/{PublishBlock,PublishBlockViewModel,LinkPublishSheet,LinkPublishViewModel,PublishStrings}.kt` (new; the block's frame for **both** kinds, the sheet with its scope parameter); `A/ui/asset/AssetDetailScreen.kt` (the two blocks' slots); `A/ui/asset/LinkSeasonSyncSheet.kt` (the browser's scope parameter only); `C/seasonsync/EntityPicker.kt` (two scopes); `A/ui/homeassistant/{HomeAssistantScreen,HomeAssistantViewModel,HomeAssistantStrings}.kt` (P113-40, the P113-41 plural, orphan tombstones); `docs/localization-glossary.md` (**helper**, **publish**); `app/src/main/res/values*/strings_ha_publishing.xml` and `values*/plurals.xml` (ten files each); every control call through B4b2's `PublishControls`, and `HomeAssistantViewModel`'s save followed by `requestDelivery()` (A03) — rows 88 (status half), 89, 90 | `A/di`, `FakeGraph`, `A/hapublish`, `A/api`, `C/**` but `EntityPicker.kt`, `ReadinessStrings.kt`, the three B3 arms |
| **D1** documents | 9 · L2 | C38's list, including the **one KDoc-only edit** in `A/fetch/UrlConnectionTransport.kt:20-22` (A11) | any other `.kt`, any `.py`, any `.xml` but the manifest comment at `:12`; any code line of `UrlConnectionTransport.kt`; `docs/versioning.md`, `docs/releases` |
| **B6b** date block and tombstones | 10 · L1 | `A/ui/asset/{PublishDateBlock,PublishDateStrings}.kt` (new), `PublishBlockViewModel.kt` (the date states and the P114-36/37 rule), the date sheet's boundary-helper field, offered only for a `MANUAL` asset with an enabled #16 binding (P114-47/48, D); `app/src/main/res/values*/strings_ha_publishing_date.xml` (new, ten files: P114-30…39, P114-47, P114-48) — row 88 (date half) | `A/di`, `FakeGraph`, `A/hapublish`, `A/api`, `C/**`, `AssetDetailScreen.kt`, `LinkPublishSheet.kt` |
| **B7** drawn + platform | 11 · L1 | `AT/hapublish/{HaPublishScreensTest,HaPublishPlatformProofTest}.kt` (new) — rows 91, 92 | every main file; every shipped `AT/` file |

**Waves and lanes (two lanes at most, owner ruling 2026-09-17; no shared emulator, no gate on the device inside a
brief).** Rev 2 splits B4b, B6 and A5 (F-17) and runs B6 after B5 (F-5): **eighteen dispatches in eleven waves.**

| wave | lane 1 | lane 2 | why concurrent is safe |
|---|---|---|---|
| 1 | S1 | A1 | S1 is `A/data/room` + one `AppGraph` constant; A1 is `C/model` + `C/schedule` + `C/season` |
| 2 | B1 | A2 | B1 is new `C/hapublish` files only; A2 owns the wave's `AppGraph` edit |
| 3 | B2 | A3 | B2 edits no graph; A3 does (one argument) |
| 4 | B3 | A4 | B3 owns the graph and the three `when` arms; A4 builds its read inside the handler |
| 5 | B4a | A5a | lane 1 owns the graph; A5a is UI (season card, completion sheet) |
| 6 | B4b1 | A5b | lane 1 owns the graph; A5b is UI (schedule, editors) — no file shared with A5a's but `ReadinessStrings.kt`, sequential by wave |
| 7 | B4b2 | — | the graph |
| 8 | B5 | — | the graph, last edit |
| 9 | B6a | D1 | UI versus docs |
| 10 | B6b | — | UI over B6a's frame |
| 11 | B7 | — | test sources over everything drawn |

**Dispatch order and dependencies:** S1 ∥ A1 → B1 ∥ A2 (A1's model, S1's columns) → B2 (B1's kinds and mapper) ∥ A3
(A1's model) → B3 (S1's DAOs, B1's model and validators; ratified P113-42/43) ∥ A4 (A1's rule, A2's mapper) → B4a
(B2's client, B3's repositories) ∥ A5a (A1) → B4b1 (B4a) ∥ A5b (A2, A5a's strings file) → B4b2 (B4b1) → B5 (B4b2;
B3's `DismissDecommission`) → B6a (B3's commands, B4a's runner, **B5's** `pendingDecommissions` read model and
`drainBeforeForget`, A5a's strings) ∥ D1 → B6b (B6a's frame and sheet, B5) → B7 (everything drawn). Each brief is
≤ 1 h by estimate. **AppGraph/FakeGraph edits:** S1, A2, A3 (`ImportTransferPack`'s `today`), B3, B4a, B4b1, B4b2, B5 — never two in one
wave, B5 last (G20).

**Between-brief gaps** (each unreachable: no screen enables a binding before B6a): S1 → B3, the tables exist with no
writer and `config_generation` stays 0 until B3 maps it; A2 → A5b, the role is settable only from tests; B2 → B4a, the client is unwired; B3 → B4b1, an enabled binding
in a test has no projector (rows script the desired value); B4a → B4b1, the runner's `DesiredStateReconciler` is a
placeholder, so a boundary read re-projects nothing until B4b1 wires the projector; B4b1 → B4b2, no background work (in-process drains only);
A3 → B5, the format moves while publishing has no read route.

**What stays untouched (R6 checks each):** `HomeAssistantStateClient.kt` but the gate call; every shipped
`seasonsync` test in `T/` **and** `CT/` (F-6); `SeasonSyncRunner`, `SeasonSyncWorker`, `SeasonSyncWork`,
`SeasonSyncGuard`, `RecordSeasonSyncResult`; `RecordCondition`, `RecordConditionWithIncident`, the two offers,
`CompleteSchedule`, `CompleteGroupMembers`, `UpdateEvent` and `LogEvent` (but `buildEvent`'s one `imported` line, A2),
`DeleteEvent`, `DeleteAsset`, `ImportBackupReplace`, `ApplyBackupMergePlan` and `BuildBackupMergePlan` but A3's context
and return-carry lines (A01, A), `ReplaceAsset`; `CreateTransferPack`, `MarkTransferredOut` and `C/transfer/**`; `21.json`; `ServiceTagRoot.kt`; the manifest but `:12`; `S/`;
the gitlink.

**The connected-suite budget (reporting only, 2026-09-29 ruling).** Today 78 classes / 430 tests / ~16 min under
software rendering. New: `HaPublishScreensTest` (~14 cases, ~1 min) and `HaPublishPlatformProofTest` (~6 cases,
~0.5 min) → **80 classes / ~450 tests / ~17.5 min**; `SeasonSyncPlatformProofTest` and `SeasonSyncScreensTest`
unchanged. **JVM:** core +~160, app +~180, MCP +~8.

## 5. Strings

**PENDING — every row is proposed English for the owner gate (`docs/localization.md`): ratified copy only enters the
ten packs, with its id in the XML comment.** Reused ratified #16 sentences are named, not repeated. Homes:
`A/ui/asset/ReadinessStrings.kt` + `strings_season_start.xml` (P114-1…29, P114-40…46, P114-50…53) and
`A/ui/asset/PublishStrings.kt` + `strings_ha_publishing.xml` (P113-*, P114-30…39, P114-47/48); the HA screen's in
`HomeAssistantStrings.kt`.

### A5a / A5b — the role and readiness (#114; A5a: P114-7…15, 29, 40, 44…46, 50…53; A5b: the rest)

**Rev 3:** P114-4, P114-4g and P114-9 carry the advisor's recommended wording (pending the owner's word, §6); P114-44…48
are new (A06, A07) and PENDING like every row. **Rev 4:** P114-44 gains the date; P114-45 is reworded for the
unverified state (D); P114-46 is amended (B: the marker also covers a borrower's record); P114-50…53 are new (D, F);
all PENDING.

| id | proposed wording | where · state |
|---|---|---|
| P114-1 | "Season Start service" | the schedule row and detail label (C33) |
| P114-2 | "Use as Season Start service" | the schedule detail action |
| P114-3 | "Stop using as Season Start service" | the clear action |
| P114-4 | "Use \"%1$s\" as %2$s's Season Start service? Completing it marks %2$s ready for the season. Services dated on or after %3$s count." | the confirm, asset target |
| P114-4g | "Use \"%1$s\" as the Season Start service for every asset in %2$s? Completing it for an asset marks that asset ready for the season. Services dated on or after %3$s count." | the confirm, group target (C1) |
| P114-5 | "This schedule is completed with one tap and no service details. A one-tap completion will still mark the asset ready." | the confirm's note on a QUICK schedule (O-2) |
| P114-6 | "Services count from" | the confirm's date field |
| P114-7 | "Ready for the season · serviced %s" | `Ready` (C33) |
| P114-8 | "Not ready: not serviced since the season ended on %s" | `NOT_DONE_SINCE_SEASON_END` |
| P114-9 | "Not ready: no qualifying service dated on or after %s." | `NOT_DONE_SINCE_COMMISSIONING` |
| P114-10 | "Not ready: the service's details are still to be filled in" | `DETAILS_PENDING` |
| P114-11 | "Not ready: the Season Start service is archived" | `SCHEDULE_ARCHIVED` |
| P114-12 | "Not ready: a Season Start service applies only to an asset with seasons that has no parent or has child assets" | `ROLE_NOT_ALLOWED` |
| P114-13 | "Not ready: two Season Start services cover this asset. Stop using one of them." | `TWO_ROLES` |
| P114-14 | "Completing this marks %s ready for the season." | the completion sheet, asset role |
| P114-15 | "Completing this for an asset marks that asset ready for the season." | the completion sheet, group role |
| P114-16 | "%s is a child asset with no child assets of its own, so it cannot have a Season Start service." | `LEAF_CHILD` |
| P114-17 | "%s is in use all year, so it has no season to start. Give it a calendar or manual season first." | `YEAR_ROUND` |
| P114-18 | "%s is archived, retired or transferred out, so it cannot have a Season Start service." | `NOT_MAINTAINED_HERE` |
| P114-19 | "This schedule is archived. Restore it first." | `SCHEDULE_ARCHIVED` (refusal) |
| P114-20 | "%1$s already has a Season Start service: \"%2$s\". Stop using that one first." | `ALREADY_COVERED` |
| P114-21 | "Every asset in this group must be able to have a Season Start service. %s" | a group member's refusal; `%s` is P114-16/17/18/20's sentence |
| P114-22 | "This group has no assets to mark ready." | `GROUP_HAS_NO_MEMBERS`, `GROUP_ARCHIVED` |
| P114-23 | "The date services count from cannot be after today." | `DATE_IN_FUTURE` |
| P114-24 | "%1$s has a Season Start service, so it needs seasons. Stop using \"%2$s\" as its Season Start service first." | strand `NEEDS_SEASONS` (asset editor) |
| P114-25 | "This change would leave %1$s a child asset with no child assets while it has a Season Start service. Stop using \"%2$s\" as its Season Start service first." | strand `WOULD_BE_LEAF_CHILD` |
| P114-26 | "This schedule is a Season Start service, so its asset or group cannot change. Stop using it as the Season Start service first." | strand `ROLE_SCHEDULE_RETARGET` (schedule editor) |
| P114-27 | "%s cannot join this group: the group's Season Start service would cover it, and it already has one or cannot have one." | strand `MEMBER_*` (group editor) |
| P114-40 | "Not ready: no Season Start service" | `NO_ROLE`, drawn only with a date binding (R-N1) |
| P114-41 | "Season Start service · services count from %s" | the schedule detail's role line |
| P114-28 | "Enter the date services count from as a calendar date." | `SetScheduleRole`'s `BadDate` (F-16) |
| P114-29 | "Not ready yet: the service is dated %s, after today" | `FUTURE_DATED` (F-8) |
| P114-42 | "Count one-tap completions" | the editor's FORM → QUICK election confirm button; the body is P114-5 (F-22) |
| P114-43 | "This schedule is a Season Start service. Confirm that one-tap completions should count before changing it to one tap." | strand `QUICK_NEEDS_ELECTION` |
| P114-44 | "Season end from Home Assistant: %1$s · checked %2$s" | the source line, `HA_SEASON_ENDED` (C9a; rev 4: the date added) |
| P114-45 | "Season end not checked with Home Assistant since %1$s · using %2$s" | the source line, `HA_UNVERIFIED`: the since date and the season end used (C9a, D; rev 4 reworded) |
| P114-46 | "Not ready: the service on record was recorded on another phone and came with this asset. Record this season's service on this phone." | `INHERITED_SERVICE_ONLY` (B; rev 4 amended) |
| P114-50 | "Services dated on or after %s count." | the cutoff line, `cutoffOn` (F) |
| P114-51 | "Season end as recorded on this phone: %s" | the source line, `PHONE_SEASON_END` (D) |
| P114-52 | "Season end from the calendar season: %s" | the source line, `CALENDAR_WINDOW` (D) |
| P114-53 | "Counted from the day the Season Start service was set: %s" | the source line, `COMMISSIONING` (F) |

### B6a — equipment status (#113) and the Home Assistant screen (P113-42/43: B3)

| id | proposed wording | where · state |
|---|---|---|
| P113-1 | "Publish equipment status to Home Assistant" | block title and its switch (C36) |
| P113-2 | "Home Assistant helper" | the sheet's id field |
| P113-3 | "Choose helper" | the browser button |
| P113-4 | "Dropdown helpers (input_select)" | the browser's scope title |
| P113-5 | "Publish %1$s's equipment status to %2$s? ServiceTag sets this helper whenever the recorded condition changes: Operational → UP, Degraded → DEGRADED, Down → DOWN. Home Assistant automations should read it and never set it." | the enable confirm |
| P113-6 | "Publish" | the confirm button |
| P113-7 | "Waiting: no equipment status is recorded, so nothing is sent." | `WAITING_FOR_SOURCE` |
| P113-8 | "Not published yet." | `PENDING` before any success |
| P113-9 | "Sending %s…" | `PENDING` |
| P113-10 | "Published %1$s · %2$s" | `PUBLISHED`: value, time (also the date block) |
| P113-11 | "Home Assistant shows %1$s, which matches ServiceTag · checked %2$s" | `MATCHES` (never "published") |
| P113-12 | "Publish now" | action (C30 reconcile mode) |
| P113-13 | "Stop publishing" | action |
| P113-14 | "Stop publishing to %s? Home Assistant keeps the value it has now." | status confirm |
| P113-15 | "%s must be a dropdown helper whose options are exactly UP, DOWN and DEGRADED." | `OPTIONS_MISMATCH` |
| P113-16 | "%s is not a dropdown helper (input_select)." | `WRONG_DOMAIN` (status) |
| P113-17 | "Home Assistant refused the value %1$s for %2$s." | `SERVICE_REFUSED` |
| P113-18 | "Home Assistant refused this change for the access token's user. That user needs permission to change helpers." | `WRITE_FORBIDDEN` |
| P113-19 | "Home Assistant did not confirm the change. ServiceTag will check the helper before sending again." | `OUTCOME_UNKNOWN` |
| P113-20 | "Home Assistant shows %1$s after the change; something else may be setting %2$s." | `REMOTE_DIFFERS` |
| P113-21 | "This setup came from another phone's backup, so this phone does not publish. Check the helper and turn publishing on again here." | `COPIED_INSTALLATION` |
| P113-22 | "%s is already set by another asset on this phone." | `TARGET_IN_USE` |
| P113-23 | "%s is the helper this asset's season follows. Choose another: ServiceTag never sets a helper it reads." | `INBOUND_SOURCE` (C26) |
| P113-24 | "Not sending: the asset is archived, retired or transferred out. Home Assistant keeps its last value." | status `NOT_MAINTAINED_HERE` |
| P113-25 | "Check helper" | `RevalidatePublishing` |
| P113-26 | "Sent when the phone can: on opening, on Publish now, or in the background where allowed. It is not instant." | the delivery limit line |
| P113-27 | "Home Assistant answered %s." | `HTTP_ERROR` (status code) |
| P113-28 | "Checking the helper…" | the sheet's fresh read |
| P113-29 | "Choose a helper first." | `BAD_ENTITY_ID` / empty |
| P113-30 | "Check the helper again before ServiceTag publishes to it." | `NEEDS_VALIDATION` (F-16) |
| P113-31 | "The helper changed while it was being checked. Check it again." | `PublishRefusal.STALE_READ` |
| P113-32 | "%s is archived, retired or transferred out, so it cannot publish to Home Assistant." | `PublishRefusal.NOT_MAINTAINED_HERE` |
| P113-40 | "When an asset publishes to Home Assistant, this phone also sets that helper with this token. Give the token's user only the rights it needs." | HA screen disclosure |
| P113-41 | **plurals** `ha_publish_forget_date_helpers` in `values/plurals.xml` (F-15): one — "%d date helper in Home Assistant still holds a Season Start date. Disconnecting first sets it to 1970-01-01 if it can; if it cannot, it keeps its date until you clear it in Home Assistant."; other — "%d date helpers in Home Assistant still hold a Season Start date. Disconnecting first sets them to 1970-01-01 where it can; any it cannot reach keep their date until you clear them in Home Assistant." (every form shows `%d`) | the Forget warning (O-11), before P16-9; `localizedPlural` |
| P113-42 (B3) |  "Stop publishing Season Start dates before changing the address: the old Home Assistant has to be told first." | `PUBLISHING_BLOCKS_ADDRESS_CHANGE` (O-12) |
| P113-43 (B3) |  "%s is a helper this phone sets. Choose the helper Home Assistant itself controls." | `OUTBOUND_TARGET` on the inbound sheet (C26) |

### B6b — the Season Start date (#114)

| id | proposed wording | where · state |
|---|---|---|
| P114-30 | "Publish Season Start date to Home Assistant" | block title and switch |
| P114-31 | "Date helpers (input_datetime)" | the browser's scope title |
| P114-32 | "%s must be a date helper with a date and no time." | `NOT_DATE_ONLY` |
| P114-33 | "Publish %1$s's Season Start date to %2$s? While %1$s is ready for the season, ServiceTag sets the service date; otherwise it sets 1970-01-01, which means not ready. Home Assistant automations should read it and never set it." | the enable confirm |
| P114-34 | "%s is not a date helper (input_datetime)." | `WRONG_DOMAIN` (date) |
| P114-35 | "Stop publishing to %s? ServiceTag first sets it to 1970-01-01, so Home Assistant stops treating the old date as a service." | date confirm |
| P114-36 | "Not yet cleared in Home Assistant: %s may still allow starts. ServiceTag keeps trying; if this phone cannot reach Home Assistant, set it to 1970-01-01 there." | PENDING tombstone / pending `1970-01-01` (C34) |
| P114-37 | "ServiceTag can no longer reach %s. It may still allow starts: set it to 1970-01-01 in Home Assistant." | UNDELIVERABLE |
| P114-38 | "I cleared it in Home Assistant" | `DismissDecommission` |
| P114-39 | "Cleared in Home Assistant: %s is 1970-01-01. Nothing more is sent while the asset is archived, retired or transferred out." | `INVALIDATED_INELIGIBLE` |
| P114-47 | "Season-ended helper (the date Home Assistant records when the season ends)" | the date sheet's boundary field (C9a, A07) |
| P114-48 | "%s is the helper ServiceTag sets. Choose the date helper Home Assistant stamps when the season ends." | `PublishRefusal.BOUNDARY_IS_TARGET` (C25) |

**Reused, ratified (#16):** P16-12 (`ENDPOINT_REFUSED`), P16-13 (`UNREACHABLE`), P16-14 (`TIMED_OUT`), P16-15
(`DENIED`), P16-16 (`AUTH_REFUSED`), P16-10 (`PublishRefusal.NO_CONNECTION`), P16-17 (`ENTITY_NOT_FOUND`), P16-11 (`NEEDS_TOKEN`), P16-50 and P16-68…81
(`NOT_ON_LOCAL_NETWORK` by cause), the `REDIRECTED`, `MALFORMED`, `NAME_NOT_LOCAL` and `TLS_FAILED` sentences, P16-9
(the Forget confirm, unchanged). **Count (rev 4):** 41 (A5a/A5b) + 35 strings and one plural (#113 and the HA screen;
P113-42/43 are B3's) + 12 (date) = **88 new strings and one plural**, ~890 entries; above the audit's ~60 by the split
reasons, the strand causes, the HA-screen lines and the boundary, cutoff and inheritance lines of revs 3 and 4.

**Rule (F-16):** every `PublishState`, `PublishErrorKind`, `PublishRefusal`, `NotReadyReason`, `BoundarySource`,
`SeasonStartRefusal` and `SeasonStartStrandCause` member named in §2 has an id above (or a named reused #16 sentence); an enabled date
binding whose desired value is `1970-01-01` and whose verified pair is not current draws P114-36 (P114-37 when its
error is NEEDS_OWNER) beside its error sentence. **Glossary:** **Season Start service**, **ready for the season**
(A5a) and **helper**, **publish** (B6a) enter `docs/localization-glossary.md` in every shipped language first.

**G-list (developer log lines, `// l10n-ok`, no URL, host, entity, Wi-Fi name, token or exception text):**
GH1 `"ha publish run failed; WorkManager retries it"` (database failure); GH2 `"ha publish could not run; it will
retry on the next resume, scheduled run, or app start"` (any other exception in the worker or a trigger); GH3 `"the ha
publish reconcile failed; the next trigger repeats it"` (the start, resume or midnight reconcile).

## 6. Owner rulings

**Rev 3.** An advisor's recommended rulings reached the owner on 2026-10-22 and were forwarded; they are **not yet the
owner's word**. Each such row reads "the plan's position, **recommended to the owner 2026-10-22, pending the owner's word**". Nothing here is ratified by this plan; the owner
gate decides every row marked **owner**. **Rev 4** changes no row's status: the controller's rulings on the external
review of rev 3 move the plan's positions in O-8 (A, B), O-17 (E) and O-19 (D) and add O-20 (B); every row the advisor
recommended still reads "pending the owner's word".

| ruling | status | the plan's position · the alternative | lands |
|---|---|---|---|
| **R-0** | **owner — PENDING** (approval of the plan recommended 2026-10-22) | The scope sentence of §1, exactly, with the per-asset boundary read inside it (rev 4, D: "…and, for a date binding on a `MANUAL` asset whose season HA drives, one read of HA's season-ended helper"); the fence (G17) binding on every brief. *Alt.:* the same scope without C1 (asset-schedule roles only; the fallback below). | header, §1 |
| **C2** | **owner — DECIDED 2026-10-22** | "In season shouldn't be the same as initial pre-season maintenance complete readiness": an HA-owned in-season boolean (inbound, #16) and a ServiceTag-owned date publication (outbound, #114); the heater re-binds through the shipped `EditSeasonSyncEntity` before date publishing is enabled; the exact-id cross-check both ways (C26). | C26, §7, §8 |
| **C1** | **owner — PENDING**; yes **recommended to the owner 2026-10-22, pending the owner's word** | `SEASON_START` may sit on a **group** schedule; readiness is derived per open member from that member's own completion event (C9); every open member must qualify at assignment (C10); one coverage per asset across asset and group roles. *Alternative:* asset schedules only (#114 §1 as written) — the fallback paragraph below. | C9, C10, C11, rows 11, 15, 16, 20 |
| **Q2 (F-12)** | **owner — PENDING** | **Voice/Assist starts and the HA gate.** *The plan's position, **recommended to the owner 2026-10-22, pending the owner's word**: (b)* — #114 §5 as written: an ordinary voice or Assist ON passes the same readiness check as an automatic start and is refused with its reason while not released; `stove_check`'s person-class exemption from the serviced check is removed **for ON**; the explicit freeze override (§8) stays the **only** waiver; the appliance's own panel stays outside HA. *Alt. (a), rev 2's position:* a person's start stays outside the check and keeps its spoken caveat; only automatic starts are gated. D1 documents whichever is ruled. | §8, C38 |
| O-1 | plan | CALENDAR "season ended" = the **first out-of-season day** (window end + 1). *Alt.:* the last in-season day (a service on that day would count for the next season). | C8, row 2 |
| O-2 | **owner — PENDING**; yes by explicit election **recommended to the owner 2026-10-22, pending the owner's word** | QUICK counts on a role schedule only by the owner's explicit election; assigning the role to a QUICK schedule shows P114-5 and the confirm is the owner's explicit election; a later FORM → QUICK edit of a role schedule asks again (F-22: `QUICK_NEEDS_ELECTION`, P114-42/43); a FORM completion with `detailsPending` never counts. *Alt.:* FORM only (C1 then unusable: group schedules are QUICK-only, `C/usecase/ScheduleCommands.kt:110-116`). | C9, C11, C33, rows 6, 20 |
| O-3 | plan | The published date is the **latest** qualifying `occurredOn`. *Alt.:* the earliest after the boundary. | C9, row 3 |
| O-4 | plan | The boundary is stored `roleSinceOn`, default today, the owner may choose any date ≤ today (shown in the confirm); one boundary for all group members. *Alt.:* per member, `max(roleSinceOn, addedAt's day)`. | C10, row 13 |
| O-5 | plan | "Parent asset" = at least one child row, whatever its lifecycle. *Alt.:* only maintained-here children. | C9, C10, row 10 |
| O-6 | plan | Retargeting a role schedule is refused; clear the role first. *Alt.:* retarget clears the role in the same write. | C11, row 20 |
| O-7 | plan | No API or MCP write for the role in V1; read projections only. *Alt.:* `PUT /v1/schedules/{id}/role` as a canonical command. | C10, C29 |
| O-8 | plan (rev 3: A01, A06; rev 4: A, B) | Transfer Pack: the role travels; an inserted role schedule's `roleSinceOn` becomes the recipient's import day, fixed once by the preview's `ImportContext` (A01); **a returning asset keeps its local role, `roleSinceOn` and its own events' markers** (A, `ReturnCarry`, C20). **Evidence policy (B):** every event a Transfer Pack import inserts is marked `imported` and never qualifies for the recipient's readiness — the recipient records its own service (scenario 8 now reads: imported on the day of the seller's service, the recipient is `INHERITED_SERVICE_ONLY` until it records its own); the marker is durable, travels in format 21, and no edit, role change or merge clears it; no clock decides it. *Alt. 1:* accept same-day inherited evidence and state the limitation in scenario 8. *Alt. 2:* strip the role at pack creation. | C9, C20, rows 3b, 21a, 49, 49a–49c |
| O-9 | plan | A merge bringing a second coverage for an asset is a **conflict** (two new reasons). *Alt.:* `SKIPPED` like the health-subject precedent, or insert with the role dropped (silent; not recommended). | C19, row 48 |
| O-10 | plan | One Room step (22) by S1 and one format step (21) by A3. *Alt.:* one step per issue (Room 22 for #114, Room 23 for #113). | C4, C18 |
| O-11 | plan | Forget warns with the count (P113-41), drains each invalidation once, then disconnects; any not confirmed stay as UNDELIVERABLE tombstones until dismissed. *Alt.:* refuse Forget until every invalidation is confirmed. | C28, C34, row 73 |
| O-12 | plan | An address change is refused while any date binding is enabled or a tombstone is pending. *Alt.:* allow, and turn each into UNDELIVERABLE. | C28, row 87 |
| O-13 | plan | The A-track (readiness on the phone, no HA) may land first; date publishing needs both tracks; nothing is released by this plan. *Alt.:* one release carrying both tracks, nothing landed separately. | §4 |
| O-14 | plan | The interim and the order: §7's commissioning sequence and §8's commissioning order (the owner's real state is in the private ledger). *Alt.:* the hand-set interim continues and publishing waits for a release. | §7, §8 |
| O-15 | plan | "Qualifying" = the shipped `detailsPending == false`; the plan accepts that any edit of the event clears the flag (`C/usecase/EventCommands.kt:293`). *Alt.:* additionally require a FORM completion to carry a value for every required profile field. | C9, limit 7 |
| **O-16 (F-8)** | **owner** | Readiness never counts a completion dated after today (reason `FUTURE_DATED`, P114-29; it starts counting on its own day through the midnight tick). The question for the owner: **should the event commands refuse a future `occurredOn` outright**, as `RecordCondition` already does (`C/usecase/RecordCondition.kt:91`)? *Plan position:* readiness only (no product change to the journal in this programme). *Alt.:* refuse future dates in `CompleteSchedule`, `CompleteGroupMembers` and `UpdateEvent` (a separate issue). | C9, row 4 |
| **O-17 (Q3; rev 4: E)** | **owner — PENDING** | **The HA in-season signal and the season-ended stamp, two facts.** *The plan's position — the heating-year latch **recommended to the owner 2026-10-22**, corrected 2026-10-23 by the controller's ruling on the external review (E), **pending the owner's word**:* **`in_season`**, the operating permission the phone follows through #16, tracks the house's heat-source assignment **edge for edge**; **`season_ended`**, the service-cycle boundary, is stamped **once per heating year** (1 August–31 July) at the first `on → off` edge in its 1 May–31 July closing window, else at the 1 August reset. **The latch applies to the stamp only.** So a June cold snap turns `in_season` ON (the freeze override can act; rev 3's latch left no way to start); last season's service stays out (the season ended in May); a service made for the snap, and the next autumn's, are ≥ May's stamp and count; the snap's end stamps nothing. Early-spring and autumn warm spells record phone END/START rows that wake and sleep the in-service schedules and leave readiness alone while HA's boundary is fresh (D; limit 16 while unverified). *Alt.:* stamp at every `on → off` edge in the closing window (simpler; a service made for a June cold snap is revoked when the snap ends). | §8, §7, row 100a |
| **O-18 (F-14)** | **owner — PENDING** | **The second appliance's season.** An asset that is year-round today needs a season before a role can cover it (`YEAR_ROUND`, P114-17 inside P114-21); the owner's real state is in the private ledger. *The plan's position, **recommended to the owner 2026-10-22, pending the owner's word**:* "Example Boiler" takes a `MANUAL` season bound (Follow) to the **same, shared** HA in-season boolean as "Example Heater"; each appliance keeps **separate completion evidence** (its own completion of the group schedule, C9) and **separate outbound date helpers — two date bindings, two helpers** (`input_datetime.example_heater_service_done`, `input_datetime.example_boiler_service_done`); both may read one season-ended helper (C9a: a read is not a claim). #16 allows two assets to bind one inbound helper (`SeasonSyncLinkRefusal`, `C/seasonsync/LinkSeasonSync.kt:23-38`, has no entity-uniqueness member; `season_sync_binding` is keyed by `asset_id` with only a `connection_id` index, `A/data/room/entities/SeasonSyncBindingEntity.kt:35`). *Alt. 1:* its own HA in-season helper (when the boiler's gate of §8 is built). *Alt. 2:* a `CALENDAR` season (no HA link). | §7 step 2b, row 16 |
| **O-19 (A07; rev 4: D)** | **owner — PENDING** | **The readiness boundary, per asset.** *Plan position (controller rulings for revs 3 and 4):* C9a — a `CALENDAR` asset's window is its boundary and HA's date never overrides it; a `MANUAL` asset with an enabled #16 binding and a boundary helper named on its date binding uses HA's `season_ended`, **fresh** while read at the binding's current revision within max(7 days, the cadence) and not before its newest activation, otherwise **unverified** — the later of the cached date and the phone's END, "unverified since" on the card (P114-45) and the API (`boundaryUnverifiedSince`), publication proceeding with HA's own comparison as the backstop; every other `MANUAL` asset uses the phone's END. *Alt.:* no boundary read; document the limitation (a late observation moves the phone's boundary later, a missed cycle leaves none) and give the owner a reconciliation workflow — backdate the END on the phone to HA's `season_ended` by hand; the phone's readiness may then disagree with HA's release until the owner does. | C9, C9a, C25, C29, C30, rows 3a, 3c, 98–101b |
| **O-20 (B)** | **owner** | **Which imports mark an event `imported`.** *Plan position:* only a Transfer Pack import (another owner's, or a borrower's, record); a backup Replace or Merge of the owner's own archives marks nothing and writes each event's marker verbatim — the same owner's history counts as recorded. *Alt.:* also mark every event a merge inserts from another installation; archives carry no installation id today (§10), so this needs a format-21 manifest key naming the exporting installation and a rule for archives older than it. | C9, C20, row 49b |
| **Vehicle** | **owner — PENDING** | *The plan's position, **recommended to the owner 2026-10-22, pending the owner's word**:* the next unallocated MINOR after the intelligence train (1.10.0 if free per #104). **No release, version bump or tag is authorized by this plan** (G14). | G14, §7 |
| R-N1 | plan | The card's readiness line is drawn only when a role covers the asset or a date binding exists (`NO_ROLE` is otherwise silent). *Alt.:* on every seasonal top-level or parent asset. | C33, row 75 |
| R-N2 | plan | `ha_publish_binding.asset_id` is a soft link (no FK), so asset delete and replace restore leave the obligation for the projector — no shipped use case is touched. *Alt.:* hook `DeleteAsset` and `ImportBackupReplace`. | C4, C34, row 71 |
| R-N3 | plan | 403 on a write is its own kind and sentence (`WRITE_FORBIDDEN`, P113-18); 404 on a service call is `HTTP_ERROR`; a missing entity is found by the readback. *Alt.:* the audit's 401/403 → `AUTH_REFUSED`, 404 → `ENTITY_NOT_FOUND`. | C23, row 53 |
| R-N4 | plan | A periodic `"ha-publish-reconcile"` at the connection's cadence repairs HA resets while the app is closed (#113's "periodic reconciliation"); no expedited work. *Alt.:* reconcile only at start, resume and Publish now. | C32 |
| R-N5 | plan | An enabled status binding on an asset that stops being maintained here resumes on its own when it returns (#16's R16-18 shape). *Alt.:* disable it. | C25, row 74 |
| R-N6 | **owner — PENDING** | The strings of §5 as proposed (88 and one plural); approved **recommended to the owner 2026-10-22, pending the owner's word** except P114-4/4g ("Services dated on or after %3$s count.") and P114-9 ("Not ready: no qualifying service dated on or after %s."), which §5 now carries; P114-44…48 (new in rev 3) and P114-44/45/46 as reworded and P114-50…53 (rev 4) are not covered by that recommendation. *Alt.:* the owner rewrites them one by one. | §5 |
| **R-N7 (F-18)** | plan, recorded | **"Publication not verified" is not a card reason** (#114 §3 lists it): the season card shows only readiness reasons; the date block carries verification and the "may still allow starts" lines. *Alt.:* the card line gains a "not yet confirmed in Home Assistant" suffix while the date binding's state is not PUBLISHED or MATCHES. | C29, C33, C36 |
| **R-N8 (Q5)** | plan | **D1 rewrites "reads, and only reads" and the outbound inventory texts in this same branch:** the docs ship with the code that makes them true; #113's "only when it ships" forbids changing them before the feature exists in the tree, which on this branch it does. *Alt.:* hold the wording for the release that carries the feature. | C38, D1 |

**If C1 is refused (F-13) — the fallback.** The owner's release becomes **two asset FORM schedules**, each
"Season Start service" on its own asset ("Example Heater"'s, and "Example Boiler"'s once O-18 gives it a season), each
with its own role, boundary and form; the group schedule "Example pre-season check" stays as it is for the ordinary
QUICK tick and carries no role. What changes: C9 loses the group branch of coverage (asset targets only) and `TWO_ROLES` can then arise only from
a merge; C10 refuses any group target with a new refusal `GROUP_TARGET` (one sentence, replacing P114-4g, P114-15,
P114-21 and P114-22: "A Season Start service must be one asset's own schedule."); C11 drops the `SaveGroup` hook and
both `MEMBER_*` causes (P114-27 goes); rows 11 (group cases), 15 (group cases), 16 and 20 (group case) are replaced by
one row `aGroupTargetIsRefused`; O-2 reverts to #114 point 3 — FORM by default, QUICK only by explicit election on an
asset schedule (P114-5 kept); the merge claim (C19) and A5b's group editor work drop; commissioning step 3 assigns
the two asset roles and step 4 completes the two FORM schedules.

**Recommended to the owner 2026-10-22, pending the owner's word, as the plan states it:** O-1, O-3…O-16 and
R-N1…R-N8 at the positions above (O-8 as amended by A06 and, in rev 4, by A and B; O-20 is new in rev 4 and not
covered by that recommendation).

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green with zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; A4 and B5 also `uv run --frozen pytest` in `M/`; A3 also in `S/` and `B/`
  (unchanged, green). No device run inside a brief.
- **The merged-tip gate, once (`docs/release-proofs.md`):**
  - **R1** JVM from scratch, including every row above, `VersionAgreementTest` (22/21), `LocalizationCoverageTest`,
    `UiLiteralGuardTest`, `ReleaseProofPolicyTest` unchanged and green.
  - **R2** the connected suite on `emulator-5554` (`-no-window -gpu swangle_indirect -feature -Vulkan`), **80 classes**,
    zero skips; rows 91 and 92 run here first (a red is one post-merge fix round); the environment recorded (one run or
    not, elapsed and device time apart; reporting only).
  - **R3** the three Python suites.
  - **R5** structural: both `ManifestContractTest` classes green with **no new permission or component**;
    `VersionAgreementTest`.
  - **R6 anchored greps (`git grep -nE`), expected counts at the tip, each hit read:**
    - `'"/api/services/"'` → 1, in `A/hapublish/HomeAssistantPublishClient.kt` only;
    - `'requestMethod = "POST"'` in `app/src/main` → 1 (that file); `'requestMethod = "GET"'` in
      `A/seasonsync/HomeAssistantStateClient.kt` → 1 (unchanged);
    - `'"/api/states'` in `app/src/main` → hits only in the two clients' GET paths (read: no POST builds it);
        - `'setRequestProperty\("Authorization"'` in `app/src/main` → 2 (the two clients);
    - `'doOutput = true'` in `app/src/main` → 1 (the publish client; F-20); `'openConnection\('` in `app/src/main` →
      the base count + 1, each hit read; `'"(PUT|PATCH|DELETE)"'` in `A/hapublish` → 0;
    - `'OutboundTargets\.NONE'` in `app/src/main` → 0 (the defaulted collaborator never used in production, F-6);
    - `'mergePlanOf\(plan\.backup, scope\.snapshot, returning, plan\.context, scope\.carry\)'` in
      `ApplyBackupMergePlan.kt` → 1, and `git diff <base> -- core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ApplyBackupMergePlan.kt`
      → that line and `ReturnScope`'s carry (its field, its construction from the rows it already removes) only, each
      hunk read (A01, A);
    - `'role_evidenc[e]_after|roleEvidenc[e]After|importA[t]\b'` in `core/src/main`, `app/src/main`, `app/schemas`
      → 0 (B; the brackets keep this line from matching itself);
    - `'deliveries\.observe|observeAll\(\)'` in `A/hapublish/{PublishProjector,PublishInputs}.kt` → only the source
      repositories' `observeAll()` (read each; no delivery flow, no tombstone `observeAll`, C);
    - `'category|\.name\b|title'` in `C/season/SeasonReadiness.kt` and `C/hapublish/BoundaryFreshness.kt` → 0 (G22);
    - `'configGeneration'` in `C/hapublish/PublishCommands.kt` → the STALE_READ compare, and `'updatedAt'` there → none
      in that compare (F);
    - `'activatedInstallationId = '` in `C/hapublish` → the Enable / Change target sites only, none in Revalidate's body
      (read them; A08);
    - `'^enum class HaServiceAction'` → 1, and its two members `'SELECT_OPTION\("input_select", "select_option"'`,
      `'SET_DATE\("input_datetime", "set_datetime"'` → 1 each;
    - `'(climate|switch|script|rest_command|light|lock|input_boolean|input_text)/'` in `A/hapublish` and
      `C/hapublish` → 0; `'set_options|turn_on|turn_off|set_value|api/states.*POST'` there → 0;
    - `'Log\.[dewiv]\('` in `A/hapublish` → exactly the G-list sites (read: no `$` template, no exception argument);
    - `'^const val INVALID_SERVICE_DATE = "1970-01-01"$'` → 1; `'"1970-01-01"'` in `app/src/main` (Kotlin) → 0;
    - `'^        const val SCHEMA_VERSION = 22$'` → 1; `'^    version = 22,$'` in `AppDatabase.kt` → 1;
      `'^    const val FORMAT_VERSION = 21$'` → 1; `'FIRST_SCHEDULE_ROLE_FORMAT = 21'` → 1; `'MIGRATION_21_22'` in
      `AppGraph.kt` and `MigrationTestSupport.kt` → each equal to `'MIGRATION_20_21'`'s count there; `git diff <base> --
      app/schemas/*/21.json` → empty; `'CREATE TABLE'` inside `MIGRATION_21_22` → 3 (read it);
    - `'ha_publish|desiredValue|activatedInstallation|reconcilePending|configGeneration'` in `C/backup`, `C/merge`,
      `C/transfer` → 0;
    - `'setExpedited|ForegroundInfo|foregroundServiceType|registerReceiver|registerNetworkCallback'` in `A/hapublish` → 0;
        - **every** `'else ->'` in the files the range adds or modifies is listed and read: none may sit in a `when` over a
      closed set this plan adds or extends (F-20);
    - `'"SEASON_START_STRANDED"'` and `'"SEASON_START_REFUSED"'` in `ApiJson.kt` → 1 each; `'^.mcp\.tool\('` (the
      tool decorator) in `server.py` → 92; `'^_MIN_HA_PUBLISHING_SCHEMA_VERSION = 22$'` → 1;
    - `git diff <base> --diff-filter=M -- app/src/test/kotlin/com/loosecannon/servicetag/seasonsync core/src/test/kotlin/com/loosecannon/servicetag/core/seasonsync`
      → empty (F-6: the defaulted collaborators keep `CT/seasonsync` byte-identical; no new file lands there either);
    - `git diff <base> -- app/src/main/AndroidManifest.xml` → the `:12` comment only (read it);
    - the tombstone check → 0; gitlink `7e0377a`; `versionName`/`versionCode` unchanged; `git diff <base> --
      app/build.gradle.kts core/build.gradle.kts gradle/libs.versions.toml tools/*/pyproject.toml tools/*/uv.lock` → empty;
      `git diff <base> -- tools/servicetag-schedules` → empty;
    - **no personal data:** the owner's HA names from `ha-inventory.md` and the real-HA proof's env keys → 0 hits in the
      range (the controller runs the list from the ledger, never from this plan).
  - **R7** (the carrying release's, written by D1 beside `docs/release-proofs.md:139`): the schema 21 → 22 and format
    20 → 21 in-place upgrade of a signed build: the role columns arrive `NONE`/null; the three tables arrive empty; an
    export after enabling a binding carries no `ha_publish` byte; the pre-upgrade export re-plans IDENTICAL; 1.8.0
    refuses the new export with `BackupNewerFormat`.
- **The real-HA proof (controller, once, after the merged-tip gate; the #16 proof's harness and redaction:
  `.superpowers/sdd/2026-10-02-issue-16/proof/`, secrets by path only, `<ha-base-url>` in every record).** The owner
  first adds **two fixture helpers** to the existing test fixture, created by hand and seeded explicitly (the TODAY
  trap, §8): **the fixture's status helper** (an `input_select` with options exactly `UP`, `DOWN`, `DEGRADED`) and
  **the fixture's date helper** (a date-only `input_datetime`, seeded `1970-01-01`); their ids live in the private
  ledger, never here. **Authorization (recommended to the owner 2026-10-22, pending the owner's word): fixture only** —
  the two helpers above, plus **checking the fixture's non-admin user's actual service-call permissions** (that it may
  call `input_select.select_option` and `input_datetime.set_datetime`, and what it is refused), on the fixture and
  nowhere else; production commissioning stays a separate, later go. Steps 1–10 use a fictional `MANUAL` "Example
  Heater" **with no #16 binding**, so its date binding names no boundary helper (C9a: the phone's END decides) and no
  step needs a third helper. **Step 11, the HA-driven variant, is SIMULATED unless the owner authorizes a third fixture
  helper** (a date-only season-ended helper, which this authorization does not name): without that word it is proven
  by rows 98–101b's doubles only, and the record says "simulated"; with it, it runs against the fixture. The asset is
  on the emulator, a debug build of the merged tip. The host only **reads** through HA's API, but for
  three named writes: step 1's `input_select.set_options` adding a fourth option and its restore, and step 5's reset
  of the helper (F-23):
  1. validate the status helper (passes); enter the fixture's season boolean as a status target → `WRONG_DOMAIN`,
     nothing sent; the host's `set_options` adds a fourth option → Check helper → `OPTIONS_MISMATCH`; restore;
  2. enable with no condition → `WAITING_FOR_SOURCE`, the host reads the helper unchanged;
  3. record OPERATIONAL → the host reads `UP`; the block shows `PUBLISHED`;
  4. DEGRADED → `DEGRADED`, DOWN → `DOWN`, each read back by the host; a second DOWN with a new reason sends nothing;
  5. the host sets the helper to `UP` → Publish now → `DOWN` restored, no source change;
  6. validate the date helper (passes); enter the status helper as a date target → `WRONG_DOMAIN`;
  7. assign the role to a FORM schedule, complete it with its details → the host reads the service date;
  8. delete the completion → the host reads `1970-01-01`;
  9. complete again, then Stop publishing → the host reads `1970-01-01` **before** the block reads stopped;
  10. Forget with a date binding enabled → the warning with its count; cancel; disconnect from the fixture's network,
      Forget → the binding's obligation listed UNDELIVERABLE; dismiss.
  11. **(simulated unless the owner authorizes the third helper)** link the asset (Follow) to the fixture's season
      boolean, enable date publishing naming the season-ended helper as its boundary, set that helper on the host to a
      date after the service (the one extra host write, only if this step runs) → Publish now → the host reads
      `1970-01-01` on the date helper and the card shows P114-44; the unverified state (P114-45) is proven by rows 99a
      and 101b only (it needs days to pass).
  Also recorded: what HA answers to a service call naming a missing entity (404 or `200 []`), and whether the non-admin
  user's refusal is a 401 or a 403, settling R-N3 in the docs. Afterwards: fixture reset, Disconnect, the record in the
  ledger with placeholders only.
- **The commissioning sequence (owner-gated, after a release carries this; never by an agent without the owner's go;
  the owner's real assets and helpers are in the private ledger — fictional names here):**
  1. **HA first (§8):** the shared in-season boolean, one `service_done` date helper **per appliance** (seeded
     `1970-01-01`; O-18: two date bindings, two helpers), the season-ended helper (seeded the commissioning date), the
     stamp automation; HA's serviced flag keeps its interim writer.
  2. **Re-bind the inbound (C2):** "Example Heater"'s #16 binding is pointed at the new in-season boolean with the
     shipped "Change entity" (`EditSeasonSyncEntity`); its mode stays FOLLOW; the next read applies at most one
     transition.
  2b. **Give "Example Boiler" a season (O-18, F-14).** An asset that is year-round today needs a season before a role
     can cover it (P114-17 inside P114-21). Before step 3, per O-18's ruling: link it (Follow) to the **same** in-season
     boolean (#16 allows it), or give it its own helper, or a CALENDAR season. Linking switches it into MANUAL at
     today's phase through the shipped link (#16 R16-Q-G discloses the IN_SERVICE re-anchor); a CONTINUOUS group
     schedule is not re-anchored.
  3. **Assign the role** on the group schedule "Example pre-season check" (C1, if ruled; else the fallback of §6: a FORM
     "Season Start service" schedule on each asset) with "Services count from" = that day — **before** any completion
     (audit headline 10): a completion dated before the role day never counts.
  4. **Complete "Example pre-season check"** for each appliance on or after that day (QUICK by election, O-2); the
     cards read Ready.
  5. **Enable date publishing** per appliance, each to its own `service_done` helper and naming the shared season-ended
     helper as its boundary (C9a): validate, confirm; the host reads each service date back; each block reads
     `PUBLISHED`.
  6. **HA gate derivation (§8):** HA's serviced flag is derived from `service_done >= season_ended`; its interim voice
     writer and only the serviced-flag action of the 1 August reset are removed; `stove_check` gains the readiness check
     for voice/Assist ON if Q2 (b) is ruled.
  Optionally, an appliance's equipment status to its own `input_select` once HA has a consumer for it.

## 8. The Home Assistant side (not this repository)

The owner's HA configuration is summarised generically here, with fictional helper names; the private inventory
(`ha-inventory.md`) holds the specifics. The two HA-side names the owner's ruling lets this plan use are `stove_check`
and `oil_zone_owner`. ServiceTag ships none of this; D1's guide carries a generic version.

- **What exists, in outline.** The heater has **one** automatic start gate, `stove_check`, which refuses an automatic
  ON while HA's serviced flag is off; a person's start bypasses it today, and the appliance's own panel is outside HA.
  The serviced flag has one manual ON writer and one OFF writer inside a yearly reset automation on 1 August (which
  also resets unrelated helpers); nothing ties it to a service or a season. The second appliance has **no** serviced
  or season check: its one automated writer, `oil_zone_owner`, arms it year round, and person-class writers bypass it.
- **Two facts, two helpers (C2; O-17 as corrected by E).** The operating permission and the service-cycle boundary are
  kept apart; the latch of rev 3 applies to the second only.
  - **`in_season`, the operating permission the phone follows.** A new `input_boolean.example_heater_in_season`
    (shared by both appliances under O-18), written by one HA automation that tracks the house's heat-source
    assignment **edge for edge**: ON at a commit to fuel, OFF at a release to electric. The phone follows it through
    #16 (Follow): each edge records a START or END row that wakes or sleeps the in-service schedules. It is **not**
    re-written at HA start: the helper restores its own state, so a restart never produces an edge.
  - **`season_ended`, the service-cycle boundary (F-11; read by ServiceTag under C9a).** A new date-only
    `input_datetime.example_heater_season_ended`, stamped with today's date **once per heating year** (1 August–31
    July) by one automation: at the in-season boolean's first `from: "on"` `to: "off"` change inside the year's
    **closing window, 1 May–31 July** — never `unavailable`/`unknown` → `off` (HA start, an integration reload), never
    at HA start, never a second time in the same heating year; **the August fallback:** if no such edge came, the
    1 August reset stamps it. At commissioning it is set explicitly to the commissioning date (a trusted first
    boundary, never a default or restored value). ServiceTag reads it (never writes it) as the boundary helper of each
    date binding on an HA-driven `MANUAL` asset (C9a). D1's generic guide says the same.
  - **The June cold snap, worked through.** 15 May: the house releases to electric → `in_season` OFF, the phone
    records END, `season_ended` := 05-15; last autumn's service is now older than the stamp, so HA is not released and
    the phone publishes `1970-01-01`. 10 June: a cold snap commits to fuel → `in_season` ON (the phone records START);
    a start is still refused on last season's service, but the explicit freeze override (below) can act because the
    appliance is in season — rev 3's latch kept `in_season` OFF until August, so neither a service nor the override
    could start it. A service on 12 June is ≥ 05-15 → the phone publishes it and HA releases. 20 June: the snap ends →
    `in_season` OFF, the phone records END, and **no new stamp** (already stamped this heating year), so that service
    still counts. 20 September: the pre-season service is ≥ 05-15 → released for the new season.
  - **Warm spells are harmless.** An early-spring or autumn release and re-commit outside the closing window toggles
    `in_season` and records phone END/START rows that only wake and sleep the in-service schedules; they stamp nothing,
    and the phone's readiness for this asset uses HA's stamp while it is fresh (C9a, row 3c). While the stamp is
    unverified the phone takes the later of the two, so a warm-spell END can withhold the date until the next good
    read (limit 16): a start refused, never one allowed.
  - *(O-17's alternative: stamp at every `on → off` edge in the closing window; a service made for a June cold snap
    is then revoked when the snap ends.)*
- **`service_done`, one per appliance (O-18).** New date-only `input_datetime.example_heater_service_done` (and
  `input_datetime.example_boiler_service_done`), each ServiceTag's from the day its publishing is enabled (no HA writer
  competes); until then the owner sets it by hand after a service (the interim, #114 §5).
- **The derived serviced flag (so `stove_check`'s automatic path is unchanged).** The existing serviced flag becomes a
  derivation: one automation sets it ON exactly when `service_done != 1970-01-01 AND service_done >= season_ended`, OFF
  otherwise, re-evaluated on either helper's change, at HA start and daily. Its manual ON writer is removed, and **only
  the serviced-flag action** is removed from the 1 August reset automation — that automation stays, with its other
  resets (F-24). The gate's rule (#114 §5) is then: `released AND in_season AND no fault/safety inhibit AND the
  existing ownership and occupancy rules`, with #113's `input_select.example_heater_equipment_status` (`DOWN`)
  available as the fault input once published.
- **Voice and Assist (Q2).** *The plan's position, recommended to the owner 2026-10-22, pending the owner's word — (b):*
  an ordinary voice or Assist ON passes the same readiness check as an automatic start: `stove_check`'s person-class
  exemption from the serviced check is **removed for ON** (it stays for OFF, which is always allowed), the reply states
  the refusal's reason, and the **explicit freeze override below is the only waiver**; the appliance's own panel stays
  outside HA. *(a), rev 2's position:* a person's start stays outside the check and keeps a spoken caveat that it is not
  serviced for the season. Whichever is ruled, D1 states it.
- **The second appliance: a gap, stated.** Nothing gates its automatic arm on a service. The place is `oil_zone_owner`,
  the one automated arming writer: it would read that appliance's own released predicate (its own `service_done`
  against the shared `season_ended`) before arming, including the freeze branch; person-class bypasses stay outside
  unless Q2 (b) is extended to them by the owner. This is the owner's HA work and is not scheduled by this plan. On the
  phone the appliance first needs a season (O-18).
- **The freeze override (#114 Rev 3).** An unserviced freeze backup raises one urgent, actionable alert on the house's
  existing actionable-notification pattern with **Start anyway (unserviced)** and **Keep off** (the latter is new).
  Start anyway sets an HA-side, one-time, time-limited override for that freeze event only, logged, shown while it
  lasts, waiving **only** the service check (faults, smoke/CO and ownership rules still apply); expiry asks again;
  stopping uses the appliance's safe sequence. ServiceTag is never told and nothing in it changes.
- **The `input_datetime` TODAY trap.** A new `input_datetime` with no `initial:` reads **today**, not unknown or
  1970, and a YAML `initial:` resets a UI or service write at every restart. So: no `initial:` (keep HA's restore
  behaviour), and write `1970-01-01` (or the commissioning date) explicitly with `input_datetime.set_datetime` right
  after creating the helper — before any gate reads it. A time trigger on an unknown `input_datetime` never fires.
- **Commissioning order.** (1) Create the helpers and seed them explicitly; (2) the stamp automation (on → off only, once per
  heating year, in the closing window); (3) the in-season follower, then the phone's re-bind (§7 step 2); (4) the derivation written but **disabled**, the
  interim writer still active; (5) the role, the completions and date publishing on the phone, verified by reading each
  `service_done` in HA; (6) enable the derivation and remove the interim writer and the reset's serviced-flag action;
  (7) only then rely on the gate. A restored or default date, and Android's delayed delivery, are never evidence of a
  recent service.

## 9. Relationships

- **#16** (plan `docs/superpowers/plans/2026-10-02-issue-16-home-assistant-season-sync.md`): reused — the connection
  and secret store (C4a, C18), the endpoint policy (C8), the client's gate (C19 steps 1–2, extracted by C21), the
  network identity (C32), the runner's schedule rule (C21–C22), the revision CAS (C11, C13), never-travels (row 20),
  the real-HA proof (§7), R16-17's tool-block convention and R16-18's resume rule. Untouched: its applier, guard,
  cadence and GET contract.
- **#105:** the browser and `pickerRows`; `EntityScope` widened by two members.
- **#69, #47:** the format-step precedents (`FIRST_RESOURCE_OWNER_FORMAT = 20`, `FIRST_INSTALLED_COMPONENT_FORMAT =
  19`, `C/backup/BackupCodec.kt:241-247`; the older-archive refusal and compare-without exception, `:462-469`,
  `C/merge/MergePlanner.kt:1062-1067`); #47 E-18 (no `else` → 500).
- **#79:** `Asset.warrantyReminderLeadDays` (`C/model/Asset.kt:38-43`) — the precedent for a field with its own
  command, never in a full-replace form (C10, C12).
- **#92:** `InstallationIdentity` (`A/prefs/InstallationIdentity.kt:28`) — the installation fence (C27).
- **#82, #86, #77:** the condition writers the projector observes; `ReplaceAsset`'s copy through
  `createInTransaction`; `maintainedHere(held)`.
- **#100:** future inbound source; C26 is where it joins. **#116:** UI indicators, deferred. **#104:** the roadmap of
  record; no release assigned. **#62:** rows 91–92 are tiers 2–3; no UI driving. **#90:** reporting only.

## 10. Audit corrections

Read on `62e39670`; each landed where noted.

1. **c1** `MaintenanceScheduleDto` is `C/backup/BackupFormat.kt:341-368` (not 335-357); `toDto` `:1258`, `toDomain`
   `:1296` (not 1273, 1315); `providers` is its last field (`:367`), so "written last" means after `providers` (C18).
2. **c2** `MIGRATION_20_21` is `A/data/room/Migrations.kt:960-985` (KDoc `:948-959`), not 950-986 (C4).
3. **c3** The `YEAR_ROUND` hook also belongs in `UpdateAsset`'s legacy pair (`C/usecase/UpdateAsset.kt:70`, which calls
   `strandedBy` and can clear a CALENDAR window); `SetMaintenanceBreak.kt:46` calls `strandedBy` but cannot change the
   mode and needs no hook (C11, row 18).
4. **c4** The re-parent check cannot live in `validateAsset` (`C/usecase/AssetCommands.kt:84-121`, pure, no
   schedules); it sits in its callers with an id, and it must also catch the **old parent** losing its last child
   (C11, row 19).
5. **c5** Two more "outbound inventory" texts must change: `A/fetch/UrlConnectionTransport.kt:22` ("the other is #16's
   `HomeAssistantStateClient`") and `docs/api/v1.md:39-42` ("the app's two `HttpURLConnection` classes"); the audit
   listed only `v1.md:38` and the manifest's `:12` (C38).
6. **c6** A service call naming a missing entity is not reliably a 404 (HA may answer `200 []`); the readback finds it.
   404 on `/api/services/…` maps to `HTTP_ERROR`, and 403 is split from 401 as `WRITE_FORBIDDEN` (C23, R-N3); the real-
   HA proof records the actual answer.
7. **c7** "Expedited where quota allows" (audit §4.2) is out: an expedited `CoroutineWorker` before API 31 needs
   `ForegroundInfo`, i.e. a foreground notification, which the fence forbids (G16, C32).
8. **c8** `SaveSchedule`'s role site is the `candidate` construction (`C/usecase/SaveSchedule.kt:153-180`, built
   wholly from the command), not the strands check at `:218-222`; without it every save wipes the role (C12, row 21).
9. **c9** `ReplaceAsset` copies schedules through `saveSchedule.createInTransaction` (`C/usecase/ReplaceAsset.kt:362`),
   so a successor's schedules carry `NONE` by construction — #114 point 6 needs no new code (C12).
10. **c10** The API decoder has `ignoreUnknownKeys = false` (`A/api/ApiJson.kt:104`): a role key in a request body is
    a 400, so the role can never ride the full-replace bodies by accident; it appears in responses only (G18, row 81).
11. **c11** The connected suite is 78 classes in **76** files / 430 tests (two files hold two classes); the audit's 78/430
    stands.
12. **c12** The #16 start-up launch is `A/ServiceTagApp.kt:86-91` (the call at `:89`) and the dispatch `:55`; the
    resume hook is `A/ui/nav/ServiceTagRoot.kt:119-120` (C31, C32).
13. **c13 (rev 2, F-7)** `HomeAssistantStateClient`'s gate is the endpoint classify and the INTERNET probe at the head of
    `exchange` (`:136-140`) plus `checkedExchange` (`:145-178`); `exchange`'s whole-call `withTimeoutOrNull`
    (`:141-142`) is **not** part of the gate and stays in each client.
14. **c14** Merge reasons are API names only (`A/api/ApiDtos.kt:145`); the phone draws none, so O-9's two new reasons
    need no phone string (C19).
15. **c15** `TransferRecordRepository.observeHeldIds()` exists (`C/ports/Repositories.kt:552`): a transfer OUT reaches the
    projector through a flow, not only at resume (C31).
16. **c16** Events and activations have no `observeAll` (`C/ports/Repositories.kt:113-124`, `:430-435`): the projector
    observes them per bound asset (C31).
17. **c17** The ten-pack string count is 79 and one plural (rev 2), not ~60 (§5), because of the split not-ready reasons, the four strand
        causes, the HA-screen lines and the date block's tombstone lines (rev 2: 79 and one plural, F-16, F-8, F-22).
18. **c18** Headline 6's "refuse or warn on delete and Forget" is replaced for delete and replace restore by a soft
    asset link (R-N2): no shipped use case is hooked, and the obligation survives by construction (C4, C34).

**Rev 3's citations (read on `62e39670` for the external audit's findings):** the apply's replan without an import
context is `C/usecase/ApplyBackupMergePlan.kt:181` (with the stale check at `:183-184`); the build's entry is
`C/usecase/BuildBackupMergePlan.kt:97-114` (the replan's twin at `:114`); `mergePlanOf` is
`C/merge/MergePlanner.kt:285`; `MergePlan`'s constructor and `fingerprint` are `C/merge/MergePlan.kt:567-595`, and
`fingerprintOf` `:639-650` hashes the archive identity and the decisions only; `ImportTransferPack`'s preview calls
`build.run` and its import calls `apply.runReturning(ready.plan, …)` (`C/usecase/ImportTransferPack.kt`), with a
`clock` and no `Today`; #16's applier dates a row the day it is applied (`C/seasonsync/RecordSeasonSyncResult.kt:49-51`,
the `day` at `:116-127`); `AssetEvent` has no installation or origin field (`C/model/Journal.kt:64-83`; `source` is
`MANUAL`/`IMPORT`/… and travels verbatim), hence rev 4's `imported` marker (B; rev 3 used a schedule-level fence);
`HaConnection` has no revision (`C/seasonsync/SeasonSyncModel.kt:23-32`), hence rev 4's `config_generation` counter
column (F; rev 3 borrowed `updatedAt`); the transport KDoc D1 edits is
`A/fetch/UrlConnectionTransport.kt:20-22`.

**Rev 4's citations (read on the worktree at `9e0ef9a3`, code unchanged since `62e39670`):** the return path's
`ReturnScope` is `C/usecase/ApplyBackupMergePlan.kt:268-380` — `of` at `:280`, the removed schedules `:288-293` (its
own and its groups'), events `:294`, the reduced snapshot `:319-321` — and `runReturning` builds it at `:172-180` and
replans at `:181`; `BuildBackupMergePlan.run` returns `mergePlanOf(backup, ReturnScope.of(snapshot,
returning).snapshot, returning)` at `:114`; the planner inserts schedules at `C/merge/MergePlanner.kt:791` and events at
`:965`, and `sameEvent` is `:647-650`; `ImportTransferPack.preview` plans with the returning set at
`C/usecase/ImportTransferPack.kt:199-208` and `import` applies at `:251`; `AssetEventDto` is
`C/backup/BackupFormat.kt:275-297` with `toDto` `:1094` and `toDomain` `:1115`; `buildEvent` constructs the event at
`C/usecase/EventCommands.kt:271` and keeps `createdAt` at `:283`; the only other `AssetEvent(` construction in main code
is the decoder's (`AssetEvent.toEntity` is `A/data/room/JournalMappers.kt:191`); `AssetEventEntity` is
`A/data/room/entities/JournalEntities.kt:198-215` (`details_pending` with `defaultValue = "0"` at `:214`);
`HaConnectionEntity` has eight columns and no counter (`A/data/room/entities/HaConnectionEntity.kt`), mapped at
`A/data/room/SeasonSyncRepositories.kt:71`, `:82`, and `HaConnection` is constructed at
`C/seasonsync/SeasonSyncCommands.kt:272` and `A/ui/homeassistant/HomeAssistantViewModel.kt:353`; `SyncCadence.hours` is
12, 24, 168, 720 (`C/seasonsync/SeasonSyncModel.kt:40-43`); `season_sync_binding` is keyed by `asset_id`
(`A/data/room/entities/SeasonSyncBindingEntity.kt`); `SeasonContext`'s `YearlyWindow` resolves each bound per year
(`C/schedule/SeasonContext.kt:211-233`), so winter (wrapping) and summer windows share one arithmetic; no backup
archive carries an installation id (`git grep -n -i installationId -- core/src/main` → none), which O-20's alternative
would need.

**Rev 2's own citation fixes (the review's "citations verified wrong"):** `T/VersionAgreementTest.kt`'s format
assertion is `:88` (A3's row; `:87` is the schema); `candidate` is `C/usecase/SaveSchedule.kt:153-180` (C12, c8);
`MaintenanceScheduleEntity` spans `A/data/room/entities/MaintenanceEntities.kt:95-155` (C4); the `OUTBOUND_TARGET`
arms are `A/ui/asset/SeasonSyncStrings.kt:161-166` and `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296` (not
`LinkSeasonSyncSheet.kt`) and the `SaveHaConnectionRefusal` arm is `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462`
(C26, C28, §4 B3); and c13 above.
