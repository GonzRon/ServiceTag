# #125 — a schedule can follow its own Home Assistant season switch: plan (rev 1, 2026-10-26)

> **Rev 1 (2026-10-26).** #125 joined ServiceTag **1.9.0** (owner ruling 2026-10-26) beside #113, #114, #116, #119,
> #121, #123 and #124. It is its own plan, with this structure: the #116 plan's. The controller's decisions of
> 2026-10-26 bind it: a schedule gets its own optional season source; that season is MANUAL by construction and keeps
> its own activation history; every due rule of that schedule reads it; readiness and publishing stay on the asset's
> season; #16's machinery is reused, scoped to the schedule; the persistence rides this release's schema 22 and
> format 21 steps; refusals and strands follow the shipped patterns, with no silent fallback; there is API/MCP parity
> for link, unlink and read; the phone reuses `LinkSeasonSyncSheet`. This plan **specifies**
> (`docs/superpowers/planning-policy.md`); implementers author the code and the tests.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> **Ledger:** `.superpowers/sdd/2026-10-22-issues-113-114/progress.md` (the controller's).
> **Branch:** `issue-113-114-ha-publishing` (the one 1.9.0 integration branch), worktree `../noteNFC-wt-113`.
> **Base:** the merged fix branch, then the master plan's waves
> (`docs/superpowers/plans/2026-10-22-issues-113-114-home-assistant-publishing.md`, rev 6.1 + its #125
> cross-reference; "the master plan", constraints G1–G31) and #116's plan.
> **Four briefs:** H1 (wave 13), H2 (wave 14), H3 (wave 15), H4 (wave 16), all after #124's N2b.
> **Paths:** `C/`, `CT/`, `A/`, `T/`, `AT/`, `M/` as in the master plan. Every `file:line` was read on `42647492`.

**Goal (the issue and the owner's case).** An asset's in-use season is `CALENDAR`, set by the owner's own dates. Its
Season Start service (#114) follows a Home Assistant "winterize season" switch that turns on with the first forecast
freeze. That schedule comes due on the day the switch turns on ("When the season starts", 0 days), while the asset's
weekly in-use schedules keep the calendar season. **One asset season cannot say both:** "Before the season starts"
needs a predictable date, and a Home Assistant start is never predicted. So a schedule may follow its own switch, and
only its due date moves. Readiness, the published date and every other asset-level fact stay on the asset's season.

## Global constraints

1. **A schedule's season source** is `ASSET` (the default, as shipped) or `OWN`. `OWN` is a `MANUAL` season by
   construction, made of the schedule's own activation rows and nothing else. The asset's other schedules, and every
   asset-level reader, never see those rows.
2. **Every due rule of an `OWN` schedule reads the schedule's season context**: `IN_SERVICE_AT_START`,
   `IN_SERVICE_RESUME_CLAMPED`, `PRE_SERVICE` (which then has no START boundary, exactly as for a `MANUAL` asset),
   #124's `IN_SERVICE_AFTER_OPENING_SERVICE`, and the break. That context is `MANUAL` mode, no calendar window, the
   **asset's** break dates (the break is the equipment's, not the season's), and the schedule's own activations
   (U-C2).
3. **Readiness and publishing stay on the asset's season** (the controller's invariant, I-R). These read the asset's
   mode, window, activations and #16 binding, never a schedule's:
   - #114's C8/C9 (the cutoff, `RECORDED_IN_ADVANCE`, the role coverage);
   - C9a's boundary authority (only an enabled **asset** #16 binding makes an asset HA-driven);
   - #113/#114's projector and published values;
   - the `season_ended` read;
   - #124's `openingServiceOn`;
   - #116's season and Home Assistant marks;
   - the asset card.

   A schedule's switch moves only that schedule's due date.
4. **#16's machinery is reused, scoped to the schedule.** That means #16's:
   - connection, token and gate;
   - entity rule, with `input_boolean` only (#105's scope);
   - cadence, poll worker, client, mapper, `HaReadOutcome`, desired-phase decision and stale rule;
   - revision guard and status fields;
   - three times (R16-3: an activation row is dated the day it is applied).

   The asset path is unchanged: every shipped #16 test passes unmodified. The master plan's G3 is amended for #125
   only: the runner and the worker gain a schedule-scoped pass beside the asset one.
5. **Persistence rides schema 22 and format 21** (no further bump), choice and argument in U-C1. The schedule's
   source and its activation rows are data, and they travel. The schedule binding is device-local and never
   travels, like #16's (the merge and the Transfer Pack follow #16's carry rules).
6. **No silent fallback.** The only ways a schedule returns to its asset's season are the explicit unlink command and
   archiving the schedule. Forgetting the connection deletes the binding (#16's cascade) and leaves the schedule on
   its own season, frozen at its last phase and drawn as stranded until the owner links again or unlinks.
7. **Refusals and strands follow the shipped patterns:**
   - a group-targeted schedule is refused (inv. 106);
   - a `YEAR_ROUND` asset's schedule may link, because its own season gives it one and no shipped invariant forbids
     it (U-C3);
   - a link that would strand a `PRE_SERVICE` schedule is refused, by the strands rule's reasoning;
   - every refusal writes nothing.
8. **Parity** (the controller's decision): link, unlink and read over `/v1` and MCP, mirroring the asset season-sync
   read (`GET /v1/assets/{id}/season-sync`, `get_season_sync`). This **narrows R16-9 and the master plan's G17 for
   schedule links only**: the asset link, Follow/Force, the token, the connection and Forget stay phone-only.
   No tool name carries `sync` or `home_assistant` (`M/tests/test_season_sync_tools.py:89-92`).
9. **Insets (the master plan's G28).** No new `Scaffold`, sheet or input dialog:
   - the season row sits on schedule detail (group C);
   - the link uses `LinkSeasonSyncSheet` (group E, unchanged in `InsetsContractTest`);
   - the unlink confirm takes no typed input.
10. **Strings** are resources in all ten packs, ratified before H4 (§5).
11. **Exhaustive `when`, never `else`**, over `ScheduleSeasonSource`, the new `ScheduleSeasonRefusal`, and every new API
    arm. #16's `SeasonSyncLinkRefusal` and its two phone arms are untouched.
12. **Standing rules** (the master plan's G19, G21): fictional names only; one-line commits; JVM first; real counted
    REDs; time boxes 1 h target, 2 h hard.

## 1. Scope

| AC | the issue's acceptance (abridged) | contracts · rows |
|---|---|---|
| AC125-1 | engine rows: the schedule's own season decides its date while the asset's decides the others; a CALENDAR asset with an HA-driven schedule; unlinking falls back to the asset's season, explicitly | U-C2, U-C4 · h1–h6, h20 |
| AC125-2 | sync rows: a schedule-scoped poll, activation rows, a stale or unreachable helper | U-C5 · h11–h14 |
| AC125-3 | Room migration and backup format in this release's step; the merge and the Transfer Pack carry the link per #16's rules | U-C1, U-C6 · h7–h10 |
| AC125-4 | API and MCP parity: link, unlink, read, and the season on the schedule's wire shape | U-C7 · h15–h17 |
| AC125-5 | the phone's season row offers "Follow Home Assistant", reusing the link sheet | U-C8 · h18, h19 |
| AC125-I | (the controller) readiness and publishing stay on the asset's season | constraint 3 · h5, h6 |

**Out of scope:**
- Force in season or out of season for a schedule (the binding stores #16's `mode`, always `FOLLOW` in 1.9.0);
- a manual START or END on a schedule's own season (its rows come from Home Assistant only);
- a schedule's own `CALENDAR` window;
- an own season on a group schedule (inv. 106);
- any asset-level change.

**Recorded limits:**
1. **A Home Assistant start is never predicted.** An `OWN` schedule out of its season shows "Out of season until
   you start it" (S90, as on a #16-driven asset). Its `PRE_SERVICE` boundary is the asset's break, if any.
2. **Stranded after Forget.** With the connection gone the schedule keeps its own season, frozen. It is not
   re-anchored or reverted; the row says so (P125-5).
3. **#116's Home Assistant mark is asset-level.** A schedule's link does not draw it. That is constraint 3's reading
   of "asset-level facts"; the schedule's own row shows the link.

## 2. Contracts

- **U-C1, the persistence: new tables, not nullable columns (decided and argued).** All of the following go in
  `MIGRATION_21_22`. S1 created it and H1 (U-C1's Room half) amends it, because schema 22 is unreleased. `22.json`
  is regenerated. An emulator holding the earlier schema 22 is wiped (dev only; no phone ever ran it). The steps:
  1. `ALTER TABLE maintenance_schedule ADD COLUMN season_source TEXT NOT NULL DEFAULT 'ASSET'`;
  2. **`schedule_season_activation`**: `id TEXT PK`, `schedule_id TEXT NOT NULL` → `maintenance_schedule(id)` ON
     DELETE CASCADE, `action TEXT NOT NULL`, `occurred_on TEXT NOT NULL`, `created_at INTEGER NOT NULL`, and
     `INDEX(schedule_id)`. These are the asset activation's columns (`asset_season_activation`,
     `A/data/room/entities/SeasonHealthEntities.kt:20-40`), without `event_id`: a schedule's season writes no journal
     entry;
  3. **`schedule_season_sync_binding`**: `schedule_id TEXT PK` → `maintenance_schedule(id)` ON DELETE CASCADE,
     `connection_id TEXT NOT NULL` → `ha_connection(id)` ON DELETE CASCADE, and every other column of
     `season_sync_binding` but `asset_id`, with the same names and defaults, plus `INDEX(connection_id)`.

  **Why not a nullable `schedule_id` on `asset_season_activation` and `season_sync_binding`:**
  - Every shipped reader of the asset rows would need a `schedule_id IS NULL` filter. Those readers are the
    recompute, the applier, the Assets list, C8/C9, the projector, the codec, the merge and the transfer graph. One
    missed filter would leak a schedule's switch into the asset's season, and into readiness and publishing: the
    one thing constraint 3 forbids. Separate tables make that leak impossible by construction.
  - `season_sync_binding`'s key is `asset_id`. Holding a schedule's row would change that key, which in SQLite is a
    table rebuild.
  - #16's tests stay byte-identical (the master plan's G3).
  - The cost is two tables, two DAOs and a codec list, all additive.

  **Room mapping:** the model `ScheduleSeasonActivation(id, scheduleId, action, occurredOn, createdAt)` maps to an
  activation row, so `SeasonContext` reads its shape. `ScheduleSeasonBinding` mirrors #16's `SeasonSyncBinding` with
  `scheduleId` in place of `assetId`.
- **U-C2, the season a schedule reads (H1, core).**
  - `enum class ScheduleSeasonSource { ASSET, OWN }` and `MaintenanceSchedule.seasonSource` (appended, default
    `ASSET`).
  - `RecomputeSchedules.seasonFor` (`C/usecase/RecomputeSchedules.kt:235-245`) returns, for an `OWN` asset-targeted
    schedule, `SeasonInputs(mode = MANUAL, seasonStartMmdd = null, seasonEndMmdd = null, blackoutStartMmdd /
    blackoutEndMmdd = the asset's, activations = the schedule's own)`, and the shipped asset inputs otherwise.
  - The same choice is made by every other caller of `seasonFor` and of `SeasonContext` for a schedule's due rules:
    `quietUntil`, the health read model's links (`A/ui/health/AssetHealthReadModel.kt:334`), and the due read
    model's `seasonMode` and `dormantUntil` (`A/ui/maintenance/DueReadModel.kt:289`, read as `MANUAL` for an `OWN`
    schedule). That makes the engine, the health clock, the why-lines and reminders follow it.
  - **Never** C8/C9, C9a, the projector, #124's reader, #116, or `AssetsViewModel` (constraint 3).
  - The strands rule (`strandedBy`, `C/usecase/SeasonCommands.kt:265-272`) reads each `PRE_SERVICE` schedule's
    **effective** context. An `OWN` schedule is stranded by an asset change only when the change removes the asset's
    break; the asset's mode and window are not its boundary.
  - The full-replace save keeps the field (`SaveSchedule.prepare`'s `candidate`, the master plan's C12 shape:
    `seasonSource = existing?.seasonSource ?: ASSET`).
  - `ReplaceAsset`'s copy creates `ASSET`, so a successor inherits no link.
  - No request body carries the field (`ignoreUnknownKeys = false`).
- **U-C3, the commands (H1, core; one `uow.write` each, no HTTP).**
  - **`LinkScheduleSeason.run(scheduleId, entityId)`**, the `LinkSeasonSync.run` shape (`C/seasonsync/LinkSeasonSync.kt:83`).
    It writes the binding (`enabled`, `FOLLOW`, revision 1) and sets `seasonSource = OWN` in one write. It adds no
    activation row: the post-link check's first read writes the first START or END through U-C5, dated the day it
    is applied. Until then the schedule is `MANUAL` with no row, so it is out of its season and no START is
    predicted. The phone and the API launch that check at once.
  - **Refusals.** A new `ScheduleSeasonRefusal` (beside, never inside, #16's `SeasonSyncLinkRefusal`,
    `:23-38`, so #16's phone arms stay untouched) has #16's meanings `NOT_MAINTAINED_HERE`, `NO_CONNECTION`,
    `NEEDS_TOKEN`, `BAD_ENTITY_ID` (an id that is not an `input_boolean`) and `ALREADY_LINKED`, the master plan's
    `OUTBOUND_TARGET` (C26, B3), and three new ones: `GROUP_TARGET`, `SCHEDULE_ARCHIVED` and `STRANDS_PRE_SERVICE`.
    The last means a `PRE_SERVICE` schedule whose effective context would have no boundary, i.e. an asset with no
    break. The C26 cross-check runs both ways, so the master plan's
    `EnablePublishing`/`ChangePublishTarget` also refuse an id a **schedule** binding reads; H1 extends B3's
    `OutboundTargets` / inbound read to the new table.
  - **A `YEAR_ROUND` asset's schedule may link.** Every shipped rule reads the schedule's effective context: a
    `PRE_SERVICE` one still needs a break (`STRANDS_PRE_SERVICE`), #124's answer still needs a role, and no other
    rule forbids it.
  - **`UnlinkScheduleSeason.run(scheduleId)`** deletes the binding and sets `seasonSource = ASSET` in one write. The
    schedule's own rows are kept as history and are no longer read. Unlinking an `ASSET` schedule is a no-op;
    unlinking an `OWN` schedule whose binding is already gone (stranded) is allowed.
  - **Archive** (`ArchiveSchedule`, after #124's N2b hook) unlinks in the same write. Restore reads the asset's
    season.
  - **Delete** of the schedule cascades both tables.
  - **Retarget** keeps the link; `NOT_MAINTAINED_HERE` is then read on the new asset at the next check.
  - Every command ends in a recompute of the schedule (`forSchedule`).
- **U-C4, the engine is untouched.** `ServicePolicyEngine` and `SeasonContext` are unchanged: an `OWN` schedule's
  inputs are an ordinary `MANUAL` context (U-C2). So `PRE_SERVICE` has only the break's boundary, IN_SERVICE waits
  with `AWAITING_START` while out of its season, and #124's arm uses the schedule's own `cycleStartAt` as `s` while
  its `openingServiceOn` stays the asset's readiness.
- **U-C5, the sync, scoped to the schedule (H3, app).**
  - **The applier.** `RecordScheduleSeasonSyncResult` (core, H1) mirrors `RecordSeasonSyncResult` with the
    schedule's own rows:
    - the revision guard and the compare-and-set binding update;
    - status, the three times and the forced-phase rule (Force never set in 1.9.0);
    - lifecycle: a schedule that is archived, or whose asset is not maintained here, takes no decision and shows
      `NOT_MAINTAINED_HERE`;
    - at most one activation row and one binding update per read, then `forSchedule`.

    The decision is #16's own desired-phase function (`C/seasonsync/DesiredPhase.kt`) over the schedule's
    `SeasonContext`.
  - **The runner.** `SeasonSyncRunner` (`A/seasonsync/SeasonSyncRunner.kt:95-185`) gains a schedule pass behind the
    same lock:
    - `syncNow`, `refreshIfStale` and `runAll` read the schedule bindings beside the asset ones, through the same
      client, gate, 30 s bound and stale rule (`seasonSyncStale`, `:61`);
    - `seasonSyncScheduleOf` (`:46`) counts an enabled schedule binding in "any enabled";
    - the worker body runs both passes;
    - the resume refresh covers both.
  - **Errors** are #16's `SyncErrorKind`s on the schedule binding, drawn with #16's sentences.
- **U-C6, the format, the merge and the pack (H2).**
  - **Format 21 carries the data:**
    - `MaintenanceScheduleDto` gains `seasonSource: String = "ASSET"` after `roleSinceOn`;
    - `BackupData` gains a list `scheduleSeasonActivations` (the asset activations' DTO shape plus `scheduleId`,
      minus `eventId`), with its manifest count key;
    - a format ≤ 20 archive carrying a non-`ASSET` source or any such row is refused as hand-built (A3's
      `FIRST_SCHEDULE_ROLE_FORMAT` gate);
    - the binding never travels: `SeasonSyncNeverTravelsTest` gains the table.
  - **The merge:**
    - the schedule compare includes `seasonSource` at format 21 (A3's `rolesCompared` shape);
    - the activation rows merge as the asset activations do (a new `MergeTable`, insert or `IDENTICAL` by id; the
      transfer classification test classifies the table);
    - an inserted `OWN` schedule arrives **with no binding**: stranded, exactly as an imported `MANUAL` asset arrives
      without its #16 link (#16's carry rule).
  - **The Transfer Pack** carries the rows with their schedule (`C/transfer` classification); a returning asset's
    schedule keeps its local source through the master plan's `ReturnCarry` shape (C20, the role's precedent).
  - **API status:** `GET /v1/status`'s `counts` gains `scheduleSeasonActivations`.
- **U-C7, the wire (H3).**
  - **Schedule reads** (`ScheduleRowResponse`, `get_schedule`, `list_schedules`) gain `seasonSource`
    (`"ASSET"` | `"OWN"`), response only.
  - **`GET /v1/schedules/{id}/season-sync`** answers in the shape of `GET /v1/assets/{id}/season-sync`
    (`A/api/ApiRouter.kt:272`, the binding's entity, state, mode, times and error, never the base URL, Wi-Fi name or
    token), plus `seasonSource`, the schedule's phase today, and its activation rows. A schedule with no binding
    answers `binding: null`.
  - **`POST /v1/schedules/{id}/season-sync`**:
    - body `{"action": "LINK", "entityId", "confirmEntityId"}` (confirm required and equal: the O-21 destination
      precedent) or `{"action": "UNLINK", "confirmUnlink": true}`;
    - it runs U-C3, then launches the post-link check on `appScope`, never awaited. The read shows its result.
    - Refusals: 409 **`SCHEDULE_SEASON_REFUSED`** `{reason}` (a `ScheduleSeasonRefusal` name, its own `is` arm); 422
      **`SCHEDULE_SEASON_CONFIRMATION_REQUIRED`**; 404 `NO_SUCH_SCHEDULE`.
    - Another verb is a 405.
  - **MCP** (a "#125" block after the master plan's "#113/#114" block; names free of `sync` and `home_assistant`):
    - `get_schedule_season(schedule_id)`;
    - `link_schedule_season(schedule_id, entity_id, confirm_entity_id=None)`, which sends nothing unless the confirm
      equals the entity and never fills it;
    - `unlink_schedule_season(schedule_id, confirm_unlink)`, which sends only when the argument `is True`;
    - each is gated at `_MIN_HA_PUBLISHING_SCHEMA_VERSION`, and each write uses `retry_on_connect=False`;
    - D1's count pins move 101 → **104** and the README's word to "One hundred and four:";
    - `command-shapes.json` gains `scheduleSeason`.
  - v1.md: a section "A schedule's own Home Assistant season (#125)", the Errors rows and the 405 row.
- **U-C8, the phone (H4).** Schedule detail (`A/ui/maintenance/ScheduleDetailScreen.kt`, group C) gains a **season row**:
  - for an `ASSET` source: P125-1 with the asset's name, and the action **"Follow Home Assistant"** (#16's ratified
    `season_sync_follow`), which opens `LinkSeasonSyncSheet` with a schedule target. The sheet's view model takes a
    target (asset or schedule) as a parameter. It is the same group-E sheet with the same browser and the
    `INPUT_BOOLEANS` scope; B6a's scope parameter stays.
  - for `OWN` with a binding: P125-2 with the entity, #16's status and error sentences, and **"Use the asset's
    season"** (P125-3) with its confirm (P125-4).
  - for `OWN` without a binding (stranded): P125-5 and both actions.
  - The refusals draw P125-6, P125-7 and the reused sentences (P16-36, P16-10, P16-11, P16-49, P113-43, and P114-19
    for `SCHEDULE_ARCHIVED`).
  - The editor is unchanged: its due rules read the effective season with no new control.

## 3. Test matrix

| # | layer | class · cases | proves | fails without |
|---|---|---|---|---|
| h1 | JVM | `CT/usecase/ScheduleSeasonRecomputeTest` (new, H1) · an `OWN` `IN_SERVICE_AT_START` schedule on a `CALENDAR` asset: its START on 10-20 makes it due 10-20 at offset 0; the asset's other schedules keep the window's dates | U-C2, AC125-1 | `seasonFor` returning the asset's inputs for every schedule |
| h2 | JVM | same · an `OWN` `PRE_SERVICE` schedule: no START boundary, only the asset's break (`BEFORE_BREAK`); with no break the link is refused `STRANDS_PRE_SERVICE` | constraint 2 | the window kept in the schedule's inputs (a `BEFORE_SEASON` date) |
| h3 | JVM | same · out of its own season: `AWAITING_START`, `DORMANT`, S90's reason; the asset's break makes it quiet as it does the asset's schedules | U-C4 | the break dropped from the schedule's inputs |
| h4 | JVM | `SeasonStoriesTest` (+1, H1; **the owner's case**, fictional) · a `CALENDAR` "Example Heater" 11-01…03-31; its Season Start schedule (`IN_SERVICE_AT_START`, 0, its yearly due already reached) follows `input_boolean.example_heater_winterize_season`; with the switch off it waits (`AWAITING_START`); the switch turns on 10-20 → due 10-20; completed 10-22 → the asset is Ready (cutoff 04-01, the window's); its weekly schedule (#124, `IN_SERVICE_AFTER_OPENING_SERVICE`, 7) is due **11-08** = max(11-01, 10-22) + 7 — before 11-01 it is `DORMANT` at that same date, the next start + 7, as `IN_SERVICE_AT_START` | U-C2, constraint 3, #124 | the weekly schedule reading the role schedule's own season (`s` = 10-20 → due 10-29) |
| h5 | JVM | `CT/season/ScheduleSeasonIsolationTest` (new, H1) · the asset-level invariant: an `OWN` schedule's START and END rows change no answer of C8/C9 (cutoff, source, reason), `haBoundaryOf`, #124's reader, the projector's desired values, #116's row facts or the Assets list's out-of-season flag (each varied with the rows present and absent) | constraint 3, I-R | a reader taking every activation of the asset's schedules |
| h6 | JVM | `T/hapublish/ScheduleSeasonPublishingTest` (new, H3, Room `FakeGraph`) · with an asset date binding enabled, a schedule's switch toggling ON and OFF moves no published value, no `season_ended` read and no delivery record | constraint 3 | the projector observing the new table |
| h7 | JVM | `T/data/room/Migration21To22Test` (amended, H2) · the source column defaults `ASSET`; the two tables with their keys, cascades and indices; the fresh and migrated schema 22 agree | U-C1 | the binding keyed by `asset_id` (two schedules of one asset collide) |
| h8 | JVM | `CT/backup/BackupFormat21Test` (+3, H2) · the source and the rows round-trip; a format-20 archive carrying either is refused; the binding is in no archive (`SeasonSyncNeverTravelsTest` +1) | U-C6 | the list left out of the codec |
| h9 | JVM | `CT/merge/ScheduleSeasonMergeTest` (new, H2) · rows merge by id; a re-merge is `IDENTICAL`; an inserted `OWN` schedule arrives with no binding (stranded); a format-20 export of this phone re-merges `IDENTICAL` against a local `OWN` schedule | U-C6, #16's carry | the source compared at format 20 |
| h10 | JVM | `CT/usecase/TransferRoundTripRoleTest` (+1, H2) · a pack carries the rows; a returning asset's schedule keeps its local source | U-C6 | the table unclassified (the classification test fails) |
| h11 | JVM | `CT/usecase/ScheduleSeasonApplierTest` (new, H1; `CT/seasonsync` untouched) · the applier: ON → one START dated the apply day; OFF → one END; an unchanged answer writes none; a stale revision is dropped; an archived schedule shows `NOT_MAINTAINED_HERE` | U-C5 | the decision reading the asset's context (the window's phase) |
| h12 | JVM | `T/seasonsync/ScheduleSeasonRunnerTest` (new, H3) · one pass reads asset and schedule bindings behind one lock; `refreshIfStale` covers a stale schedule binding; an unreachable helper records its kind and changes no season; the schedule rule enqueues with only a schedule binding enabled; every shipped #16 runner, worker and scenario test green unchanged (pins) | U-C5, AC125-2 | `anyEnabled` reading asset bindings only (nothing polls) |
| h13 | JVM | `CT/usecase/ScheduleSeasonCommandsTest` (new, H1) · each refusal, with nothing written; a `YEAR_ROUND` asset's schedule links; link then unlink returns to the asset's dates **only** on unlink; archive unlinks; Forget leaves `OWN` with no binding (stranded) and the dates frozen | U-C3, constraint 6 | Forget's cascade resetting the source (a silent fallback) |
| h14 | JVM | `CrossCheckTest` (+2, H1) · a schedule binding's entity is refused as a publish target and the reverse | U-C3, master C26 | the cross-check reading only `season_sync_binding` |
| h15 | contract | `T/api/ScheduleSeasonRoutesTest` (new, H3) · read shape (no URL, Wi-Fi name or token, a byte scan); LINK with and without the confirm; UNLINK; each refusal as `SCHEDULE_SEASON_REFUSED` `reason`; another verb 405; `ApiReadsWriteNothingTest` +1 | U-C7 | the handler defaulting `confirmEntityId` |
| h16 | contract | `T/api/MaintenanceCommandShapeTest` (+2, H3) · every schedule read carries `seasonSource`; a body carrying it is 400 | U-C7 | the row mapping omitting it |
| h17 | contract | `M/tests/test_schedule_season_tools.py` (new, H3) · the three tools' routes and bodies; the confirms never filled; gate 22; `retry_on_connect=False`; no tool name carries `sync` or `home_assistant` (the shipped pin green); every count pin at 104 | U-C7 | the tool copying `entity_id` into the confirm |
| h18 | JVM | `T/ui/maintenance/ScheduleSeasonRowViewModelTest` (new, H4) · each source and state draws its sentence; the sheet opens with the schedule target and `INPUT_BOOLEANS`; the unlink confirm; each refusal's sentence (exhaustive) | U-C8 | the sheet opened with the asset target (the asset gets linked) |
| h19 | Compose | `AT/ui/maintenance/ScheduleSeasonScreensTest` (new, H4, ~6 cases; **the UI proof**) · the row in its three states; "Follow Home Assistant" opens the sheet fully expanded; "Use the asset's season" and its confirm | U-C8 | a state with no drawn sentence |
| h20 | JVM | `ScheduleSeasonRecomputeTest` (+1, H1) · the unlinked schedule reads the asset's window from the unlink's write on, and its own rows stay stored | AC125-1 "explicitly" | unlink deleting the history |
| h21 | JVM | `InsetsContractTest`, `LocalizationCoverageTest`, `UiLiteralGuardTest` (pins; H4's strings in all packs) | constraints 9, 10 | none: pins |

**Hazards:**
- Z1: the schedule's switch leaking into readiness or publishing (h5, h6);
- Z2: a silent return to the asset's season (h13, h20);
- Z3: a schedule binding not polled, or polled outside the lock (h12);
- Z4: a link inherited by a successor or restored as live (h8, h9);
- Z5: a `PRE_SERVICE` schedule stranded by its own link (h2).

## 4. Files, fences, order

| brief | wave · lane (the master plan's §4) | touches | never touches |
|---|---|---|---|
| **H1** model, commands, applier, the effective season (core, JVM) | 13 · L2 (after #124's N2b, wave 10; beside B7) | `C/model/Maintenance.kt` (`seasonSource`); `C/usecase/RecomputeSchedules.kt` (`seasonFor`), `SaveSchedule.kt` (`candidate`), `SeasonCommands.kt` (the effective context in `strandedBy`), `ArchiveSchedule.kt` (unlink); `C/seasonsync/SeasonSyncPorts.kt` (the schedule ports); `C/seasonsync/ScheduleSeason.kt` (new: the two commands, the applier, the model); `C/hapublish/PublishCommands.kt` (C26's schedule half); `CT/testing/InMemorySeasonSync.kt` (the doubles); the new `CT/usecase`, `CT/season` tests; `SeasonStoriesTest` (+1) — rows h1–h5, h11, h13, h14, h20; ~60 min | `A/**`, `C/backup`, `C/merge`, `C/transfer`, the engine, every shipped `CT/seasonsync` file, `docs`, `tools` |
| **H2** Room, format 21, merge, pack (core + app data, graph) | 14 · L2 (after H1; beside U2) | `A/data/room/{Migrations,AppDatabase}.kt` (the step amended), the entities and two DAOs (new), `ScheduleSeasonRepositories.kt` (new), `MaintenanceMappers.kt`; `app/schemas/…/22.json` (regenerated); `C/backup/{BackupFormat,BackupCodec}.kt`, `C/merge/{MergePlanner,MergePlan,MergeTable…}.kt`, `C/transfer/TransferGraph.kt` (the classification); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (repositories, commands); the amended and new tests — rows h7–h10; ~60 min | `A/api`, `A/ui`, `A/seasonsync`, `A/hapublish`, `M/`, `S/`, `B/` |
| **H3** the sync scope and the wire (app, graph's last edit) | 15 · L1 | `A/seasonsync/{SeasonSyncRunner,SeasonSyncWorker}.kt` (the schedule pass, only); `A/api/{ApiRouter,ScheduleSeasonHandlers,ApiJson,ScheduleRowResponse,MaintenanceDtos}.kt`; `docs/api/{v1.md,command-shapes.json}`; `M/src/servicetag_mcp/server.py`, `M/README.md`, the count-pin sites (101 → 104); `A/di/AppGraph.kt`, `T/testing/FakeGraph.kt` (the runner's schedule pass, the handler group) — **the graph's last edit**; the new tests — rows h6, h12, h15–h17; ~60 min | `A/ui`, `C/**`, every shipped `T/seasonsync` test, `S/`, `B/` |
| **H4** the phone | 16 · L1 (beside D1) | `A/ui/maintenance/{ScheduleDetailScreen,ScheduleDetailViewModel,ScheduleSeasonRow}.kt` (the row, new file), `A/ui/asset/{LinkSeasonSyncSheet,LinkSeasonSyncViewModel}.kt` (the target parameter only); `strings_maintenance.xml` ×10 (P125-1…7, ratified first); `T/ui/maintenance/ScheduleSeasonRowViewModelTest.kt`, `AT/ui/maintenance/ScheduleSeasonScreensTest.kt` (new) — rows h18, h19, h21; ~55 min | `A/di`, `FakeGraph`, `C/**`, `A/api`, `A/seasonsync`, `InsetsContractTest.kt`, `docs`, `tools` |

**Waves:** waves 13–16 of the master plan's §4 (rev 6.1 + this cross-reference). H1 sits beside B7 (wave 13; `AT/`
only versus core). H2 sits beside U2 (wave 14; data and graph versus the Assets screen). H3 runs alone (wave 15; the
graph's last edit, `A/api`, `M/`). H4 sits beside D1, which moves to wave 16 (UI versus docs; D1 then also documents
#125 in `docs/home-assistant-season-sync.md`). Two lanes at most; each brief is an hour or less. **Dependencies:**
- H1 needs the master plan's A1 (C8/C9), A2 (`SaveSchedule`'s `candidate`), B3 (C26, the #16 port flows) and #124's
  N2a/N2b (`RecomputeSchedules`, `ArchiveSchedule`).
- H2 needs S1 (schema 22), A3 (format 21, the gate, `ReturnCarry`) and N2b (the codec and planner).
- H3 needs B4b2's worker and A4w/B5w2's `A/api`, `M/` and count pins (101).
- H4 needs B6a's `LinkSeasonSyncSheet` scope parameter.

**Between-brief gaps:**
- H1 → H2: the commands work on in-memory doubles only.
- H2 → H3: links persist but nothing polls them and the API has no route.
- H3 → H4: the API links and the phone does not draw the row.

## 5. Strings

**Pending, for the owner's one batch (the controller, 2026-10-26).** Home: `strings_maintenance.xml`.

| id | proposed wording | where |
|---|---|---|
| P125-1 | "Season: %s's season" | the row, `ASSET` source (the asset's name) |
| P125-2 | "Season: follows %s in Home Assistant" | the row, `OWN` with a binding (the entity id) |
| P125-3 | "Use the asset's season" | the unlink action |
| P125-4 | "Stop following Home Assistant? This schedule will use %s's season again, and its own season history is kept." | the unlink confirm |
| P125-5 | "No longer linked to Home Assistant: this schedule keeps its own season as it was. Follow Home Assistant again, or use the asset's season." | `OWN` without a binding (after Forget, or after an import) |
| P125-6 | "A group's schedule follows no season of its own." | `GROUP_TARGET` |
| P125-7 | "This schedule is done before the season starts, and a Home Assistant season has no start date to be ready before. Change when it should be done first." | `STRANDS_PRE_SERVICE` |

**Reused, ratified:** `season_sync_follow` ("Follow Home Assistant"); #16's link sheet and status sentences
(P16-10, P16-11, P16-36, P16-49 and the stale and unreachable lines); P113-43 (`OUTBOUND_TARGET`); P114-19
(`SCHEDULE_ARCHIVED`); S90. **Count:** seven new strings.

## 6. Plan positions (inside the controller's decisions)

| # | position | why |
|---|---|---|
| H-1 | New tables, not nullable columns | U-C1's argument: constraint 3 by construction, no key rebuild, #16's tests untouched |
| H-2 | The own season carries the asset's break | the break is the equipment's; "the break" in the decision is read through the schedule's context |
| H-3 | `FOLLOW` only in 1.9.0 | the issue asks for "Follow Home Assistant"; Force would add strings and states |
| H-4 | Forget leaves the schedule `OWN` and stranded; only unlink or archive returns it | #16's Forget leaves the asset `MANUAL` with its history; "no silent fallback" |
| H-5 | Linking over `/v1` and MCP needs a destination confirm and launches the check unawaited | the O-21 and Publish-now precedents; G17 narrowed for schedule links only |
| H-6 | #116's Home Assistant mark stays asset-level | constraint 3's reading; the schedule's row shows its link |

## 7. Proofs

- **Per brief:** `./gradlew :core:test :app:testDebugUnitTest --rerun` green, zero failures and skips, and
  `:app:compileDebugAndroidTestKotlin`; H3 also `uv run --frozen pytest` in `M/`; H2 also in `S/` and `B/`
  (unchanged, green).
- **At the 1.9.0 gate** (the master plan's §7):
  - **R1** includes rows h1–h18, h20, h21;
  - **R2** includes `ScheduleSeasonScreensTest` (85 classes with the master plan's and #116's);
  - **R3** includes the MCP at 104 tools;
  - **R-K** observes the schedule detail's row and the sheet opened from it;
  - **the real-HA proof** gains one fixture step only if the owner names a fixture switch. Otherwise it is
    simulated by h11/h12's doubles, and the record says so.
  - **R6 greps:**
    - `'schedule_season_activation|scheduleSeasonActivation'` in `C/season`, `C/hapublish`, `A/hapublish` and
      `AssetsViewModel` → 0 (constraint 3);
    - `'"SCHEDULE_SEASON_REFUSED"'` and `'"SCHEDULE_SEASON_CONFIRMATION_REQUIRED"'` in `ApiJson.kt` → 1 each;
    - `'^.mcp\.tool\('` in `server.py` → 104;
    - `'"confirmEntityId": entity_id|"confirmUnlink": True'` in `server.py` → 0;
    - `git diff <base> --diff-filter=M -- app/src/test/kotlin/com/loosecannon/servicetag/seasonsync core/src/test/kotlin/com/loosecannon/servicetag/core/seasonsync`
      → empty;
    - `'MIGRATION_21_23|MIGRATION_22_23|FORMAT_VERSION = 22'` → 0 (no further bump).
- **R7** (the master plan's): the 1.8.1 → 1.9.0 upgrade adds the column `ASSET` and the two tables empty.
- **Release:** #125 ships in 1.9.0 (versionCode 23); the release task writes its note beside the other seven. Tagging
  is not authorized.

## 8. Relationships

- **#16:** reused, scoped (constraint 4); R16-9 narrowed for schedule links only.
- **#113/#114:** constraint 3; C26 extended both ways; the master plan's G3, G17 and G20 amended in its rev 6.1
  cross-reference.
- **#124:** an `OWN` schedule's `s` is its own cycle start, and its opening-service day is the asset's readiness (h4).
- **#116:** its marks stay asset-level (H-6).
- **#121:** no new surface (constraint 9).
