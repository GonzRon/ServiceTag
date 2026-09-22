# ServiceTag 1.2 — operational maintenance: MASTER PLAN

> **For agentic workers — EXECUTION IS GATED. Do not start a brief.** **No brief in this package executes until the owner's pre-implementation authorization** (the L2 sequence's step 9: "STOP at the pre-implementation authorization gate. No 1.2 production code, no production phone, no release"). Until that authorization is recorded in the ledger, **this package is read-only**: plan review, string ratification and controller reconciliation only — **no 1.2 production code, no production phone, no release, no wave opened.** Once the owner has authorized implementation, REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` to execute the package brief by brief in §14.3's wave order.
>
> This plan follows `docs/superpowers/planning-policy.md`: **it specifies contracts, invariants and test matrices; implementers author the code and the tests.** Nothing here is meant to be transcribed into the tree. A fenced block appears only where it pins an interface, a data shape or an algorithm boundary.

**Goal:** one canonical maintenance-scheduling engine and the local delivery path around it, shipped as ServiceTag **1.2.0 / versionCode 13** — schedules that target one Asset or one MaintenanceGroup, derived due state that is never stale, truthful per-member group completion with an explicit "Close this round", a provider-neutral reminder port, the local digest provider with an inexact alarm and a 12 h backstop, nonce-protected notification quick actions, the scan completion sheet, the dashboard with maintenance status, reminder health with idempotent repair, and per-tag placement labels — plus the three surfaces those ten issues presuppose and none owns: the **Maintenance** destination, the **schedule editor** with the five operations, and the **group screens**.

**Spec (the authority):** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` — **revision 4.1, owner-ratified and committed**, with `.superpowers/sdd/2026-09-22-servicetag-1.2-operational-maintenance/owner-rulings-2026-09-22.md` (D-1…D-28, the navigation ruling, the proportionality ruling and the no-overnight-blocking ruling) binding on top of it. Every contract, table, wire shape, error code, invariant number and ratified string below traces to a spec section, cited inline as `spec §n`. **Where this plan decides something the spec left open it says `Plan decision:` and the decision is listed again in §18.** Nothing the owner ruled is re-decided here.

**Architecture:** the shipped three-layer shape is unchanged. `:core` gains the maintenance domain (aggregates, the recompute function, the use cases, the repository ports and the provider-neutral `ReminderProvider`) and stays free of Android and of any provider type (spec §2.5, invariant 48). `:app` gains the Room v6 tables and mappers, the Android delivery platform (permissions, two channels, receivers, the digest alarm, the WorkManager backstop, quick actions), the Maintenance navigation destination with its screens, and the new `/v1` routes. `tools/servicetag-mcp/` gains seventeen tools over those routes. Derived state (`schedule_state`) has exactly one write path — `ScheduleRecompute.rebuild` — and is never exported; delivery state (`schedule_local_delivery`) is device-local and never exported or merged (spec §2.2, §2.5, invariants 17, 64, 65).

**Tech stack:** Kotlin 2.4.20 / AGP 9.4.0, Room 3.0.3 with KSP and an exported schema, kotlinx-serialization 1.9.0, Compose (BOM 2026.08.00) with Navigation3, JUnit 5 in `:core`, JUnit 4 + Robolectric-free unit tests and `androidTest` connected tests in `:app`, Python 3.12 + `uv` + `pytest` for the MCP server. One new runtime dependency: **`androidx.work:work-runtime-ktx`, declared by B06 in `gradle/libs.versions.toml` and added to `:app` only** (spec §5.3; the catalog has no `androidx.work` today — verified against `gradle/libs.versions.toml`, `app/build.gradle.kts`, `core/build.gradle.kts`, `settings.gradle.kts`).

---

## 1. Global constraints

Copied from the spec's rulings and the repository rules. Binding on every brief.

- **Execution is gated (owner, the L2 sequence's step 9).** **No brief starts until the owner explicitly authorizes implementation after reading the planning summary**, and that authorization is recorded in the ledger. Until then: no 1.2 production code, no production phone, no release, no wave opened. This bullet is first because it is the one an agent handed only this document must read before anything else.
- **The spec is the authority.** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` — **revision 4 plus §11's revision-4.1 edits** — together with `.superpowers/sdd/2026-09-22-servicetag-1.2-operational-maintenance/owner-rulings-2026-09-22.md`. **Cite the committed path, never the workspace draft**, so there is one authority. An implementer who finds this plan disagreeing with the spec follows the spec and reports the disagreement — **except where this plan has named a deviation** (§18's two, decisions 3 and 4) — and an implementer who finds the spec silent asks the controller rather than inventing a user-visible behaviour.
- **Proportionality (owner, 2026-09-22, binding).** "One test is enough… do not block on exorbitantly strict acceptance tests." Test matrices are **one test per hazard class, not per permutation**; an invariant naming several facts is one test asserting them together (spec §6 preamble, §8 header).
- **No overnight blocking (owner, 2026-09-22, binding).** "I don't want to be blocked on any tests that require overnight at all, period." **No acceptance procedure in any brief may wait on an overnight or multi-day measurement.** Where real-world timing matters — the digest alarm, the 12 h backstop — acceptance is a deterministic test against an injected `Today`/shadow clock or a shadow `AlarmManager`.
- **S4 gates nothing, and this plan owns that statement.** There is **no S4 gate anywhere in this package**: not on a brief, not on a review, not on a merge, not on the release. **B06 is not gated on S4** — it is designed and written now on the default alarm-plus-backstop design of §12 (spec §5.9, D-23 as amended). No brief's Ordering section may name S4 as a predecessor, and any brief that does is wrong. Its result is recorded when it arrives; see §12.3. **Whether the trial has been armed, and when, is operational state and lives in the ledger, not here.**
- **Version.** `versionName` **1.2.0**, `versionCode` **13** (D-1). The unused `1.1.1` / code 13 reservation at `docs/versioning.md:33` is struck. Room schema **5 → 6**. Backup format **5 → 6**, a MINOR under the D-2 clarification. API version stays **1**, extended additively (D-19).
- **Status is never stored** (spec §2.2, invariant 18). `rebuild` is the only write path into `schedule_state` (invariant 17).
- **A termination that clears no postponement writes no column on `maintenance_schedule` and never bumps its `updated_at`** (spec §2.1, invariants 68, 69). This is load-bearing: `IDENTICAL` compares every backup-format field including `updatedAt` (`core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlanner.kt:62-63`, `docs/api/v1.md:247`).
- **A merge only ever inserts.** No `UPDATE` verdict is added (`core/.../core/merge/MergePlan.kt:22-32`); one conflict anywhere writes nothing (`MergePlan.kt:136-141`, `MergePlanner.kt:415-433`). Invariants 66, 67.
- **Nothing destructive is added.** No route, MCP tool or UI action deletes a schedule, a group or a closure; `POST /v1/events` cannot create a completion (spec §4.1, invariants 43, 76). `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` (`docs/api/v1.md:202-211`) gains the new paths.
- **The scan never mutates.** `servicetag://` links and NFC dispatch stay navigation-only; mutation follows an explicit in-app action (spec §2.8, invariants 57, 58). `ResolveTag`'s `lastScannedAt` write is informational and unchanged (`core/.../core/usecase/ResolveTag.kt:45-48`).
- **Strings.** Every user-visible string is either **ratified** (spec §9.1, quoted verbatim — a brief may not paraphrase one) or marked **PROPOSED** in §17 and returned for the owner's ratification **before** the brief that draws it executes.
- **Out of scope, do not build:** #14 season re-entry (`seasonReentry`, `seasonReentryOffsetDays` are stored and never read — invariant 26); #15 supplies and `SUPPLY(id)` subjects; #47 material requirements; #9/#10/#34 Todoist, `reminder_projection`, `provider_op`, `integration_account` — **no projection and no outbox table is created in 1.2**; #26 fatigue controls; #25 provider-choice UI beyond one enabled row; #44 interactive conflict resolution; #18 the preferences screen (1.2 carries only the digest hour and the global reminders switch, in B06); #17 template-seeded schedules; nested groups, rule-based membership, group NFC identity, a group deep link, `group.location`, `asset_event.cost_minor`/`currency`, and the `supplies`/`sync_problems` channels (spec §1.2, D-20).
- **Untouched by this release** unless a brief's Files section names it: `libs/`, `tools/servicetag-bundle/`, `core/.../core/nfc/`, `core/.../core/journal/`, the attachment and backup-artifact paths, `app/.../ui/theme/`, and every existing user-visible string.
- **Privacy and hygiene (binding, every tracked file, report and review).** No private inventory, no device serials or models, no e-mail addresses, no `/home/<user>` paths (write `~`), no pairing codes, no real `backupSetId`. Fixtures use fictional nouns and brands.
- **Repository rules.** One commit per task; single casual subject, no body, no trailers, no attribution; identity from `git log -1 --format=%ae master`; implementers do not push; **no implementer runs adb, an emulator, a device command, or `gradlew` outside the sandbox tool the controller names**; never work in `/tmp`.
- **Grep expectations are anchored patterns from the start** (`docs/superpowers/planning-policy.md:41`), so a comment that names a grep can never match it.

### 1.2 Scope questions — PENDING OWNER RULING at the gate

The plan's counterpart to spec §1.2's out-table. Three requirements of **#5** are named by the issue, are not in spec §1.2's exclusions, and are covered by **no spec section, no brief and no ruling** — so they are neither in nor out. They are listed here rather than decided, because either answer is defensible and only the owner's is binding. **Each must be ruled at the pre-implementation gate**; a brief may not resolve one by building it or by quietly leaving it out.

| # | the requirement | recommended default | the alternative |
|---|---|---|---|
| **F2** | **#5's "Group/filter by category and maintenance status"** (`issue-5.md:24`). The plan preserves the shipped search-field set and adds attention **sections**, which groups by status but is not the *filter* the issue names; **category** filtering appears nowhere, and the shipped view model filters by lifecycle and search only (`app/.../ui/dashboard/DashboardViewModel.kt:109-136`) | **in 1.2:** one B08 scope line and one matrix row — a filter over category and over status, applied after the lifecycle and search filters, with the section order unchanged | **explicitly out**, recorded in the spec's exclusions with the reason (the sections already answer "what needs attention", and a filter UI is a design the owner has not made) |
| **F3** | **#5's "next maintenance item and its due date/*value* on each asset row"** (`issue-5.md:26`), with the meter dependency the issue itself names (`:32-35`). The due **date** is covered — `effective_due_on` is the sort key and the row's value. The meter **due value**, #5's own example "due at 170 h, now 165 h", is specified for the **scan sheet** only (§11.2, B09's composed display assertion) and for **no dashboard row**. Meters are in 1.2 by **D-3** and the meter model shipped, so the dependency #5 named is satisfied and the gap is real | **in 1.2:** one clause in §11.1's read-model bullet — "and, where a meter rule exists, the threshold and the current reading" — plus one B08 matrix row. `DueItem` already carries `computedDueMeter` and `currentMeter`, so this is a rendering change, not a contract change | **explicitly out**, recorded in the spec's exclusions: the dashboard shows the date and the sheet shows the numbers |
| **F4** | **#5's "Quick actions for scan tag, add asset, log maintenance"** (`issue-5.md:27`). Today these ship only as **empty-state** buttons; there is no persistent quick-action affordance and **no "log maintenance" action anywhere** in the plan — conspicuous in the release that first gives "log maintenance" a schedule to log against | **in 1.2:** one B08 scope line and one matrix row — the three actions on the Maintenance destination, with "log maintenance" routing into **B14's `CompletionFlow`** and the other two reusing the shipped routes | **explicitly out**, recorded in the spec's exclusions: the Maintenance destination's due-work section is the path to completion, and a quick-action row is #18/#26 territory |

**If the owner rules any of the three *in*, the work is B08's** (F4's completion route is B14's, already built), it is **one scope line plus one matrix row** each, and it changes **no contract, no invariant and no wire shape** — which is why the package is ready for the gate with these open. **If the owner rules any *out*, the controller records it in the spec's §1.2 exclusions** so a later reconciliation does not re-raise it. **A new user-visible string in any of the three is a §17 PROPOSED item, not a brief's to invent** (F2's filter labels, F3's meter line form, F4's three action labels).

---

---

## 2. Shared contract A — Room schema 5 → 6

Spec §3.1. The shipped database is `version = 5` (`app/.../data/room/AppDatabase.kt:38`), `AppGraph.SCHEMA_VERSION = 5` (`app/.../di/AppGraph.kt:237`), migrations chained at `AppGraph.kt:88`, schemas committed under `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/{1..5}.json`. D4 §15's version plan is stale (attachments shipped at v5), so schedules land at **v6**.

### 2.1 New tables

Column names are snake_case as everywhere in `app/.../data/room/entities/`. `TEXT` dates are ISO-8601 `YYYY-MM-DD`, exactly as `asset_event.occurred_on` already is (`core/.../core/model/Journal.kt:52`). Instants are epoch-millisecond `INTEGER`.

| table | column | type | null | notes |
|---|---|---|---|---|
| `maintenance_group` | `id` | TEXT | PK | durable UUID |
| | `name` | TEXT | no | descriptive only; **never identity** (invariant 7) |
| | `description` | TEXT | no | carries context; there is no `location` (D-26) |
| | `archived_at` | INTEGER | yes | archive is not delete |
| | `created_at`, `updated_at` | INTEGER | no | |
| | indices | | | `name` |
| `maintenance_group_member` | `id` | TEXT | PK | durable child id (D-14) |
| | `group_id` | TEXT | no | FK `maintenance_group` CASCADE |
| | `asset_id` | TEXT | no | FK `asset` CASCADE |
| | `sort_order` | INTEGER | no | |
| | `added_at` | INTEGER | no | server-stamped; **never edited** (invariant 79) |
| | `removed_at` | INTEGER | yes | server-stamped; **never cleared** (invariant 79) |
| | indices | | | `UNIQUE(group_id, asset_id, added_at)`, `group_id`, `asset_id` |
| `maintenance_schedule` | `id` | TEXT | PK | |
| | `asset_id` | TEXT | yes | FK `asset` CASCADE |
| | `group_id` | TEXT | yes | FK `maintenance_group` CASCADE |
| | `title`, `description` | TEXT | no | |
| | `time_interval` | INTEGER | yes | |
| | `time_unit` | TEXT | yes | `DAY｜WEEK｜MONTH｜YEAR` |
| | `time_basis` | TEXT | no | `FIXED｜COMPLETION` |
| | `anchor_on` | TEXT | yes | non-null whenever `time_interval` is non-null (§2.3 below) |
| | `lead_days` | INTEGER | no | |
| | `meter_definition_id` | TEXT | yes | FK `measurement_definition` **RESTRICT** |
| | `meter_interval`, `anchor_meter`, `meter_lead` | REAL | yes | meter values are `Double`, as `measurement.value_num` is |
| | `season_behavior` | TEXT | no | `FOLLOW_ASSET｜IGNORE` |
| | `season_reentry` | TEXT | yes | **stored, never read in 1.2** (D-4) |
| | `season_reentry_offset_days` | INTEGER | yes | **stored, never read in 1.2** (D-4) |
| | `completion_mode` | TEXT | no | `QUICK｜FORM` |
| | `profile_id` | TEXT | yes | FK `event_profile` **SET NULL** |
| | `reminders_enabled` | INTEGER | no | |
| | `status` | TEXT | no | `ACTIVE｜PAUSED｜ARCHIVED` — the lifecycle column, **not** the derived status word |
| | `postponed_due_on` | TEXT | yes | the row's **only** override (D-13 moved the snooze out) |
| | `created_at`, `updated_at` | INTEGER | no | |
| | indices | | | `(asset_id, status)`, `(group_id, status)`, `meter_definition_id`, `profile_id` |
| `schedule_provider` | `schedule_id`, `provider` | TEXT | no | composite PK; FK schedule CASCADE |
| | `enabled` | INTEGER | no | a **set**, not an enum column (#4 "Provider selection") |
| `occurrence_closure` | `id` | TEXT | PK | |
| | `schedule_id` | TEXT | no | FK schedule CASCADE |
| | `occurrence_on` | TEXT | no | the occurrence key events carry |
| | `closed_on` | TEXT | no | the termination's effective date |
| | `created_at` | INTEGER | no | audit, and the next round's open-instant input |
| | indices | | | `UNIQUE(schedule_id, occurrence_on)`, `schedule_id` |
| | | | | **immutable, append-only: no `updated_at`, no UPDATE, no DELETE** but the CASCADE (spec §2.9, invariant 37) |
| `schedule_state` | `schedule_id` | TEXT | PK | FK schedule CASCADE |
| | `last_completed_on` | TEXT | yes | latest member **completion** only |
| | `last_completion_event_id` | TEXT | yes | |
| | `last_completed_meter`, `current_meter`, `computed_due_meter` | REAL | yes | |
| | `last_termination_effective_on` | TEXT | yes | the termination's **effective date** `E`, never its occurrence key (spec §2.2, T2) |
| | `last_termination_kind` | TEXT | no | `COMPLETED｜CLOSED｜NONE` |
| | `computed_due_on` | TEXT | yes | |
| | `effective_due_on` | TEXT | yes | the sort key: `postponed_due_on ?: computed_due_on` |
| | `season_active` | INTEGER | no | |
| | `computed_for_on` | TEXT | no | the `T` used |
| | `computed_at` | INTEGER | no | |
| | indices | | | `effective_due_on` |
| | | | | derived: **never exported** (invariant 64) |
| `schedule_local_delivery` | `schedule_id` | TEXT | PK | FK schedule CASCADE |
| | `snoozed_until_at` | INTEGER | yes | D-13 |
| | `last_notified_at` | INTEGER | yes | drives the 3-day overdue re-notification (D-5) |
| | `first_entry_seen` | INTEGER | no | DUE_SOON is announced once (D-5) |
| | `action_nonce` | TEXT | yes | the current notification's nonce (D-21) |
| | `nonce_issued_at` | INTEGER | yes | |
| | `updated_at` | INTEGER | no | |
| | | | | device-local: **never exported, never merged** (invariants 64, 65) |

**`Plan decision:` `schedule_state` carries `computed_due_meter`** (spec §2.2 names it among the materialised fields but §3.1's table does not enumerate columns); it is derived like the rest and is what `/v1/due` and the dashboard read.

**`Plan decision:` `anchor_on` is nullable, non-null exactly when `time_interval` is non-null.** Spec §2.1 lists `anchorOn: LocalDate` under the time side while §4.1's command has it optional and a meter-only schedule has no series; making the column nullable is the only shape that represents both without a sentinel date.

### 2.2 The altered table

`asset_event` gains three columns and two indices (spec §3.1, D4 §5):

| column | type | null | FK / notes |
|---|---|---|---|
| `schedule_id` | TEXT | yes | FK `maintenance_schedule` **SET NULL** — archiving a schedule keeps the link, deleting one nulls it |
| `occurrence_on` | TEXT | yes | the occurrence key; **immutable once written** |
| `details_pending` | INTEGER | no, default 0 | 1 when a minimal completion is created against a `FORM` schedule |

New indices: `UNIQUE(schedule_id, occurrence_on, asset_id)` and `(schedule_id, occurred_on DESC)`. SQLite treats NULLs as distinct, so the unique index is **inert for every non-completion event** — the same property D4 §15 relies on. The shipped `UNIQUE(source, source_ref)` (`app/.../data/room/entities/JournalEntities.kt:167`) is **not** overloaded for occurrence identity; `source_ref` stays free for sync provenance.

`EventSource` widens from `MANUAL, IMPORT` (`core/.../core/model/Journal.kt:38`) to include **all three** of `SCHEDULE_QUICK_COMPLETE`, `TODOIST_SYNC`, `TELEMETRY` at once, though only the first is ever written in 1.2 (D-18b). Reason: `enumOrCorrupt` throws `BackupCorrupt` on an unknown name (`core/.../core/backup/BackupFormat.kt:247-249`), so a later archive carrying `TODOIST_SYNC` would be unreadable by a 1.2 build; declaring all three now is free, and safe because an older build refuses a format-6 archive before reading any row (`core/.../core/backup/BackupCodec.kt:150-151`).

### 2.3 What is **not** a SQL constraint, and why

**`Plan decision:` — and a named *spec deviation*, not a silence (§18): no `CHECK` constraint is declared anywhere.** Spec §2.1 states two (`(asset_id IS NULL) <> (group_id IS NULL)`; `time_interval IS NOT NULL OR meter_definition_id IS NOT NULL`) as domain rules. Room 3 cannot declare a `CHECK` on an `@Entity`, so one written only into the migration would exist on upgraded databases and not on fresh ones — two different schemas for one version, against the one-source rule the migration file itself states (`app/.../data/room/Migrations.kt:7-16`). Both are therefore **use-case invariants with tests**, exactly as spec §2.3 already rules for the at-most-one-open membership rule (a partial unique index is not expressible in Room either). Every rule enforced this way, and its owner:

| rule | invariant | enforced in | proved by |
|---|---|---|---|
| exactly one of `asset_id` / `group_id` | 1 | schedule save use case (B02); API **422** `SCHEDULE_TARGET_INVALID` (B12); merge `SCHEDULE_TARGET_INVALID` (B01) | B02, B12, B01 |
| at least one of the time and meter sides | 10 | schedule save use case (B02); API **422** | B02, B12 |
| a group target carries no meter rule and no `profile_id`, and is `IGNORE` season | 2, 3, 27 | schedule save use case (B02); API **422** | B02, B12, B14 |
| at most one membership row per `(group, asset)` with `removed_at IS NULL` | 80 | group save / add-member use cases (B03); API **422** `MEMBER_ALREADY_OPEN` (B12); merge `GROUP_MEMBER_ALREADY_OPEN` as an archive-internal guard (B01) | B03, B12, B01 |

**A schedule's shape violations are 422, not 409** (spec revision 4.1). Both targets set, neither set, no rule side at all, a meter rule on a group target and `FOLLOW_ASSET` on a group target are **bad-rule refusals** — the command describes a schedule that cannot exist — so they answer **422** with the domain's own problem names, like every other validation failure (`docs/api/v1.md:229`). 409 stays for refusals **about state**: an occurrence taken, closed, complete or not closeable, a completion of an archived schedule, an archive from a newer build.

The unique indices **are** real database constraints and are what make idempotence provable rather than hoped for: `UNIQUE(schedule_id, occurrence_on, asset_id)` (invariant 32) and `UNIQUE(schedule_id, occurrence_on)` on closures (invariant 38).

### 2.4 The migration

`MIGRATION_5_6` in `app/.../data/room/Migrations.kt`, chained into `AppGraph.kt:88`, `AppDatabase.version` → 6, `AppGraph.SCHEMA_VERSION` → 6, `app/schemas/.../6.json` committed. Every statement is **copied verbatim from the exported `6.json`**, in the `MIGRATION_4_5` shape (`Migrations.kt:211-235`) — the migration and the compiled entity have one source, and Room validates the result on open.

Because `asset_event` is **altered** and not only added to, this is not a pure table addition. **`Plan decision:` — a named *spec deviation*, not a silence (§18): spec §3.1 prescribes "`ALTER TABLE ADD COLUMN` ×3 plus two `CREATE INDEX`, all copied from `6.json`"; this plan fixes the two properties instead and leaves the statements to the implementer's proof.** **The requirement, not the implementation:** after the migration, (a) the database validates against the compiled `6.json` — same columns, same primary keys, same foreign keys, same indices as a fresh v6 install, and (b) **every pre-existing row of every pre-existing table is byte-identical to what it was before**, asserted by a `MigrationTest` that seeds v5 rows, migrates, and reads them back (D4 §15 lines 584-585). Two implementations satisfy that — three `ALTER TABLE ADD COLUMN` (the `schedule_id` column carrying its `REFERENCES … ON DELETE SET NULL` clause with a NULL default) plus two `CREATE INDEX`, or the 12-step table recreate — and the choice is the implementer's, decided by which one the validation and the row-identity proof actually accept. B01 records which it used and why.

---

## 3. Shared contract B — backup format 5 → 6

Spec §3.2. `BackupCodec.FORMAT_VERSION` 5 → 6 (`core/.../core/backup/BackupCodec.kt:47`).

`BackupData` (`BackupFormat.kt:231-240`) gains three lists, each **defaulting to empty** so every format ≤5 archive still decodes unchanged — the pattern its four existing optional lists already use (`BackupFormat.kt:236-239`):

```kotlin
val maintenanceGroups: List<MaintenanceGroupDto> = emptyList(),        // members travel inside
val maintenanceSchedules: List<MaintenanceScheduleDto> = emptyList(),  // providers travel inside
val occurrenceClosures: List<OccurrenceClosureDto> = emptyList(),      // their own rows, never nested
```

and `AssetEventDto` (`BackupFormat.kt:191-208`) gains `scheduleId: String? = null`, `occurrenceOn: String? = null`, `detailsPending: Boolean = false`.

### 3.1 New DTO field sets

Field counts are part of the contract: B01's round-trip proof asserts each DTO's serial descriptor element names against this table, so a field added later with a default cannot drift past a generator or a client unnoticed.

| DTO | fields | count |
|---|---|---|
| `MaintenanceGroupDto` | `id, name, description, archivedAt, createdAt, updatedAt, members` | 7 |
| `GroupMemberDto` | `id, assetId, sortOrder, addedAt, removedAt` | 5 |
| `MaintenanceScheduleDto` | `id, assetId, groupId, title, description, timeInterval, timeUnit, timeBasis, anchorOn, leadDays, meterDefinitionId, meterInterval, anchorMeter, meterLead, seasonBehavior, seasonReentry, seasonReentryOffsetDays, completionMode, profileId, remindersEnabled, status, postponedDueOn, createdAt, updatedAt, providers` | 25 |
| `ScheduleProviderDto` | `provider, enabled` | 2 |
| `OccurrenceClosureDto` | `id, scheduleId, occurrenceOn, closedOn, createdAt` | 5 |

`GroupMemberDto` carries no `groupId` and `ScheduleProviderDto` no `scheduleId`, exactly as `ProfileFieldDto` carries no `profileId` (`BackupFormat.kt:140-145`): a child row's owner is its position in the tree.

**Closures do not travel inside the schedule DTO.** They must be their own rows, or closing a round would change the schedule's exported content and every later re-import would be `CONTENT_DIFFERS` — the defect D-8 rejected (spec §3.2).

### 3.2 Encoder, counts, decoder

- **Sorting** (`BackupCodec.kt:80-98`'s determinism discipline): the three new top-level lists by `id`; a group's `members` by `(sortOrder, id)`; a schedule's `providers` by `provider`. `Plan decision:` members tie-break on `id` because `sortOrder` is not promised unique within a parent and the planner's own normalisation is `(sortOrder, id)` (`MergePlanner.kt:454-466`, with its reasoning at `:64-66`); two stable sorts over two different bases would otherwise disagree.
- **New `counts` keys** (`BackupCodec.kt:108-120`, 11 keys today → 16): `maintenanceGroups`, `groupMembers`, `maintenanceSchedules`, `scheduleProviders`, `occurrenceClosures`.
- **Decoder:** the `formatVersion > FORMAT_VERSION` refusal (`BackupCodec.kt:150-151`) and the `formatVersion >= 5 ⇒ backupSetId` check (`:155-157`) are unchanged. Every new row is validated by `toDomain()` in the same eager pass as the shipped tables (`BackupCodec.kt:174-180`), so a corrupt schedule, membership or closure is refused **before any import begins**. `validateGraph` gains the new references: a member's `asset_id`, a schedule's target / meter definition / profile, a closure's `schedule_id`, and an event's `schedule_id`.
- **`schedule_state` and `schedule_local_delivery` are not exported** — the first because every column is on D4 §12's derived side, the second because it is device-local. `schedule_state` is rebuilt after any import.
- **Direction:** 1.2 reads formats 1–6. A format ≤5 archive restores with no groups, no schedules and no closures, its events decoding with all three new fields at their defaults, and **restoring it never invents a schedule** (invariant 62). A 1.1.x build refuses a format-6 archive loudly with `BackupNewerFormat` rather than dropping rows (invariant 63).

---

## 4. Shared contract C — the merge

Spec §3.3, §3.4. `MergeTable` (`core/.../core/merge/MergePlan.kt:20`) is both the write order and the conflict sort key. Three members are inserted **in dependency position** (D-19):

```
ASSETS, GROUPS, DEFINITIONS, PROFILES, SCHEDULES, CLOSURES, LINKS, TAGS, EVENTS, ATTACHMENTS
```

A group's members reference assets, so `GROUPS` follows `ASSETS`. A schedule references an asset or a group, a meter definition and a profile, so `SCHEDULES` follows all four. A closure references only a schedule, so `CLOSURES` follows it. An event references a schedule, so `EVENTS` keeps its place. `MergeWrites` (`MergePlan.kt:142-150`), `MergeSnapshot` (`:163-173`) and `MergeReport` (`:182-196`) each gain `groups`, `schedules` and `closures` in write-order position.

**One in-code sentence goes stale the moment this lands.** `MergePlan.kt:14-20`'s KDoc reads "The **seven** canonical tables, **in the order a merge must write them**"; it becomes **ten**, and it is **B01's** edit, in B01's own diff, with a grep in B01's gate. It is called out here beside the enum rather than left to the whole-branch review, because a stale comment immediately above the enum an implementer is editing is the cheapest kind of document rot to catch and the easiest to miss.

### 4.1 Per-table rules

The planner's five-step shape is unchanged (`MergePlanner.kt:29-60`): own id → second identity where one exists → every unique constraint, against the destination **and** the archive's own accepted rows → every reference → bytes (attachments only).

| table | identity | unique constraints checked | new `CONFLICT` reasons |
|---|---|---|---|
| `GROUPS` | row `id` only — **never a name** (#55 AC 12, invariant 7) | `UNIQUE(group_id, asset_id, added_at)`; the at-most-one-open rule; member child-row PKs | `CONTENT_DIFFERS`; `GROUP_MEMBER_WINDOW_TAKEN`\*; `GROUP_MEMBER_ALREADY_OPEN`\*; `OWNER_NOT_AVAILABLE`; `CHILD_ROW_ID_TAKEN` |
| `SCHEDULES` | row `id` | `schedule_provider` PK `(schedule_id, provider)` | `CONTENT_DIFFERS`; `OWNER_NOT_AVAILABLE` (target asset/group, meter definition or profile absent); `SCHEDULE_TARGET_INVALID` (both or neither set) |
| `CLOSURES` | row `id`, **plus `(schedule_id, occurrence_on)` independently of the id** | that pair, against destination and archive | `CONTENT_DIFFERS`; `CLOSURE_DIVERGED`; `CLOSURE_DUPLICATED_IN_ARCHIVE`; `OWNER_NOT_AVAILABLE`; `CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` rides on an `IDENTICAL` |
| `EVENTS` (altered) | row `id`, plus the shipped `(source, source_ref)` | the new `UNIQUE(schedule_id, occurrence_on, asset_id)` | existing `EVENT_SOURCE_REF_TAKEN`; new `SCHEDULE_OCCURRENCE_TAKEN` |

`MergeReason` therefore gains seven members: `GROUP_MEMBER_WINDOW_TAKEN`, `GROUP_MEMBER_ALREADY_OPEN`, `SCHEDULE_TARGET_INVALID`, `CLOSURE_DIVERGED`, `CLOSURE_DUPLICATED_IN_ARCHIVE`, `CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`, `SCHEDULE_OCCURRENCE_TAKEN`. `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` and `CHILD_ROW_ID_TAKEN` already exist (`MergePlan.kt:39-107`).

**\* The two membership codes are archive-internal planner guards** (spec revision 4.1), exactly as `PROFILE_FIELD_DEFINITION_TAKEN` is (`MergePlan.kt:73-81`). Because membership rows travel **inside** the group DTO and a group's identity is its row id alone, two phones that disagree about a group's membership disagree about the **group row's content**, and that surfaces as **`CONTENT_DIFFERS` on the group row** — not as a membership code. `GROUP_MEMBER_WINDOW_TAKEN` and `GROUP_MEMBER_ALREADY_OPEN` are therefore reachable from an archive that is internally inconsistent (two of its own groups claiming one `(group_id, asset_id, added_at)`, or one group carrying two open windows for one asset) or from a hand-built archive whose membership collides with a destination row under a different group id. **B01's test rows for these two codes must be constructed on that basis** — a crafted archive, not a two-phone divergence story — and B01 also proves that ordinary divergent membership between two phones is `CONTENT_DIFFERS` on `GROUPS` and nothing else.

### 4.2 The closure's second identity

Spec §2.9's table, verbatim as the contract:

| situation | verdict |
|---|---|
| the row id is absent here and `(schedule_id, occurrence_on)` is unclaimed | `INSERT` |
| the row id is here and every backup-format field matches | `IDENTICAL` |
| the row id is here and any field differs | `CONFLICT / CONTENT_DIFFERS` |
| a local row under a different id holds this `(schedule_id, occurrence_on)` and is otherwise equal field for field | `IDENTICAL / CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` |
| a local row under a different id holds it and differs (a different `closed_on`, say) | `CONFLICT / CLOSURE_DIVERGED` |
| two rows of one archive claim one `(schedule_id, occurrence_on)` | `CONFLICT / CLOSURE_DUPLICATED_IN_ARCHIVE` |
| the schedule it names is neither here nor being inserted | `CONFLICT / OWNER_NOT_AVAILABLE` |

The equivalence test is the shipped one, `dto.copy(id = local.id) == local.toDto()` (`MergePlanner.kt:270`). Two devices that closed the same round on the same day merge cleanly; a genuine disagreement about *when* a round was closed is a conflict for a human. A closure has no `updated_at` and no mutable field, so a re-imported unchanged closure is **always** `IDENTICAL`.

### 4.3 The post-apply rebuild is total, deliberately

An imported event touches its own schedule, every schedule of its asset (through `current_meter`) and every group schedule whose required set contains that asset; an imported membership row changes required sets; an imported closure terminates a round; an imported meter reading moves a threshold. Rather than enumerate that closure: **after any merge apply, `rebuild` runs for every schedule in the database, inside the same transaction** (spec §3.3). At this scale it is cheap and provably complete. `schedule_state` itself never appears in a plan.

### 4.4 Why a re-import of an unchanged schedule stays `IDENTICAL`

Spec §3.4. An occurrence advancing writes (1) a new `asset_event` or `occurrence_closure` row — a new id either way, therefore an `INSERT`; (2) `schedule_state` and `schedule_local_delivery`, **neither exported**; (3) the postponement, **and only when it was set** (§1's conditional-write rule). So the rule holds unconditionally for a schedule that was never postponed, which is what lets two phones exchange completions and closures safely. It **cannot** hold for a schedule postponed on one phone and cleared on the other — the two genuinely disagree about the current occurrence's due date, and `CONTENT_DIFFERS` with no automatic winner is correct under "timestamps never pick a winner" (`docs/api/v1.md:263`). The snooze cannot produce that conflict at all, because D-13 keeps it out of the export.

---

## 5. Shared contract D — the scheduling domain

Spec §2.1, §2.2. Written against the shipped model (`core/.../core/model/Asset.kt:5-30`, `Journal.kt:9-59`, `Season.kt:9-57`, `TagBinding.kt:13-25`, `Ids.kt:3-22`), the ports (`core/.../core/ports/Repositories.kt:21-109`, `UnitOfWork.kt:7`) and the use-case shape (`core/.../core/usecase/LogEvent.kt:27-33`: validate, then one `uow.write`).

### 5.1 Identity and the date port

`Ids.kt` gains two inline value classes beside the seven shipped ones (`Ids.kt:3-22`): `ScheduleId` and `GroupId`.

`Clock` supplies `nowMillis()` only (`core/.../core/ports/Clock.kt:3`), and the engine needs a **date**. 1.2 adds one port, and the engine **never reads a clock itself** (D5 line 13: "`T` … passed in explicitly everywhere"):

```kotlin
fun interface Today { fun localDate(): java.time.LocalDate }   // core/.../core/ports/Today.kt
```

`Plan decision:` a port rather than a bare `LocalDate` parameter at the top of the stack — spec §2.2 allows either. Reason: `AppGraph` already owns `Clock`, the digest and backstop runs need the same value, and a fake `Today` is how every deterministic date test in B02/B03/B06 is written without touching a real clock. `rebuild` itself still takes `T` as a parameter, so the pure function stays pure (invariant 16).

### 5.2 `MaintenanceSchedule`

The aggregate root. Fields are §2.1's table exactly; the target is a sealed type mirroring `TagTarget` (`core/.../core/model/TagBinding.kt:7-11`):

```kotlin
sealed interface ScheduleTarget {
    data class AssetTarget(val assetId: AssetId) : ScheduleTarget
    data class GroupTarget(val groupId: GroupId) : ScheduleTarget
}
```

`Plan decision:` the time unit enum is named **`RecurrenceUnit`** (`DAY, WEEK, MONTH, YEAR`), not `TimeUnit`. Spec §2.1 writes `timeUnit: TimeUnit?`; `TimeUnit` collides with `java.util.concurrent.TimeUnit` and would need an import alias at every use site. The wire and column values are the four names, unchanged.

**Recurrence, as contract:**

| rule | statement | spec |
|---|---|---|
| FIXED series | every due date lies on `anchorOn + k·interval`, computed **with a multiplier, never iteratively**, so month-end and leap-day clamping cannot drift | §2.1, invariant 11 |
| FIXED advance | on a termination of the occurrence due `D` with effective date `E`: `nextDue = smallest seriesDate(k) > max(D, E)` | §2.1 |
| no backlog | a very late termination skips forward and produces exactly **one** next occurrence | §2.1, D-7, invariant 13 |
| COMPLETION advance | `nextDue = E.plus(interval)`; with no termination, `anchorOn` | §2.1, invariant 12 |
| the FIXED pin | a **never-terminated** FIXED schedule's occurrence is pinned from immutable configuration: `computedDueOn = smallest seriesDate(k) >= max(anchorOn, createdOn)`. It never re-floats on Today, and the same pin applies after the sole completion is deleted | D-27, invariants 23, 25 |
| the pin's floor after an edit | on a schedule with no terminations, the floor becomes the **edit date**: `smallest seriesDate(k) >= max(anchorOn, editedOn)`, where `editedOn` is the row's `updated_at` date at the edit. Without it, re-anchoring an old never-terminated schedule today would pin it immediately overdue. `05-scheduling-semantics.md:346` is **superseded** and is corrected by B13 | §2.1, D-27, T3 |
| meter side | `computedDueMeter = lastCompletedMeter + meterInterval`; `currentMeter` is the latest reading of the meter definition across the asset's events; with no completion the baseline is `anchorMeter`; with neither, `NO_DATA` | §2.1, D-3, D5 §3 |
| combined | both sides present = "whichever first"; due when either side is due; status is the **worst of** the two | §2.1, D5 §4 |
| calendar arithmetic | `plusMonths`/`plusYears` clamp to the last valid day; a Feb 29 anchor returns to Feb 29 in the next leap year because it is computed from the anchor; weeks and days are plain arithmetic; the engine never sees an instant | D5 §2.3 |
| #11's third flavour | **not adopted** (C8): there are two bases, and "reset on early completion" is COMPLETION | §2.1 |

**The two overrides, and the one rule an implementer will otherwise get wrong.** `postponedDueOn` is the row's **only** override; the snooze lives in `schedule_local_delivery` (D-13). And:

> A completion is an insert into `asset_event` plus a `rebuild`; a closure is an insert into `occurrence_closure` plus a `rebuild`. Either touches the `maintenance_schedule` row **only** when `postponed_due_on` is actually set. One that clears nothing writes no column and does not bump `updated_at`. The same holds for each group member's completion.

`issue-4.md:42` reads "Complete … clears `postponed_due_on` and `snoozed_until_at`", which an implementer would render as an unconditional update. **It must be conditional** (spec §2.1, invariants 68, 69).

### 5.3 `ScheduleState` and `rebuild`

`ScheduleRecompute.rebuild(schedule, events, closures, membership, T)` is the **only** write path into `schedule_state` (invariant 17). It runs after every event insert/update/delete, every closure insert, every schedule edit, every import, and in the digest and backstop runs. It is **pure** in (config, events, closures, membership, `T`) and **idempotent** (invariants 15, 16 — asserted as properties over the function, not with two devices).

Materialised fields are §2.1's `schedule_state` columns. `lastTerminationEffectiveOn` is the termination's **effective date** `E`, not its occurrence key: the key `D` is not materialised because `rebuild` recomputes it from `occurrence_on`. `lastCompletedOn` keeps its narrower meaning — the latest member *completion* — so "last done" never reports a round nobody did.

**`prevDue` is read, not reconstructed** (spec §2.2, invariant 70):

> For FIXED, `prevDue` — the occurrence a termination satisfied — is the terminating row's `occurrence_on`, never a series date reconstructed from a completion's `occurred_on`. A completion event with **no** `occurrence_on` — a pre-1.2 row, or one re-pointed by hand — falls back to the largest series date `<= occurred_on`, which is correct except for an early completion; the fallback is documented as approximate.

Why it is load-bearing: D5 §5's reconstruction ("the largest series date `<= last_completed_on`", `05-scheduling-semantics.md:135-137`) returns the *wrong* occurrence for an early completion — anchor Jan 1, quarterly, due Apr 1, completed Mar 20 reconstructs `prevDue = Jan 1` and yields Apr 1 where D5 §10.1 requires **Jul 1**. B13 corrects D5 §5 and §10.5.

**Status is a pure function of the row plus `T`, computed at read time and never stored** (invariant 18): `OK | DUE_SOON | DUE | OVERDUE | INACTIVE_SEASON | PAUSED | NO_DATA`, worst-of the time and meter sides, `OVERDUE > DUE > DUE_SOON > OK`. Snooze does not appear in status. A schedule due `T` is DUE all day. Time zone (D-6): the engine sees no instant, `T` is the injected device-local date, a zone change never changes *what* is due, only *when* the phone says so, and `tzId` (`Journal.kt:54`) stays audit-only.

---

## 6. Shared contract E — groups, membership and occurrences

Spec §2.3, §2.4.

```kotlin
MaintenanceGroup(id: GroupId, name, description, archivedAt: Long?, createdAt, updatedAt,
                 members: List<GroupMember>)
GroupMember(id: String, assetId: AssetId, sortOrder: Int, addedAt: Long, removedAt: Long?)
```

Members are **child rows of the group aggregate** with durable ids that survive backup verbatim, as `ProfileField`/`ProfileConsumable` are of `EventProfile` (`core/.../core/model/Journal.kt:24-35`). A group is **not** an Asset, is not in the Asset parent/child tree, has no serial number, and **never receives an NFC identity** (invariants 4, 5).

**Membership is append-only in its temporal fields.** Removing a member stamps `removed_at`; re-adding the same asset later **inserts a new row** with a new durable id and a new `added_at`, and never clears an existing `removed_at` (invariants 79, 80). `addedAt` and `removedAt` are **server-stamped and appear in no command**. A membership row is never hard-deleted while any completion or closure references an occurrence its window covered (invariant 8). Archiving a group hides it and its schedules and retains history; archiving or retiring a member **Asset** leaves membership rows untouched, excludes it from *new* occurrences by lifecycle, and leaves an open round alone (D-10, D-16).

### 6.1 Occurrences: what rows exist

**There is no general occurrence table.** The occurrence's identity and its member basis are **derived from history**. The one materialised occurrence-scoped row is §7's closure fact, which exists **only** for a round the owner explicitly closed.

1. A completion is an ordinary `asset_event` on the **real asset**, through the shipped event path (`core/.../core/usecase/LogEvent.kt:27-33`, `EventCommands.kt:31-42`), with `schedule_id` and `occurrence_on` set.
2. `occurrence_on` is stamped at write time from the schedule's `computedDueOn` — **not** the postponed date, so a postpone cannot move an occurrence key (invariant 21). Immutable once written.
3. `source = SCHEDULE_QUICK_COMPLETE` for a one-tap completion; a form completion stays `MANUAL`; `schedule_id` carries the relationship either way (D-18b). `details_pending = 1` only for a minimal completion against a `FORM` schedule — reachable from the notification quick action and from `POST /v1/schedules/{id}/complete` with no `values`, and from nowhere else.
4. **Idempotence is a database constraint**, not a discipline: `UNIQUE(schedule_id, occurrence_on, asset_id)` (invariant 32).

**Asset-targeted occurrence.** One required asset: the schedule's own.

**Group-targeted occurrence** — a checklist of members:

| concept | definition | spec |
|---|---|---|
| **open instant** | the maximum `created_at` over the **previous** occurrence's terminating rows — its member completion events and, if it was closed, its closure row — or the schedule's `created_at` for the first occurrence. `created_at` is exported and compared by the merge, so the instant is identical on every set of rows; `occurred_on` and `closed_on` are deliberately **not** used, because a backdated completion must not move the membership basis | §2.4, invariant 73 |
| **open date** | that instant's calendar date in the device zone | §2.4 |
| **required set** | members whose `[addedAt, removedAt)` window covers the open instant — a pure function of membership rows plus history, with no snapshot table | §2.4, D-10, invariant 33 |
| **completed set** | member assets holding an event with `(schedule_id, occurrence_on)` | §2.4 |
| **complete** | the completed set is **non-empty and covers** the required set. Only then does a completion advance the schedule | §2.4, invariant 30 |
| **empty required set** | not actionable: never offered for completion, never counted as due, **never closeable**, and the schedule reports `NO_DATA`. **Emptiness never means complete.** Creating a group-targeted schedule on a group with no members is refused (422) | §2.4, invariants 74, 77 |

"Complete all" writes one event per not-yet-completed required member in one `uow.write`; the unique index makes a repeat a no-op rather than a duplicate (invariant 31). Completing one member **never** writes an event on another and never on a non-member (invariants 28, 29). Progress ("3 of 5 complete") is derived. An asset in two groups with the same operation gets two schedules and two occurrences, with **no semantic deduplication**; the editor shows a non-blocking warning (D-11).

A recurrence edit **abandons** an open partially complete occurrence (D-9): the recorded member completions stay as truthful history and the edited rule creates the new current occurrence.

---

## 7. Shared contract F — round closure (the D-8 fact)

Spec §2.9. Closure is **immutable, exported history**: one append-only row per closed round, sparse by construction — an ordinary completed round produces no row, and a schedule always finished on time carries zero closure rows for its whole life.

**What "Close this round" writes.** In one `uow.write`: exactly one `occurrence_closure` row `(new uuid, schedule_id, occurrence_on = the schedule's current computedDueOn, closed_on, created_at = now)`, then `rebuild`. It writes **no column on `maintenance_schedule`** and **no `asset_event` on any asset** — which is the whole point: a closed round never claims anybody did the work (invariants 35, 36). Closing the same round twice is refused by the unique index; the route answers 409 `OCCURRENCE_ALREADY_CLOSED` and the first closure stands (invariant 38). Once a round is closed, no further completion may be written with that `occurrence_on` (invariant 39, a 409); work done afterwards is logged as an ordinary journal event with no `schedule_id`. In 1.2 the action is offered **only on group-targeted schedules**, and only on a round whose required set is non-empty.

**Who sets `closed_on`, and its range** (mirroring D-25, which the owner already ruled for completions):

> `closedOn` defaults to today and may be **any date from the occurrence's open date through today, inclusive**; a future date, or one before the occurrence opened, is 422 `CLOSED_ON_OUT_OF_RANGE`. The in-app action offers today by default and the same past range, using the ratified **"When was this done?"** affordance; the API accepts the same range, so the two paths cannot diverge.

**`terminations` — how `rebuild` reads closures and events together.** A closure is a termination with a date, so both recurrence bases advance from it exactly as they do from a completion. The function is **total**:

```
terminations(schedule, events, closures, membership):
  for each occurrence key D in this schedule's events or closures:
    C(D) = events with (schedule_id, occurrence_on = D)      // may be empty
    R(D) = required members of D                             // may be empty
    if R(D) is empty            -> D is not a termination     // invariant 77
    else if C(D) is non-empty and assets(C(D)) ⊇ R(D)
                                -> (D, max occurred_on over C(D), COMPLETED)
    else if a closure exists for (schedule, D)
                                -> (D, max(closed_on, max occurred_on over C(D) or closed_on), CLOSED)
    else                        -> D is still open; not a termination
  ordered by (D, effectiveOn)
```

The five consequences, each of which is why this shape was chosen (spec §2.9), all of which a test in B03 pins:

1. **A full completion beats a closure for the same occurrence.** A stray closure for a round that is in fact fully completed is inert. Local writes cannot create that ambiguity, but a merge can, and this precedence makes the outcome deterministic without a cross-table check in the planner (invariant 40).
2. **`max(closed_on, …)` keeps the effective date monotone** even if a member completion carries a date later than the closure date.
3. **Both bases advance from a closure.** COMPLETION: `nextDue = closed_on + interval`. FIXED: `closed_on` enters `nextDue = smallest seriesDate(k) > max(D, E)`, so a late closure skips the series forward exactly as a late completion does and an early closure does not move it. No special case was needed.
4. **The same history reproduces the same recurrence after later rounds advance.** A recurrence edit does **not** touch existing closures: their `occurrence_on` values may lie off the new series, which is harmless because `nextDue` is computed against the new series from `max(D, E)`. **An implementer must not "tidy" old closures after an edit** (invariant 14).
5. **And after an event is deleted.** Delete a member completion of a later round and that round is open again; `last` falls back to the earlier closure and the due date returns to the value derived from `closed_on` (invariants 24, 41).

A closed round is also **immune to membership churn** — it stays `CLOSED` whatever `required(D)` later becomes — whereas a completed round is not: a membership change can make `required(D)` grow and reopen it. Closure is the more durable of the two facts, which is what D-8 asked for.

One asymmetry, recorded rather than fixed: deleting a schedule preserves its completion events (`schedule_id` SET NULL) but cascades its closures away. A closure is meaningless without its schedule, and **no 1.2 route deletes a schedule** (invariant 76), so it is unreachable in 1.2.

---

## 8. Shared contract G — the reminder port and delivery state

Spec §2.5. One provider-neutral port in `:core`, with **no Android and no provider type in its signature** (#28 AC 1, invariant 48). Shape as D3 §7.1 (`docs/design/03-target-architecture.md:184-203`):

```kotlin
data class ReminderSubject(
    val key: SubjectKey, val title: String, val body: String,
    val dueOn: LocalDate?, val leadDays: Int,
    val state: SubjectState,                          // ACTIVE | PARKED(reentryOn) | COMPLETED | WITHDRAWN
    val rule: RuleFacts?,                             // basis, interval, unit, hasMeter, seasonal
    val contentHash: String,
)
interface ReminderProvider {
    val id: ProviderId
    suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport
    suspend fun pullChanges(): List<RemoteChange>     // LOCAL: always empty
    suspend fun health(): List<HealthFinding>
}
```

`reconcile` is the **whole write surface**: it receives the desired state of every subject the provider owns and makes the provider match it. There is deliberately **no "create one reminder" call**, which is what makes idempotence structural (#28 AC 2, invariant 45) and #27's repair safe; `contentHash` suppresses no-op work (invariant 46). Subjects come from `schedule_provider` rows: **one subject list per enabled provider** (invariant 49). A group-targeted schedule is **one** subject, not one per member, with the progress line in its `body` (D-15). A seasonally inactive or paused schedule arrives as `PARKED(reentryOn)`, never absent and never overdue (invariant 47). `rule` carries the rule's *facts* so a future provider can decide whether its own recurrence engine can carry the subject; the provider never sees the schedule entity.

`Plan decision:` the four types the spec names but does not define are B04's, with these shapes fixed here so B06, B10 and B12 can compile against them: `SubjectKey` is a sealed type with `Schedule(ScheduleId)` in 1.2 (`Supply` is #15); `ProviderId` is an enum with **`LOCAL` only** in 1.2 (`TODOIST` is Phase 5 and adding it now would be a dead member the editor could write); `ReconcileReport` carries `posted: Int, cleared: Int, unchanged: Int, problems: List<String>`; `HealthFinding` carries `code: String, severity: Severity (INFO|WARN|ERROR), message: String, repair: RepairAction?` where `RepairAction` is a sealed type with `Automatic(code)` and `OpenSystemSettings(code)` and `OpenInApp(code)`. Reason: spec §2.5 fixes `ReminderSubject` and the port but leaves these four to the implementation, and three briefs need one agreed shape. **The seven finding codes are the spec's** (§5.8); their sentences are PROPOSED (§17).

**The local provider has no projection rows** (D4 §10). Its "projection" is the posted notifications plus the armed alarm — which is why it is rebuildable from nothing (invariant 44) and why 1.2 creates no projection or outbox table. Its bookkeeping is the six columns of `schedule_local_delivery` (§2.1). **Nothing in it is canonical, exported or merged.** Losing it costs at most one repeated notification, which is why it is the right place for all four facts, and the provider must behave correctly — at worst noisily — when it is **empty**. The nonce is persisted, not in-process, so a quick action still works after process death, and it is cleared on successful use and on replacement or reconcile (D-21, invariant 56).

---

## 9. Shared contract H — the `/v1` additions

Spec §4.1. The version stays **1**; the changes are additive and documented (D-19). Everything obeys the page's discipline: one request per connection; unknown fields rejected (`docs/api/v1.md:95`); response rows are the backup format's own DTOs from the same mappers (`:98-103`); **PATCH, and `POST …` with an `id`, are full replaces** (`:134-143`); **request shape ⊂ response shape** (`:145-154`); the route table is an explicit `when` over path segments (`app/.../api/ApiRouter.kt:63-120`) under the 64 KiB cap (`ApiRouter.kt:7`); envelopes follow `app/.../api/ApiDtos.kt:44-87`.

### 9.1 Route table

| method | path | body | success | notes |
|---|---|---|---|---|
| `GET` | `/v1/groups` | — | 200 | `{groups: [MaintenanceGroupDto]}`, archived included, by name |
| `POST` | `/v1/groups` | group command | 201 | `{group}` |
| `GET` | `/v1/groups/{id}` | — | 200 | `{group}` |
| `PATCH` | `/v1/groups/{id}` | group command | 200 | `{group}`; **full replace**; an omitted member **soft-removes** it; a member sent **without an `id`** for an asset that already has an open membership row is **422 `MEMBER_ALREADY_OPEN`** |
| `POST` | `/v1/groups/{id}/archive` | `{"archived": true｜false}` | 200 | `{group}`; archive is not delete; history retained |
| `GET` | `/v1/groups/{id}/schedules` | — | 200 | group-targeted only |
| `GET` | `/v1/assets/{id}/groups` | — | 200 | `{groups: [...]}` — #55's asset → groups direction |
| `GET` | `/v1/schedules` | — | 200 | `{schedules: [MaintenanceScheduleDto]}` |
| `GET` | `/v1/assets/{id}/schedules` | — | 200 | asset-targeted only; sibling of `…/definitions` (`docs/api/v1.md:120`) |
| `POST` | `/v1/schedules` | schedule command | 201 | `{schedule}` |
| `GET` | `/v1/schedules/{id}` | — | 200 | `{schedule, state, status, computedForOn}` |
| `PATCH` | `/v1/schedules/{id}` | schedule command | 200 | `{schedule}`; **full replace**; a rule change clears `postponedDueOn`, abandons an open partial occurrence (D-9), moves the D-27 pin's floor to the edit date, and rebuilds |
| `POST` | `/v1/schedules/{id}/pause` | `{"paused": true｜false}` | 200 | `{schedule}` |
| `POST` | `/v1/schedules/{id}/archive` | `{"archived": true｜false}` | 200 | `{schedule}` |
| `POST` | `/v1/schedules/{id}/complete` | completion command | 201 | `{event, schedule, state}`; **the only route that writes a completion event** |
| `POST` | `/v1/schedules/{id}/postpone` | `{"postponedDueOn": "YYYY-MM-DD"｜null}` | 200 | `{schedule, state}`; changes no rule, creates no event; `null` clears |
| `POST` | `/v1/schedules/{id}/close-round` | `{"closedOn": "YYYY-MM-DD"}` optional | 201 | `{closure, schedule, state}`; group-targeted only; writes one `occurrence_closure` row and nothing else |
| `GET` | `/v1/schedules/{id}/closures` | — | 200 | `{closures: [OccurrenceClosureDto]}`, oldest first — read-only history |
| `GET` | `/v1/due` | — | 200 | `{items: [...]}` — the dashboard read model, attention-ordered |

### 9.2 The three commands

Each satisfies request ⊂ response: no `id`, `status`, `createdAt`, `updatedAt`, `addedAt`, `removedAt` or `archivedAt` appears in any of them.

| command | fields | rules |
|---|---|---|
| **group** | `{name, description, members: [{id?, assetId, sortOrder}]}` | `name` required; `description` defaults `""`. A member's `id` identifies an existing membership row to **keep**; one with no `id` is an **add**, and an add for an asset that already has an open membership row is **422 `MEMBER_ALREADY_OPEN`** (invariant 80) — the caller meant "keep it", and the way to say that is to send its `id`. `addedAt`/`removedAt` are server-stamped and in no command |
| **schedule** | `{title, description, targetAssetId?, targetGroupId?, timeInterval?, timeUnit?, timeBasis?, anchorOn?, leadDays, meterDefinitionId?, meterInterval?, anchorMeter?, meterLead?, seasonBehavior, seasonReentry?, seasonReentryOffsetDays?, completionMode, profileId?, remindersEnabled, providers: [{provider, enabled}]}` | exactly one of the two targets; at least one of the time and meter sides; `postponedDueOn` is **not** in the command — it has its own route |
| **completion** | `{occurredOn, occurredTime?, tzId, notes?, values?, consumables?, assetId?}` | `assetId` is **required and validated as a required member** for a group-targeted schedule, and **must be absent or the schedule's own asset** otherwise. Any valid past `occurredOn` is accepted (D-25) |

### 9.3 Notes that pin the contract

- **A `PATCH /v1/groups/{id}` that omits a member soft-removes it.** The route stamps `removed_at` and **never deletes a row** (invariant 8). "A member list left out" means every membership becomes closed — not that rows disappear — and every past occurrence's required set is unchanged. Re-adding the asset later inserts a new row.
- Both `…/archive` routes return the row (200), not 204, matching `docs/api/v1.md:119`.
- **`status` is not stored and is therefore in no command.** `GET /v1/schedules/{id}` returns the row, the derived state and the computed status word with the `today` used echoed as `computedForOn`. **`ScheduleStateDto` is a derived read projection, deliberately outside `BackupData`; no command accepts it and no archive carries it.**
- **`GET /v1/due` item shape:** `{scheduleId, targetKind ("ASSET"｜"GROUP"), assetId?, groupId?, title, status, effectiveDueOn?, computedDueMeter?, currentMeter?, lastCompletedOn?, lastTerminationEffectiveOn?, lastTerminationKind, completionMode, membersRequired?, membersComplete?, rank}` — every field present, defaults encoded (`docs/api/v1.md:105-106`). A group-targeted schedule is **one** item with the two member counts and is counted **once** (D-15). `Plan decision:` `rank` is the 0-based index of the item in the attention order §10 defines, so a client can reproduce the app's order without re-deriving it.
- **The status split, stated once** (spec revision 4.1). **422 = the command describes something that cannot exist** (a bad rule): `SCHEDULE_TARGET_INVALID` (both targets set, or neither), no rule side at all, a meter rule or `FOLLOW_ASSET` on a group target, a group-targeted schedule on an **empty group**, `CLOSED_ON_OUT_OF_RANGE`, and `MEMBER_ALREADY_OPEN`. **409 = a refusal about state:** `SCHEDULE_OCCURRENCE_TAKEN` (a repeat for an occurrence that asset already completed), `OCCURRENCE_CLOSED` (completing a closed occurrence), and `close-round`'s four — `CLOSE_NOT_SUPPORTED` (asset-targeted), `OCCURRENCE_ALREADY_CLOSED`, `OCCURRENCE_ALREADY_COMPLETE`, `OCCURRENCE_NOT_CLOSEABLE` (an empty required set) — plus a completion of an archived schedule. Every one of these codes carries user meaning; **no new code is a bare `CONTENT_DIFFERS`-style catch-all.**
- **No route to delete or amend a closure. No snooze endpoint** (under D-13 the snooze is not canonical data). **`POST /v1/events` cannot create a completion**: its command has no `scheduleId` and no `source`, and 1.2 does not add them.
- `/v1/status`'s `counts` map (`app/.../api/ApiHandlers.kt:104-119`) gains `groups`, `schedules` and `closures` keys.
- Errors reuse the shipped envelope and mapping (`docs/api/v1.md:213-234`).

### 9.4 `import_merge`

`POST /v1/import-merge/plan` and `…/apply` keep their paths, their `application/zip` type and their 4 MiB ceiling (`ApiRouter.kt:10-14`), and now read a format **≤6** archive. The report gains `groups`, `schedules` and `closures` tallies, and `conflicts` gains §4.1's new reason codes. `applicable` is still the only field a client must read; a merge still only ever inserts; one conflict still writes nothing. The page's own prose "Each of the seven tables" (`docs/api/v1.md:195`) becomes wrong and is on B12's edit list.

---

## 10. Shared contract I — the MCP tools

Spec §4.3. `tools/servicetag-mcp/` gains **seventeen** tools, named as the shipped twenty-one are (`tools/servicetag-mcp/README.md:56-63`), conventions unchanged: an unknown argument is rejected before the tool body runs; on an edit the tool reads the row, overlays only supplied arguments and submits the complete replacement; an **omitted** argument and an explicit **`null`** both mean "leave alone"; clearing is explicit and **by name** through `clear_fields`.

| tool | shape | notes |
|---|---|---|
| `list_groups`, `get_group`, `list_asset_groups`, `list_schedules`, `get_schedule`, `list_closures`, `list_due` | read | plain pass-throughs |
| `create_group`, `create_schedule` | create | every field explicit |
| `update_group` | **overlay** | the member list is the hard case: an overlay treating an omitted `members` as "leave alone" makes closing every membership impossible, so that is `clear_fields=["members"]`; a supplied list replaces wholesale, soft-removing every member it omits |
| `update_schedule` | **overlay** | |
| `archive_group`, `pause_schedule`, `archive_schedule` | state | boolean argument, no overlay |
| `postpone_schedule` | **overlay, sharpest nullable case** | under the null-means-unchanged rule (`README.md:77-84`), `postponed_due_on=None` cannot clear a postponement, so clearing is `clear_fields=["postponed_due_on"]` even though the wire route accepts a literal `null` |
| `complete_schedule`, `close_round` | **no overlay** | like `update_event` (`README.md:86-90`): every argument explicit, because each is a new fact and nothing about it can be inherited from a row. `close_round`'s docstring states plainly that it records that a round ended **without** claiming the outstanding members were serviced, and that the row can never be amended or deleted |

**B12 audits every nullable argument on all seventeen tools for the `postpone_schedule` hole and documents each one's clear path.** No destructive tool is added (`README.md:111-113` stays true): no delete-group, no delete-schedule, no delete-closure, no snooze. `import_merge` keeps its shape and now accepts a format-6 archive. `README.md:58`'s "Twenty-one" becomes **thirty-eight** and is on B12's edit list.

---

## 11. Shared contract J — navigation, the Maintenance destination and the dashboard

Spec §2.6, §2.8, §5.8, the navigation ruling.

**Primary navigation becomes Dashboard · Assets · Maintenance.** `TopLevelRoutes` (`app/.../ui/nav/Route.kt:64`) gains a third member and `BottomBar`'s `iconFor`/`labelFor` (`app/.../ui/nav/BottomBar.kt:37-46`) gain its row; the label is the ratified **"Maintenance"**. The NFC scan stays an interaction mechanism, not a tab — consistent with D12 §16, which already removed the Scan tab in 2B-2 (`docs/design/12-visual-design-apollo-service-binder.md:1217-1223`). Maintenance hosts **due work, schedules (including PAUSED), maintenance groups and reminder health**.

`Plan decision:` the new `Route` members, since spec §2.6 names the destination but not the back stack: `Maintenance` (object), `ScheduleDetail(id)`, `ScheduleEdit(scheduleId: String?, targetAssetId: String?, targetGroupId: String?)`, `GroupDetail(id)`, `GroupEdit(id: String?)`, `ReminderHealth` (object), `MaintenanceSheet(assetId: String, tagId: String?)`. All `@Serializable`, carrying **ids and never whole domain objects**, as `Route.kt:6-9` requires. `Route.readsTags()` (`Route.kt:90-93`) is **unchanged** — no new screen reads tags, so no new screen holds reader mode.

`DeepLinkRoute` (`core/.../core/links/DeepLinkRoute.kt:18-27`) gains `servicetag://schedule/<uuid>` with the same canonical-UUID shape check (`DeepLinkRoute.kt:16`) and the same navigation-only guarantee, plus the matching `intent-filter` `data` line beside `AndroidManifest.xml:44-45`. **No group deep link** (D-17).

### 11.1 The dashboard read model

A read model over `Asset` + `ScheduleState`, **no new canonical rows**, extending the shipped `DashboardState`/`DashboardViewModel` (`app/.../ui/dashboard/DashboardViewModel.kt:40-50`, `:109-136`), which today filters by lifecycle and search only. The existing search-field set (`DashboardViewModel.kt:60-67`) is unchanged.

- Sort key is `effective_due_on`; **status is computed at read time**, so a stale status is unrepresentable. Because re-entry is deferred (D-4) and the FIXED pin is immutable (D-27), the stored sort key is `T`-independent and cannot mis-sort behind a stale `computed_for_on`.
- **The section order is D12 §10's and every state has a home:** fixed at **ATTENTION · UPCOMING · CURRENT · OUT OF SEASON**, empty sections omitted (`12-visual-design-apollo-service-binder.md:706-707`).

| status | section | why |
|---|---|---|
| `OVERDUE`, `DUE` | ATTENTION | |
| **`NO_DATA` with a repair action** — a missing meter baseline | **ATTENTION** | it **is** actionable: "Log meter reading" repairs it, and #27 raises a finding for it |
| **`NO_DATA` from an empty required set** (§6.1, invariants 74, 77) | **no section at all** | it is **not** actionable: not counted as due, not promoted, never offered on the scan sheet, never closeable. **Emptiness never means complete, and it never means actionable either** |
| `DUE_SOON` | UPCOMING | |
| `OK` | CURRENT | |
| `INACTIVE_SEASON` | OUT OF SEASON | satisfies #5 AC 2 |
| **`PAUSED`** | **no dashboard section at all** | the dashboard answers what needs attention and a paused schedule needs nothing; it is listed under Maintenance → Schedules carrying the PAUSED label |

- `INACTIVE_SEASON`, `PAUSED` and `NO_DATA` each render **distinctly from `OVERDUE`** wherever they appear, by wording plus icon plus position and never by colour alone (#5 AC 2, D12 §5 `:274-296`), and the hierarchy survives grayscale.
- **A due schedule on a component asset is never hidden.** The shipped list shows only top-level assets while the query is blank (`DashboardViewModel.kt:121`), which would hide a component's schedule and break #5 AC 1. A component row with an **actionable** schedule is **promoted to its attention rank** and rendered with its parent named, reusing the `parentName` the row already carries (`DashboardViewModel.kt:124`); the blank-query "parts live on their systems" rule still governs every non-actionable row (invariant 75).
- **"Actionable" is surface-specific, and deliberately so.** The dashboard's **promotion** rule means a status in ATTENTION or UPCOMING (decision 29). The **scan sheet's** admission set is D-18a's (§11.2) and never opens for `DUE_SOON` alone. Both are right for their own surface, and decision 27 puts **one** projection behind four surfaces — so **`DueReadModel` exposes the `status` and an empty-required-set flag and each surface applies its own predicate; it must not carry a single `isActionable` boolean.** A shared flag would be wrong for one surface the moment it was right for the other, and because the projection feeds the dashboard, the Maintenance shell, B09's sheet and `/v1/due`, a single mis-specified predicate would land on all four at once.
- A group-targeted schedule is **one row that expands to its members' completion state**, and it **counts once** in every due total however many members are outstanding (D-15).
- Archived assets stay out of the default view and keep their history. A **health badge** appears when any finding is ≥ WARN (#27, D3 §7.3).

### 11.2 The scan completion sheet

Built on the shipped resolver: `ResolveTag` returns a `Resolution` and the UI switches on that and nothing else (`core/.../core/usecase/ResolveTag.kt:16-30`, `:45-58`). **Only `Resolution.OpenAsset` may reach the sheet**; `Unbound`, `Revoked`, `UnknownV1`, `NeedsNewerApp`, `NotOurs` and `PreSplitLink` never do (invariant 58). The reader-mode hold stays owned by `ReaderMode` (`app/.../ui/nfc/ReaderMode.kt:20-25`) — 1.2 adds a destination, not a second NFC session.

Flow: resolve tag → asset → evaluate the asset's schedule state (**including group schedules it is a required member of**) → no actionable work ⇒ open the asset as today; otherwise the sheet, titled **"Maintenance"**.

**Which states appear** (D-18a): `DUE` and `OVERDUE` always; `NO_DATA` **when it has a repair action** — the missing meter baseline, offered as **"Log meter reading"**; `DUE_SOON` **only as a passenger**, when the sheet is already open for another actionable item. `OK`, `INACTIVE_SEASON` and `PAUSED` never appear, and neither does an archived or disabled schedule. Items are ordered by §11.1's attention ordering.

---

## 12. Shared contract K — the Android delivery platform

Spec §5. The shipped manifest declares only `NFC` and `INTERNET`, one launcher activity with the `servicetag://asset|tag` filter, and the NFC dispatch activity (`app/src/main/AndroidManifest.xml:3-12`, `:29-47`, `:51-62`).

| concern | contract | spec |
|---|---|---|
| permission | `POST_NOTIFICATIONS` only, requested **on first schedule creation with a rationale** — not at launch. The request lives in the editor (B14) with B05's plumbing | §5.1, #24 AC 1 |
| exact alarms | the merged manifest declares **neither** `SCHEDULE_EXACT_ALARM` **nor** `USE_EXACT_ALARM`, asserted against the merged manifest | §5.1, invariant 52 |
| alarm | one **inexact daily digest alarm** at the user's hour via `setAndAllowWhileIdle(RTC_WAKEUP)` (or `setWindow` with a 30-minute window), re-armed by its own receiver after firing. The instant is `ZonedDateTime.of(T_next, reminderTime, deviceZone).toInstant()`; non-existent spring-forward local times resolve forward per `java.time`, ambiguous fall-back times to the earlier offset; on a zone or DST change day the digest may fire twice or not at all — the backstop is what guarantees it eventually fires | §5.2, D-6 |
| backstop | a `PeriodicWorkRequest` every **12 h, flex 4 h**, that recomputes every `schedule_state`, posts anything missed, and **re-arms the alarm if it is absent**. WorkManager survives reboot and force-stop; alarms do not | §5.3 |
| receivers | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, plus the quick-action receiver. **None is exported**, every one is addressed with an explicit intent, and every `PendingIntent` is `FLAG_IMMUTABLE` | §5.4, invariants 54, 60 |
| channels | **exactly two**, created once: `maintenance_due` at DEFAULT importance and `maintenance_overdue` at HIGH. **No `supplies` and no `sync_problems` channel** (D-20 = B; #24 AC 3 amended). A channel the user has muted is detectable and becomes a health finding | §5.5, invariant 53 |
| preferences | **two values only**, owned by B06: the digest hour (default **09:00 local**, editable) and the global reminders switch `REMINDERS_GLOBALLY_OFF` reads. The preferences screen stays #18 | §5.6 |
| digest policy | one summary notification per run listing DUE and OVERDUE; per-item notifications only for DUE/OVERDUE that are **not snoozed**; DUE_SOON announced **only on first entry**; overdue re-notification **every 3 days**. `last_notified_at` and `first_entry_seen` carry the bookkeeping. Meter-only schedules are evaluated **when a reading is saved**, with an immediate notification if it crosses DUE, because there is no date to alarm on | §5.7, D-5 |
| quick actions | four, each carrying a **random per-notification nonce** persisted in `schedule_local_delivery` and checked by the receiver | §5.8, D-21 |
| denied permission | **nothing** in scheduling state, the alarm and backstop machinery, or the reminder settings is disabled. `NOTIFICATIONS_BLOCKED` appears with a repair that opens system settings, and the dashboard carries the one-line dismissible ratified sentence rather than a repeated prompt | §5.8, D-22, invariant 61 |

### 12.1 The four quick actions and what each writes

| action | mechanism | writes |
|---|---|---|
| **"Done"**, `QUICK` | broadcast to a non-exported receiver | the completion event + `rebuild` + `reconcile`. **Never starts an activity from a receiver** (API 31+ trampoline rule, invariant 55). **Carve-out:** a `QUICK` schedule that carries a **meter rule** cannot complete without its reading (#11, D5 §3), so its "Done" routes into the canonical completion flow as an **activity** `PendingIntent` like a `FORM` schedule's, and the notification never fabricates a reading |
| **"Done"**, `FORM` | `PendingIntent.getActivity` into the completion form | nothing until the form is saved |
| **"Snooze 1 day"** | broadcast | `snoozed_until_at` in the device-local table only; **no date, no event**. The dashboard still shows OVERDUE, badged "Snoozed until <date>" (invariant 20) |
| **"Open"** | `PendingIntent.getActivity` | nothing; `servicetag://schedule/<uuid>` only |

A group-targeted schedule's notification offers **"Open"** only: "Done" on a group cannot honestly mean all members, so it opens the checklist (D-7).

### 12.2 Health findings shipped in 1.2

`NOTIFICATIONS_BLOCKED`, `DIGEST_ALARM_MISSING`, `BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `REMINDERS_GLOBALLY_OFF`, `SCHEDULE_NO_PROVIDER`, `NO_DATA` (spec §5.8, #27). Repair is **only** what is unambiguous and idempotent — re-arm an alarm, re-enqueue a worker. **No conflict is ever auto-repaired** (invariant 50); every Todoist finding is Phase 5. The health screen lives under Maintenance and is also where the platform realities are **explained rather than hidden**: an OEM standby bucket of `RESTRICTED` or battery optimisation "restricted", and Android 17 not dispatching NFC to an app in the stopped state after a force-stop. `targetSdk 37` plus `android:permission="android.permission.DISPATCH_NFC_MESSAGE"` on the dispatch activity is **Phase 7**, recorded so it is not forgotten.

### 12.3 S4 — informational, gating nothing

Spec §5.9, D-23 as amended. **S4 gates nothing, and this plan is where that is stated.** There is no S4 gate on a brief, a review, a merge or the release, and **B06 is not gated**: it is designed and written now on the default alarm-plus-backstop design of §12. No brief's Ordering section names S4.

S4 is **one overnight baseline observation** on the owner's development phone, run by the controller from a throwaway `:s4probe` module in a worktree that is never merged, declaring its own `androidx.work` so nothing under `app/` or `core/` changes — its WorkManager version is **not** B06's dependency decision. **Whether it has been armed, and when, is operational state and belongs in the ledger, not in this plan.** The result is recorded in `s4-result.md` in the SDD workspace and posted as a comment on #24 when it arrives. A **PASS** confirms the default design and changes nothing. A **REVISIT** — the alarm missed outright, or fired more than 4 h after target — reopens B06 *after the fact* as an amendment, the likely remedies being a shorter backstop period, a second alarm re-arm point, or surfacing the OEM restriction more prominently through #27.

---

## 13. Cross-cutting invariants

Spec §6's eighty, each restated in a few words with the brief whose test matrix proves it. Per the proportionality ruling an invariant naming several facts is **one** test asserting them together. A brief's review checks that every invariant in its column has a named test.

**Reading the "proved by" column.** A brief named without a qualifier proves the invariant where it is enforced — in the domain, the schema or the wire. A brief marked **(UI)** proves the narrower fact that **its surface cannot violate** the invariant: B14 and B15 cannot reach a forbidden state through the editor or the group screens, and B09 cannot reach one through the sheet. Both kinds of proof are required, and this column is the crosswalk a reviewer checks a brief's own invariant list against — so a brief's list and this column must agree in both directions.

| # | invariant | proved by |
|---|---|---|
| 1 | a schedule targets exactly one Asset or one group | B02 , B14 (UI) |
| 2 | a group-targeted schedule carries no meter rule | B02 , B14 (UI) |
| 3 | a group-targeted schedule carries no `profileId` | B02 , B14 (UI) |
| 4 | a group is never an Asset row and never in the Asset tree | B03 |
| 5 | a group never holds an NFC identity; no tag resolves to one | B03, B09 (structurally — `TagTarget` has no group case) |
| 6 | `(group_id, asset_id, added_at)` unique; many groups per asset; several closed windows per group | B01, B03 |
| 7 | a name, location or category is never identity, in the domain or in a merge | B01, B03, B15 (UI) |
| 8 | a membership row is never hard-deleted while a completion or closure references a covered occurrence | B03, B12, B15 (UI) |
| 9 | at most one current occurrence per schedule | B02 |
| 10 | `effectiveDueOn` is null only when there is no time rule | B02 |
| 11 | every FIXED `computedDueOn` lies on `anchorOn + k·interval` | B02 |
| 12 | COMPLETION: `computedDueOn == lastTerminationEffectiveOn + interval` when a termination exists | B02 |
| 13 | a very late termination produces exactly one next occurrence — never a backlog | B02 |
| 14 | advancing never rewrites history: no stored event or closure is edited by an advance | B02, B03 |
| 15 | `rebuild(rebuild(x)) == rebuild(x)` | B02 |
| 16 | `rebuild` is pure in (config, events, closures, membership, `T`) — a property over the function | B02 |
| 17 | `rebuild` is the only write path into `schedule_state` | B02, structural grep |
| 18 | status is never stored in any form | B02, structural grep |
| 19 | a completion, a closure or a recurrence edit clears the postponement; an edit abandons an open partial occurrence | B02, B03, B14 (UI) |
| 20 | snooze changes no `*_on` column and creates no event | B06, B07, B14 (UI) |
| 21 | a postponement moves the current occurrence only; the next comes from the rule | B02, B14 (UI) |
| 22 | `INACTIVE_SEASON` and `PAUSED` never notify and never count as due | B02, B06, B08 |
| 23 | status is monotone in `T` between history changes; a season boundary is not a violation | B02 |
| 24 | deleting the latest completion moves the due date back and reopens its round | B02, B03 |
| 25 | a never-terminated FIXED due date is pinned from immutable configuration; only an edit moves the floor, to the edit date | B02, B14 (UI) |
| 26 | `seasonReentry` and `seasonReentryOffsetDays` are stored and never read | B02, structural grep |
| 27 | a group target is `IGNORE` season only; `FOLLOW_ASSET` on one is rejected | B02, B14 (UI) |
| 28 | a group completion never writes an event on a non-member | B03, B09 (UI), B15 (UI) |
| 29 | a group completion never marks an unselected member complete | B03, B09 (UI), B15 (UI) |
| 30 | a group occurrence is incomplete until every required member is complete, or it is closed | B03 |
| 31 | "Complete all" records each required member's completion exactly once | B03 |
| 32 | a repeat `(schedule, occurrence, asset)` creates no second event — **by unique index, not by a check in code** | B01, B03 |
| 33 | a required set never depends on today's membership list; no membership operation changes a past one | B03, B15 (UI) |
| 34 | a completion always names a real asset; a schedule never owns an event of its own | B03 |
| 35 | closing writes one closure row, no schedule column, no `asset_event` | B03, B14 (UI) |
| 36 | a closed round never claims any member did work | B03, B14 (UI) |
| 37 | `occurrence_closure` is immutable: no `updated_at`, no UPDATE, no DELETE but the CASCADE | B01, B03, structural grep |
| 38 | closing twice leaves exactly one row and the second attempt is refused | B03, B12 |
| 39 | no completion may be written with an `occurrence_on` that already has a closure | B03, B12 |
| 40 | for one occurrence a full completion takes precedence over a closure; a closure for a complete round is inert | B03 |
| 41 | `rebuild` advances from the latest termination, completion or closure alike | B03 |
| 42 | closures are exported and merged as their own table; a diverged closure is a CONFLICT, never auto-resolved | B01 |
| 43 | no route, tool or UI action deletes or amends a closure | B12, B14, structural grep |
| 44 | the reminder projection is derivable from schedule state alone and rebuildable from nothing | B06 |
| 45 | `reconcile` twice with the same list has no second effect | B04, B06 |
| 46 | `contentHash` suppresses a no-op update | B04 |
| 47 | a seasonally inactive or paused schedule arrives as `PARKED(reentryOn)` | B04, B06 |
| 48 | `:core` compiles with no Android and no provider dependency; no port type names a provider object | B04, structural grep |
| 49 | two enabled provider rows yield two subject lists with no change to the port | B04 |
| 50 | no health repair ever resolves a conflict | B10 |
| 51 | every health finding has a positive test **and** a negative control | B10 |
| 52 | the merged manifest declares neither alarm permission | B05 |
| 53 | exactly two channels are created; no `supplies`, no `sync_problems` | B05 |
| 54 | no boot, time or quick-action receiver is exported; every `PendingIntent` is `FLAG_IMMUTABLE` | B05, B07 |
| 55 | a notification action never starts an activity from a receiver on API 31+ | B07 |
| 56 | a stale, missing or already-used nonce is rejected and writes nothing | B07 |
| 57 | a scan, an NFC dispatch and a `servicetag://` link never mutate | B07 (the `schedule` host), B09, B11 |
| 58 | an unknown, unbound, lost, retired, malformed or foreign tag never reaches the completion path | B09 |
| 59 | editing a placement label rewrites no NFC payload and changes no binding | B11 |
| 60 | the digest alarm is re-armed after `BOOT_COMPLETED`, `TIME_SET` and `TIMEZONE_CHANGED` | B06 |
| 61 | denied permission leaves every path working and produces exactly one finding | B05, B06, B10 |
| 62 | a format ≤5 archive still decodes and restores, with every new field at its default | B01 |
| 63 | a format-6 archive is refused by an older build with `BackupNewerFormat`, never partially read | B01 |
| 64 | neither `schedule_state` nor `schedule_local_delivery` is ever exported or merged | B01, B06, structural grep |
| 65 | no notification-delivery state is exported or merged | B01, B06 |
| 66 | a merge still has no `UPDATE` verdict | B01, structural grep |
| 67 | one conflict anywhere writes nothing at all | B01 |
| 68 | a termination that clears no postponement writes no schedule column and never bumps `updated_at` | B02, B03 |
| 69 | a re-import of an unchanged, un-postponed schedule is `IDENTICAL` after its occurrences advanced | B01 |
| 70 | FIXED `prevDue` comes from the terminating row's `occurrence_on`; the D5 §5 reconstruction is the documented-approximate fallback | B02 |
| 71 | a schedule or closure whose owner is neither local nor being inserted is `OWNER_NOT_AVAILABLE` | B01 |
| 72 | groups and closures are never coalesced by name or by date | B01 |
| 73 | a group occurrence's open instant is a single stated exported value — a property, not a two-device test | B03 |
| 74 | a schedule whose current occurrence has an empty required set is not actionable and not counted | B03, B08, B09 (UI), B14 (UI), B15 (UI) |
| 75 | a schedule on an asset the dashboard hides by default is still reachable at its attention rank | B08 |
| 76 | `POST /v1/events` cannot create a completion; nothing deletes a schedule, a group or a closure | B12 |
| 77 | an occurrence with an empty required set is never a termination and can never be closed | B03, B12, B14 (UI) |
| 78 | `closed_on` is never in the future and never earlier than its occurrence's open date | B03, B12, B14 |
| 79 | `removed_at` is never cleared and `added_at` never edited by any use case, route or tool | B03, B12, B15 (UI) |
| 80 | at most one membership row per `(group, asset)` has `removed_at IS NULL` | B01, B03, B12, B15 (UI) |

---

## 14. Briefs, dependency graph and the lane plan

### 14.1 The fifteen briefs

Spec §7's boundaries, in its order. The last column is an **estimate of the implementation's size**, carried over from spec §10's per-brief bands — it is **not** a target for the brief document's own length. The delivered briefs are 98–137 lines because each references the master plan's shared contract instead of restating it, which is the anti-restatement discipline working; **depth is judged against spec §10's content list, not against a line count.**

| brief | file | owns | consumes | est. implementation size (lines) |
|---|---|---|---|---|
| B01 | `B01-schema-and-format-6.md` | Room v6 entities, DAOs, mappers and `MIGRATION_5_6`; `6.json`; format 6 (three lists, three event fields, counts, sorting, decoder, `validateGraph`); `MergeTable`/`MergeWrites`/`MergeSnapshot`/`MergeReport` and the seven new reason codes; the total post-apply rebuild hook. **No engine, no UI** | §2–§4 | 300–380 |
| B02 | `B02-scheduling-engine.md` | `MaintenanceSchedule`, `ScheduleState`, `ScheduleRecompute.rebuild`, status, FIXED/COMPLETION/meter/combined arithmetic, the four operations, `ScheduleRepository`, the `Today` port | B01 | 320–400 |
| B03 | `B03-groups-occurrences-and-closure.md` | `MaintenanceGroup`, append-only temporal membership, the open instant, required/completed sets, the empty-required rule, group completion, "Complete all", occurrence idempotence, **`CloseRound`** and the total `terminations` | B01, B02 | 340–400 |
| B04 | `B04-reminder-provider-port.md` | the port, `ReminderSubject`, `RuleFacts`, `SubjectState`, the subject-building use case, a fake provider. **No Android** | B02, B03 | 150–220 |
| B05 | `B05-platform-ownership.md` | manifest permissions and receivers, the two channels, the permission-request plumbing, the no-exact-alarm assertions, restricted/muted detection, a home for the S4 observation | — | 180–250 |
| B06 | `B06-local-reminder-provider.md` | digest alarm, backstop worker, receivers, the `reconcile` implementation, `schedule_local_delivery`, digest policy, meter-crossing notification, the two preferences, **its own WorkManager dependency declaration** | B04, B05 | 320–400 |
| B07 | `B07-quick-actions.md` | the four actions, the persisted nonce, trampoline-safe routing, the `servicetag://schedule` link | B06 | 180–250 |
| B08 | `B08-dashboard-and-maintenance-shell.md` | the due read model over the shipped `DashboardViewModel`, §11.1's section placement, component promotion, the group row, the health badge, grayscale acceptance; **the bottom bar becoming three** and the Maintenance shell | B02, B03 | 260–340 |
| B09 | `B09-scan-completion-sheet.md` | the sheet titled "Maintenance", D-18a's state set, D5 §7A's per-item detail, Review/Snooze/Postpone, the backdatable completion, the sequential form flow, the group-member offer, the navigation-only proofs | B02, B03, B08, B14 | 280–360 |
| B10 | `B10-reminder-health-and-repair.md` | `ReminderHealthCheck`, the seven findings with positive tests and negative controls, idempotent repair, the Health section, **the draft health sentences for ratification** | B05, B06, B08 | 200–270 |
| B11 | `B11-tag-placement-labels.md` | binding and edit affordance, the all-tags list on asset detail, the label in the scan result, the backup/merge preservation proof. **No schema change** | — (independent) | 150–210 |
| B12 | `B12-api-and-mcp-surface.md` | §9's routes, the three commands, the DTOs, the router additions, the destructive-route assertions, the seventeen MCP tools and the `clear_fields` audit, `/v1/status` counts, the edits to `docs/api/v1.md` and the MCP README | B01–B03, B08 | 340–400 |
| B13 | `B13-version-docs-and-release-proofs.md` | `versionName` 1.2.0 / `versionCode` 13, `docs/versioning.md`, the D5 §5 and §10.5 corrections, design-doc updates, the release-level proof commands | all | 150–220 |
| B14 | `B14-schedule-editor-and-operations.md` | create and edit a schedule against one asset or one group with the ratified wording; the in-app complete / snooze / postpone / **close this round** / edit-recurrence actions and the `closedOn` range; the D-11 warning; the `POST_NOTIFICATIONS` request on first creation; the backdatable completion | B02, B03, B05 | 300–380 |
| B15 | `B15-groups-ui-and-asset-integration.md` | group list and detail inside Maintenance with members and progress, add / soft-remove / re-add / archive; asset detail's schedules section and its "groups this asset belongs to" section | B03, B08's shell | 200–260 |

### 14.2 Dependency graph

```
B01 ──┬─► B02 ──┬─► B03 ──┬─► B04 ──► B06 ──► B07
      │         │         │            ▲
      │         │         │        B05 ┘
      │         └─────────┴─► B08 ──┬─► B10
      │                             ├─► B15
      │                             └─► B09 ◄── B14 ◄── B05
      └─────────────────────────► B12 (needs B01–B03 + B08's read model)
B11 (independent, no schema change)          B13 (last: needs all)
```

**B01 gates B02 and B03. B08's shell gates B15 and B10's placement. B14 gates B09.** B05 runs in parallel with B02/B03 — it touches no domain. B11 runs in either lane at any time. **Nothing gates on S4, and B06 in particular does not** (§12.3).

**Two edges the diagram does not draw.** **B10 also consumes B05** (`PlatformState`, `NotificationPermission` — decision 22) and **B06** (the digest alarm and the backstop worker it detects and repairs — decision 24). The graph shows only B10's *placement* edge from B08's shell; its detection dependencies are the other two, and B10's own Ordering section states all three.

### 14.3 Lane plan

Two implementers concurrently at most (owner ruling, `feedback-two-lanes`), one implementer and one independent reviewer per brief, with scoped re-reviews of fixes.

**The three isolation rules, which the wave table below obeys rather than assumes:**

1. **One git worktree per lane, on its own branch cut from the release tip.** The controller merges each brief `--no-ff` into the release branch, and **no lane rebases the other's merged work away**. Two concurrent implementers never share a checkout.
2. **Connected runs are serialised on the device.** The wave table's **device** column names which lane takes the emulator **first**; the other queues behind it. Only **one lane holds `emulator-5554` at a time** — requested from and released to the controller, `ANDROID_SERIAL` pinned — **unless** the controller has provisioned a second AVD, in which case that brief's gate names its own serial. Instrumented runs wipe device data (`feedback-device-proof-automation`), so two lanes running connected suites against one serial invalidate each other's results. **A lane waiting for the device works on its unit gate meanwhile**, which is where most of every brief's matrix lives.
3. **The two lanes in a wave never share a *file set*.** Waves 4–7 both run in `:app` — that is unavoidable, since eleven of the fifteen briefs touch it — and they are separated by **package** and by the **Files section of each brief**, which is the real boundary. `AppGraph.kt` and `ServiceTagRoot.kt` are the two files several briefs touch; the controller resolves those at merge, and a lane that needs a line in the other lane's file asks rather than edits.

| wave | lane A | lane B | device first | file-set separation |
|---|---|---|---|---|
| 1 | **B01** | **B11** | B11 only | `:core` + `data/**` vs `ui/asset`, `ui/scan`. Disjoint. B11 merges whenever it is green |
| 2 | **B02** | **B05** | neither | `:core` + one Room adapter vs `reminders/**` + the manifest. Disjoint |
| 3 | **B03** | **B04** | neither | `:core/schedule`, `usecase`, one adapter vs `:core/reminders`. Disjoint |
| 4 | **B06** | **B08** | **B06**, then B08 | `reminders/**`, `prefs`, the version catalog vs `ui/maintenance`, `ui/dashboard`, `ui/nav`. Disjoint but for `AppGraph.kt` |
| 5 | **B14** | **B07** | **B14**, then B07 | `ui/maintenance/Schedule*` vs `reminders/QuickAction*`, `links`, the manifest, `DeepLinkRoute.kt`. Disjoint but for `AppGraph.kt`, `ServiceTagRoot.kt` |
| 6 | **B09** | **B15** | **B09**, then B15 | `ui/maintenance/MaintenanceSheet*`, `ui/scan` vs `ui/maintenance/Group*`, `ui/asset`. Disjoint but for `ServiceTagRoot.kt` |
| 7 | **B12** | **B10** | **B10** only | `api/**`, `tools/servicetag-mcp/**`, `docs/api` vs `reminders/ReminderHealthCheck`, `ui/maintenance/Health*`. Disjoint |
| 8 | **B13** | — | B13 only | single lane by design |

**B04 must land before wave 4 opens**, because **B06 consumes it**: it rides wave 3 lane B as soon as B03's domain lands, and if it slips, **wave 4 lane A slips with it**. It may not ride wave 4 lane B — that is the wave B06 is in.

**Two lanes is a ceiling, not a target.** A wave whose lane B is not ready runs as a single lane; Gradle contention is never serialised away, and a lane blocked on the device or on the other lane's merge is a lane the controller may simply not open.

---

## 15. The release gate

Owner-run and owner-gated; the controller stops where the owner's click is required.

1. Every brief merged and independently reviewed; scoped re-reviews of every fix round closed; whole-branch **release** review clean (scope, unreviewed mutations, document consistency, ratified strings only, hygiene, evidence correspondence, release mechanics).
2. **Every PROPOSED string in §17 ratified by the owner before the brief that draws it executes.** No release is cut carrying an unratified user-visible string.
3. `versionName` **1.2.0**, `versionCode` **13**; `AppDatabase.version` and `AppGraph.SCHEMA_VERSION` **6**; `BackupCodec.FORMAT_VERSION` **6**; `/v1/status` echoing 6 and 6; `docs/versioning.md` carrying the D-2 clarification, the 1.2.0 / code 13 row and **no** 1.1.1 reservation.
4. §16's controller proofs all PASS at the exact final tip.
5. Tree clean; push the exact final tip; **CI green on that exact commit** (jobs `build`, `mcp`, `bundle`; zero annotations).
6. Annotated tag **`servicetag-v1.2.0`** on that exact commit. The release workflow reaches the protected `release` environment and **waits for the owner's approval in GitHub** — the controller reports the run as waiting and stops.
7. After approval: verify the published artifact — `sha256sum -c` on a fresh download, `apksigner` verifies with **one** signer whose certificate equals `RELEASE_CERT_SHA256` (the same signer as 1.0.0 and 1.1.0), badging `1.2.0 / 13`, not debuggable, permissions exactly `NFC` + `INTERNET` + `POST_NOTIFICATIONS` + the AndroidX app-private receiver permission, **and neither alarm permission present**.
8. **Install on the development phone** (the legacy handset, never the production one) over 1.1.0 in place, code 12 → 13; prove the migration carried the phone's existing rows; then prove, on that phone: the **API** (create a group, create a group-targeted schedule, complete one member, close the round, read the closures), the **MCP** (the same sequence through the seventeen tools, including a `clear_fields=["postponed_due_on"]` clear), and **groups** (the group screens, progress, soft-remove, re-add). Report counts and PASS/FAIL, never a serial.
9. Tracker: comment on **#24** amending AC 3 to the two channels (D-20 = B); re-tier **#49** from `[NEXT]` to `[MVP]`; post the **S4** result on #24 with its verdict; close the ten issues against their acceptance criteria with the evidence; file whatever the release surfaced.
10. Evidence committed in the SDD workspace and the owner's copies refreshed in the owner's canonical folder (`~/Documents/Architecture and Design Review/ServiceTag Android/1.2.0/`).

**The production phone is not part of this release's proof.** Its install is the owner's separate decision after the development-phone proof.

---

## 16. Controller proofs (the release-level gate)

Run by the controller at the final tip, not by an implementer. Every grep is an **anchored** pattern.

1. **Unit gate from scratch:** `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest --rerun-tasks --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips. **The baseline is measured at `d790506`, the last code-changing commit**, and the release branch is cut from the docs-only tip that carries this plan and the spec. The controller records the baseline counts before wave 1 and asserts that the final counts equal the baseline plus the sum of the per-brief counts each brief's review recorded. `Plan decision:` the plan does not hardcode a test total, because the baseline moved with the Stage-A bundle work and the plan author may not run Gradle; the controller's recorded baseline is the number of record.
2. **Connected suite on `emulator-5554`** (never a phone here): the whole `androidTest` suite green, zero skips; the new classes present. `ANDROID_SERIAL` pinned. The emulator is the Android Studio flatpak AVD; instrumented runs wipe data, so **no phone runs a suite**.
3. **Python:** `cd tools/servicetag-mcp && uv run --frozen pytest` green; `cd tools/servicetag-bundle && uv run --frozen pytest` green and unchanged.
4. **Structural greps.** Every one is an **anchored pattern with its command and its expected count**, per `docs/superpowers/planning-policy.md:41` — written this way from the start so a comment that names a grep can never match it. `<base>` is the release branch's base commit. **They are a list and not a table on purpose: an ERE alternation contains a `|`, which a markdown table cell would require escaping as `\|`, and a pattern that has to be un-escaped before it runs is exactly the silently-matching-nothing failure the anchored-pattern rule exists to prevent.** Run each line as written.

   - **MCP tool count** — `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **38**
   - **one `MergeTable`, ten members** — `grep -c '^enum class MergeTable' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **1**; the review reads the member list against §4's order
   - **the stale KDoc gone** — `grep -c 'seven canonical tables' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0**
   - **no `UPDATE` verdict** — `grep -cE '^[[:space:]]*UPDATE[,[:space:]]*$' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0**
   - **`:core` stays Android-free** — `grep -rlE '^import (android|androidx)\.' core/src/main` → no output
   - **no dead provider identifier** — `grep -rn '\bTODOIST\b' core/src/main app/src/main | grep -vE '(Journal|BackupFormat)\.kt'` → no output
   - **the deferred re-entry columns have no reader** — `grep -rc 'seasonReentry' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → **0** for every file
   - **the closure table is immutable** — `grep -rliE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?.?occurrence_closure' app/src/main core/src/main` → no output
   - **the closure DAO is insert-and-query** — `grep -nE '@(Update|Delete)' app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/MaintenanceDaos.kt` → no line inside the closure DAO; the review reads the file
   - **no exact alarm** — `grep -rlE 'android:name="android\.permission\.(SCHEDULE|USE)_EXACT_ALARM"' app/src/main app/build/intermediates/merged_manifests` → no output
   - **two channels, no third** — `grep -rc 'createNotificationChannel' app/src/main` → **≥ 1** (B05 may register both channels from one site or one call per channel; the plan does not fix that shape), and the assertion that actually carries invariant 53 is `grep -rliE '"(supplies|sync_problems)"' app/src/main core/src/main` → **no output**, with the review reading the two ids off the creation site(s)
   - **every `PendingIntent` immutable** — `grep -rnE 'PendingIntent\.(getBroadcast|getActivity|getService)' app/src/main | grep -vc 'FLAG_IMMUTABLE'` → **0**
   - **no exported new receiver** — `grep -cE '<receiver' app/src/main/AndroidManifest.xml` → **5** (the manifest declares none today; §12 adds the four platform receivers and B07's quick-action receiver), and `grep -cE '<receiver[^>]*android:exported="true"' app/src/main/AndroidManifest.xml` → **0**, with the per-element assertion in B05's test
   - **derived and delivery state unexported** — `grep -rlE '(schedule_state|ScheduleState|schedule_local_delivery|ScheduleLocalDelivery)' core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/main/kotlin/com/loosecannon/servicetag/core/merge` → no output
   - **the four numbers agree** — `grep -c 'version = 6' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt`, `grep -c 'SCHEMA_VERSION = 6' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt`, `grep -c 'FORMAT_VERSION = 6' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt`, `grep -c 'versionCode = 13' app/build.gradle.kts`, `grep -c 'versionName = "1.2.0"' app/build.gradle.kts` → **1** each
   - **the schemas** — `test -f app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/6.json` → present; `git diff --stat <base>..HEAD -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/1.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/2.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/3.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/4.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/5.json` → empty
   - **the stale prose gone** — `grep -ci 'twenty-one' tools/servicetag-mcp/README.md` → **0**; `grep -c 'seven tables' docs/api/v1.md` → **0**
   - **§1's untouched list** — `git diff --stat <base>..HEAD -- libs/ tools/servicetag-bundle/ core/src/main/kotlin/com/loosecannon/servicetag/core/nfc/ core/src/main/kotlin/com/loosecannon/servicetag/core/journal/ app/src/main/kotlin/com/loosecannon/servicetag/ui/theme/` → empty
5. **Migration proof:** the `MigrationTest` from B01 runs in the unit gate; separately, the controller restores a **real format-5 archive** through the app on the emulator, upgrades, and confirms every count is unchanged and no schedule was invented.
6. **End-to-end on `emulator-5554`** through a connected MCP client with the Developer API screen open: create a group with two members, create a group-targeted QUICK schedule, `list_due` shows **one** item with `membersRequired = 2, membersComplete = 0`; complete one member → `membersComplete = 1` and the item still due; `close_round` → 201, a second `close_round` → 409 `OCCURRENCE_ALREADY_CLOSED`, `complete_schedule` on that occurrence → 409 `OCCURRENCE_CLOSED`; `list_closures` shows one row; `closedOn` tomorrow → 422 `CLOSED_ON_OUT_OF_RANGE`; export, `import_merge(plan_only=True)` on the same phone → all `IDENTICAL` including the schedule and the closure; `pm clear` after.
7. **Hygiene over the whole range:** no private nouns, no e-mail, no home path, no serial, no pairing code; single-subject commits; one author identity; submodule clean at its pinned tag (`bash tools/check-submodule-pin.sh`).
8. **Badging and artifact proof** as §15.7.

---

## 17. String ratification table

Every user-visible string 1.2 introduces. **RATIFIED** strings are quoted verbatim from spec §9.1 and a brief may not paraphrase one. **PROPOSED** strings return for the owner's ratification before the brief that draws them executes (spec §12, D-24).

**Every status and treatment word D12 already carries is pre-ratified with it** (`docs/design/12-visual-design-apollo-service-binder.md:274-296`, `:289`, `:311-313`, `:706-707`), which covers the seven status terms, the four dashboard section labels, and **REMINDER FAILED** for the failure family (B10) — D12 is approved visual-design direction recorded as part of the design authority.

| string | state | used by |
|---|---|---|
| **"Maintenance"** (scan-sheet title **and** the navigation destination) | RATIFIED (D-24, one change) | B08, B09 |
| "Maintenance group" | RATIFIED | B15 |
| "3 of 5 complete" (the progress form) | RATIFIED | B08, B15, B09 |
| "Complete selected" | RATIFIED | B09, B15 |
| "Complete all" | RATIFIED | B15, B09 |
| "Close this round" (the action label) | RATIFIED | B14 |
| "Open asset" | RATIFIED | B09 |
| "Not now" | RATIFIED | B09 |
| "Review maintenance" | RATIFIED | B09 |
| "Snooze" | RATIFIED | B09, B14 |
| "Postpone" | RATIFIED | B09, B14 |
| "When was this done?" | RATIFIED | B09, B14 |
| "Tag placement" | RATIFIED | B11, B09 (the sheet header's scanned-tag caption) |
| "Log meter reading" | RATIFIED | B08 (an ATTENTION `NO_DATA` row's repair), B09, B10 |
| notification actions "Done" / "Snooze 1 day" / "Open" | RATIFIED | B07 |
| "Snoozed until <date>" | RATIFIED | B07, B08 |
| "Reminders are off because notifications are blocked." | RATIFIED | B08 |
| "ServiceTag needs notification permission to remind you when maintenance is due." | RATIFIED | B14 (with B05's plumbing) |
| "This asset already has a similar schedule through another group." | RATIFIED | B14 |
| editor wording "Every N", "Repeats from", "the scheduled date", "when I complete it", "Remind me N days early" | RATIFIED | B14 |
| status terms **OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE** | RATIFIED (D-24; pre-ratified with D12 `:274-296`, `:311-313`) | B08, B09, B14, B15 |
| dashboard section labels **ATTENTION · UPCOMING · CURRENT · OUT OF SEASON** | RATIFIED (pre-ratified with D12 `:706-707`) | B08 |
| the seven health-finding sentences (`NOTIFICATIONS_BLOCKED`, `DIGEST_ALARM_MISSING`, `BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `REMINDERS_GLOBALLY_OFF`, `SCHEDULE_NO_PROVIDER`, `NO_DATA`) — one plain sentence **and one repair label** each | **PROPOSED** (spec §12 item 1) | B10 drafts, owner ratifies |
| the "Close this round" **confirmation body**. Spec §12's draft: *"Close this round? The members not marked done will not be recorded as serviced."* | **PROPOSED** (spec §12 item 2) | B14 |
| the **schedule editor's remaining field labels** — the target picker, the season-behaviour choice, the completion-mode choice, the profile picker, the four meter-rule fields (definition, interval, baseline, lead) and the single provider row. D-24 ratified the time-rule and lead fragments only | **PROPOSED** (see §18 decision 12) | B14 drafts, owner ratifies |
| the two **notification channel display names and descriptions** shown in Android system settings for `maintenance_due` and `maintenance_overdue` | **PROPOSED** (see §18 decision 12) | B05 drafts, owner ratifies |
| the **digest summary notification** title and body (the one-per-run summary listing DUE and OVERDUE) and a per-item notification's title and body form | **PROPOSED** (see §18 decision 12) | B06 drafts, owner ratifies |
| the Maintenance destination's **four section labels** (due work · schedules · groups · reminder health) and its empty-state line | **PROPOSED** (see §18 decision 12) | B08 drafts, owner ratifies |

**Any user-visible string a brief needs that this table does not list is a finding for the controller before that brief executes; no brief invents one.** Thirteen of the fifteen briefs say so in their own Strings section; the four that draft a PROPOSED class (B05, B06, B08, B14) say it of everything beyond the class they were given.


### 17.1 The PROPOSED drafts, for ratification at the gate

Every unratified string in the six classes above, **drafted concretely so the owner can ratify or rewrite at the gate instead of a brief blocking on it later**. Forty-four strings. They are short, plain, and in the register of the ratified ones — "Snoozed until \<date\>", "Reminders are off because notifications are blocked." Placeholders are shown as `<n>`, `<asset>`, `<date>`, `<unit>`. **Every one stays PROPOSED until the owner rules**; a brief may use a draft only after ratification, and the owner rewriting one costs nothing at this stage.

**(a) The seven health findings — one sentence and one repair label each (B10).** `NO_DATA`'s label is the already-ratified "Log meter reading", so thirteen of the fourteen are new.

| finding | PROPOSED sentence | PROPOSED repair label |
|---|---|---|
| `NOTIFICATIONS_BLOCKED` | "Notifications are turned off, so maintenance reminders will not arrive." | "Open notification settings" |
| `DIGEST_ALARM_MISSING` | "The daily reminder check is not scheduled, so today's maintenance may go unannounced." | "Reschedule the check" |
| `BACKSTOP_WORK_MISSING` | "The background safety check is not running, so a missed reminder would not be caught." | "Restart the check" |
| `APP_RESTRICTED` | "This phone is holding ServiceTag back in the background, so reminders may arrive late or not at all." | "Open battery settings" |
| `REMINDERS_GLOBALLY_OFF` | "Reminders are turned off in ServiceTag." | "Turn reminders on" |
| `SCHEDULE_NO_PROVIDER` | "\<n\> schedules have reminders switched on but no way to deliver them." | "Open the schedule" |
| `NO_DATA` | "\<n\> schedules need a meter reading before they can come due." | **RATIFIED** — "Log meter reading" |

**(b) The "Close this round" confirmation body (B14).** Spec §12's own draft, carried forward unchanged:

> "Close this round? The members not marked done will not be recorded as serviced."

**(c) The schedule editor's field labels (B14).** D-24 ratified "Every N", "Repeats from", "the scheduled date", "when I complete it" and "Remind me N days early"; these fifteen are the rest.

| field | PROPOSED label | PROPOSED options |
|---|---|---|
| target picker | "This applies to" | "One asset" · "A maintenance group" |
| season behaviour | "Out of season" | "Pause with the asset's season" · "Remind me year round" |
| completion mode | "Completing this takes" | "One tap" · "The full form" |
| profile picker (FORM only) | "Use this form" | — |
| meter definition | "Also due by use" | — |
| meter interval | "Every \<n\> \<unit\> of use" | — |
| meter baseline (`anchor_meter`) | "Last done at" | — |
| meter lead | "Remind me \<n\> \<unit\> early" | — |
| provider row | "Remind me through" | — |

**(d) The two notification channels, as Android shows them in system settings (B05).**

| channel | PROPOSED name | PROPOSED description |
|---|---|---|
| `maintenance_due` | "Maintenance due" | "Reminders for maintenance that is due." |
| `maintenance_overdue` | "Maintenance overdue" | "Reminders for maintenance that is past due." |

**(e) The digest summary and the per-item notification (B06).** The status words in these are already ratified; what is proposed is the wrapper.

| notification | PROPOSED title | PROPOSED body |
|---|---|---|
| the digest summary (one per run) | "\<n\> maintenance items need attention" | "\<n\> overdue, \<n\> due." |
| a per-item, DUE | "\<asset\> — \<title\>" | "Due \<date\>." |
| a per-item, OVERDUE | "\<asset\> — \<title\>" | "Overdue since \<date\>." |
| a per-item, a meter threshold crossed | "\<asset\> — \<title\>" | "Due at \<n\> \<unit\>, now \<n\>." |

**(f) The Maintenance destination's four section labels and its empty state (B08).**

| surface | PROPOSED string |
|---|---|
| section 1 — due work | "Due work" |
| section 2 — schedules | "Schedules" |
| section 3 — maintenance groups | "Maintenance groups" |
| section 4 — reminder health | "Reminders" |
| empty state, a phone with no schedules | "No maintenance schedules yet. Add one from an asset or a maintenance group." |

**Count: 44 PROPOSED strings** — 13 health sentences and labels (the fourteenth is ratified), 1 close confirmation, 15 editor labels and options, 4 channel names and descriptions, 6 notification titles and bodies, 5 Maintenance labels. **Plus, conditionally:** any string the three §1.2 scope questions need if the owner rules them in — F2's filter labels, F3's meter line form, F4's three quick-action labels — which are PROPOSED items in that event and not a brief's to invent.

---

## 18. Plan decisions

Every place this plan decided a detail the spec left open, with its one-line reason. The plan review and the owner see the whole list here.

The four **revision-4.1 amendments** — that S4 gates nothing (§1, §12.3), `MEMBER_ALREADY_OPEN` as a 422 (§9.1, §9.2, §9.3), the two membership merge codes as archive-internal planner guards with phone-to-phone divergence surfacing as `CONTENT_DIFFERS` on the group row (§4.1), and the schedule command's shape violations as 422 rather than 409 (§2.3, §9.3) — are **spec, not plan decisions**, and are not numbered here.

**Two entries below — 3 and 4 — are named spec deviations, not silences.** They depart from an explicit statement of spec §2.1 (the two `CHECK` constraints) and spec §3.1 (the prescribed migration statements) rather than fill a gap. Both are recorded here for the owner and the spec author, the controller carries them with this plan's review, and **§1's follow-the-spec rule does not apply to a deviation this plan has named** — an implementer follows the plan for these two and reports anything else.

1. **§2.1 — `schedule_state` carries `computed_due_meter`.** Spec §2.2 names it among the materialised fields; §3.1's table does not enumerate columns, and `/v1/due` and the dashboard read it.
2. **§2.1 — `anchor_on` is nullable, non-null exactly when `time_interval` is non-null.** §2.1 types it non-null while §4.1's command has it optional and a meter-only schedule has no series; this is the only shape that represents both without a sentinel date.
3. **Spec deviation** (not a silence) — **§2.3, no `CHECK` constraint is declared anywhere.** Room 3 cannot declare one on an `@Entity`, so a CHECK written only into the migration would exist on upgraded databases and not on fresh ones. The two CHECKs spec §2.1 states become use-case invariants with tests, exactly as §2.3 already rules for the at-most-one-open membership rule.
4. **Spec deviation** (not a silence) — **§2.4, the migration's implementation is the implementer's choice between `ALTER TABLE ADD COLUMN` ×3 and a table recreate**, decided by which one satisfies Room's validation against `6.json` and the row-identity proof. The plan fixes the two requirements, not the statements.
5. **§3.2 — a group's `members` are encoded in `(sortOrder, id)` order.** `sortOrder` is not promised unique within a parent, and `MergePlanner.ordered()` already normalises child lists on `(sortOrder, id)`; two stable sorts over two different bases would disagree.
6. **§5.1 — `Today` is a `fun interface` port in `core/.../core/ports/`**, not a bare parameter at the top of the stack. Spec §2.2 allows either; `AppGraph` already owns `Clock`, the digest and backstop runs need the same value, and a fake `Today` is how every date test is written. `rebuild` still takes `T` as a parameter.
7. **§5.2 — the time-unit enum is `RecurrenceUnit`, not `TimeUnit`.** `TimeUnit` collides with `java.util.concurrent.TimeUnit`. The four names on the wire and in the column are unchanged.
8. **§8 — the shapes of `SubjectKey`, `ProviderId`, `ReconcileReport` and `HealthFinding`/`RepairAction`/`Severity`.** Spec §2.5 fixes `ReminderSubject` and the port but leaves these to the implementation, and B04, B06, B10 and B12 need one agreed shape. `ProviderId` carries **`LOCAL` only** in 1.2 — adding `TODOIST` now would be a dead member the editor could write. **Consequence, recorded rather than worked around:** with a single member, and `schedule_provider` keyed `(schedule_id, provider)`, one schedule cannot hold two enabled rows, so **invariant 49 / #28 AC 4 is proved *structurally* in 1.2** — the provider is a **parameter** of `forProvider` and the key of `all()`'s map, so a second member is purely additive and needs **no change to the port**. The **behavioural** half of AC 4, two lists actually built for two providers, lands with the Todoist provider in **Phase 5**. A sealed or value `ProviderId` admitting a test-only second member was considered and rejected as a larger change than the fact it would prove.
9. **§9.3 — `/v1/due`'s `rank` is the 0-based index of the item in the attention order.** Spec §4.1 names the field without defining it; a client must be able to reproduce the app's order without re-deriving it.
10. **§11 — the seven new `Route` members and their parameters.** Spec §2.6 names the Maintenance destination but not the back stack; every key carries ids only, as `Route.kt:6-9` requires, and `Route.readsTags()` is unchanged because no new screen reads tags.
11. **§10 / §16 — the MCP tool count becomes 38** (21 + 17), which is the number `README.md:58` must state and the structural grep must find.
12. **§17 — the PROPOSED set is larger than spec §12's two items.** Spec §12 says two strings and nothing else, but **four** more classes are user-visible and unratified: the two notification **channel display names and descriptions** (visible in Android system settings), the **digest summary and per-item notification** title and body, the Maintenance destination's **four section labels** and empty-state line, and the **schedule editor's remaining field labels** — D-24 ratified the time-rule and lead fragments only, leaving the target picker, the season-behaviour and completion-mode choices, the profile picker, the four meter-rule fields and the provider row with no ratified wording. They are marked PROPOSED and routed to B05, B06, B08 and B14 to draft rather than invented in a brief, and §17 closes with the rule that any string it does not list is a finding rather than a brief's to invent. **This is the one place the plan extends the spec's open-items list, and the plan review should confirm it.**
13. **§14.3 — the wave, worktree and device assignment.** Spec §7 fixes the dependencies and the owner's two-lane rule fixes the concurrency; the pairing of briefs into waves is this plan's, chosen so that **the two lanes in a wave never share a file set**. Waves 4–7 both run in `:app` — unavoidable, since eleven briefs touch it — and are separated by package and by each brief's Files section, with `AppGraph.kt` and `ServiceTagRoot.kt` named as the two shared files the controller resolves at merge. **One worktree per lane** and **`emulator-5554` held by one lane at a time**, connected runs serialised, are part of this decision.
14. **§16.1 — no test total is hardcoded.** The baseline moved with the Stage-A bundle work and the plan author may not run Gradle; the controller records the baseline at `d790506` and asserts baseline + the per-brief counts.
15. **§16 — the structural grep list and each expected count.** The planning policy requires anchored patterns from the start; the specific list is this plan's derivation from the invariants that are structural rather than behavioural.
16. **Per-brief review gates.** Spec §8 gives hazard classes, not commands; each brief's gate is this plan's, built from the CI unit command (`.github/workflows/ci.yml:25`), connected classes on `emulator-5554` where UI is involved, `uv run --frozen pytest` for MCP, and the structural greps that belong to that brief.

### Decisions made inside a brief

Each is marked `Plan decision:` at its point of use in the brief named. **Forty-one in total** with the sixteen above.

| # | brief | decision |
|---|---|---|
| 17 | B01 | **The Room adapter for a port belongs to the brief that declares the port** — `ScheduleRepository`/`ScheduleStateRepository` to B02, `GroupRepository`/`ClosureRepository` to B03, `ScheduleLocalDeliveryRepository` to B06. Spec §7's `(:core)` label says where the *domain* lives, not that an adapter is unowned; B01 declares the DAO operations those adapters call |
| 18 | B01 | **`ApplyBackupMergePlan` gains a `rebuildAll: suspend () -> Unit` seam.** §4.3 requires a total rebuild inside the apply's transaction and B01 gates B02, so B01 proves "invoked once, inside, after the writes" and B02 wires the engine |
| 19 | B02 | **The derived status enum is `DueStatus`**, because `ScheduleStatus` is already the stored lifecycle enum and one name for two things is how a status gets stored by accident (invariant 18) |
| 20 | B02 | **A `RecomputeSchedules` collaborator** with a `groupSchedulesRequiring(assetId)` seam, because "every event write rebuilds" is a closure over group membership that no single use case can compute; B02 ships the asset half, B03 fills the seam |
| 21 | B03 | **`ClosureRepository` exposes `insert`, not `upsert`**, and no update or delete. Every other port offers `upsert`; copying that here would hand a caller the amendment invariant 37 forbids |
| 22 | B05 | **`PlatformState` and `NotificationPermission` are interfaces** with one Android-backed implementation each, which is what lets B10's seven findings each have a positive test **and** a negative control without an emulator per case |
| 23 | B05 | **The permission request lives behind B05's seam and is invoked from B14's editor only.** Spec §5.1 rules *where* it is requested but not *who owns the call*; splitting plumbing from call site is what makes #24 AC 1 assertable |
| 24 | B05 | **The four platform receivers are declared in B05 with one `ReminderTrigger` seam**, implemented in B06 — their manifest declarations and non-exported discipline are B05's invariants, what they *do* is B06's policy |
| 25 | B06 | **`NonceStore` and `ReminderSnooze` are declared in B06**, consumed by B07, because D-21 makes the nonce a column of the delivery row B06 owns and two briefs must not write one column |
| 26 | B06 | **No meter-crossing branch in the event use case.** Spec §5.7's "evaluated when a reading is saved" rides the existing recompute-then-reconcile sequence, keeping the engine free of a delivery concern |
| 27 | B08 | **`DueReadModel` is one shared projection** used by the dashboard, the Maintenance shell, B09's sheet and B12's `/v1/due`. Spec §2.6 fixes the ordering and spec §2.8 reuses it; one projection is the only way they cannot drift. It exposes the **`status` and an empty-required-set flag** and **carries no single `isActionable` boolean** — "actionable" is surface-specific (§11.1) and one flag would be wrong for one surface the moment it was right for the other |
| 28 | B08 | **`HealthSummary` is declared in B08 and implemented in B10**, so the badge can land and be tested against a fake before B10 exists |
| 29 | B08 | **"Actionable" for the component-promotion rule means a status in ATTENTION or UPCOMING** — `OVERDUE`, `DUE`, `DUE_SOON`, and `NO_DATA` **only in its repairable meter-baseline form**. Spec §2.6 says "an actionable schedule" without enumerating; promoting `OK` and `INACTIVE_SEASON` would undo #39's blank-query rule entirely, and promoting an **empty-required-set** `NO_DATA` would put an unactionable obligation in ATTENTION permanently, against invariant 74 (§11.1) |
| 30 | B08 | **The total sort order behind `rank`:** (section ordinal, `effectiveDueOn` ascending nulls last, `title` case-insensitively, `scheduleId`). Spec §2.6 fixes the sort key but not a total order, and `rank` must be deterministic for a client and a test |
| 31 | B10 | **A severity per finding** — `NOTIFICATIONS_BLOCKED` ERROR, `REMINDERS_GLOBALLY_OFF` INFO, the other five WARN. Spec §5.8 names the codes and #27 the "≥ WARN" badge threshold, but no severity per finding, without which the badge is undefined |
| 32 | B10 | **Where the check runs:** app launch, the backstop worker, and the Health screen. **Not** "after every sync" — there is no sync in 1.2 — and **not** per dashboard emission, which would read the standby bucket on every keystroke |
| 33 | B11 | **The label edit affordance is an inline edit on the asset detail's tags row**, not a new screen. Spec §2.7 requires it be editable without rewriting the payload but not where; the tags list is where the owner is already comparing the two tags |
| 34 | B11 | **B09 reads the placement label off the `TagBinding` its `Resolution.OpenAsset` already carries**, so B11 exposes no new interface and the two briefs have **no ordering dependency** in either direction |
| 35 | B12 | **MCP argument names are snake_case mirrors of the camelCase wire fields** (`postponed_due_on` ↔ `postponedDueOn`), the shipped convention, with each docstring naming its wire field |
| 36 | B14 | **`CompletionFlow` is a shared collaborator**, which is why B14 gates B09: spec §2.8 has the sheet delegate entirely to the #4 use cases and spec §2.9 gives the in-app close the same date affordance as the API, and one collaborator is how the surfaces cannot diverge |
| 37 | B14 | **D-11's "similar" is a case-insensitive `title` match** between the asset's own schedules and the schedules of the groups it is an open member of. D-11 rules the warning in and deduplication out but does not define the trigger; a title match cannot itself become a deduplication rule, and the warning is non-blocking |
| 38 | B15 | **Asset detail's schedules section lists asset-targeted schedules only**; group-targeted work is reached through the groups section. Listing both would show one obligation twice and contradict D-15's counted-once principle — the scan sheet is the surface that deliberately merges them |
| 39 | B15 | **Archived groups are listed, visibly distinguished, rather than hidden.** Spec §2.3's "hides it and its schedules" is applied to the schedules and the due work; a group the owner cannot find is a group whose retained history is unreachable, and 1.2 has no filter UI to build |
| 40 | B06 | **The channel-per-status mapping:** `OVERDUE` subjects on `maintenance_overdue` (HIGH), `DUE` and `DUE_SOON` on `maintenance_due` (DEFAULT). Spec §5.5 names the two channels and their importances but not which subject state uses which; the mapping follows the channel names, and the alternative — everything on one channel — would waste the HIGH importance D-20 created |
| 41 | B09 | **A scoped read-only measurements seam** (`LastCompletionReadings`) supplies the last completion's key readings the sheet must show (D5 §7A). `DueItem` cannot carry them — profile values are heterogeneous per schedule — and B09's own gate forbids reaching for a write port; the seam is declared read-only and the gate grep is narrowed to the write methods |

---

## 19. Fix wave — what changed

Four independent plan reviews (`plan-review-master.md`, `plan-review-B01.md` … `plan-review-B15.md`, 2026-09-22) returned **NEEDS CHANGES** on the master plan (4 Blocking, 6 Should-fix, 5 Note), on **B02** and on **B09** (one Blocking each), and APPROVE on the other thirteen with should-fixes and notes. Every Blocking and every Should-fix is fixed below; the notes are taken where cheap. Nothing on the reviewers' "must not be lost" list was traded away — §2.3's four-rule table, §4.1's archive-internal-guard footnote, §5.2's conditional-write rule, §5.3's `prevDue` rule, §7's five consequences, §12.3's triple S4 statement, §13's mapping, and decisions 8 and 19 are all intact.

### Blocking

| # | finding | fix |
|---|---|---|
| master B1 | `NO_DATA` was one state and is two, against invariant 74 | §11.1's table now carries **two `NO_DATA` rows** — repairable meter-baseline (ATTENTION, counted, admitted to the sheet) and empty-required-set (no section, not counted, not promoted, never offered, never closeable). Decision 29 restated to admit `NO_DATA` **only in its repairable form**; decision 27 now states that `DueReadModel` exposes the status **and an empty-required-set flag** and carries **no `isActionable` boolean**. B08 gains a matrix row proving the split on a component asset |
| master B2 | the lane plan's isolation claim contradicted its own wave table | §14.3 rewritten: **three numbered isolation rules** — one worktree per lane on its own branch cut from the release tip, **`emulator-5554` held by one lane at a time** with connected runs serialised and a waiting lane working its unit gate, and file-set (not module-path) separation. The wave table gains a **device** column and a **file-set separation** column naming `AppGraph.kt` and `ServiceTagRoot.kt` as the two shared files the controller resolves at merge. Decision 13 restated. "Two lanes is a ceiling, not a target" added |
| master B3 | §17 claimed completeness; the editor's remaining labels were neither ratified nor PROPOSED; B14 had no "drafts nothing" guard | §17 gains the **editor field-labels PROPOSED row** (target picker, season behaviour, completion mode, profile picker, the four meter-rule fields, the provider row) and a closing rule: **any string the table does not list is a finding, and no brief invents one**. Decision 12 extended from three classes to four. B14's Strings section gains the PROPOSED paragraph **and** the explicit guard |
| master B4 | the pre-implementation gate was nowhere, while the first line ordered a worker to execute | The `For agentic workers` line now **opens with "EXECUTION IS GATED. Do not start a brief."** and states the read-only condition; §1's **first bullet** is the same gate. No brief starts until the owner explicitly authorizes implementation after reading the planning summary |
| B02 | Purpose and a matrix row claimed the **snooze** operation, which B06 owns | Purpose now says **three** operations (complete, postpone, edit recurrence) plus the lifecycle pair, and states that snooze is B06's under D-13 with no port here that could reach the column. The matrix row drops the snooze clause and the **invariant 20** citation, leaving 19 and 21. The split note now says "the three operations" |
| B09 | the sheet's display contract needed event measurements no interface supplied, and its own gate grep forbade the read | Interfaces declares a **scoped read-only `LastCompletionReadings` seam** (`forEvent(eventId) -> List<Measurement>`, backed by the shipped `EventRepository.get`, **no write method**), recorded as **decision 41**. The gate grep is narrowed to the **write methods** (`(events\|closures)\.(upsert\|insert\|delete)`) with a sentence saying why the bare identifier was wrong |

### Should-fix

| # | finding | fix |
|---|---|---|
| master S1 | decisions 3 and 4 override explicit spec statements | Both relabelled **"Spec deviation (not a silence)"** at their point of use and in §18, whose preamble now states that §1's follow-the-spec rule **does not apply to a deviation this plan has named** |
| master S2 | the graph omitted B05 → B10 and B06 → B10 | §14.2 gains "**Two edges the diagram does not draw**", naming both with their decisions |
| master S3 | "B04 may ride wave 4 lane B" broke B06's dependency | Replaced: **B04 must land before wave 4 opens**; if it slips, wave 4 lane A slips with it; it may not ride wave 4 lane B — that is B06's wave |
| master S4 | `MergePlan.kt:14-20`'s "seven canonical tables" KDoc was on no edit list | §4 names it as **B01's** edit; B01's `MergePlan.kt` Modify bullet and its gate grep (`grep -c 'seven canonical tables'` → **0**) both carry it |
| master S5 | several §16.4 greps were prose, not anchored commands | §16.4 is now a **table of eighteen commands with expected counts**, `<base>`-parameterised |
| master S6 | "actionable" meant two sets behind one projection | §11.1 gains the **surface-specific paragraph**: the dashboard's promotion set is ATTENTION-or-UPCOMING, the sheet's is D-18a's, and the projection exposes status + flag rather than one boolean |
| B01 | invariant 65 omitted from the list | Added, with the note that **one grep discharges B01's half of both 64 and 65** |
| B04 | invariant 22 overclaimed | Dropped from the list and from the matrix row, with the reason stated: B04 proves the adjacent **47** and has no notification or dashboard code |
| B08 | §17 omitted B08 as a user of "Log meter reading" | Added to that row's used-by column |
| B09 | invariant 5 not declared; §17 omitted B09 as a user of "Tag placement" | Invariant 5 added with its **structural** proof named (`TagTarget` has no group case); §17's row gains B09 |
| B12 | §13 omitted B12 from invariant 80 | Added, and §13 gains a **legend** distinguishing enforcement proofs from **(UI)** cannot-violate proofs |
| B14 | invariants 74 and 77 cited in a matrix row but absent from the list | Added, with the reason: the "Close this round" gate is where an empty required set must be unreachable through the UI |

### Notes taken

§13's attribution crosswalk reconciled in both directions (**B06** added to 47/61/64, **B07** to 57, **B09** to 5, **B12** to 80, and the **(UI)** briefs B09/B14/B15 to the invariants their surfaces cannot violate: 7, 8, 28, 29, 33, 74, 77, 79, 80). §1's spec citation now points at the **committed** path, "revision 4 plus §11's revision-4.1 edits" (N1). §14.1's last column relabelled an **implementation**-size estimate, with the reason brief documents are shorter (N2). §16.1 says the baseline is measured at **`d790506`, the last code-changing commit**, with the release branch cut from the docs-only tip (N3). §17 gains the blanket line that **every word D12 carries is pre-ratified**, covering B10's **REMINDER FAILED** (N4). The `MergePlanner.kt:~460` tilde citation is now `:454-466` with its reasoning at `:64-66` (N5). B05's `PendingIntent` grep is labelled a **standing trip-wire** that first bites in B06/B07 (B05 N1). B06's channel-per-status mapping is tagged a `Plan decision:` and catalogued as **decision 40** (B06 N2). B07's manifest bullet is split into two numbered additions, since a `<receiver>` is not one line (B07 N2). B11 states that invariant 5 is the **group-identity discipline** it must not break, not a group rule it implements (B11 N2). B12 gains a matrix row proving an **overlay still round-trips through the owning use case's validation** (B12 N1). B13's diff-scope line now says the `docs/` and `README.md` edits are outside that diff and are covered by item 7's greps (B13 N2). B14 gains the **comparison-set clause** on the duplicate-warning row, ruling out a global title collision (B14 N2). B15 and B09 state what their **(UI)** attribution means (B15 N2).

### Left unfixed

**None.** Every Blocking and Should-fix item across the sixteen reviews is fixed in place; the five master notes and the ten brief notes are all taken. The two notes that were purely observational — the master's N2 and each brief review's "size vs. estimate" note, which every reviewer explicitly marked "no action needed" — are answered by §14.1's relabelled column rather than by lengthening the briefs, because the reviewers found **no content gap** behind the line counts and padding would trade the anti-restatement discipline for a number.

**Plan decisions after this wave: 41** (§18: sixteen in the numbered list, twenty-five in the per-brief table, of which **40** — B06's channel-per-status mapping — and **41** — B09's read-only readings seam — are new here).

### Residuals — the scoped re-review and the reconciliation

The scoped re-review of the fix wave returned **READY FOR THE GATE** with five residuals (R1–R5, none contract-level), and the reconciliation against the ten issues, the eighty invariants and every ruling returned five findings plus eight notes. This edit closes every one that is the plan's to close, and refers the three that are the **owner's** to §1.2.

| # | source | finding | fix |
|---|---|---|---|
| **R1** | re-review | B03 and B04 still offered B04 the wave-4 escape hatch §14.3 had just closed | both Ordering sections rewritten: B04 takes **lane B of wave 3** and **must land before wave 4 opens** because B06 consumes it; "it may not ride wave 4 — lane A is B06 and lane B is B08" |
| **R2** | re-review | B09 did not pin §11.1's promise that an empty-required-set `NO_DATA` is never offered on the sheet | the clause is added **inside the existing D-18a row** (no new test, so proportionality is untouched), **74** is added to B09's invariant list as a UI-boundary fact, and §13's invariant-74 column gains **B09 (UI)** |
| **R3** | re-review | two §16.4 counts wrong; the table's `\|` was markdown escaping, not grep syntax | `<receiver` **6 → 5** (the manifest declares none today; §12 adds five), `createNotificationChannel` **"1 site" → "≥ 1"** with the `supplies`/`sync_problems` grep named as the assertion that actually carries invariant 53, and **§16.4 is now a list rather than a table** so every ERE alternation runs as written — with a sentence saying why the shape changed |
| **R4** | re-review | the wave table's device column named one holder where two briefs need the emulator | the column is now **device first**, each cell reads "**Bxx**, then Byy", and isolation rule 2 states that the column names which lane takes the emulator first and that the other queues behind it |
| **R5** | re-review | nested bold in B01's invariant list | inner pair dropped |
| **F1** | reconciliation | invariant 49 / #28 AC 4 unconstructable: `ProviderId` has one member, so two enabled provider rows cannot exist and the two-list case cannot be built | **controller ruling taken: `ProviderId` stays an enum with `LOCAL` only.** Decision 8 and B04's `ProviderId` row and matrix row now record that the two-provider case is proved **structurally** — the provider is a parameter of `forProvider` and the key of `all()`'s map, `reconcile` names no provider, and a second member is purely additive — and that **AC 4's behavioural half lands with Phase 5**. The rejected alternative (a sealed or value `ProviderId` with a test-only second member) is recorded as rejected |
| **F5** | reconciliation | the **notification's own** visual-design acceptance (#11, #21) was proved by no brief | one proportionate matrix row in **B06**: a posted notification carries the ratified status word plus its icon from the semantic tokens, never an improvised colour, and the DUE/OVERDUE distinction survives with colour removed — the one surface B08's in-app grayscale proof does not reach |
| **N1** | reconciliation | §13 did not credit B14 on the ten invariants B14's own list claims, while the legend asserts the crosswalk agrees in both directions | **B14 (UI)** added to rows **1, 2, 3, 19, 20, 21, 25, 27, 35, 36** |
| **N2** | reconciliation | #50 AC 8 was contract in B09's Interfaces but named by no matrix row | folded into B09's "one due quick item" row, retitled "**and the reminder quiesced by state**", asserting `reconcile` runs and that the notification is not the source of truth |
| **N5** | reconciliation | §1 and §12.3 said the S4 trial was "already armed" — operational state, which spec revision 4.1 struck from the spec for the same reason | both sentences removed; both places now say that **whether it has been armed, and when, belongs in the ledger, not in this plan** |
| **N6** | reconciliation | §12.1's "Done, QUICK → broadcast" row omitted the meter carve-out that B07's Interfaces states | the row gains it: a `QUICK` schedule with a **meter rule** routes "Done" into the canonical completion flow as an **activity** `PendingIntent`, because it cannot complete without its reading and the notification never fabricates one |
| **F2 · F3 · F4** | reconciliation | three #5 requirements — category/status **filtering**, the **meter due value** on an asset row, and a **"log maintenance"** quick action — are in no spec section, no brief and no ruling | **not decided here.** New **§1.2** lists all three as **PENDING OWNER RULING at the gate**, each with its recommended default (in 1.2: one B08 scope line plus one matrix row) and its alternative (explicitly out, recorded in the spec's exclusions), plus the note that none changes a contract, an invariant or a wire shape — and that any new string they need is a §17 PROPOSED item, not a brief's to invent |

**Recorded as deliberate, no action (reconciliation N3, N4, N7, N8 and every reviewer's "size vs. estimate" note):** #21 AC 1's device half is proved off-device by the no-overnight-blocking ruling; #4 AC 3's "observed in the UI" is proved in the domain under proportionality; B13 claims none of the eighty because it changes no behaviour; #49 AC 8 is covered from the other side by B11's "a second tag becoming a second asset" row; and the brief line counts are answered by §14.1's relabelled column, because every reviewer found no content gap behind them.

**§17 now carries a concrete PROPOSED draft for all forty-four unratified strings** (§17.1), across all six classes, so the owner ratifies or rewrites at the gate rather than a brief blocking on a string later. Every one stays marked PROPOSED.
