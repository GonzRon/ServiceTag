# ServiceTag 1.4 — archaeology for #14, #60 and #61 (read-only)

**Date:** 2026-09-24 · **Base:** `master` at `76135a9` (the 1.3.0 release plus the #62 brief) · **Status:** evidence for the 1.4 spec; decides nothing.

Evidence for one programme: operating seasons with calendar **and** manual activation (#14), maintenance service policy (#60), operational condition plus derived health (#61). Choices are offered as candidates; §I lists what needs the owner.

Shorthand: `core/…/` = `core/src/main/kotlin/com/loosecannon/servicetag/core/`, `app/…/` = `app/src/main/kotlin/com/loosecannon/servicetag/`, `mcp/` = `tools/servicetag-mcp/src/servicetag_mcp/`; "spec 1.2" = `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`; D4/D5/D12 = `docs/design/04-…`, `05-…`, `12-…`. Private Stage-B records appear only by fixture role.

---

## A. The current architecture, as found

### A.1 Schema 7 and the migration chain

`AppDatabase` is version 7 with 19 entities (`app/…/data/room/AppDatabase.kt:40-60`). These tables matter to this programme (from `app/schemas/…/7.json`):

| Table | What it holds that 1.4 touches |
|---|---|
| `asset` | `status` (ACTIVE/ARCHIVED), `retired_on`, `in_service_on`, `parent_asset_id`, **`season_start_mmdd`, `season_end_mmdd`** (`entities/AssetEntity.kt:59-60`) |
| `maintenance_schedule` | rule columns, `lead_days`, **`season_behavior`, `season_reentry`, `season_reentry_offset_days`** (`entities/MaintenanceEntities.kt:142-144`), `status`, `postponed_due_on`, `updated_at` |
| `schedule_state` | derived; **`season_active`** (`MaintenanceEntities.kt:238`), `computed_due_on`, `effective_due_on`, termination facts |
| `occurrence_closure` | immutable, no `updated_at`; unique `(schedule_id, occurrence_on)` |
| `asset_event` | journal; `kind` includes `SEASON_START`, `SEASON_END`, `INCIDENT`, `REPLACEMENT`; completion link `(schedule_id, occurrence_on, asset_id)` unique |
| `schedule_local_delivery` | device-local snooze and nonce; never exported |
| `maintenance_group`, `maintenance_group_member` | group rounds; append-only membership windows |
| `external_link` | the 2.6 tombstone: kept, never read, never re-purposed (`Migrations.kt:346-347`) |

There is **no** table for condition, health, season activation, service policy or reminder health; #27's `HealthFinding` (`core/…/reminders/ReminderPort.kt:185-190`) is computed on demand by `ReminderHealthCheck.run()` (`app/…/reminders/ReminderHealthCheck.kt:168-200`).

Migrations 1→7 live at `Migrations.kt:17, 109, 176, 211, 263, 357`; 5→6 and 6→7 are additive, 2→3 and 3→4 used the 12-step recreate. With `minSdk` 26 on platform SQLite (`app/build.gradle.kts:41, 114-117`), `DROP COLUMN` (3.35) and `RENAME COLUMN` (3.25) cannot be relied on: **retiring a schedule column means recreating `maintenance_schedule`**, which five tables reference.

### A.2 The scheduling engine

- **Basis.** `TimeBasis { FIXED, COMPLETION }` (`core/…/model/Maintenance.kt:61`). "Meter" is not a basis. It is an independent side (`meterDefinitionId`, `meterInterval`, `anchorMeter`, `meterLead`), folded with the time side worst-of (`schedule/ScheduleStatus.kt:114-128`).
- **Recompute.** `ScheduleRecompute.rebuild` is pure over (schedule, events, closures, membership, `T`, zone, season window) and the only writer of `schedule_state` (`schedule/ScheduleRecompute.kt:65-121`; invariants 15-17, spec 1.2:1098-1100). "Advance" is always "insert a row, then rebuild".
- **Due date.** FIXED advances to the first series date after `max(D, E)`, one occurrence however late (`ScheduleRecompute.kt:243-267`, invariant 13). A never-terminated FIXED schedule is pinned from `max(anchor, updated_at's date)` (`:289-290`, D-27), so pause, postpone and closure leave `updated_at` alone (`usecase/PauseSchedule.kt:13-18`, `PostponeSchedule.kt:24-27`, `CloseRound.kt:24-25`).
- **Season.** `seasonActive` is `true` for IGNORE and otherwise `Season.inSeason(window, T)` (`ScheduleRecompute.kt:307-320`, `model/Season.kt:27-38`). `statusOf` answers PAUSED, then INACTIVE_SEASON, then NO_DATA, then the fold (`ScheduleStatus.kt:110-129`). Group targets get `season = null` and so are never seasonal (`usecase/RecomputeSchedules.kt:176-185`). `FOLLOW_ASSET` on a group is refused (`usecase/ScheduleCommands.kt:282-284`).
- **Finding A-1: the season never reaches the due date.** `computedDueOn` does not read the window, and `seasonReentry*` is never read (invariant 26, spec 1.2:1110, enforced by `core/src/test/…/schedule/ScheduleStructuralTest.kt:71-94`). A FOLLOW_ASSET weekly schedule last done in April is still due in April when the season reopens in October, so it reads **OVERDUE by six months on its first in-season day**. It is one occurrence, never a backlog, but #14 AC 4 ("DUE on Oct 15") fails today. The Stage-B draft records the same consequence for a seasonal monthly check. Any maintenance-overdue health keyed on this date would read critical on day one.
- **Statuses.** `DueStatus { OK, DUE_SOON, DUE, OVERDUE, INACTIVE_SEASON, PAUSED, NO_DATA }` (`ScheduleStatus.kt:26-46`). Only DUE_SOON, DUE and OVERDUE notify. The ratified words are OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE (`reminders/BuildReminderSubjects.kt:193-202`).
- **Windows and rounds.** `occurrenceWindowOpensOn = effectiveDueOn − leadDays` (`usecase/GroupCommands.kt:158-159`) gates `CloseRound`, which is group-only, immutable and claims no work (`CloseRound.kt:15-49`, invariant 36).
- **Occurrence keys** are stamped from `computedDueOn`, never from the postponed date (`model/Journal.kt:66-72`). A completion's event kind is its profile's kind (`usecase/CompleteSchedule.kt:81`).

### A.3 Reminders

`BuildReminderSubjects` maps ARCHIVED → `Withdrawn`, PAUSED → `Parked(null)`, out of season → `Parked(seasonReentryOn)` (`BuildReminderSubjects.kt:121-130`), and **recomputes the next season start itself from the asset's `MM-DD`** (`:138-143, 224-231`) — a second season derivation outside the engine, which #60 AC 7 forbids once a policy exists. `RuleFacts.seasonal` feeds the content hash (`:152`, `reminders/ContentHash.kt:64`). The port shape `Parked(reentryOn: LocalDate?)` is already provider-neutral (`ReminderPort.kt:49-65`).

### A.4 The dashboard attention model

Every surface reads one projection, `DueItem`, **one per schedule** (`app/…/ui/maintenance/DueReadModel.kt:66-104`). The sections are `ATTENTION, UPCOMING, CURRENT, OUT_OF_SEASON`, and PAUSED gets none (`:33, 301-309`). Assets with nothing drawn fall into a plain `DashboardRow` list (`ui/dashboard/DashboardViewModel.kt:44-48, 80-82`). **No row kind exists for an asset-level fact.** A DOWN asset with no due schedule lands in the quiet asset list, not in ATTENTION.

### A.5 Scan sheet (#50) and placement labels (#49)

A scan reaches the maintenance sheet only when `ScanSheetOffer.has(assetId)` (`ui/scan/ScanViewModels.kt:235`, `ui/maintenance/MaintenanceSheetViewModel.kt:114-125`): DUE, OVERDUE or repairable NO_DATA on a reminders-enabled schedule, DUE_SOON only as a passenger (`:143-166`); otherwise it opens the asset detail. "Maintenance", "Open asset", "Not now", "Review maintenance" and "Tag placement" are ratified (`:34-55`). **A DOWN asset with nothing due never reaches the sheet.**

### A.6 Journal, lifecycle, and three meanings of "in service"

Events are editable and deletable (`docs/api/v1.md:140-141`). `EventKind` has `SEASON_START`/`SEASON_END` (`model/Journal.kt:21-22`), seeded as quick actions by the power-equipment template (`journal/SeedTemplates.kt:94-103`). "In service" already means three things: the commissioning date `Asset.inServiceOn` ("In service date", `ui/asset/AssetEditScreen.kt:191`); `targetInService`, neither retired nor archived (`ScheduleStatus.kt:85-92`); and #14's manual "start/end of service". "Out of service" would also collide with DOWN. The lifecycle has a retirement **date** but no instant (D5:446-463).

### A.7 Backup, merge, API

Backup `FORMAT_VERSION = 7` (`core/…/backup/BackupCodec.kt:55`); a newer archive is refused before any row is read (`:175-176`). `BackupData` has eleven arrays, `externalLinks` included (`backup/BackupFormat.kt:350-366`). The planner has eleven `MergeTable` members in write order (`merge/MergePlan.kt:33-36`), **no UPDATE verdict** (`:41-52`), and compares every backup-format field, timestamps included (`merge/MergePlanner.kt:80-84`). Spec 1.2 §3.4 (:724-752) is the precedent: an unchanged schedule re-imports IDENTICAL because advancing it only **inserts** rows.

The API decodes strictly (`ignoreUnknownKeys = false`, `app/…/api/ApiJson.kt:57-65`), omitted fields take defaults (omitted `seasonBehavior` → IGNORE, `api/MaintenanceDtos.kt:254`), and schedule PATCH is a full replace (`docs/api/v1.md:164`).

---

## B. The reserved 1.2 season fields: verdicts

| Field (where) | Verdict | Why |
|---|---|---|
| `asset.season_start_mmdd` / `season_end_mmdd` (`Asset.kt:28-29`, `AssetEntity.kt:59-60`, `BackupFormat.kt:101-102`, `ApiDtos.kt:217-218`, MCP `server.py:314-315, 386-387, 493-494`, D4:176) | **Survive**, as the calendar mode's boundaries. Add an explicit activation mode beside them. | They answer #14's own question, "when is this asset normally in use". But "both null" currently means year-round, so a manual asset needs a mode discriminator rather than a sentinel. |
| `maintenance_schedule.season_behavior` + `SeasonBehavior { FOLLOW_ASSET, IGNORE }` (`Maintenance.kt:64`, `MaintenanceEntities.kt:142`, `BackupFormat.kt:284`, `MaintenanceDtos.kt:254`, MCP `server.py:1190, 1288, 1378-1380`) | **Replace**: subsumed by the service-policy enum (FOLLOW_ASSET → in-service/re-entry, IGNORE → continuous) | It answers only "is this gated by the season"; #60's families decide gating **and** timing, so a separate axis could disagree with them. IGNORE also lumps year-round work with startup/shutdown tasks. |
| `season_reentry` TEXT (`MaintenanceEntities.kt:143`, `BackupFormat.kt:285`, MCP `server.py:1191, 1289, 1381`) | **Replace** | **Two contradictory published meanings**: enum `AT_START \| RESUME_CLAMPED` (D4:381) versus "`seasonReentry` is `MM-DD`" (`docs/api/v1.md:326`, MCP `server.py:1207`). Never validated (`ScheduleCommands.kt:240-301`), never read, and a subset of #60's families. Old archives must still decode: map recognised names, treat anything else as null — refusing them would be MAJOR (`docs/versioning.md:21`). |
| `season_reentry_offset_days` INTEGER (`MaintenanceEntities.kt:144`) | **Rename/generalise** into the policy's offset | Both AT_START "+N days" and pre-season "N days before start" need a day count relative to a boundary. One signed offset serves both. |
| `schedule_state.season_active` (`MaintenanceEntities.kt:238`), `ScheduleStateDto.seasonActive` (`MaintenanceDtos.kt:75`) | **Replace in the table, survive on the wire** as a derived compatibility boolean | The table is derived and never exported (invariant 64), so it can change freely. It needs a phase (active, dormant, blackout, pre-season) plus an actionable date. API clients may already branch on `seasonActive`. |
| `DueStatus.INACTIVE_SEASON`, the word "OUT OF SEASON", `AttentionSection.OUT_OF_SEASON` (`ScheduleStatus.kt:27`, `DueReadModel.kt:33`, D12:280) | **Survive** | #14 keeps INACTIVE_SEASON explicitly, and a manually ended hot tub is exactly this. It must **not** stand for a blackout deferral: in a blackout the asset may be in use, so the word would be false (question I-8). |
| `EventKind.SEASON_START` / `SEASON_END` (`Journal.kt:21-22`, `docs/api/v1.md:307`) | **Survive as journal vocabulary; never the activation fact** | Completions take the profile's kind (`CompleteSchedule.kt:81`), and the development phone already has yearly startup/shutdown tasks with such profiles, so their completions would toggle seasons. Events are also editable, deletable and user-kinded. Renaming the values breaks format ≤7 decoding (`Journal.kt:38-44`). |
| 422 `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET` and its domain problem (`ScheduleCommands.kt:91-92`, `docs/api/v1.md:438`) | **Survive as a code, restated** as "a season-dependent policy on a group target" (if I-11 keeps groups non-seasonal) | Clients branch on codes, and the rule's reason, that a group has no window, is unchanged. |
| `SubjectState.Parked(reentryOn)` (`ReminderPort.kt:65`) | **Survive** | It is provider-neutral: a date or null. Its **input** must move to the engine's policy result, not `BuildReminderSubjects.seasonReentryOn`. A manual asset parks with `null`, as a pause does. |
| Ratified editor and asset strings: "Out of season", "Pause with the asset's season", "Remind me year round" (`ScheduleEditScreen.kt:78-80`); "Year-round", "Off means this asset is only in use between two dates each year.", "Season starts", "Season ends" (`AssetEditScreen.kt:247-268`) | **Replace or re-ratify** | "Pause" collides with the PAUSED lifecycle, and the helper sentence is false for a manual season. #60 asks for "When should this maintenance be done?" |
| Invariant 26 and its structural test (`ScheduleStructuralTest.kt:71-94`), and D5 §6's "nothing is stored at season end" (D5:228) plus MANUAL_STARTUP "deferred" (D5:219) | **Retire explicitly** in the 1.4 spec | Otherwise the shipped grep proof fails the first time the engine reads the policy, and D5 contradicts the manual activation facts. |

---

## C. The five fixtures: today and the new model

Stage-B loaded 4 groups and 43 schedules (37 FIXED, 6 COMPLETION, no meter rule). **All 43 are `IGNORE`, none carries a re-entry value, and no Stage-A asset carries a season window.** The reserved fields are therefore empty across real data, and the production phone predates the maintenance schema.

**C.1 Master-bedroom UPS: DOWN, battery replacement pending.**
*Today:* the asset has REPLACEMENT and INSPECTION quick actions. The one-off replacement was excluded from Stage B (not a recurrence) and recorded as the #61 gap; the self-test has no cadence. There is no one-off work item (every schedule needs a rule, `ScheduleCommands.kt:243-245`), so only an INCIDENT event or a note can say anything. A scan opens the asset detail (A.5); the dashboard shows a quiet asset.
*New model:* an immutable DOWN transition with reason and date; derived current condition; an attention row with no schedule behind it; scan routing that admits condition. The eventual REPLACEMENT event resets a configured battery-age baseline, then "Mark operational?" is offered, never assumed, and the DOWN row stays in history.

**C.2 UPS battery-pack aging.**
*Today:* a battery pack is its own asset. It has one COMPLETION two-year "load test and battery replacement" FORM schedule whose profile is REPLACEMENT-kind. D4:195-197 intends battery age to be read as `last_completed_on` of such a schedule. *Hazard:* this schedule combines a test with a replacement, so a load test logged as its completion would reset "battery age" without any battery having changed.
*New model:* a named health subject ("battery age") with an expected-life rule. Its baseline is an explicit replacement fact (a REPLACEMENT event, or a configured profile), not "any completion". It must be bindable later to a #47 `InstalledAssembly.installed_on` (§D.5).

**C.3 Mower and generator: missed engine service.**
*Today:* the mower has a yearly FIXED FORM oil change anchored in late autumn, right after its use season, tagged `seasonal-example`. Other engine equipment carries two-year FIXED oil changes tagged `meter-candidate`, with no meter rule loaded. The generator has only a yearly FIXED test run (INSPECTION kind) and no engine-service schedule. OVERDUE is the only signal of neglect.
*New model:* an "engine oil service" subject linked to the oil-change schedule. It stays nominal through a grace period, then passes through warning and critical bands, and a completion re-baselines it. The generator fixture needs a fictional engine-service schedule, because the real data has none.

**C.4 Seasonal equipment: winter snowblower, summer mower.**
*Today:* the snowblowers carry two-year FIXED FORM oil changes anchored in late autumn or early winter, 14-day leads, tagged `pre-season-example`. No asset has a window, so a raw due inside winter is simply due in winter. Startup/shutdown tasks on other seasonal equipment are FIXED yearly IGNORE schedules with SEASON_START/SEASON_END-kind quick actions, as D5:223-226 intends. One yearly pre-season check is **group-targeted** and cannot follow a season at all (A.2).
*New model:* calendar windows (the snowblower's wraps the year); a pre-season policy that makes work whose raw due falls in the coming season or a winter blackout actionable before the season; an asset-level blackout for the owner's "no routine winter maintenance". Missed pre-service work stays visible; season start is never completion.

**C.5 Hot-tub water care: maintained medium, manual season.**
*Today:* one weekly FIXED FORM schedule (TREATMENT-kind profile), 1-day lead, IGNORE, anchored on a weekend day; the source's time of day gave way to the single digest hour. No window and no way to start or stop a season. Pausing is the only freeze, and on resume the FIXED series is months behind and reads OVERDUE (`PauseSchedule.kt:13-15`). Editing the anchor instead moves the pin floor, clears a postponement and abandons a group round. The template's water-chemistry definitions already carry ranges (`SeedTemplates.kt:48-52`), a future health input.
*New model:* MANUAL mode with immutable START/END facts; an in-service policy that re-enters at START plus an offset; a "water care" subject driven by the weekly schedule, its clock frozen while the season is out (§H.1).

---

## D. Schema 8 and backup format 8

**D.1 The governing rule** (spec 1.2 §3.4): **a season start or end, a condition transition and a health baseline are each an INSERT of a new immutable row, never an update to `asset` or `maintenance_schedule`.** Otherwise every toggle makes the asset row CONTENT_DIFFERS between phones and, with no UPDATE verdict, blocks the whole archive. A materialised "current condition" or "season active" column on `asset` breaks this.

**D.2 Candidate tables** (names are placeholders):

| Table | Kind | Shape notes | Exported / merged |
|---|---|---|---|
| `asset_season_activation` | immutable fact (closure precedent) | `id, asset_id, action START\|END, on, created_at`; optional `event_id` | yes; its own rows, never nested in the asset |
| `asset_condition` | immutable fact | `id, asset_id, condition, occurred_on, occurred_time?, tz_id, reason, event_id?, created_at`; no `updated_at` | yes |
| `health_subject` (+ child `health_source` rows, as group members) | configuration aggregate | subject: `asset_id, name, kind (ASSET\|MEDIUM\|later COMPONENT), weight, sort_order, archived_at`; source: `kind AGE\|MAINTENANCE_OVERDUE, schedule_id?, baseline rule, expected life, grace, warning, critical` | yes; a divergence is CONTENT_DIFFERS on the subject |
| asset columns: `season_mode`, blackout window, aggregation policy | configuration | additive `ADD COLUMN` | yes (asset row) |
| schedule policy columns | configuration | policy enum + signed offset | yes (schedule row) |
| `schedule_state` additions (phase, actionable date); new `health_state` | derived | rebuilt by the recompute | **never** (invariant 64 precedent) |

Baselines are best kept as canonical **events** (REPLACEMENT, completions), not a separate baseline table. This keeps "replacement resets without erasing history" true by construction.

**D.3 Migration 7→8.** Mostly additive (`CREATE TABLE`s, `ADD COLUMN` on `asset` and `maintenance_schedule`), the 5→6/6→7 shape. The retired schedule columns force a choice (I-24): a 12-step recreate of `maintenance_schedule` (minSdk 26 rules out DROP COLUMN; five tables reference it) or unread physical tombstones. Either way existing values are mapped — losslessly on real data, where every row is IGNORE with a null re-entry.

**D.4 Format 8.** New arrays default to `emptyList()`, so format ≤7 decodes; old `seasonBehavior`/`seasonReentry*` map leniently on decode (§B); manifest `counts` gain keys. Old builds refuse format 8 before reading a row (`BackupCodec.kt:175-176`), so 1.4.0 is a MINOR (`docs/versioning.md:21`). API import accepts formats 1–8 (`docs/api/v1.md:143`).

**D.5 Merge tables 12 and up.** Append after `REFERENCES`, the 1.3 "last is the smaller diff" precedent (`MergePlan.kt:30-31`): `SEASON_ACTIVATIONS` (asset, optional event), `CONDITIONS` (asset, optional event), `HEALTH_SUBJECTS` (asset, schedule). Identity is the row id. A second identity is wrong for conditions (two transitions a day are legitimate). Two phones recording the same START give two rows, harmless under a latest-row state machine; an "equivalent local row" arm would be reachable only from replayed archives, as for references (`MergePlan.kt:193-196`). OWNER_NOT_AVAILABLE covers a missing asset or event; new reasons are needed only for archive-internal guards.

**D.6 Tombstones.** `external_link`, `ExternalLinkDto` and the `externalLinks` array stay byte-for-byte: not bumped, not re-purposed, not reused for condition or health. `MergeTable.LINKS` keeps its place.

**D.7 #44 and #47 seams.** #44 needs these tables to remain **append-only history**, so its future UPDATE or field-aware merge applies to configuration rows only, and asset-ID remapping must rewrite `asset_id` in the three new tables. #47 introduces `InstalledAssembly` and `InstalledComponentInstance` with `installed_on`/`removed_on`. A health subject needs a nullable binding point (a subject `kind` plus a reserved nullable target id) and must not invent assembly rows now. #15 touches the programme only through consumables on completion events. No stock read feeds health in 1.4.

---

## E. API and MCP implications

**Conventions to keep.** `UPPER_SNAKE` codes. **422** means the body describes something that cannot exist, **409** means the store refuses the state (`docs/api/v1.md:425`). Immutable history gets no DELETE or PATCH, and "What has no endpoint" is extended (`:377-392`). Only `/v1/import-merge/apply`'s 409 carries a report.

**Routes the programme needs:**

- `POST /v1/assets/{id}/season` `{action, on}` → 201 with the row and derived state; candidate 409 `SEASON_NOT_MANUAL`, 409 `SEASON_ALREADY_STARTED`/`…_ENDED` (I-4), 422 `BAD_DATE`; `on` bounded like `closedOn` (not future). `GET` of the same path: mode, window, history, phase.
- The schedule command gains policy fields, with 422s for impossible combinations (pre-season on a year-round asset or a group target).
- `POST /v1/assets/{id}/condition` → 201; `GET /v1/assets/{id}/conditions` for history. Condition is **not** an asset-command field: PATCH is a full replace (`:128`) and would bypass the audit (#61 AC 9).
- `GET /v1/assets/{id}/health`, derived like a status (invariant 18): per-subject value, band and driver, the aggregate, the policy, `computedForOn`. Health configuration gets schedule-style CRUD.
- `/v1/due` promises "every field present" per **schedule** item (`:226-233`); asset rows fit a new `/v1/attention` better than a union bolted onto it. `/v1/status` reports format 8.

**Version-skew hazard (a finding).** The MCP's `update_schedule` sends an explicit list of known fields (`mcp/server.py:1359-1395`). The app rejects unknown keys and defaults omitted ones. So a 1.3 MCP against a 1.4 app **silently resets the new policy fields on any edit**, the same bug class the 1.1.0 review found in Task 4. In the other direction, a 1.4 app that drops `seasonBehavior` answers every 1.3 client's schedule write with 400. The same applies to `update_asset` and a new `season_mode`. The spec must choose between lockstep release, aliasing and an explicit retirement code (question I-23). `_SCHEDULE_NULLABLE_CLEARABLE` (`server.py:1250-1255`) and the docstring's "`season_reentry` is `MM-DD`" (`:1207, 1217`) change with it.

**New MCP tools:** start/end season, record condition, list conditions, get health, save or archive a health subject. Each append-only tool writes one row and has no overlay, as `close_round` does (`docs/api/v1.md:280`).

---

## F. UI implications and the string budget

| Surface | What appears |
|---|---|
| **Asset detail** | Condition badge on the identity plate, a fourth independent fact beside retired/archived/out of season (`AssetDetailScreen.kt:613-632`); condition history; health with contributors ("72 — Water care 55, Pump 100"); for a manual asset, START/END with confirmation. |
| **Dashboard** | An asset-level attention row kind: DOWN prominent with no schedule behind it, DEGRADED distinct from DOWN and from due; health warning/critical rows are candidates; a condition filter beside categories (`DashboardFilters.kt:48-51`). |
| **Maintenance tab** | Schedules show policy and phase. The "Reminders" row (`MaintenanceScreen.kt:41, 163`) is reminder health; do not rename it "Health". |
| **Scan sheet** | Condition before maintenance; `ScanSheetOffer`/`scanSheetItems` must admit a DOWN or DEGRADED asset with nothing due (A.5) while one predicate still decides routing and contents; change only by explicit tap; #49's caption unchanged. |
| **Schedule editor** | "When should this maintenance be done?" (before the season · when it starts · whenever due), offset, blackout note; warns when a seasonal anchor lies outside the window (D5:225); links a health subject. |
| **Asset editor** | Season mode (year-round / calendar / manual), blackout window, health subjects and aggregation policy. |

**Grayscale.** D12 requires position, wording, icon and colour, with colour as reinforcement only (D12:270-272). **D12 makes the normal state cool blue, not green** (D12:1001), which contradicts #61's "green → yellow → red" (question I-15). D12 has no treatment yet for DOWN, DEGRADED or the health bands.

**String budget.** Every sentence is ratified before execution (planning policy "Unchanged"; spec 1.2 §9.1); the 1.2 gate ratified 44. **Estimate for 1.4: 55–75 new strings plus 7 to re-ratify** (§B): condition words and actions ~12, season mode/actions/history ~12, policy/offset/blackout/warnings ~10, health bands, driver templates, configuration and aggregation ~18, dashboard/sheet/filter ~8, API refusal sentences ~8.

---

## G. Concept separation

| Concept | Question it answers | Where it lives today | Where it would live |
|---|---|---|---|
| Asset lifecycle | Is this unit still ours or in use at all? | `asset.status` ACTIVE/ARCHIVED and `retired_on`; `targetInService` (`ScheduleStatus.kt:85-92`); no lifecycle instant | Unchanged. Never set by condition or health. |
| Commissioning date | When was it first put in service? | `asset.in_service_on`, "In service date" | Unchanged. Keep the word "service" out of season UI (question I-18). |
| Operational condition | Can I rely on it right now? | nowhere: INCIDENT events and notes only | the `asset_condition` history, with the current value derived |
| Derived health | How deteriorated or at-risk is it, and why? | nowhere; measurement LOW/HIGH per reading only | health configuration rows plus a derived `health_state` |
| Maintenance schedule state | What work is due? | `DueStatus`, computed at read; `schedule_state` | Same, with the status taken from the **policy-derived actionable date** |
| Operating season | When is it normally in use? | asset `MM-DD` plus `Season.inSeason`; `season_active` | Asset mode + window + activation facts. The phase is derived. |
| Maintenance service policy | When should this work be done relative to the season? | `season_behavior` only; re-entry reserved and unread | Schedule policy + offset; asset blackout window |
| Reminder health | Is the reminder mechanism working? | `HealthFinding`, computed on demand; "Reminders" row; code named `Health*` | Unchanged. Rename the code namespace (question I-19). |

**Rules to carry forward.** Nothing derives condition; health never changes it (#61 AC 14); condition never pauses a schedule. The policy moves only the **actionable** date — **the occurrence key stays `computedDueOn`**, or the idempotence index and closures break (A.2).

---

## H. The critical interaction: what starts the health-degradation clock

**Terms.** *Raw due* = the recurrence's `computedDueOn`. *Actionable due* = the raw date after season, re-entry, pre-season and blackout policy, plus any postponement. A maintenance-overdue source degrades once today passes actionable due + grace; #61 AC 24 already rules out the raw date as the start. Candidate rules:

**H.1 Manually frozen hot-tub programme** (END may be recorded while an item is open):
- **H.1a Freeze-and-carry:** lateness accrues only while started; frozen at END, resumed after START.
- **H.1b Reset at activation:** START re-baselines to nominal; first actionable due = START + offset; pre-END lateness is history.
- **H.1c Not tracked while out:** no value (not "nominal") between END and START, then H.1a or H.1b.
- *Leaning:* H.1c + H.1b for a maintained medium (renewed at season change); H.1a for hardware.

**H.2 Pre-service snowblower** (calendar winter season plus a winter blackout):
- **H.2a** Clock starts at the pre-service deadline (season start, or blackout start if earlier, minus any margin), runs through season and blackout while reminders are suppressed, and entering service never resets it (#61 AC 26).
- **H.2b** As H.2a but capped at warning until the asset is in season — weaker than AC 26's intent.
- **Pull rule:** (i) any raw due inside the coming season or the blackout, or (ii) only inside the season. A raw due after the season is never pulled.

**H.3 Blackout** (routine work suppressed while the asset may be in use):
- **H.3a Defer:** actionable = blackout end; clock starts there.
- **H.3b Pull earlier:** actionable = blackout start or the pre-season window; clock starts there.
- **H.3c Already late when the blackout opens:** notifications suppressed, status stays OVERDUE, health keeps degrading (versus parking it as deferred).
- *Leaning:* H.3a/H.3b per policy, H.3c always — #60's "must remain visible".

**H.4 Calendar re-entry:** `AT_START` (+ offset): actionable = season start (this cycle) + offset when the raw date is outside the season or earlier; `RESUME_CLAMPED`: actionable = max(raw, season start). The clock starts at actionable + grace, never at the pre-dormancy raw date (Finding A-1).

**Cross-cutting candidates:**
- **Lateness measure:** (a) wall-clock days since actionable due, or (b) days counted only while the programme is active. (b) is deterministic from windows and activation rows and keeps `rebuild` pure (invariant 16) if those rows join its inputs.
- **A closure never re-baselines health** — it claims no work (invariant 36).
- **Postponement moves the clock** (canonical, explicit); **a snooze never does** (device-local).
- **The meter side has no date:** a blackout can suppress its notifications but cannot defer it; its clock needs its own rule, e.g. from the first reading at or past the threshold.
- **Age sources** ignore season and policy: the clock is the baseline date.

---

## I. Owner questions (one line each, with a recommendation)

1. **Season mode:** add an asset-level `season_mode` (year-round / calendar / manual)? *Rec: yes; keep the `MM-DD` columns for calendar mode.*
2. **Activation facts:** a dedicated immutable table, or SEASON_START/END journal events? *Rec: a dedicated table, optionally linked to an event (§B).*
3. **Switching to manual:** what state does the asset start in? *Rec: the switch must state the current state, writing the first row, so there is no silent default.*
4. **A repeated START while already started:** refuse with 409, or accept idempotently? *Rec: 409, as closures do.*
5. **Policy shape:** one schedule enum (continuous / in-service with re-entry / pre-season) replacing `season_behavior` and the re-entry fields? *Rec: yes; map FOLLOW_ASSET to in-service AT_START offset 0 (the D5 §10.4 example).*
6. **Keep both re-entry variants,** AT_START (+ offset) and RESUME_CLAMPED? *Rec: yes; D5 §10.4 shows both are needed.*
7. **Blackout scope:** asset-level, schedule-level or global? *Rec: asset-level; an indoor UPS has no winter blackout.*
8. **Status word for work deferred by a blackout:** a new derived status, or reuse OUT OF SEASON? *Rec: a new status; the asset may be in use.*
9. **Does the blackout apply to continuous schedules** (startup and shutdown tasks fall inside it by design)? *Rec: no.*
10. **Pre-season margin:** reuse `lead_days` or a separate signed offset? *Rec: a separate offset; the lead stays the DUE SOON window.*
11. **Group targets:** policies on groups, given that a Stage-B pre-season check is group-targeted? *Rec: not in 1.4; groups stay continuous.*
12. **Hot-tub health across END and START:** H.1a, H.1b or H.1c? *Rec: H.1c + H.1b for a maintained medium.*
13. **Pre-service clock:** H.2a or H.2b, and pull rule (i) or (ii)? *Rec: H.2a with (i).*
14. **Blackout clock:** defer or pull earlier, and does H.3c always hold? *Rec: per-policy choice; H.3c always.*
15. **Health colours:** keep D12's blue-normal palette or adopt green → yellow → red? *Rec: keep D12; bands carry an icon, a word and a position.*
16. **Representation:** a 0–100 score with three stored band thresholds, or categories only? *Rec: a score with bands.*
17. **Default aggregation:** worst subject or primary subject? *Rec: worst-limiting, and primary when there is only one subject; always list contributors.*
18. **Words:** band names, avoiding "healthy"; and season actions avoiding "in service"/"out of service" (A.6)? *Rec: ratify a fresh set; no "service" in season UI.*
19. **Code naming:** rename the reminder-health types (`HealthScreen`, `HealthFinding`, `Severity`) so they don't collide with asset health? *Rec: yes, to `ReminderHealth*`.*
20. **The battery-age baseline:** a REPLACEMENT event, or any completion of the linked schedule? *Rec: an explicit replacement fact (C.2).*
21. **"Replacement pending":** free-text condition reason, or a structured one-off work item? *Rec: reason text plus an optional event link; no one-off schedule in 1.4.*
22. **Condition history:** immutable, with correction by a new transition, and no stored UNKNOWN? *Rec: yes, yes, and absence means "not recorded".*
23. **API compatibility for retired fields:** lockstep app + MCP release with a retirement code, or accept old names as aliases? *Rec: lockstep, and a 422 naming the replacement field; no silent aliasing.*
24. **Retired schedule columns:** recreate `maintenance_schedule` to drop them, or keep them as unread physical tombstones? *Rec: recreate; the documented meanings contradict each other.*
25. **Derived health in archives:** export it, or recompute only? *Rec: recompute only (invariant 64 precedent); export configuration and inputs.*
26. **Routing:** should a DOWN or DEGRADED asset route a scan to the sheet? *Rec: yes, through the same single predicate.*
27. **Dashboard placement:** DOWN at the top of ATTENTION, and DEGRADED where? *Rec: DOWN first in ATTENTION; DEGRADED in ATTENTION after due items.*
28. **Notifications:** do condition or health produce notifications in 1.4? *Rec: no; display only.*
29. **Linked repair completion:** offer "Mark operational?" after it? *Rec: offer, never auto-apply.*

---

## J. What could not be determined

- **The development phone's asset rows.** Hot tub, snowblowers, mower and battery pack came from the production export, not Stage-A; their windows and parentage (is the battery pack a child of its UPS?) need a phone read this pass could not do. Stage-A has neither, and every Stage-B schedule is IGNORE.
- **Whether any API client ever wrote a non-null `seasonReentry`.** Stage-B wrote none and production predates the schedule tables; other callers cannot be ruled out.
- **The owner's blackout dates, pre-season margins, grace periods and band thresholds** — recorded nowhere.
- **Todoist `PARKED` behaviour (#9)** — not built; only the local provider exists.
- **Read-time versus materialised health cost** at the owner's scale — untested; `schedule_state` suggests materialising is cheap.
- **A D12 treatment for DOWN, DEGRADED and health bands** — none exists; needed before ratification.
- **The #47 schema** — issue text only, so §D.7's binding point follows intent, not tables.
