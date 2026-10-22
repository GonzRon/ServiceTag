# #113 + #114 — Home Assistant publishing: equipment status and the Season Start service: master plan (rev 2.1, 2026-10-22)

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
> Q2, both positions in §8 (§6, §8). **F-13** the C1-refused fallback (§6). **F-14** the boiler needs a season first,
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
> ready-for-the-season are two helpers in two directions) and the stove's inbound binding following "the HA serviced
> flag" — and carries **C1 PROPOSED** (the `SEASON_START` role on a group schedule) and O-1…O-14 plus R-N1…R-N6 to the
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

**Goal:** a fictional "Example Heater" (a pellet stove, top-level, `MANUAL` season, its inbound #16 binding following
the HA-owned `input_boolean.example_heater_in_season`) gets two **outbound**, owner-enabled publications to helpers
its Home Assistant administrator created. **#113:** its recorded operational condition, the current row of
`ConditionHistory`, is mirrored to `input_select.example_heater_equipment_status` as exactly `UP`, `DEGRADED` or
`DOWN`, through the one allowlisted service action `input_select/select_option`, and nothing is sent while no
condition is recorded. **#114:** one of its schedules, "Annual stove service", carries the passive role
`SEASON_START`, set on a day that becomes its commissioning boundary; the asset reads **Ready for the season** exactly
when that schedule has a qualifying completion of this asset dated on or after the later of that boundary and the day
its season last ended; the qualifying date, or `1970-01-01` whenever it is not ready, is mirrored to the date-only
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
   `(connection, entity)` pair (`UNIQUE(connection_id, entity_id)` plus a command refusal, C25); no binding of either
   kind ever names an entity an inbound #16 binding reads, and no inbound binding ever names an entity an outbound one
   writes (G7).
2. **Allowlisted service actions only.** The app's only write to Home Assistant is
   `POST <base>/api/services/input_select/select_option` with `{"entity_id", "option"}` or
   `POST <base>/api/services/input_datetime/set_datetime` with `{"entity_id", "date"}`, chosen by a two-member enum
   (`HaServiceAction`, C22), never by a string parameter. Never `POST /api/states`, never `climate`, `switch`,
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
   and packed under C18–C20; legitimate service history travels as it does today.
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
    `NotReadyReason`, `SeasonStartRefusal`, `SeasonStartStrandCause`, `HaServiceAction`, `ScheduleRole`, and each new
    API arm (#47 E-18: a new exception never reaches `ApiJson`'s `else` → 500).
12. **Every user-visible string is a resource in all ten packs** (`values` + `values-{b+zh+Hans,de,es,fr,hi,it,ja,pt,ru}`),
    ratified in §5 before its brief runs, read through getters, held by `LocalizationCoverageTest` and
    `UiLiteralGuardTest`; developer log lines are §5's G-list with `// l10n-ok`.
13. **The gitlink and the tombstones stay.** `libs/nfc-tag-core` stays `7e0377a`; `git diff <base> -- . ':!app/schemas'
    | grep -cE '^[-+].*(external_link|externalLinks)'` → 0.
14. **One schema step, one format step.** Room 21 → 22 (S1) and backup format 20 → 21 (A3), forward-only; `21.json`
    and every format literal below 21 are never edited; no `versionName`/`versionCode` change (MINOR by
    `docs/versioning.md:24-25`, unreleased on master, emulator only, the owner picks the vehicle).
15. **HTTP stays outside domain transactions.** No use case awaits the network inside `uow.write`; the sender reads one
    `uow.read` snapshot, performs at most one POST and one GET, then records with a revision compare-and-set (C17,
    C30). `RecordCondition` keeps "one immutable row, and nothing else" (`C/usecase/RecordCondition.kt:14-19`); no
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
19. **No personal data.** Fixtures and docs use the fictional names of the Goal, `ha.example`,
    `http://192.168.0.10:8123`, `ExampleHomeWifi`; the owner's entity ids, address, Wi-Fi, host name, token and HA
    automation names never enter the repository (§8 names two HA-side places the owner's ruling allows, and only in
    this plan).
20. **AppGraph and FakeGraph** are edited only by S1, A2, A3, B3, B4a, B4b1, B4b2 and B5, never by two concurrent
    briefs; B5 is the last of all (§4).
21. **Standing rules.** Commits: one casual lowercase subject line, no body, no trailer, no tool name; implementers
    never write ledgers; JVM first; every counted RED a real mutation (`--no-build-cache --rerun-tasks`), quoted,
    reverted; no rerun-until-green; time boxes 1 h target, 2 h hard, fix rounds 45 min.

## 1. Scope

**R-0, the scope sentence (proposed for ratification):** *#113 + #114 = two optional, per-asset, owner-enabled
outbound publications from ServiceTag to administrator-created Home Assistant helpers on the existing #16 connection —
the current operational condition to an `input_select` with exactly `UP`/`DOWN`/`DEGRADED`, and the Season Start
service date (or `1970-01-01`) to a date-only `input_datetime` — through two allowlisted service actions, one
serialized latest-desired-state sender with generation fencing and readback, post-commit projection and reconcile
scans, installation-fenced activation and no inheritance; plus a passive `SEASON_START` role on one schedule per
eligible asset (or, under C1, per group), a commissioning boundary, a pure readiness rule, its phone lines and
read-only API/MCP projections. Nothing more.*

**In scope — #113's acceptance lines (AC113-n) and #114's (AC114-n), each on its contracts and rows:**

| AC | the issue's words (abridged) | contracts · rows |
|---|---|---|
| AC113-1 | an administrator-created helper with exactly the three options is selected or entered, validated, enabled for one asset; later changes publish automatically | C14, C16, C22, C25, C31 · rows 30–34, 52, 60, 63 |
| AC113-2 | exact mapping; initial publication needs a known source; no-condition and not-yet-published are honest; a helper's existing value is never our push | C15, C17 · rows 25–29, 37, 38 |
| AC113-3 | UI, offers, incident write, API/MCP and merge use one post-commit projection; rolled-back, duplicate, backdated non-current writes publish nothing | C31 · rows 61–66 |
| AC113-4 | UP → DEGRADED → DOWN reaches the helper; HA observes the changes; ServiceTag sets only that field | C30, C22 · rows 58, 85 (real HA) |
| AC113-5 | offline transitions coalesce to the latest, survive process death, recover under the network policy; nothing is relabelled DOWN | C17, C30, C32 · rows 36, 57, 59, 67 |
| AC113-6 | missing or wrong-domain helper, changed options, refused token, TLS/redirect/network failure, ambiguous completion are bounded; edits and disable mid-request cannot falsely succeed | C17, C23, C30 · rows 39–44, 53–56 |
| AC113-7 | initial publication, Publish now and reconciliation repair a stale or reset helper | C17, C31 · rows 38, 59, 85 |
| AC113-8 | no writer inherited by backup, restore or transfer; same-install merges and lifecycle covered; two assets cannot claim one helper | C6, C25, C27 · rows 12, 46–48, 64, 69 |
| AC113-9 | #16's season semantics and network/secret guarantees and #105's picker stay correct | C21, G3 · rows 50, 51, 86 |
| AC113-10 | docs: helper, mapping, initial/manual/restored values, one writer, examples, current-state semantics, Android limits; "reads, and only reads" restated | C38 · D1 |
| AC114-1 | a top-level or parent asset with seasons can mark one schedule `SEASON_START`, which sets its boundary; leaf child, `YEAR_ROUND`, group (as amended by C1) and a second role are refused with a sentence | C7, C10, C11 · rows 13–20 |
| AC114-2 | ready only with a boundary and a qualifying completion on or after it; the not-ready list; a new END; the same-day case | C8, C9 · rows 1–11 |
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

## 2. Contracts

### Common (C1–C3)

- **C1, the words.** Core package `C/hapublish/` (the outbound domain: kinds, binding, delivery, tombstone, error
  kinds, projections, mapper, validators, the state machine, commands, ports) and `C/season/SeasonReadiness.kt` (the
  rule); app package `A/hapublish/` (client, runner, worker, work, dispatch, projector, Room adapters); UI in
  `A/ui/asset/` (the card line, the two publishing blocks, the setup sheets) and `A/ui/maintenance/` (the role).
  Tables `ha_publish_binding`, `ha_publish_delivery`, `ha_publish_decommission`; columns `maintenance_schedule.role`,
  `maintenance_schedule.role_since_on`. Domain: `PublishKind { EQUIPMENT_STATUS, SEASON_SERVICE_DATE }`,
  `PublishBinding`, `PublishDelivery`, `DecommissionTombstone`, `PublishErrorKind`, `PublishState`, `HaServiceAction`,
  `RemoteEntity`, `ScheduleRole { NONE, SEASON_START }`, `Readiness`, `ReadinessBoundary`, `ReadinessBoundaryKind`,
  `NotReadyReason`, `OutboundTargets`, `SeasonStartRefusal`,
  `SeasonStartStrandCause`. Wire values: the options `UP`, `DEGRADED`, `DOWN`; the invalid date
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
  1. `ALTER TABLE maintenance_schedule ADD COLUMN role TEXT NOT NULL DEFAULT 'NONE'` and
     `ALTER TABLE maintenance_schedule ADD COLUMN role_since_on TEXT` (entity
     `A/data/room/entities/MaintenanceEntities.kt:95-155` gains the two columns with the same defaults, so a fresh v22
     and a migrated v22 agree; no index — coverage is a use-case rule, C10).
  2. **`ha_publish_binding`** — `id TEXT PK`; `asset_id TEXT NOT NULL` (**a soft link, no foreign key**: deleting or
     replace-wiping an asset leaves the row for the projector to decommission, C34 — the `eventId` / `healthPrimarySubjectId`
     soft-link precedent); `connection_id TEXT NOT NULL` → `ha_connection(id)` **ON DELETE CASCADE** (Forget writes
     tombstones first, C28); `kind TEXT NOT NULL`; `entity_id TEXT NOT NULL`; `enabled INTEGER NOT NULL`;
     `revision INTEGER NOT NULL`; `activated_installation_id TEXT` (null while never enabled); `validated_at INTEGER`;
     `validated_revision INTEGER`; **`claim_entity_id TEXT`** (= `entity_id` while enabled, null while disabled: the
     claim); `created_at INTEGER NOT NULL`; `updated_at INTEGER NOT NULL`. Indices: **`UNIQUE(asset_id, kind)`**,
     **`UNIQUE(connection_id, claim_entity_id)`** — SQLite's UNIQUE treats nulls as distinct, so this is the partial
     "unique among **enabled** bindings" index Room can declare (F-9) — and `INDEX(connection_id)`.
  3. **`ha_publish_delivery`** — `binding_id TEXT PK` → `ha_publish_binding(id)` ON DELETE CASCADE;
     `desired_value TEXT` (null = nothing to send); `desired_revision INTEGER NOT NULL`; `desired_at INTEGER`;
     `last_published_value TEXT`; `last_published_at INTEGER`; `published_desired_revision INTEGER`;
     `published_binding_revision INTEGER`; `last_verified_value TEXT`; `last_verified_at INTEGER`;
     `verified_binding_revision INTEGER`; `verified_desired_revision INTEGER` (F-1: verification is of a
     `(binding revision, desired revision)` pair); `remote_value TEXT` (the last value read, bounded);
     `remote_read_at INTEGER`; `error_kind TEXT`; `error_detail TEXT`; `error_at INTEGER`;
     `outcome_unknown_since INTEGER`; `attempts_at_revision INTEGER NOT NULL DEFAULT 0`; `last_attempt_at INTEGER`.
  4. **`ha_publish_decommission`** — `id TEXT PK`; `connection_id TEXT NOT NULL` (**no foreign key**: it outlives a
     Forget, C34); `entity_id TEXT NOT NULL`; `asset_id TEXT` (display only, soft); `installation_id TEXT NOT NULL`;
     `state TEXT NOT NULL` (`PENDING` | `UNDELIVERABLE`); `requested_at INTEGER NOT NULL`; `error_kind TEXT`;
     `error_at INTEGER`; `revision INTEGER NOT NULL`. Index `UNIQUE(connection_id, entity_id)` (one obligation per
     helper; a second is merged into the first, C34).
  No other table, column, index or row moves; `21.json` is unchanged; `AppGraph.SCHEMA_VERSION` 21 → 22 and the
  migration list gains the step; `MigrationTestSupport` its twin.
- **C5, the DAOs.** One DAO per table with: `observeAll()` on bindings (the projector's input, C31); a binding update
  that is a **compare-and-set on `revision`** (writes only when the stored row's revision is `next.revision - 1`,
  answers whether it wrote — `C/seasonsync/SeasonSyncPorts.kt:20-25`'s contract); a delivery update that is a
  compare-and-set on **both** the binding's revision and `desired_revision` (C17's record step); tombstone insert-or-
  merge by `(connection_id, entity_id)`; deletes by id. Every binding write sets `claim_entity_id`
  from `enabled` in the same statement. No DAO query reads a token column (there is none).
- **C6, never travels.** No `BackupData`, DTO, `MergeTable`, `TransferTableClass`, manifest-count map or codec names
  any of the three tables; `T/backup/SeasonSyncNeverTravelsTest.kt` gains the three tables and the key words
  `ha_publish`, `desired_value`, `activated_installation_id` and an example entity id (row 12). The two role columns
  **do** travel (C18).

### A1 — the role and the readiness rule (C7–C9; core JVM)

- **C7, `ScheduleRole`.** `enum class ScheduleRole { NONE, SEASON_START }` and, on `MaintenanceSchedule`
  (`C/model/Maintenance.kt:100-133`), two fields appended after `providers` with defaults `role = ScheduleRole.NONE`,
  `roleSinceOn: String? = null` (ISO date). **Invariant R1:** `roleSinceOn != null` ⇔ `role == SEASON_START`
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
    MANUAL's "out of season from its own day".
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
data class ReadinessBoundary(val on: LocalDate, val kind: ReadinessBoundaryKind)  // core.schedule has BoundaryKind
enum class ReadinessBoundaryKind { SEASON_END, COMMISSIONING }
enum class NotReadyReason {
    NO_ROLE, ROLE_NOT_ALLOWED, TWO_ROLES, SCHEDULE_ARCHIVED,
        NOT_DONE_SINCE_SEASON_END, NOT_DONE_SINCE_COMMISSIONING, DETAILS_PENDING, FUTURE_DATED,
}
fun seasonReadiness(
    asset: Asset, assets: Collection<Asset>, schedules: Collection<MaintenanceSchedule>,
    groups: Collection<MaintenanceGroup>, events: Collection<AssetEvent>,   // this asset's events
    activations: List<SeasonActivation>, today: LocalDate,
): Readiness
```

  **Coverage.** The role schedules covering `asset` are the `SEASON_START` schedules that are not ARCHIVED (PAUSED
  counts as live) and either target it (`AssetTarget(asset.id)`) or target a non-archived group of which it is an
  **open** member (`removedAt == null`, `C/usecase/GroupCommands.kt:188`). Then, in this order (the first that holds
  answers): none covering and an archived role schedule targets it or its group, **or a role schedule targets an
  archived group of which it is an open member** (F-28) → `SCHEDULE_ARCHIVED`; none → `NO_ROLE`;
  two or more → `TWO_ROLES`; the asset is `YEAR_ROUND`, or has a parent and no child row (`AssetTree.children`,
  `C/model/AssetTree.kt:44`; O-5: any child row counts) → `ROLE_NOT_ALLOWED`. Otherwise with the one role schedule `s`:
  - **boundary** = the later of `s.roleSinceOn` (kind COMMISSIONING) and
    `SeasonContext.of(asset.seasonInputs(activations)).lastSeasonEndOnOrBefore(today)` (kind SEASON_END; on a tie
    SEASON_END); `roleSinceOn` is never null here (R1; a row that breaks R1 reads `NO_ROLE`, defensively);
  - **qualifying** = an event with `scheduleId == s.id`, `assetId == asset.id`, `!detailsPending`, and
        `boundary.on <= LocalDate.parse(occurredOn) <= today` (inclusive: a completion on the END day counts — #114 §2;
    a completion dated after today never qualifies until its day, F-8, O-16);
  - any qualifying → `Ready(max qualifying occurredOn, s.id, boundary)` (O-3: the latest);
    - else any non-pending event of `s` on this asset dated after today → `FUTURE_DATED`;
  - else any event of `s` on this asset on or after the boundary with `detailsPending` → `DETAILS_PENDING`;
  - else `NOT_DONE_SINCE_SEASON_END` or `NOT_DONE_SINCE_COMMISSIONING` by the boundary's kind.
  **Never read:** condition, health, `SeasonPhase` today, reminders, closures (a closed round is not a completion),
  any other schedule's events, a parent's or child's events, `occurrenceOn`, `createdAt`. **Pure:** no clock; `today`
  is a parameter; a malformed `occurredOn` is skipped, never thrown. Under C1 the same function runs per member,
  reading that member's own events of the group schedule.

### A2 — the role command and the refusals (C10–C13; core JVM, one app mapper)

- **C10, `SetScheduleRole` (`C/usecase/SetScheduleRole.kt`, new).**
  `suspend fun run(id: ScheduleId, role: ScheduleRole, sinceOn: String? = null): MaintenanceSchedule`, one
  `uow.write`. `NONE` clears both fields (no refusal but `NoSuchSchedule`; allowed on an archived schedule; clearing a
  `NONE` is a no-op that writes nothing). `SEASON_START` — `sinceOn` defaults to today, must parse (`BadDate`, 422 shape
  of `ConditionProblem`'s) and be ≤ today (a bad date is P114-28); re-setting a role schedule with a new date moves only `roleSinceOn`.
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
  (`C/usecase/SaveSchedule.kt:153-180`): it gains `role = existing?.role ?: NONE` and
  `roleSinceOn = existing?.roleSinceOn` (`buildEvent`'s `occurrenceOn` precedent, `C/usecase/EventCommands.kt:289-291`).
  `ScheduleCommand`, `ScheduleForms` (`A/api/ScheduleForms.kt`) and the MCP's `update_schedule` body never carry a role
  (K11). `createInTransaction` (`:90-91`), which `ReplaceAsset` uses to copy schedules (`C/usecase/ReplaceAsset.kt:362`),
  therefore creates `NONE`: a successor never inherits a predecessor's role or boundary (#114 point 6).
- **C13, the mapper.** `A/data/room/MaintenanceMappers.kt` (or wherever `MaintenanceScheduleEntity` maps — the brief
  confirms by `git grep -n 'MaintenanceScheduleEntity('`) maps `role` by enum name and `role_since_on` verbatim, and
  throws on a stored row that breaks R1 only in debug assertions — a read model never crashes on a merged row (it reads
  `NO_ROLE`).

### B1 — the outbound core (C14–C17; core JVM)

- **C14, the model (`C/hapublish/PublishModel.kt`).**
  - `PublishBinding(id, assetId, connectionId, kind: PublishKind, entityId, enabled, revision: Long,
    activatedInstallationId: String?, validatedAt: Long?, validatedRevision: Long?, createdAt, updatedAt)` — the
    columns of C4.2 under Kotlin names. `revision` is the **generation**: +1 on enable, disable, target change,
    re-validation and connection re-authorisation (C28).
  - `PublishDelivery(bindingId, desiredValue: String?, desiredRevision: Long, desiredAt: Long?, lastPublishedValue,
    lastPublishedAt, publishedDesiredRevision, lastVerifiedValue, lastVerifiedAt, remoteValue, remoteReadAt,
    errorKind: PublishErrorKind?, errorDetail, errorAt, outcomeUnknownSince, attemptsAtRevision, lastAttemptAt)`.
    **Three facts kept apart (#113):**     `lastPublishedAt` moves only when **our** POST at the current `(revision, desiredRevision)` was
    accepted **and** its readback matched (it records `publishedBindingRevision`, `publishedDesiredRevision`);
    `lastVerifiedAt` moves whenever a readback equals the desired value (with or without our POST) and records
    `verifiedBindingRevision`, `verifiedDesiredRevision`; `remoteValue` is whatever the last read saw. **F-1:** the
    delivery is current only when `lastVerifiedValue == desiredValue` **and** the verified pair equals the binding's
    current `(revision, desiredRevision)`. **F-2:** the connection's re-authorisation bump (C28) moves `revision` and,
    when `validatedRevision` equalled the old revision, `validatedRevision` with it — a fence against in-flight
    replies, never a demand to re-validate; a pending `1970-01-01` keeps going.
  - `DecommissionTombstone(id, connectionId, entityId, assetId?, installationId, state: PENDING|UNDELIVERABLE,
    requestedAt, errorKind?, errorAt?, revision)`.
  - `enum class PublishErrorKind` — the reused transport kinds `ENDPOINT_REFUSED, DENIED, UNREACHABLE, TIMED_OUT,
    AUTH_REFUSED, REDIRECTED, HTTP_ERROR, MALFORMED, NEEDS_TOKEN, NOT_ON_LOCAL_NETWORK, NAME_NOT_LOCAL, TLS_FAILED,
    ENTITY_NOT_FOUND` and the new `WRITE_FORBIDDEN, WRONG_DOMAIN, OPTIONS_MISMATCH, NOT_DATE_ONLY, SERVICE_REFUSED,
    OUTCOME_UNKNOWN, REMOTE_DIFFERS, COPIED_INSTALLATION`. A **separate** enum: #16's `SyncErrorKind`
    (`C/seasonsync/SeasonSyncModel.kt:108-126`) and its ratified `when`s are untouched; a pure
    `publishKindOf(SyncErrorKind)` maps the shared transport members (exhaustive; the season-only members
    `UNSUPPORTED_STATE`, `NOT_MAINTAINED_HERE`, `NOT_MANUAL`, `DATE_BEFORE_HISTORY` map to `MALFORMED` /
    unreachable arms the brief documents).
  - **Retry class** (pure, exhaustive): `TRANSIENT` = `UNREACHABLE, TIMED_OUT, HTTP_ERROR` (5xx only; a 4xx detail is
    permanent), `OUTCOME_UNKNOWN`; `WAIT` = `NOT_ON_LOCAL_NETWORK, DENIED` (no retry; the next start, resume or Publish
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
    hasTime == false` else `NOT_DATE_ONLY`.
  - `remoteMatches(kind, remote, desired)`: exact string equality of `state` with the desired option, or with the
    desired `YYYY-MM-DD` (HA's date-only state).
- **C17, the delivery state machine (pure, `C/hapublish/DeliverySteps.kt`).** Inputs: one snapshot
  `Snap(binding, delivery, connectionPresent, hasToken, installationId)`; outputs a plan and, after I/O, a record.
  - **Gate:** not enabled, `activatedInstallationId != installationId` (`COPIED_INSTALLATION`), no token
    (`NEEDS_TOKEN`), `validatedRevision != revision` (`NEEDS_VALIDATION`), `desiredValue == null` → `Skip(state)`.
    - **Plan:** `ReadFirst` when `outcomeUnknownSince != null` **or** the verified pair is not the current
    `(revision, desiredRevision)` (initial publication, re-enable, target change and reconcile compare first, #113);
    `Post(value)` when the read differs; **`Done` ⇔ `lastVerifiedValue == desiredValue` and the verified pair equals
    the current pair** (F-1), and the caller is not a reconcile. `drain()` selects every enabled binding whose verified
    pair differs from its current pair, **except (R-2)** one whose recorded `errorKind` at its current
    `(rev, dRev)` has class NEEDS_OWNER (`AUTH_REFUSED`, `WRITE_FORBIDDEN`, `SERVICE_REFUSED`, `REMOTE_DIFFERS`, the
    validation kinds); the midnight tick and the worker, which are not eligibility changes, also skip one whose error
    at the current pair is WAIT. Reconcile mode (start, resume, Publish now) retries both — the plan's choice, so an
    owner who fixed HA's side sees it repaired at the next open — and so does any move of the pair (a command, a new
    desired value, a re-authorisation).
  - **Steps:** `ReadFirst` → read; equal → record **verified** (`lastVerifiedAt`, `remoteValue`; not published) and
    stop; different → `Post`; `Post` → 200 → read back → equal → record **published + verified**; read back differs →
    `REMOTE_DIFFERS` (NEEDS_OWNER for this revision); POST non-200 → that kind; POST outcome unknown → set
    `outcomeUnknownSince`, kind `OUTCOME_UNKNOWN` (TRANSIENT; the next attempt reads first, never re-POSTs blind).
    A readback whose validator fails → that validation kind, and `validatedRevision` cleared (re-validation needed).
  - **Record:** a compare-and-set on `(binding.revision, delivery.desiredRevision)` as snapshotted: a moved generation
    or a newer desired value **drops** the result entirely (no field moves, the sender loops); `attemptsAtRevision`
    counts per desired revision and resets when it moves. **Coalescing** is structural: only the latest desired value
    exists to send.
  - **Never:** a transport failure, a missing helper or an unavailable HA recorded as a source change or mapped to
    `DOWN`; `lastPublishedAt` moved by a read alone.

### A3 — format 21, the merge and the Transfer Pack (C18–C20; core JVM)

- **C18, format 21.** `MaintenanceScheduleDto` (`C/backup/BackupFormat.kt:341-368`) gains `role: String = "NONE"` and
  `roleSinceOn: String? = null`, **after `providers`**, written as explicit values (the format-17 reference-role
  precedent, `C/backup/BackupCodec.kt:139-145`); `toDto` (`:1258`) and `toDomain` (`:1296`) carry them; `toDomain`
  refuses an unknown role name and a row breaking R1 (`BackupCorrupt`). `FORMAT_VERSION` 20 → **21**
  (`BackupCodec.kt:191`); `internal const val FIRST_SCHEDULE_ROLE_FORMAT = 21` beside `FIRST_RESOURCE_OWNER_FORMAT`
  (`:247`); a format ≤20 archive whose schedule carries a non-`NONE` role is refused as hand-built (the `:462-469`
  shape). Forward-only: 1.8.0 refuses a format-21 archive with `BackupNewerFormat` and a format-21 pack with
  `NewerPack`. `VersionAgreementTest` (`T/VersionAgreementTest.kt:86-88`, `:158-159`) moves to 22/21; the
  `StageABundleConformanceTest` pin and `B/`'s format pin are checked and moved only as twins.
- **C19, the merge.** In `C/merge/MergePlanner.kt`'s schedules section (`:752-797`):
  - **Compare:** `rolesCompared = formatVersion >= FIRST_SCHEDULE_ROLE_FORMAT` (`:1062`'s shape); against an older
    archive a local row with a role compares without `role`, `roleSinceOn` and the `updatedAt` the role write moved
    (`sameReference`'s three arms, `:1063-1067`), so re-merging this phone's own pre-role export stays `IDENTICAL`.
  - **One coverage per asset (O-9, plan position: refuse):** an INSERT of a `SEASON_START` schedule whose coverage
    (C9's rule over the destination plus this plan's accepted groups and members) meets an asset a destination role
    covers → `CONFLICT`, new reason **`SEASON_START_HELD_BY_A_LOCAL_ROW`** (detail: the local schedule id); one another
    accepted archive row already claimed → `CONFLICT`, **`SEASON_START_DUPLICATED_IN_ARCHIVE`** (the health-subject
    pair, `:1168-1201`, as conflicts). `docs/api/v1.md`'s merge tables name both (A3 edits only those rows).
  - Applying a merge writes the role as part of the inserted row through the shipped `schedules.upsert`; no apply
    code changes (`C/usecase/ApplyBackupMergePlan.kt` untouched).
- **C20, the Transfer Pack (O-8, plan position).** The role travels in the pack as data (`C/transfer/TransferGraph.kt:202`
  unchanged). On import, `ImportTransferPack` plans with an **import day**: every incoming `SEASON_START` row the
  plan INSERTs gets `roleSinceOn = importDay` (the recipient's own commissioning boundary — a predecessor's service
  never makes the recipient ready, scenario 8); a row matched to a local id (a pack coming back) compares without
  `roleSinceOn` and keeps the local value. The planner takes `importDay: LocalDate?` (null for a backup merge, which
  keeps the archive's value — scenario 7). No pack key, class or manifest map moves.

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
enum class HaServiceAction(val domain: String, val service: String, val valueKey: String) {
    SELECT_OPTION("input_select", "select_option", "option"),
    SET_DATE("input_datetime", "set_datetime", "date"),
}
sealed interface CallOutcome {
    data object Accepted : CallOutcome                         // a 200: accepted, never "verified"
    data class Refused(val kind: PublishErrorKind, val detail: String?) : CallOutcome   // nothing changed, we know
    data class Unknown(val detail: String?) : CallOutcome      // the body may have reached HA
}
```

  The action is chosen by `PublishKind` in one exhaustive `when` (`EQUIPMENT_STATUS → SELECT_OPTION`,
  `SEASON_SERVICE_DATE → SET_DATE`); the path is built from the enum only. **Never** a log line, message or `toString`
  with the URL, host, entity, body, Wi-Fi name or token.
- **C23, the error mapping (POST; GET is C16).** Phases: **before the body is written** (gate refusal, connect, TLS
  handshake, writing the body fails before `getOutputStream().close()` returns) — the request is known not to have
  reached HA; **after** (waiting for the status line or reading the body) — it may have. The publish client's own whole-call bound
  (30 s, wrapping gate + request) answers `Refused(TIMED_OUT)` if it fires before the body is written and
  `Unknown` after (F-7).

| what happens | outcome | `PublishErrorKind` | sentence |
|---|---|---|---|
| gate: endpoint refused / INTERNET denied / not on home network / name not local | `Refused` | `ENDPOINT_REFUSED` / `DENIED` / `NOT_ON_LOCAL_NETWORK` (+ #16's cause detail) / `NAME_NOT_LOCAL` | P16-12 / P16-15 / P16-50 (+ P16-68…81 by cause) / #16's sentence (reused) |
| connect fails, refused, unreachable, before write | `Refused` | `UNREACHABLE` / `TIMED_OUT` | P16-13 / P16-14 (reused) |
| TLS failure (any `SSLException` in the cause chain), before write | `Refused` | `TLS_FAILED` | #16's (reused) |
| 200 | `Accepted` | — | — |
| 400 | `Refused` | `SERVICE_REFUSED` (detail: the value) | P113-17 |
| 401 | `Refused` | `AUTH_REFUSED` | P16-16 (reused) |
| 403 | `Refused` | `WRITE_FORBIDDEN` | P113-18 |
| 404 | `Refused` | `HTTP_ERROR` (detail `404`) — a missing **entity** is found by the readback, not the service status (§10 c6) | P113-27 |
| 300–399 | `Refused` | `REDIRECTED` | #16's (reused) |
| 500–599, other | `Refused` | `HTTP_ERROR` (detail the code) | P113-27 |
| read timeout, reset or EOF after the body was written; the 30 s whole-call bound after the write | `Unknown` | `OUTCOME_UNKNOWN` | P113-19 |
| cancellation | propagates (disconnects) | — | — |

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
  the helper **before** the command (a fresh `read`, C22) and hands the `RemoteEntity` in; the command re-validates it
  (C16) so a stale or forged read cannot enable:
  - `EnablePublishing(assetId, kind, entityId, fresh: RemoteEntity, installationId)`: refusals
        `PublishRefusal.{BAD_ENTITY_ID, WRONG_DOMAIN, OPTIONS_MISMATCH, NOT_DATE_ONLY, NO_CONNECTION, NOT_MAINTAINED_HERE,
    TARGET_IN_USE, INBOUND_SOURCE, STALE_READ}` (P113-29, P113-16/P114-34, P113-15, P114-32, P16-10 reused, P113-32,
    P113-22, P113-23, P113-31); creates or updates the asset's binding of that
    kind, `enabled = true`, revision +1, `validatedRevision = revision`, `activatedInstallationId = installationId`; a
    PENDING **or UNDELIVERABLE** tombstone for the same `(connection, entity)` is deleted in the same write —
    superseded: the new owner writes its own value, and its block now draws that helper's state, so no separate dismiss
    or line is owed for the old obligation (N-2). **F-1:** Enable and Change target clear `lastPublished*`, `lastVerified*`, the verified and published pairs,
    `remote*`, `error*` and `outcomeUnknownSince` in the same write, so a re-enable after a decommission, or a new
    target, reads and publishes again and never shows an inherited "Published" line. **N-2:** Revalidate on the **same**
    helper clears only `error*` and `outcomeUnknownSince`: its pair move already forces a read-back, and its
    "Published" line is a true fact about our own push to that helper, kept. **F-9:**
    the claim is per enabled binding: another asset's **disabled** binding on the same pair does not block Enable (its
    `claim_entity_id` is null); re-enabling that disabled binding is then `TARGET_IN_USE`, naming the holder. Then the
    projector computes the first desired value (C31): **initial publication requires a known source** (status:
    `WAITING_FOR_SOURCE` sends nothing).
  - `ChangePublishTarget(assetId, kind, newEntityId, fresh, installationId)`: as Enable for the new id; for the date
    kind a tombstone for the **old** `(connection, entity)` in the same write **only when the stored binding was
    enabled** (it held the claim; R-1) (C34); for the status kind the old
    helper is let go silently (#113: preserve the last reported value).
    - `DisablePublishing(assetId, kind)`: `enabled = false`, revision +1; for the date kind a tombstone in the same write;
    **disabling a disabled binding is a no-op** — no revision bump, no tombstone (R-1);
    the status kind writes **no** tombstone (#113: the remote value is preserved, F-10). A tombstone always carries the
    binding's `activatedInstallationId`, never the running one, so a copied installation draws it but never drains it
    (F-25).
    - `RevalidatePublishing(assetId, kind, fresh)`: "Check helper" — sets `validatedRevision` (revision +1) when the
    fresh read validates, else records the validation kind (N-2: the "Published" line is kept).
  - **Invariant I19 (R-1):** no PENDING tombstone coexists with an enabled binding whose claim names the same
    `(connection, entity)`. Every tombstone insert-or-merge (Disable, Change target, the projector's orphan retirement,
    Forget) first checks the claim in the same write and is **skipped** when an enabled binding holds the pair.
  - `DismissDecommission(id)`: deletes one tombstone at the owner's word ("I cleared it in Home Assistant", P114-38).
  - `RequestPublishNow(assetId?)` is not a command: the runner's entry (C30).
  - **Status kind on a not-maintained asset:** Enable is refused (`NOT_MAINTAINED_HERE`); an enabled binding whose asset
    becomes not maintained stops sending (C15) and resumes on its own when the asset is maintained here again (#16's
    R16-18 shape; #113 "re-evaluate on return/unarchive").
- **C26, the C2 cross-check (both directions, exact ids, one connection).** `EnablePublishing` and
  `ChangePublishTarget` refuse an `entityId` any `season_sync_binding` row names (`INBOUND_SOURCE`, P113-23);
  `LinkSeasonSync.run` (`C/seasonsync/LinkSeasonSync.kt:83`) and `EditSeasonSyncEntity.run`
  (`C/seasonsync/SeasonSyncCommands.kt:120`) refuse an id any `ha_publish_binding` names, enabled or not, and any
  PENDING tombstone (new `SeasonSyncLinkRefusal.OUTBOUND_TARGET`, `C/seasonsync/LinkSeasonSync.kt:23`; P113-43). The
  domains are disjoint today (`input_boolean` in, `input_select`/`input_datetime` out — audit headline 9); the check
  is the guard for #100 and any later kind. The new collaborator reaches the two #16 commands as a trailing
  parameter `outbound: OutboundTargets = OutboundTargets.NONE` (G3, F-6). **B3 owns every exhaustive `when` the new
  member reaches (F-4):** `A/ui/asset/SeasonSyncStrings.kt:161-166` and `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296`
  (`OUTBOUND_TARGET` → P113-43), with P113-43 ratified before B3.
- **C27, the installation fence.** `activatedInstallationId` is written only by Enable, Change target and Revalidate,
  from `InstallationIdentity.id()` (`A/prefs/InstallationIdentity.kt:28`, the no-backup directory, created per
  installation). The sender, the projector and the tombstone drain compare it with the running installation and treat
  a mismatch as `COPIED_INSTALLATION`: nothing is sent, nothing is invalidated, the block says so (P113-21) and only
  an explicit re-validate + enable on this phone re-stamps it. A token re-entered after a platform restore (#16's
  NEEDS_TOKEN path) therefore never re-activates a copied writer (audit headline 7, K7). Tombstones carry
  `installation_id` under the same rule.
- **C28, the shipped connection commands.** `SaveHaConnection` (`C/seasonsync/SeasonSyncCommands.kt:231-311`): its
  `reauthorized` branch (`:284-289`) also moves every publish binding's revision (CAS), so a send built before the
  change is dropped; **O-12 (plan position): an address change is refused** with new
  `SaveHaConnectionRefusal.PUBLISHING_BLOCKS_ADDRESS_CHANGE` (P113-42) while any date binding is enabled or any
  tombstone of this connection is PENDING — the old HA must hear `1970-01-01` first. A token or network change is
  allowed and, per F-2, keeps every binding validated: pending invalidations are delivered, not parked in
  NEEDS_VALIDATION. B3 adds the `PUBLISHING_BLOCKS_ADDRESS_CHANGE` arm to
  `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462` (P113-42, ratified before B3); the new collaborators of
  `SaveHaConnection` and `ForgetHaConnection` are trailing defaulted parameters (F-6). `ForgetHaConnection` (`:318-332`): in the same write that deletes the connection, each enabled date
  binding and each PENDING tombstone of it becomes an **UNDELIVERABLE** tombstone (no foreign key, so it survives);
  the app layer runs the drain first (C34). Status bindings go with the CASCADE.

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
| `boundary` | `null`, or `{"on": "YYYY-MM-DD", "kind": "SEASON_END" \| "COMMISSIONING"}` |

  "Publication not verified" is **not** a readiness reason: it is the publishing projection's `state` (C35). Every
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
  `drain()` (the worker's and the projector's: every enabled binding whose verified pair is not its current
  `(revision, desiredRevision)` (F-1), less C17's R-2 skips, plus PENDING tombstones; returns whether any TRANSIENT kind remains); `drainBeforeForget(): List<TombstoneOutcome>` (C34). Each
  binding: snapshot in one `uow.read` (binding, delivery, connection, `SecretStore.get(connection.id)` — the #16 key —
  installation id), C17's plan, at most one GET + one POST + one GET, then C17's CAS record in one `uow.write`; a
  dropped record loops at most twice per call (a third newer desired value waits for the next trigger). Never holds a
  transaction across I/O; never throws a transport failure (values only); a Room failure surfaces as
  `PublishDatabaseFailed` for the worker's retry (#16's `SeasonSyncDatabaseFailed` shape). The schedule rule is
  #16's `seasonSyncScheduleOf` (`A/seasonsync/SeasonSyncRunner.kt:46-55`) over "any publish binding enabled or any
  tombstone pending", reused, not copied.

### B4b1 / B4b2 — the projector and triggers (C31), the worker and the wiring (C32); app JVM

- **C31, `PublishProjector` (`A/hapublish/PublishProjector.kt`) and the reconcile.**
  - `suspend fun reconcileDesired(today: LocalDate): Boolean` — one `uow.read` snapshot of the enabled bindings, their
    assets, `transferRecords` held ids, conditions, schedules, groups, events and activations of the bound assets; C15
    per binding; then one `uow.write` that, per binding whose desired value **differs** from the stored one, writes it
    with `desiredRevision + 1` and `desiredAt` (CAS on the binding revision); a binding whose asset row is missing is
    retired as C34 says; so is a **disabled** binding whose asset is missing (F-9: its row is deleted, and no tombstone is written for it —
    R-1: it holds no claim; any obligation was written at Disable). Answers whether anything changed. **Idempotent:** a second call over the same database writes
    nothing (row 61). Duplicate, rolled-back and backdated-non-current writes change no projection and so write nothing.
  - **Observation (prompt, not correctness):** on `appScope`, a `combine` of `bindings.observeAll()`,
    `conditions.observeAll()` (`C/ports/Repositories.kt:449`), `assets.observeAll()` (`:61`), `schedules.observeAll()`
    (`:193`), `groups.observeAll()` (`:168`), `transferRecords.observeHeldIds()` (`:552`) and, per enabled binding's
    asset, `events.observeForAsset` (`:123`) and `activations.observeForAsset` (`:434`), debounced 300 ms →
    `reconcileDesired(today)` → when it answers true, `runner.drain()` in process and `PublishWork.enqueueOnce()`. Every
    writer of audit §1.2 (UI, offers, incident write, API/MCP, merge, Transfer Pack, replace restore) reaches Room, so
    the projector covers them with no use case touched.
  - **Triggers that do not depend on a flow (the crash gap and time-only change, K14):** `ServiceTagApp.onCreate`
    after `startSeasonSync` (`A/ServiceTagApp.kt:86-91`); the resume hook — `ResumeRefresh`
    (`A/seasonsync/ResumeRefresh.kt`, called at `A/ui/nav/ServiceTagRoot.kt:119-120`) gains the publish reconcile in
    the graph's lambda, `ServiceTagRoot` itself untouched; a **local-midnight tick** on `appScope` (delay until the
    next local midnight, re-armed; no receiver, no alarm); the worker's start; Publish now. **F-3:** start and resume run
    `reconcileDesired(today)` then **reconcile mode** (`publishNow(null)`: every enabled binding read back, so an HA
    restart or reset is repaired at the next open even under FOREGROUND_ONLY); resume is rate-limited to once per
    60 minutes of process time since the last **completed** read-back — a pass refused by the gate (WAIT,
    NEEDS_TOKEN, COPIED_INSTALLATION) does not start the window (N-1); start always runs it; the midnight tick and the worker
    run `reconcileDesired` then `drain()`; Publish now is reconcile mode.
- **C32, the work.** `PublishWorker : CoroutineWorker` reached through `HaPublishDispatch` (assigned synchronously in
  `ServiceTagApp.onCreate` beside `SeasonSyncDispatch.runner` at `:55`). Two unique works:
  - **`"ha-publish"`**, one-time, `CONNECTED`, `BackoffPolicy.EXPONENTIAL` from 30 s,
        `ExistingWorkPolicy.APPEND_OR_REPLACE` (F-26: a run whose snapshot predates a new desired value is followed by
    another, never dropped), **not expedited** (G16): body = reconcile + drain; `retry()` while a TRANSIENT kind
    remains and `runAttemptCount < 10`, else `success()` (bounded; the periodic work, start, resume or Publish now
    resumes it); `retry()` on `PublishDatabaseFailed`; cancellation propagates.
  - **`"ha-publish-reconcile"`**, periodic at the connection's cadence (`SyncCadence.hours`), `CONNECTED`, `UPDATE`
    policy: body = reconcile in **reconcile mode** (every enabled binding read back) + drain, always `success()`.
  - Both enqueued only when C30's schedule rule answers `PERIODIC`; `FOREGROUND_ONLY` and `PAUSED` enqueue nothing and
    pending state waits for start, resume, midnight-in-process or Publish now (#113: background retries honour the
    connection's setting and its Android grants, `A/ui/homeassistant/LocationAsks.kt`). `ensure`/`cancel` after every
    C25/C28/C34 commit that changes "any enabled", the cadence or the eligibility.
    - **Wiring:** B4b1 wires the projector, the resume lambda (`AppGraph.kt:901`) and the midnight tick; B4b2 the work
    seam and the dispatch; `FakeGraph` the scripted transport and a recording work seam.

### A5a / A5b — the phone: readiness and completion lines (A5a), the role and the strand sentences (A5b) (C33; app JVM)

- **C33.** Drawn from the existing read models; no use case added here.
  - **The season card** (`A/ui/asset/AssetDetailScreen.kt:477-488`, `SeasonSection` `:1278`): one readiness line under
    the season, **only when a role covers the asset or a date binding exists** (R-N1): `Ready` → P114-7 with the date;
    `NotReady` → P114-8…13 by reason (`NO_ROLE` drawn only when a date binding exists: P114-40). The view model reads
    C9 with `today` from the shipped `Today` and recomputes on resume (no cached `val`).
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
  `{"kind": "EQUIPMENT_STATUS"|"SEASON_SERVICE_DATE", "entityId", "enabled", "state": PublishState name,
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
    `INPUT_DATETIMES("input_datetime")`): pick or type the exact id, a fresh read, the validation answer, then the
    confirm naming the exact destination and the mapping (P113-5 / P114-33), Enable.
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
  `app/src/main/AndroidManifest.xml:12` comment (the one manifest change); `A/fetch/UrlConnectionTransport.kt:22`
  KDoc (comment-only); `docs/design/09-security-privacy.md`, `docs/local-first-and-permissions.md`,
  `docs/capabilities.md` (one paragraph each); `docs/release-proofs.md` the schema-22 / format-21 R7 paragraph beside
  `:139`'s. No release row in `docs/versioning.md` (the owner picks the vehicle).

### The two state machines, as tables (C17, C34)

**Delivery, per enabled binding.** Stored: the binding's `revision` (generation) and the delivery's `desiredRevision`,
`desiredValue`, `lastVerifiedValue`, `outcomeUnknownSince`, `errorKind`. The snapshot is `(rev, dRev)`; every record is
a compare-and-set on both.

| from (derived `PublishState`) | event | guard | action | to |
|---|---|---|---|---|
| any | projector: desired value changes | binding enabled | `desiredValue := v`, `dRev += 1`, `attemptsAtRevision := 0` (one write) | `PENDING` |
| enabled | Disable | — | `rev += 1`; a tombstone for the date kind (R-1: only because the stored binding was enabled) | `NOT_ENABLED` |
| `NOT_ENABLED` | Disable | — | nothing (no-op, R-1) | `NOT_ENABLED` |
| any | `SaveHaConnection` re-authorises (token, network) | — | `rev += 1`; `validatedRevision += 1` when it equalled the old `rev` (F-2) | unchanged (still validated; pending `1970-01-01` keeps going); in-flight results dropped; the verified pair is no longer current, so the next drain reads back |
| any | Enable, Change target | fresh read validates | `rev += 1`; delivery's published, verified, remote, error and `outcomeUnknownSince` cleared (F-1); Change target of an enabled date binding tombstones the old pair (R-1) | `PENDING` (reads first, then publishes) |
| any | Revalidate (same helper) | fresh read validates | `rev += 1`; `error*`, `outcomeUnknownSince` cleared; the published line kept (N-2) | `PENDING` (reads back) |
| `ERROR` (NEEDS_OWNER at the current pair) | `drain()` (projector, midnight, worker) | — | skipped (R-2) | unchanged until reconcile mode or a pair move |
| `ERROR` (WAIT at the current pair) | midnight tick, worker | — | skipped (R-2) | unchanged until reconcile mode, a projector change or a pair move |
| `PENDING` | drain | gate passes (C17) | snapshot `(rev, dRev)`; `ReadFirst` or `Post` | (I/O) |
| (I/O) | read equals desired, no POST made | CAS holds | `lastVerified* := v, now`, verified pair `:= (rev, dRev)`; error cleared | `MATCHES` |
| (I/O) | POST 200, readback equals desired | CAS holds | `lastPublished* := v, now`, published pair `:= (rev, dRev)`; `lastVerified* := v, now`, verified pair `:= (rev, dRev)`; error cleared | `PUBLISHED` |
| (I/O) | POST 200, readback differs | CAS holds | `errorKind := REMOTE_DIFFERS`, `remoteValue := read` | `ERROR` (NEEDS_OWNER) |
| (I/O) | POST `Refused(kind)` | CAS holds | `errorKind := kind`; `attemptsAtRevision += 1` | `ERROR` (by kind's retry class) |
| (I/O) | POST `Unknown` | CAS holds | `outcomeUnknownSince := now`, `errorKind := OUTCOME_UNKNOWN` | `OUTCOME_UNKNOWN` |
| `OUTCOME_UNKNOWN` | drain | gate passes | `ReadFirst`: equal → as "read equals" (`outcomeUnknownSince := null`, **not** published); differs → `Post` | `MATCHES` / (I/O) |
| (I/O) | a read or POST validator fails | CAS holds | `errorKind := WRONG_DOMAIN`/`OPTIONS_MISMATCH`/`NOT_DATE_ONLY`; `validatedRevision := null` | `NEEDS_VALIDATION` |
| (I/O) | any outcome | CAS **fails** (rev or dRev moved) | nothing written | the newer state; the runner loops (≤ 2) |
| `MATCHES`/`PUBLISHED` | reconcile mode (Publish now, periodic, start, resume — F-3) | — | `ReadFirst` again | (I/O) |
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
| I3 | readiness is a pure function of the asset tree, schedules, groups, this asset's events, its activations and `today` | 1–11 |
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

### Interfaces between briefs (consumes → produces)

| brief | consumes | produces (the boundary later briefs code against) |
|---|---|---|
| S1 | — | the v22 entities and DAOs (C4, C5); `MIGRATION_21_22`; `22.json` |
| A1 | `SeasonContext`, `AssetTree`, the model | `ScheduleRole`, the two fields; `lastSeasonEndOnOrBefore`; `seasonReadiness(…) : Readiness` |
| B1 | `ConditionHistory`, `Readiness` (A1), `HaStateMapper`'s public helpers | `PublishKind`, `PublishBinding`, `PublishDelivery`, `DecommissionTombstone`, `PublishErrorKind` + retry class, `PublishState`, `equipmentStatusOf`, `seasonDateOf`, `desiredValueOf`, `RemoteEntity` + mapper, validators, `DeliverySteps` |
| A2 | A1's model; S1's columns | `SetScheduleRole`, `SeasonStartRefused`, `SeasonStartStrands`, the role mapping, the two API codes |
| A3 | A1's model | format 21; `FIRST_SCHEDULE_ROLE_FORMAT`; the two `MergeReason`s; the planner's `importDay` |
| B2 | B1's kinds and mapper; #16's gate | `HaRequestGate`; `HomeAssistantPublishClient.read/call`; `HaServiceAction`; `CallOutcome` |
| B3 | S1's DAOs; B1's model and validators; `InstallationIdentity`; ratified P113-42/43 | the three ports and Room adapters; the three new `when` arms; `EnablePublishing`, `ChangePublishTarget`, `DisablePublishing`, `RevalidatePublishing`, `DismissDecommission`; the C26/C28 refusals; the in-memory doubles |
| A4 | A1's rule; A2's mapping | `GET …/season-readiness`; the schedule `role` keys; `get_season_readiness` |
| B4a | B2's client; B3's ports; `SecretStore` | `PublishRunner.publishNow/drain/drainBeforeForget`; `PublishDatabaseFailed` |
| B4b1 | B4a; every repository's flow | `PublishProjector.reconcileDesired`; the observer; the midnight tick; the start and resume read-back reconcile |
| B4b2 | B4b1 | `PublishWorker`, `PublishWork` (both unique works), `HaPublishDispatch` |
| A5a | A1 | the readiness line, the completion line; `ReadinessStrings`; two glossary terms |
| A5b | A2, A5a | the role action and dialogs, the refusal and strand sentences, the editor's QUICK election |
| B5 | B4a, B4b1, B4b2, B3 | the decommission paths; `GET …/ha-publishing`; `get_ha_publishing`; the Forget orchestration |
| B6a | B3's commands, B4a's runner, B5's read model and `drainBeforeForget`, A5a's strings | the status block, the blocks' frame, the setup sheet, the scopes, the HA screen lines, two glossary terms |
| B6b | B6a, B5 | the date block, the tombstone lines and the F-16 rule |
| B7 | everything drawn; B2, B4b2 | two androidTest classes |
| D1 | everything | the documents and the R7 paragraph |

## 3. Test matrix

Rows: id · layer (**JVM** core or app; **Compose**; **contract** = API route or pytest; **platform** = device class;
**real-HA** = §7's controller proof, never a gate) · class · cases · the behaviour proven · **the mechanism by which it
fails without the change** (the counted RED is that mutation, `--no-build-cache --rerun-tasks`, quoted, reverted). Case
names are the implementer's; what each proves is not. "pin" = a shipped assertion re-run unchanged, no RED.

### A1 — the rule (rows 1–11; `CT/season/SeasonReadinessTest`, `CT/schedule/SeasonContextLastEndTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 1 | JVM | `SeasonContextLastEndTest` · `manualAnswersTheLatestEndOnOrBefore`; `manualStartOnlyAnswersNull`; `anEndAfterAnEndAnswersTheLater`; `yearRoundAnswersNull` | C8 MANUAL / YEAR_ROUND | the filter on `action == END` removed (a START is answered) |
| 2 | JVM | same · `calendarAnswersTheFirstOutOfSeasonDayOfTheLatestFinishedWindow` (window 10-01…04-20, d = 2026-04-21 → 04-21; d = 04-20 → 2025-04-21); `aWrappingWindowAndFeb29Resolve`; `aMalformedWindowAnswersNull` | C8 CALENDAR (O-1), K4 | `latestEndOnOrBefore(d)` used instead of `(d − 1) + 1` (04-20 answered on 04-20) |
| 3 | JVM | `SeasonReadinessTest` · `aQualifyingCompletionAfterTheEndIsReady` (scenario 1: END 04-20, service 09-18 → Ready 09-18); `theLatestOfSeveralQualifyingIsPublished` (O-3) | C9 core, AC114-2 | `>=` written `>` against the boundary **and** `max` replaced by `first` — two counted |
| 4 | JVM | same · `aCompletionOnTheEndDayCounts` (END and completion both 04-20); `aCompletionTheDayBeforeTheEndDoesNot`; `aFutureDatedCompletionCountsOnlyFromItsDay` (F-8: `FUTURE_DATED` today, Ready on its day) | the same-day pin (#114 §2, K4); O-16 | the comparison made strict; **and** the `<= today` filter removed — two counted |
| 5 | JVM | same · `aCompletionBeforeTheCommissioningBoundaryNeverCounts` (role set 10-22, completion 10-21 → `NOT_DONE_SINCE_COMMISSIONING`); `theLaterOfEndAndCommissioningWins` | commissioning boundary (#114 review 2, scenario 1 setup) | the boundary computed from the END alone |
| 6 | JVM | same · `aDetailsPendingCompletionIsNotReadyWithItsReason`; `filledInDetailsMakeItReady` (the same event with the flag cleared); `anOlderQualifyingPlusANewerPendingIsReadyFromTheOlder` | K3, scenario 3 | the `!detailsPending` filter removed |
| 7 | JVM | same · `aNewEndAfterTheServiceMakesItNotReady`; `aBackdatedEndBeforeTheServiceKeepsItReady`; `aBackdatedEndAfterTheServiceRevokesIt` | AC114-3 re-derivation | `lastSeasonEndOnOrBefore(today)` replaced by the END cached at role time |
| 8 | JVM | same · `deletingOrRepointingTheCompletionReDerives` (an event re-pointed to another schedule of the asset via `UpdateEvent`'s `scheduleId`, `EventCommands.kt:289`); `anotherSchedulesCompletionNeverCounts`; `aChildsOrParentsCompletionNeverCounts` | AC114-3, "never inferred" | the `scheduleId == s.id` or `assetId == asset.id` filter removed |
| 9 | JVM | same · `aCalendarToManualSwitchRowIsABoundary` (the switch END dated today revokes an earlier service — pinned and documented); `aManualToCalendarSwitchReadsTheWindow` | inv. 90 consequences (C8) | activations read in CALENDAR (the window ignored) |
| 10 | JVM | same · `noRoleIsNoRole`; `anArchivedRoleScheduleIsScheduleArchived`; `aPausedRoleScheduleIsLive`; `twoRolesAreTwoRoles`; `aLeafChildOrYearRoundIsRoleNotAllowed`; `aParentWithAnArchivedChildIsAllowed` (O-5); `aRoleOnAnArchivedGroupIsScheduleArchived` (F-28) | C9's reason order | the `status != ARCHIVED` filter removed (an archived schedule's completion reads Ready) |
| 11 | JVM | same · `aGroupRoleRunsPerMemberOnItsOwnEvents` (C1: boiler done, stove not → stove NotReady, boiler Ready); `aRemovedMemberIsNoRole`; `conditionHealthPhaseAndRemindersChangeNothing` (the same inputs with each varied) | C1, AC114-3 "never" | the `assetId` filter dropped for group schedules (the boiler's event makes the stove Ready) |

### S1 — schema 22 (rows 12, 22–24 and pins; `T/data/room/Migration21To22Test`, `HaPublishDaoTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 12 | JVM | `T/backup/SeasonSyncNeverTravelsTest` (+4) · `anExportCarriesNoPublishBindingDeliveryTombstoneOrEntityByte`; `aMergeLeavesThemAndTheirRevisions`; `aTransferPackCarriesNone`; `aReplaceRestoreLeavesThemForTheProjector` | G5, G7, AC113-8 | none: tripwires (row 22's RED covers the shape) |
| 22 | JVM | `Migration21To22Test` · `theRoleColumnsDefaultToNoneAndNull`; `theThreeTablesExistEmptyWithTheirIndicesAndKeys`; `everyV21TableIsUnchanged` (the #47 snapshot shape); `theMigratedSchemaEqualsAFreshVersion22` | C4 | the binding's `asset_id` written with a CASCADE key (the schema compare fails) |
| 23 | JVM | `HaPublishDaoTest` · `oneBindingPerAssetAndKind`; `oneBindingPerConnectionAndEntity`; `deletingTheConnectionTakesItsBindingsButNoTombstone`; `deletingTheAssetLeavesTheBinding` | C4, G1 | `UNIQUE(connection_id, entity_id)` dropped |
| 24 | JVM | same · `aStaleRevisionUpdateAnswersFalse`; `aDeliveryRecordAtAMovedDesiredRevisionAnswersFalse` | C5 CAS | the delivery CAS reads only the binding revision |

### A2 — the role command and hooks (rows 13–21; `CT/usecase/SetScheduleRoleTest`, `SeasonStartStrandsTest`, new)

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
| 39 | JVM | same · `outcomeUnknownReadsBeforeResending` (remote already UP → verified, no second POST); `outcomeUnknownWithADifferentRemotePosts` | ambiguous completion (AC113-6) | `ReadFirst` skipped after `Unknown` (a blind re-POST) |
| 40 | JVM | same · `aStaleReplyAtAMovedGenerationRecordsNothing` (disable/target change between snapshot and record); `aReplyForAnOlderDesiredRevisionRecordsNothing` | generation fencing (AC113-6) | the CAS compares only the desired revision |
| 41 | JVM | same · `aReadbackThatDiffersAfterA200IsRemoteDiffers`; `aReadbackThatFailsValidationClearsTheValidation` (options changed in HA) | C17, "changed options" | the readback skipped after 200 (`Accepted` recorded as published) |
| 42 | JVM | same · `eachKindHasItsRetryClass` (exhaustive table); `aTransportFailureIsNeverDown` (the desired value and the source untouched) | K1, "never relabelled DOWN" | `UNREACHABLE` classed `NEEDS_OWNER` |
| 43 | JVM | `PublishModelTest` · `noModelTypeHasASecretField` (reflection over `C/hapublish`); `everyKindMapsFromSyncErrorKindExhaustively` | G4, C14 | a `token` field on `PublishBinding` |
| 44 | JVM | same · `theAllowlistHasExactlyTwoActions` (`HaServiceAction.entries` names, domains, services) | G2 | a third member added |
| 42a | JVM | `DeliveryStepsTest` · `aNeedsOwnerErrorIsNotRetriedByDrainUntilThePairMovesOrPublishNow` (also: a WAIT error skipped by the tick and the worker, retried at resume) | R-2, the retry class | `drain()` without the skip (a `WRITE_FORBIDDEN` helper re-POSTed on every projector change) |
| 45 | JVM | same · `invalidDateIsDeclaredOnce` (the constant equals `"1970-01-01"`) | C1 | none: pin |
| 45a | JVM | `DeliveryStepsTest` · `aVerifiedValueAtAnOlderBindingRevisionIsNotDone`; `drainSelectsByThePairNotTheValue` | F-1 | `Done` answered from `lastVerifiedValue` alone |

### A3 — format 21 (rows 46–49; `CT/backup/BackupFormat21Test`, `CT/merge/ScheduleRoleMergeTest`, `CT/usecase/TransferPackRoleTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 46 | JVM | `BackupFormat21Test` · `theRoleRoundTrips`; `aFormat20ArchiveDecodesAsNone`; `aFormat20ArchiveCarryingARoleIsRefused`; `anUnknownRoleOrABrokenR1IsCorrupt`; `aFormat22ArchiveIsNewer` | C18, G6 | the older-format refusal removed |
| 47 | JVM | `ScheduleRoleMergeTest` · `thisPhonesPreRoleExportReMergesIdentical` (local row gained a role); `aFormat21ArchiveComparesTheRole` | C19 compare | `rolesCompared` always true (CONTENT_DIFFERS on the phone's own export) |
| 48 | JVM | same · `aSecondCoverageIsHeldByALocalRow`; `twoArchiveRolesOverOneAssetAreDuplicated`; `noApplyCodeMoved` (the apply writes the row as planned) | C19 one coverage, O-9 | the claim map not seeded from the destination |
| 49 | JVM | `TransferPackRoleTest` · `anInsertedRoleScheduleIsRecommissionedOnTheImportDay` (scenario 8); `aPackComingBackKeepsTheLocalBoundary`; `aBackupMergeKeepsTheArchivesBoundary` (scenario 7) | C20, #114 point 6, O-8 | `importDay` ignored (the predecessor's boundary makes the recipient Ready) |

### B2 — the client (rows 50–56; `T/hapublish/HomeAssistantPublishClientTest`, `T/seasonsync/HaRequestGateTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 50 | JVM | `HomeAssistantStateClientTest`, `CurrentNetworkReaderTest`, `NetworkSecurityConfigTest`, `SeasonSyncRunnerTest`, `SeasonSyncScenarioTest`, `SeasonSyncWorkerBodyTest` green **unchanged** (`git diff <base>` empty on each) | G3 after C21's extraction | none: pins |
| 51 | JVM | `HaRequestGateTest` · each gate refusal opens nothing for both clients (endpoint, INTERNET, home network with causes, private resolution, same host); `theGateCarriesNoRequestTimeout` (F-7) | C21 shared | the publish client calling `open` before the gate |
| 52 | JVM | `HomeAssistantPublishClientTest` · `postsExactlyThePathBodyAndFiveHeaders` (`methodAtHead == "POST"`, path `/api/services/input_select/select_option`, body `{"entity_id":"input_select.example_heater_equipment_status","option":"UP"}`); `theDateActionPostsADateBody`; `noRedirectNoCacheNoProxy` | C22, G2 | `instanceFollowRedirects` left true |
| 53 | JVM | same · each row of C23's table → its outcome (400, 401, 403, 404, 302, 500) | C23, AC113-6 | 403 mapped to `AUTH_REFUSED` |
| 54 | JVM | same · `aConnectFailureBeforeTheWriteIsRefused`; `aReadTimeoutAfterTheWriteIsUnknown`; `theWholeCallBoundAfterTheWriteIsUnknown` | C23 phases | the after-write timeout mapped to `TIMED_OUT` (a resend without readback) |
| 55 | JVM | same · `tlsFailureIsTlsFailed`; `a200BodyIsCappedAndDiscarded` | C22 caps | the cap removed (a 65 KiB body read whole) |
| 56 | JVM | same · `noLogNoMessageNoToStringCarriesUrlEntityBodyOrToken` (a captured log seam; every exception message) | G4 | an exception message with the URL |

### B3 — commands (rows 60, 63, 64, 69, 86, 87, 93–97; `CT/hapublish/PublishCommandsTest`, `CT/seasonsync/CrossCheckTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 60 | JVM | `PublishCommandsTest` · `enableValidatesTheFreshReadAndStampsTheInstallation`; `aStaleOrForeignReadIsRefused`; `enableWithNoConditionWaits` | C25, AC113-1/2 | the command trusting the UI's "validated" flag (no re-validation) |
| 63 | JVM | same · `aSecondAssetOnTheSameHelperIsTargetInUse`; `onePerAssetAndKind`; `enableOverAPendingTombstoneSupersedesIt`; `anotherAssetsDisabledBindingDoesNotBlockAndReEnablingItIsTargetInUse`; `aDisabledBindingWhoseAssetIsDeletedIsRetired` (F-9) | G1, AC113-8 | the pre-check removed (the unique index throws a 500-shaped error instead of the refusal) |
| 64 | JVM | same · `disableAndChangeTargetMoveTheGeneration`; `theDateKindWritesATombstoneInTheSameWrite`; `theStatusKindLetsGoSilently` | C25, K12 | the tombstone written after the commit (a crash between loses it: the double's rollback shows it absent) |
| 69 | JVM | `InstallationFenceTest` · `aCopiedBindingSendsNothingEvenWithAToken` (Room rows restored, a new installation id, a token re-entered); `revalidateAndEnableReStampIt`; `aCopiedBindingsTombstoneIsDrawnNeverDrained` (F-25) | C27, K7, AC113-8 | the fence compares nothing (the copy POSTs) |
| 93 | JVM | `PublishCommandsTest` · `reEnableAfterDecommissionSendsAgain` (disable → tombstone drained to `1970-01-01` → re-enable → the date is POSTed and read back) | F-1, AC114-6 | `Done` answered from `lastVerifiedValue` alone (HA keeps `1970-01-01`, the block reads MATCHES) |
| 94 | JVM | same · `aTargetChangePublishesToTheNewHelper`; `aNewTargetShowsNoInheritedPublishedLine` | F-1, #113 "never attributed" | the delivery fields not cleared by Change target |
| 95 | JVM | `CT/hapublish/DefaultedCollaboratorTest` · `theFourCommandsCompileWithTheirShippedPositionalArguments`; `theDefaultRefusesNothing` | F-6, G3 | none: pin (R6's `OutboundTargets.NONE` grep is the guard) |
| 96 | JVM | `PublishCommandsTest` · `aDisabledBindingsTargetChangeNeverTombstonesAPairAnotherAssetClaims` (A disabled on H, B enabled on H, A changes target to H2: no tombstone for H, B's date stands) | R-1, I19 | the tombstone inserted unconditionally (HA receives `1970-01-01` over B's date) |
| 97 | JVM | same · `disablingADisabledBindingWritesNothing` (no revision bump, no tombstone) | R-1 | the tombstone inserted unconditionally |
| 86 | JVM | `CrossCheckTest` · `linkAndEditRefuseAnOutboundTarget`; `enableAndChangeTargetRefuseAnInboundSource`; `aPendingTombstonesEntityIsOutboundToo` | C26, G9, K6 | the inbound refusal checks enabled bindings only |
| 87 | JVM | `ConnectionHooksTest` · `anAddressChangeIsRefusedWhileADateBindingOrPendingTombstoneExists` (O-12); `aTokenChangeBumpsEveryPublishRevision`; `forgetTurnsUndeliveredDateObligationsIntoUndeliverableTombstones` (O-11); `aTokenChangeKeepsBindingsValidatedAndDeliversPendingInvalidations` (F-2) | C28, K9 | the reauthorised branch bumps only season bindings; **and** `validatedRevision` left behind (pending invalidations park in NEEDS_VALIDATION) — two counted |

### B4a/B4b1/B4b2 — runner, projector, worker (rows 57–59, 61–62, 65–68; `T/hapublish/PublishRunnerTest`, `PublishProjectorTest`, `PublishWorkerBodyTest`, `PublishScenarioTest`, new)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 57 | JVM | `PublishRunnerTest` · `twoConcurrentDrainsPostOnce` (single flight); `aDisableDuringTheRequestRecordsNothing`; `noTransactionSpansTheRequest` (the scripted transport asserts no open write) | C30, AC113-6, G15 | the `Mutex` removed (two POSTs) |
| 58 | JVM | `PublishScenarioTest` · `upDegradedDownEachReachTheHelperInOrder` (scripted HA records three POSTs and serves readbacks); `aServiceDateThenADeletionPublishesTheDateThen1970` | AC113-4, AC114-5 | the projector not triggering a drain on change |
| 59 | JVM | same · `offlineTransitionsSurviveProcessDeathAndCoalesce` (desired written, the graph rebuilt, `UNREACHABLE` then reachable → one POST of the latest); `homeWifiOnlyWithBackgroundOffWaitsForResume` | AC113-5, K1 | the desired value held in memory only |
| 61 | JVM | `PublishProjectorTest` · `reconcileIsIdempotent` (second call writes nothing); `aChangeBumpsTheDesiredRevisionOnce` | C31 | `desiredRevision` bumped on every call |
| 62 | JVM | same · `eachWriterReachesTheProjection`: `RecordCondition`, `AcceptOperationalOffer`, `AcceptImpairmentOffer`, `RecordConditionWithIncident`, the API's `POST …/conditions`, a merge apply, a Transfer Pack import, a replace restore — each through the Room-backed `FakeGraph`; `aWindowEditOrModeSwitchRepublishes` (a CALENDAR window edit and a MANUAL → CALENDAR switch move the date, F-27) | AC113-3 (one seam) | the projector observing only `conditions.observeForAsset` of the UI's asset |
| 65 | JVM | same · `aRolledBackWriteChangesNothing` (a `RecordConditionWithIncident` whose event half fails) | AC113-3 | the projector reading uncommitted state (none: Room; RED = the projector called inside the use case) |
| 66 | JVM | same · `startResumeMidnightAndWorkerEachReconcile` (the crash gap: a desired value never written before death is written at the next start); `startAndResumeReadBackAVerifiedHelper` (an HA reset found at the next open, FOREGROUND_ONLY included; resume at most once per 60 min; F-3) | C31 triggers, #113 crash gap and comparison | the start-up reconcile omitted; **and** start calling `drain()` instead of reconcile mode — two counted |
| 67 | JVM | `PublishWorkerBodyTest` · `transientRetriesBoundedByTenAttempts`; `needsOwnerSucceeds`; `aDatabaseFailureRetries`; `cancellationPropagates`; `anUnassignedDispatchSucceeds` | C32 | `retry()` on every failure (unbounded) |
| 68 | JVM | same · `aCalendarWindowEndPassingAtMidnightPublishes1970` (no database write, the tick only) | K14, AC114-3 | the midnight tick omitted |

### A4, B5 — API and MCP (rows 80–84; contract)

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| 80 | contract | `T/api/SeasonReadinessRoutesTest` (new) · each `NotReadyReason` and Ready as keys; `noSuchAsset404`; `aGroupRoleNamesGroup` | C29 | `reason` serialized from `toString` |
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

## 4. Files, fences, order and the gate budget

| brief | wave · lane | touches | never touches |
|---|---|---|---|
| **S1** schema 22 | 1 · L1 | `A/data/room/entities/MaintenanceEntities.kt` (two columns, the entity `:95-155`); `A/data/room/entities/{HaPublishBindingEntity,HaPublishDeliveryEntity,HaPublishDecommissionEntity}.kt`, `A/data/room/dao/{HaPublishBindingDao,HaPublishDeliveryDao,HaPublishDecommissionDao}.kt` (new); `A/data/room/{Migrations,AppDatabase}.kt` (`MIGRATION_21_22`, version 22); `A/di/AppGraph.kt` (`SCHEMA_VERSION`, the migration list **only**); `T/testing/FakeGraph.kt`, `MigrationTestSupport` (twins); `app/schemas/…/22.json` (generated); `T/data/room/{Migration21To22Test,HaPublishDaoTest}.kt` (new); `T/backup/SeasonSyncNeverTravelsTest.kt` (+4); `T/VersionAgreementTest.kt` (schema only: `:87`, `:158`) — rows 12, 22–24 | `C/**`, `A/api`, `A/ui`, `A/seasonsync`, `21.json`, `docs`, `tools` |
| **A1** rule | 1 · L2 | `C/model/Maintenance.kt` (`ScheduleRole`, two fields); `C/schedule/SeasonContext.kt` (`lastSeasonEndOnOrBefore`, `YearlyWindow` made class-visible); `C/season/SeasonReadiness.kt` (new); `CT/season/SeasonReadinessTest.kt`, `CT/schedule/SeasonContextLastEndTest.kt` (new) — rows 1–11 | `A/**`, `C/usecase`, `C/backup`, `C/merge`, `C/transfer`, every other `C/schedule` line, `docs`, `tools` |
| **A2** role command | 2 · L2 | `C/usecase/SetScheduleRole.kt` (new); `C/usecase/{SeasonCommands,SetSeasonMode,UpdateAsset,SaveAssetSettings,SaveSchedule,SaveGroup}.kt` (the exceptions, the helper, one call each, `candidate`'s two fields, the QUICK-election parameter); `A/data/room/MaintenanceMappers.kt` (role mapping); `A/api/ApiJson.kt` (the two `is` arms only); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (`setScheduleRole`, constructor arguments); `CT/usecase/{SetScheduleRoleTest,SeasonStartStrandsTest,ScheduleRoleSurvivesTest}.kt` (new) — rows 13–21 | `C/backup`, `C/merge`, `C/transfer`, `C/schedule`, `C/hapublish`, `A/ui`, `A/hapublish`, every other `A/api` file, `docs`, `tools` |
| **B1** outbound core | 2 · L1 | `C/hapublish/{PublishModel,Projections,RemoteEntityMapper,Validators,DeliverySteps}.kt` (new); `CT/hapublish/*Test.kt` (new) — rows 25–45 | every shipped file; `A/**`; `C/seasonsync` (read-only use of its public helpers) |
| **A3** format 21 | 3 · L2 | `C/backup/{BackupFormat,BackupCodec}.kt`; `C/merge/{MergePlanner,MergePlan}.kt` (the compare, the claim, two `MergeReason`s); `C/usecase/ImportTransferPack.kt` (`importDay`); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (only if `ImportTransferPack` gains a constructor argument); `T/VersionAgreementTest.kt` (format: `:88`, `:159`); `docs/api/v1.md` (the merge tables' two reasons, the format line); `CT/backup/BackupFormat21Test.kt`, `CT/merge/ScheduleRoleMergeTest.kt`, `CT/usecase/TransferPackRoleTest.kt` (new); the twins `git grep -n 'FORMAT_VERSION\|formatVersion = 20'` names, read first — rows 46–49 | `C/usecase/ApplyBackupMergePlan.kt`, `C/transfer/**`, `A/ui`, `A/hapublish`, `A/seasonsync`, `tools` (but a format pin twin in `B/` if one exists) |
| **B2** client + gate | 3 · L1 | `A/seasonsync/HaRequestGate.kt` (new, the moved checks, no timeout); `A/seasonsync/HomeAssistantStateClient.kt` (calls the gate inside its own unchanged `withTimeoutOrNull`; nothing else); `A/hapublish/HomeAssistantPublishClient.kt` (new); `T/seasonsync/HaRequestGateTest.kt`, `T/hapublish/HomeAssistantPublishClientTest.kt` (new) — rows 50–56 | `A/di`, `FakeGraph`, every shipped test file, `C/**`, `A/fetch`, the manifest, `docs`, `tools` |
| **B3** commands | 4 · L1 | `C/hapublish/{PublishPorts,PublishCommands}.kt` (new); `C/seasonsync/{LinkSeasonSync,SeasonSyncCommands}.kt` (C26's refusal and C28's bump, refusal and tombstones, each new collaborator a **trailing defaulted** parameter, F-6); `A/data/room/HaPublishRepositories.kt` (new); **the three exhaustive `when`s the two new enum members reach (F-4):** `A/ui/asset/SeasonSyncStrings.kt:161-166` (`OUTBOUND_TARGET`), `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296` (`OUTBOUND_TARGET` → P113-43 on the entity line), `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462` (`PUBLISHING_BLOCKS_ADDRESS_CHANGE` → P113-42) — those arms only, with P113-42/43 in all ten packs; `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (repositories, commands, `DismissDecommission`); `CT/testing/InMemoryHaPublish.kt` (new); `CT/hapublish/PublishCommandsTest.kt`, `CT/hapublish/{CrossCheckTest,ConnectionHooksTest}.kt`, `T/hapublish/InstallationFenceTest.kt` (new; **not** in `CT/seasonsync`) — rows 60, 63, 64, 69, 86, 87 | every shipped `CT/seasonsync` test file (byte-identical, G3); every other `A/ui` line; `A/api`; `C/usecase`, `C/backup`, `C/merge`, `docs`, `tools` |
| **A4** API/MCP read | 4 · L2 | `A/api/{ApiRouter,SeasonHealthHandlers,ScheduleRowResponse,SeasonReadinessDtos}.kt` (the route, the handler, the keys, a new DTO file); `docs/api/v1.md` (the route, the keys, the counts); `M/src/servicetag_mcp/server.py`, `M/README.md`; `T/api/{SeasonReadinessRoutesTest (new),MaintenanceCommandShapeTest,ApiReadsWriteNothingTest}.kt`; `M/tests/test_ha_publishing_tools.py` (new, the readiness half) — rows 80, 81, 83, 84 (half) | `A/di`, `FakeGraph`, `C/**`, `A/ui`, `A/hapublish`, `S/`, `B/` |
| **B4a** runner | 5 · L1 | `A/hapublish/PublishRunner.kt` (new); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (client, runner); `T/hapublish/PublishRunnerTest.kt` (new) — row 57 | `A/ui`, `A/api`, `C/**`, `ServiceTagApp.kt`, `ServiceTagRoot.kt` |
| **A5a** readiness + completion lines | 5 · L2 | `A/ui/asset/{AssetDetailScreen,ReadinessStrings}.kt` (the line; `ReadinessStrings.kt` new), the readiness view-model state beside the season card, `A/ui/maintenance/CompletionFlow.kt` (the line); `docs/localization-glossary.md` (**Season Start service**, **ready for the season**, in every shipped language, first); `app/src/main/res/values*/strings_season_start.xml` (new, ten files: P114-7…15, P114-29, P114-40) — rows 75, 77, 79 (its half) | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `A/ui/homeassistant`, `SeasonSyncBlock.kt`, `A/ui/maintenance` but `CompletionFlow.kt` |
| **B4b1** projector + triggers | 6 · L1 | `A/hapublish/{PublishProjector,MidnightTick}.kt` (new); `A/ServiceTagApp.kt` (the start reconcile); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (projector, the resume lambda at `AppGraph.kt:901`, the tick); `T/hapublish/PublishProjectorTest.kt` (new) — rows 61, 62, 65, 66, 68 | `A/ui/**` (`ServiceTagRoot.kt` untouched), `A/api`, `C/**`, the manifest |
| **A5b** the role on the phone | 6 · L2 | `A/ui/maintenance/{SchedulesSection,ScheduleDetailScreen,ScheduleDetailViewModel,ScheduleEditViewModel,GroupEditViewModel}.kt`, `A/ui/asset/AssetViewModels.kt` (the editor's strand causes), `A/ui/asset/ReadinessStrings.kt` (its rows); `strings_season_start.xml` ×10 (P114-1…6, P114-16…28, P114-41…43) — rows 76, 78, 79 (its half) | `A/di`, `FakeGraph`, `C/**`, `A/hapublish`, `A/api`, `AssetDetailScreen.kt`, `CompletionFlow.kt` |
| **B4b2** worker + wiring | 7 · L1 | `A/hapublish/{PublishWorker,PublishWork,HaPublishDispatch}.kt` (new); `A/ServiceTagApp.kt` (the dispatch beside `:55`); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (work seam, dispatch); `T/hapublish/{PublishWorkerBodyTest,PublishScenarioTest}.kt` (new) — rows 58, 59, 67 | `A/ui/**`, `A/api`, `C/**`, the manifest |
| **B5** decommission + read | 8 · L1 | `C/hapublish/Decommission.kt` (new); `A/hapublish/{PublishRunner,PublishProjector}.kt` (drain, `drainBeforeForget`, orphan retirement); `A/api/{ApiRouter,HaPublishingHandlers,HaPublishingDtos}.kt`; `docs/api/v1.md` (the route); `M/src/servicetag_mcp/server.py`, `M/README.md`, `M/tests/test_ha_publishing_tools.py` (the publishing half); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the Forget orchestration, the tombstone read model) — **the last edit of either**; `CT/hapublish/DecommissionTest.kt`, `T/hapublish/DecommissionScenarioTest.kt`, `T/api/HaPublishingRoutesTest.kt` (new) — rows 70–74, 82, 84 (half) | `A/ui/**`, `C/usecase`, `C/backup`, `C/merge`, `S/`, `B/` |
| **B6a** status block, sheet, HA screen | 9 · L1 | `A/ui/asset/{PublishBlock,PublishBlockViewModel,LinkPublishSheet,LinkPublishViewModel,PublishStrings}.kt` (new; the block's frame for **both** kinds, the sheet with its scope parameter); `A/ui/asset/AssetDetailScreen.kt` (the two blocks' slots); `A/ui/asset/LinkSeasonSyncSheet.kt` (the browser's scope parameter only); `C/seasonsync/EntityPicker.kt` (two scopes); `A/ui/homeassistant/{HomeAssistantScreen,HomeAssistantViewModel,HomeAssistantStrings}.kt` (P113-40, the P113-41 plural, orphan tombstones); `docs/localization-glossary.md` (**helper**, **publish**); `app/src/main/res/values*/strings_ha_publishing.xml` and `values*/plurals.xml` (ten files each) — rows 88 (status half), 89, 90 | `A/di`, `FakeGraph`, `A/hapublish`, `A/api`, `C/**` but `EntityPicker.kt`, `ReadinessStrings.kt`, the three B3 arms |
| **D1** documents | 9 · L2 | C38's list | any `.kt`, `.py`, `.xml` but the manifest comment at `:12`; `docs/versioning.md`, `docs/releases` |
| **B6b** date block and tombstones | 10 · L1 | `A/ui/asset/{PublishDateBlock,PublishDateStrings}.kt` (new), `PublishBlockViewModel.kt` (the date states and the P114-36/37 rule); `app/src/main/res/values*/strings_ha_publishing_date.xml` (new, ten files: P114-30…39) — row 88 (date half) | `A/di`, `FakeGraph`, `A/hapublish`, `A/api`, `C/**`, `AssetDetailScreen.kt`, `LinkPublishSheet.kt` |
| **B7** drawn + platform | 11 · L1 | `AT/hapublish/{HaPublishScreensTest,HaPublishPlatformProofTest}.kt` (new) — rows 91, 92 | every main file; every shipped `AT/` file |

**Waves and lanes (two lanes at most, owner ruling 2026-09-17; no shared emulator, no gate on the device inside a
brief).** Rev 2 splits B4b, B6 and A5 (F-17) and runs B6 after B5 (F-5): **eighteen dispatches in eleven waves.**

| wave | lane 1 | lane 2 | why concurrent is safe |
|---|---|---|---|
| 1 | S1 | A1 | S1 is `A/data/room` + one `AppGraph` constant; A1 is `C/model` + `C/schedule` + `C/season` |
| 2 | B1 | A2 | B1 is new `C/hapublish` files only; A2 owns the wave's `AppGraph` edit |
| 3 | B2 | A3 | B2 edits no graph; A3 may (one argument) |
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
≤ 1 h by estimate. **AppGraph/FakeGraph edits:** S1, A2, A3 (conditional), B3, B4a, B4b1, B4b2, B5 — never two in one
wave, B5 last (G20).

**Between-brief gaps** (each unreachable: no screen enables a binding before B6a): S1 → B3, the tables exist with no
writer; A2 → A5b, the role is settable only from tests; B2 → B4a, the client is unwired; B3 → B4b1, an enabled binding
in a test has no projector (rows script the desired value); B4b1 → B4b2, no background work (in-process drains only);
A3 → B5, the format moves while publishing has no read route.

**What stays untouched (R6 checks each):** `HomeAssistantStateClient.kt` but the gate call; every shipped
`seasonsync` test in `T/` **and** `CT/` (F-6); `SeasonSyncRunner`, `SeasonSyncWorker`, `SeasonSyncWork`,
`SeasonSyncGuard`, `RecordSeasonSyncResult`; `RecordCondition`, `RecordConditionWithIncident`, the two offers,
`CompleteSchedule`, `CompleteGroupMembers`, `UpdateEvent`, `DeleteEvent`, `DeleteAsset`, `ImportBackupReplace`,
`ApplyBackupMergePlan`, `ReplaceAsset`; `C/transfer/**`; `21.json`; `ServiceTagRoot.kt`; the manifest but `:12`; `S/`;
the gitlink.

**The connected-suite budget (reporting only, 2026-09-29 ruling).** Today 78 classes / 430 tests / ~16 min under
software rendering. New: `HaPublishScreensTest` (~14 cases, ~1 min) and `HaPublishPlatformProofTest` (~6 cases,
~0.5 min) → **80 classes / ~450 tests / ~17.5 min**; `SeasonSyncPlatformProofTest` and `SeasonSyncScreensTest`
unchanged. **JVM:** core +~160, app +~180, MCP +~8.

## 5. Strings

**PENDING — every row is proposed English for the owner gate (`docs/localization.md`): ratified copy only enters the
ten packs, with its id in the XML comment.** Reused ratified #16 sentences are named, not repeated. Homes:
`A/ui/asset/ReadinessStrings.kt` + `strings_season_start.xml` (P114-1…29, P114-40…43) and
`A/ui/asset/PublishStrings.kt` + `strings_ha_publishing.xml` (P113-*, P114-30…39); the HA screen's in
`HomeAssistantStrings.kt`.

### A5a / A5b — the role and readiness (#114; A5a: P114-7…15, 29, 40; A5b: the rest)

| id | proposed wording | where · state |
|---|---|---|
| P114-1 | "Season Start service" | the schedule row and detail label (C33) |
| P114-2 | "Use as Season Start service" | the schedule detail action |
| P114-3 | "Stop using as Season Start service" | the clear action |
| P114-4 | "Use \"%1$s\" as %2$s's Season Start service? Completing it marks %2$s ready for the season. Services recorded on or after %3$s count." | the confirm, asset target |
| P114-4g | "Use \"%1$s\" as the Season Start service for every asset in %2$s? Completing it for an asset marks that asset ready for the season. Services recorded on or after %3$s count." | the confirm, group target (C1) |
| P114-5 | "This schedule is completed with one tap and no service details. A one-tap completion will still mark the asset ready." | the confirm's note on a QUICK schedule (O-2) |
| P114-6 | "Services count from" | the confirm's date field |
| P114-7 | "Ready for the season · serviced %s" | `Ready` (C33) |
| P114-8 | "Not ready: not serviced since the season ended on %s" | `NOT_DONE_SINCE_SEASON_END` |
| P114-9 | "Not ready: not serviced since Season Start was set on %s" | `NOT_DONE_SINCE_COMMISSIONING` |
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

**Reused, ratified (#16):** P16-12 (`ENDPOINT_REFUSED`), P16-13 (`UNREACHABLE`), P16-14 (`TIMED_OUT`), P16-15
(`DENIED`), P16-16 (`AUTH_REFUSED`), P16-10 (`PublishRefusal.NO_CONNECTION`), P16-17 (`ENTITY_NOT_FOUND`), P16-11 (`NEEDS_TOKEN`), P16-50 and P16-68…81
(`NOT_ON_LOCAL_NETWORK` by cause), the `REDIRECTED`, `MALFORMED`, `NAME_NOT_LOCAL` and `TLS_FAILED` sentences, P16-9
(the Forget confirm, unchanged). **Count (rev 2):** 34 (A5a/A5b) + 35 strings and one plural (#113 and the HA screen; P113-42/43 are B3's) + 10
(date) = **79 new strings and one plural**, ~800 entries; above the audit's ~60 by the split reasons, the strand causes and the HA-screen lines.

**Rule (F-16):** every `PublishState`, `PublishErrorKind`, `PublishRefusal`, `NotReadyReason`, `SeasonStartRefusal`
and `SeasonStartStrandCause` member named in §2 has an id above (or a named reused #16 sentence); an enabled date
binding whose desired value is `1970-01-01` and whose verified pair is not current draws P114-36 (P114-37 when its
error is NEEDS_OWNER) beside its error sentence. **Glossary:** **Season Start service**, **ready for the season**
(A5a) and **helper**, **publish** (B6a) enter `docs/localization-glossary.md` in every shipped language first.

**G-list (developer log lines, `// l10n-ok`, no URL, host, entity, Wi-Fi name, token or exception text):**
GH1 `"ha publish run failed; WorkManager retries it"` (database failure); GH2 `"ha publish could not run; it will
retry on the next resume, scheduled run, or app start"` (any other exception in the worker or a trigger); GH3 `"the ha
publish reconcile failed; the next trigger repeats it"` (the start, resume or midnight reconcile).

## 6. Owner rulings

| ruling | status | the plan's position · the alternative | lands |
|---|---|---|---|
| **R-0** | **owner — PENDING** | The scope sentence of §1, exactly; the fence (G17) binding on every brief. *Alt.:* the same scope without C1 (asset-schedule roles only; the fallback below). | header, §1 |
| **C2** | **owner — DECIDED 2026-10-22** | "In season shouldn't be the same as initial pre-season maintenance complete readiness": an HA-owned in-season boolean (inbound, #16) and a ServiceTag-owned date publication (outbound, #114); the stove re-binds through the shipped `EditSeasonSyncEntity` before date publishing is enabled; the exact-id cross-check both ways (C26). | C26, §7, §8 |
| **C1** | **owner — PROPOSED** | `SEASON_START` may sit on a **group** schedule; readiness is derived per open member from that member's own completion event (C9); every open member must qualify at assignment (C10); one coverage per asset across asset and group roles. *Alternative:* asset schedules only (#114 §1 as written) — the fallback paragraph below. | C9, C10, C11, rows 11, 15, 16, 20 |
| **Q2 (F-12)** | **owner — PENDING** | **Voice/Assist starts and the HA gate.** *(a), the plan's position pending the owner:* a person's start (voice, Assist) stays outside `stove_check`, as in the owner's Stage 2 design, and keeps its spoken caveat ("It isn't marked serviced for the season, though."); only automatic starts are gated. *(b):* #114 §5 as written — voice/Assist inside the one gate, refused while not released. §8 states both; D1 documents whichever is ruled. | §8, C38 |
| O-1 | plan | CALENDAR "season ended" = the **first out-of-season day** (window end + 1). *Alt.:* the last in-season day (a service on that day would count for the next season). | C8, row 2 |
| O-2 | plan (forced by C1) | QUICK counts on a role schedule; assigning the role to a QUICK schedule shows P114-5 and the confirm is the owner's explicit election; a later FORM → QUICK edit of a role schedule asks again (F-22: `QUICK_NEEDS_ELECTION`, P114-42/43); a FORM completion with `detailsPending` never counts. *Alt.:* FORM only (C1 then unusable: group schedules are QUICK-only, `C/usecase/ScheduleCommands.kt:110-116`). | C9, C11, C33, rows 6, 20 |
| O-3 | plan | The published date is the **latest** qualifying `occurredOn`. *Alt.:* the earliest after the boundary. | C9, row 3 |
| O-4 | plan | The boundary is stored `roleSinceOn`, default today, the owner may choose any date ≤ today (shown in the confirm); one boundary for all group members. *Alt.:* per member, `max(roleSinceOn, addedAt's day)`. | C10, row 13 |
| O-5 | plan | "Parent asset" = at least one child row, whatever its lifecycle. *Alt.:* only maintained-here children. | C9, C10, row 10 |
| O-6 | plan | Retargeting a role schedule is refused; clear the role first. *Alt.:* retarget clears the role in the same write. | C11, row 20 |
| O-7 | plan | No API or MCP write for the role in V1; read projections only. *Alt.:* `PUT /v1/schedules/{id}/role` as a canonical command. | C10, C29 |
| O-8 | plan | Transfer Pack: the role travels; an inserted role schedule's `roleSinceOn` becomes the recipient's import day. *Alt.:* strip the role at pack creation. | C20, row 49 |
| O-9 | plan | A merge bringing a second coverage for an asset is a **conflict** (two new reasons). *Alt.:* `SKIPPED` like the health-subject precedent, or insert with the role dropped (silent; not recommended). | C19, row 48 |
| O-10 | plan | One Room step (22) by S1 and one format step (21) by A3. *Alt.:* one step per issue (Room 22 for #114, Room 23 for #113). | C4, C18 |
| O-11 | plan | Forget warns with the count (P113-41), drains each invalidation once, then disconnects; any not confirmed stay as UNDELIVERABLE tombstones until dismissed. *Alt.:* refuse Forget until every invalidation is confirmed. | C28, C34, row 73 |
| O-12 | plan | An address change is refused while any date binding is enabled or a tombstone is pending. *Alt.:* allow, and turn each into UNDELIVERABLE. | C28, row 87 |
| O-13 | plan | The A-track (readiness on the phone, no HA) may land first; date publishing needs both tracks; nothing is released by this plan. *Alt.:* one release carrying both tracks, nothing landed separately. | §4 |
| O-14 | plan | Production interim and order: §7's production sequence and §8's commissioning order. *Alt.:* the hand-set interim continues and publishing waits for a release. | §7, §8 |
| O-15 | plan | "Qualifying" = the shipped `detailsPending == false`; the plan accepts that any edit of the event clears the flag (`C/usecase/EventCommands.kt:293`). *Alt.:* additionally require a FORM completion to carry a value for every required profile field. | C9, limit 7 |
| **O-16 (F-8)** | **owner** | Readiness never counts a completion dated after today (reason `FUTURE_DATED`, P114-29; it starts counting on its own day through the midnight tick). The question for the owner: **should the event commands refuse a future `occurredOn` outright**, as `RecordCondition` already does (`C/usecase/RecordCondition.kt:91`)? *Plan position:* readiness only (no product change to the journal in this programme). *Alt.:* refuse future dates in `CompleteSchedule`, `CompleteGroupMembers` and `UpdateEvent` (a separate issue). | C9, row 4 |
| **O-17 (Q3)** | **owner** | **The HA in-season signal.** *Plan position:* HA's in-season boolean is a **heating-year latch** — ON at the first fuel commit after 1 August, OFF at the first electric release on or after 1 May (otherwise at the 1 August reset) — so a late-winter re-commit after an early spring release never records an END on the phone and never stamps `season_ended`, and the autumn service stands all heating year. *Alt.:* follow the assignment edge for edge and accept a re-service tick after a cold snap re-commit. | §8, §7 |
| **O-18 (F-14)** | **owner** | **The oil boiler's season.** The production read of 2026-10-22 records the Oil Boiler as `YEAR_ROUND` / IN_SEASON, top-level, no children, so a group role covering it is refused today (`YEAR_ROUND`, P114-17 inside P114-21). It needs a season before step 3. *Plan position:* a `MANUAL` season bound to the **same** HA in-season boolean as the stove — #16 allows two assets to bind one inbound helper (`SeasonSyncLinkRefusal`, `C/seasonsync/LinkSeasonSync.kt:23-38`, has no entity-uniqueness member; `season_sync_binding` is keyed by `asset_id` with only a `connection_id` index, `A/data/room/entities/SeasonSyncBindingEntity.kt:35`). *Alt. 1:* its own HA in-season helper (when the oil gate of §8 is built). *Alt. 2:* a `CALENDAR` season (no HA link). | §7 step 2b, row 16 |
| R-N1 | plan | The card's readiness line is drawn only when a role covers the asset or a date binding exists (`NO_ROLE` is otherwise silent). *Alt.:* on every seasonal top-level or parent asset. | C33, row 75 |
| R-N2 | plan | `ha_publish_binding.asset_id` is a soft link (no FK), so asset delete and replace restore leave the obligation for the projector — no shipped use case is touched. *Alt.:* hook `DeleteAsset` and `ImportBackupReplace`. | C4, C34, row 71 |
| R-N3 | plan | 403 on a write is its own kind and sentence (`WRITE_FORBIDDEN`, P113-18); 404 on a service call is `HTTP_ERROR`; a missing entity is found by the readback. *Alt.:* the audit's 401/403 → `AUTH_REFUSED`, 404 → `ENTITY_NOT_FOUND`. | C23, row 53 |
| R-N4 | plan | A periodic `"ha-publish-reconcile"` at the connection's cadence repairs HA resets while the app is closed (#113's "periodic reconciliation"); no expedited work. *Alt.:* reconcile only at start, resume and Publish now. | C32 |
| R-N5 | plan | An enabled status binding on an asset that stops being maintained here resumes on its own when it returns (#16's R16-18 shape). *Alt.:* disable it. | C25, row 74 |
| R-N6 | plan | The strings of §5 as proposed (79 and one plural). *Alt.:* the owner rewrites them one by one. | §5 |
| **R-N7 (F-18)** | plan, recorded | **"Publication not verified" is not a card reason** (#114 §3 lists it): the season card shows only readiness reasons; the date block carries verification and the "may still allow starts" lines. *Alt.:* the card line gains a "not yet confirmed in Home Assistant" suffix while the date binding's state is not PUBLISHED or MATCHES. | C29, C33, C36 |
| **R-N8 (Q5)** | plan | **D1 rewrites "reads, and only reads" and the outbound inventory texts in this same branch:** the docs ship with the code that makes them true; #113's "only when it ships" forbids changing them before the feature exists in the tree, which on this branch it does. *Alt.:* hold the wording for the release that carries the feature. | C38, D1 |

**If C1 is refused (F-13) — the fallback.** The production release becomes **two asset FORM schedules**, each
"Season Start service" on its own asset (the stove's, and the boiler's once O-18 gives it a season), each with its
own role, boundary and form; the group "Pre-winter check" stays as it is for the ordinary QUICK tick and carries no
role. What changes: C9 loses the group branch of coverage (asset targets only) and `TWO_ROLES` can then arise only from
a merge; C10 refuses any group target with a new refusal `GROUP_TARGET` (one sentence, replacing P114-4g, P114-15,
P114-21 and P114-22: "A Season Start service must be one asset's own schedule."); C11 drops the `SaveGroup` hook and
both `MEMBER_*` causes (P114-27 goes); rows 11 (group cases), 15 (group cases), 16 and 20 (group case) are replaced by
one row `aGroupTargetIsRefused`; O-2 reverts to #114 point 3 — FORM by default, QUICK only by explicit election on an
asset schedule (P114-5 kept); the merge claim (C19) and A5b's group editor work drop; production step 3 assigns the
two asset roles and step 4 completes the two FORM schedules.

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
    - `'ha_publish|desiredValue|activatedInstallation'` in `C/backup`, `C/merge`, `C/transfer` → 0;
    - `'setExpedited|ForegroundInfo|foregroundServiceType|registerReceiver|registerNetworkCallback'` in `A/hapublish` → 0;
        - **every** `'else ->'` in the files the range adds or modifies is listed and read: none may sit in a `when` over a
      closed set this plan adds or extends (F-20);
    - `'"SEASON_START_STRANDED"'` and `'"SEASON_START_REFUSED"'` in `ApiJson.kt` → 1 each; `'^@mcp\.tool\('` in
      `server.py` → 92; `'^_MIN_HA_PUBLISHING_SCHEMA_VERSION = 22$'` → 1;
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
  trap, §8): `input_select.servicetag_test_equipment_status` with options exactly `UP`, `DOWN`, `DEGRADED`, and
  `input_datetime.servicetag_test_service_done`, date only, seeded `1970-01-01`; and confirms the fixture's non-admin
  user may call `input_select.select_option` and `input_datetime.set_datetime`. A fictional MANUAL "Example Heater"
  on the emulator, a debug build of the merged tip. The host only **reads** through HA's API, but for three named writes: step 1's `input_select.set_options` adding a
  fourth option and its restore, and step 5's reset of the helper (F-23):
  1. validate the `input_select` (passes); enter the season fixture boolean as a status target → `WRONG_DOMAIN`, nothing
     sent; the host's `set_options` adds a fourth option → Check helper → `OPTIONS_MISMATCH`; restore;
  2. enable with no condition → `WAITING_FOR_SOURCE`, the host reads the helper unchanged;
  3. record OPERATIONAL → the host reads `UP`; the block shows `PUBLISHED`;
  4. DEGRADED → `DEGRADED`, DOWN → `DOWN`, each read back by the host; a second DOWN with a new reason sends nothing;
  5. the host sets the helper to `UP` → Publish now → `DOWN` restored, no source change;
  6. validate the date-only `input_datetime` (passes); enter the `input_select` as a date target → `WRONG_DOMAIN`;
  7. assign the role to a FORM schedule, complete it with its details → the host reads the service date;
  8. delete the completion → the host reads `1970-01-01`;
  9. complete again, then Stop publishing → the host reads `1970-01-01` **before** the block reads stopped;
  10. Forget with a date binding enabled → the warning with its count; cancel; disconnect from the fixture's network,
      Forget → the binding's obligation listed UNDELIVERABLE; dismiss.
  Also recorded: what HA answers to a service call naming a missing entity (404 or `200 []`), settling R-N3 in the
  docs. Afterwards: fixture reset, Disconnect, the record in the ledger with placeholders only.
- **The production sequence (owner-gated, after a release carries this; never by an agent without the owner's go):**
  1. **HA first (§8):** the in-season boolean, `service_done` (seeded `1970-01-01`), `season_ended` (seeded the
     commissioning date), the stamp automation; the HA serviced flag keeps its interim writer.
    2. **Re-bind the inbound (C2):** on the production phone, the stove's #16 binding is pointed at the new in-season
     boolean with the shipped "Change entity" (`EditSeasonSyncEntity`); its mode stays FOLLOW; the next read applies
     at most one transition.
  2b. **Give the boiler a season (O-18, F-14).** The production read of 2026-10-22 (the ledger) records the Oil Boiler
     as `YEAR_ROUND` / IN_SEASON, top-level, no children: a group role covering it is refused today (P114-17 inside
     P114-21). Before step 3, per O-18's ruling: link it (Follow) to the **same** in-season boolean as the stove (#16
     allows it, O-18), or give it its own helper, or a CALENDAR season. Linking switches it into MANUAL at today's
     phase through the shipped link (#16 R16-Q-G discloses the IN_SERVICE re-anchor); the group schedule is CONTINUOUS
     and is not re-anchored.
    3. **Assign the role** on the group "Pre-winter check" (C1, if ruled; else the fallback of §6: a FORM "Season Start
     service" schedule on each asset) with
     "Services count from" = that day — **before** any completion (audit headline 10): a completion dated before the
     role day never counts.
  4. **Complete "Pre-winter check"** for the stove and the boiler on or after that day (QUICK, O-2); the cards read
     Ready.
  5. **Enable date publishing** for the stove (and the boiler once its helper exists): validate, confirm; the host
     reads the service date back; the block reads `PUBLISHED`.
    6. **HA gate derivation (§8):** HA's serviced flag is derived from `service_done >= season_ended`; its voice writer
     and only the serviced-flag action of the 1 August reset are removed; `stove_check` is unchanged; Q2 decides
     voice/Assist.
  Optionally, the stove's equipment status to its own `input_select` once HA has a consumer for it.

## 8. The Home Assistant side (not this repository)

The owner's HA configuration, from `ha-inventory.md`, written here with fictional helper names; the two HA-side places
the owner's ruling lets this plan name are `stove_check` and `oil_zone_owner`. ServiceTag ships none of this; D1's
guide carries a generic version.

- **What exists.** The stove has **one** automatic start gate, `stove_check`, which refuses an automatic ON while the
  HA serviced flag is off (`not_serviced`); a person's start ignores it by design, and the appliance's own panel is
  outside HA. The serviced flag today has one ON writer (a voice command) and one OFF writer (an action inside a
  yearly reset automation on 1 August, which also resets several unrelated helpers); nothing ties it to a service or a
  season. The oil boiler has **no** serviced or season check: its one automated writer, `oil_zone_owner`, arms a floor
  year round, and person-class writers bypass it.
- **The in-season boolean for the re-bind (C2; O-17).** A new `input_boolean.example_heater_in_season`, written by one
  HA automation as a **heating-year latch** over the heat-source assignment (the inventory's best operating-window
  signal): ON at the first commit to fuel after 1 August; OFF at the first release to electric on or after 1 May, or
  else at the 1 August reset. A spring release followed by a late cold-snap re-commit therefore never turns it off and
  on again, so neither the phone (no END) nor HA (no stamp) revokes the autumn service. It is **not** re-written at HA
  start: the helper restores its own state, so a restart can never produce an on → off transition. *(O-17's
  alternative: follow the assignment edge for edge, accepting a re-service after a re-commit.)*
- **The `season_ended` stamp (F-11).** A new date-only `input_datetime.example_heater_season_ended`, written by one
  automation whose trigger is the in-season boolean's state changing **`from: "on"` `to: "off"`** only — never
  `unavailable`/`unknown` → `off` (HA start, an integration reload), never at HA start — setting today's date. At
  commissioning it is set explicitly to the commissioning date (a trusted first boundary, never a default or restored
  value). D1's generic guide says the same.
- **`service_done`.** A new date-only `input_datetime.example_heater_service_done`, ServiceTag's from the day publishing
  is enabled (no HA writer competes); until then the owner sets it by hand after a service (the interim, #114 §5).
- **The derived serviced flag (so `stove_check` is unchanged).** The existing HA serviced flag becomes a derivation:
  one automation sets it ON exactly when `service_done != 1970-01-01 AND service_done >= season_ended`, OFF otherwise,
  re-evaluated on either helper's change, at HA start and daily. Its voice ON writer is removed, and **only the
  serviced-flag action** is removed from the 1 August reset automation — that automation stays, with its other resets
  (F-24). `stove_check`, its check order and its refusal text stay. The gate's rule (#114 §5) is then: `released AND
  in_season AND no fault/safety inhibit AND the existing ownership and occupancy rules`, with #113's
  `input_select.example_heater_equipment_status` (`DOWN`) available as the fault input once published.
- **Voice and Assist (Q2, owner — both positions).** *(a), the plan's position pending the owner:* a person's start
  stays outside `stove_check`, as the owner's Stage 2 design has it; the voice reply keeps its caveat sentence ("It
  isn't marked serviced for the season, though."), which now reads the derived flag, so the person is told and decides.
  *(b), #114 §5 as written:* the voice/Assist ON is routed through the same gate (refused with its reason while not
  released), leaving only the appliance's own panel outside HA. Whichever is ruled, D1 states it.
- **The oil boiler: a gap, stated.** Nothing gates an automatic oil arm on a service. The place is `oil_zone_owner`,
  the one automated arming writer: its plan would read the boiler's own released predicate (its own `service_done` /
  `season_ended` pair) before arming, including the freeze branch; the person-class bypasses (holds, the bedroom
  script, the thermostat) stay outside, as the stove's person starts do. This is the owner's HA work and is not
  scheduled by this plan. On the phone the boiler first needs a season (O-18).
- **The freeze override (#114 Rev 3).** An unserviced freeze backup raises one urgent, actionable alert on the house's
  existing actionable pattern — an ntfy message whose view actions open the local action page that posts a
  `local_only` webhook once, handled `mode: queued`, followed by an ntfy with the outcome — with **Start anyway
  (unserviced)** and **Keep off** (no "Keep off" exists yet; the nearest pattern is the existing "Start … anyway"
  button). Start anyway sets an HA-side, one-time, time-limited override for that freeze event only, logged, shown as
  a persistent notification while it lasts, waiving **only** the service check (faults, smoke/CO and ownership rules
  still apply); expiry asks again; stopping uses the appliance's safe sequence. ServiceTag is never told and nothing
  in it changes.
- **The `input_datetime` TODAY trap.** A new `input_datetime` with no `initial:` reads **today**, not unknown or
  1970, and a YAML `initial:` resets a UI or service write at every restart. So: no `initial:` (keep HA's restore
  behaviour), and write `1970-01-01` (or the commissioning date) explicitly with `input_datetime.set_datetime` right
  after creating the helper — before any gate reads it. A time trigger on an unknown `input_datetime` never fires.
- **Commissioning order.** (1) Create the three helpers and seed them explicitly; (2) the stamp automation (on → off
  only); (3) the in-season latch, then the phone's re-bind (§7 step 2); (4) the derivation written but **disabled**,
  the interim writer still active; (5) the role, the completion and date publishing on the phone, verified by reading
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

**Rev 2's own citation fixes (the review's "citations verified wrong"):** `T/VersionAgreementTest.kt`'s format
assertion is `:88` (A3's row; `:87` is the schema); `candidate` is `C/usecase/SaveSchedule.kt:153-180` (C12, c8);
`MaintenanceScheduleEntity` spans `A/data/room/entities/MaintenanceEntities.kt:95-155` (C4); the `OUTBOUND_TARGET`
arms are `A/ui/asset/SeasonSyncStrings.kt:161-166` and `A/ui/asset/LinkSeasonSyncViewModel.kt:290-296` (not
`LinkSeasonSyncSheet.kt`) and the `SaveHaConnectionRefusal` arm is `A/ui/homeassistant/HomeAssistantViewModel.kt:458-462`
(C26, C28, §4 B3); and c13 above.
