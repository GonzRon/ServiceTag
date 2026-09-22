# B01 — Room schema 5 → 6, backup format 6, and the merge

**Read first:** the master plan's §1 (global constraints), §2 (schema), §3 (format 6), §4 (the merge) and §13 (invariants). Those sections are the contract; this brief is the work.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §3.1, §3.2, §3.3, §3.4, §2.9 (the closure fact), §2.3 (membership).

## Purpose

Land the data contract 1.2 needs, and nothing else: seven new Room tables plus three new columns and two new indices on `asset_event`, the `MIGRATION_5_6` that produces them without touching a single existing row, the exported `6.json`, backup **format 6** (three new top-level lists, three new event fields, five new `counts` keys, deterministic sorting, eager validation, graph validation) and the merge extension (three new `MergeTable` members in dependency position, seven new reason codes, the closure's second identity, and the total post-apply rebuild). **No engine, no reminder, no UI, no API.** Every brief after this one compiles against what this brief produces, which is why it gates B02 and B03 and why its field sets and index names are asserted rather than assumed.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/MaintenanceEntities.kt` — the seven `@Entity` classes of master plan §2.1, with the columns, foreign keys and indices that table gives, in the style of `entities/JournalEntities.kt`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/MaintenanceDaos.kt` — the DAOs (see Interfaces).
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/MaintenanceMappers.kt` — entity ↔ domain mappers, in the style of `data/room/JournalMappers.kt`.
- `core/src/main/kotlin/com/loosecannon/servicetag/core/model/Maintenance.kt` — the data classes and enums the DTOs and mappers need: `MaintenanceGroup`, `GroupMember`, `MaintenanceSchedule`, `ScheduleTarget`, `ScheduleProviderRow`, `OccurrenceClosure`, `ScheduleState`, `RecurrenceUnit`, `TimeBasis`, `SeasonBehavior`, `CompletionMode`, `ScheduleStatus`, `TerminationKind`, plus `ScheduleId`/`GroupId` added to `model/Ids.kt`. **Data shapes only** — no recurrence arithmetic, no status function, no use case: those are B02's and B03's.
- `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/6.json` (generated and committed).
- Tests: `app/src/test/kotlin/.../data/MaintenanceMigrationTest.kt`, `app/src/test/kotlin/.../data/MaintenanceDaoConstraintTest.kt`, `core/src/test/kotlin/.../backup/BackupFormat6Test.kt`, `core/src/test/kotlin/.../merge/MergePlannerMaintenanceTest.kt`.

**Modify**

- `app/.../data/room/AppDatabase.kt` — seven entities added to the list; `version = 6`; the new DAO accessors.
- `app/.../data/room/entities/JournalEntities.kt` — `AssetEventEntity` gains `schedule_id`, `occurrence_on`, `details_pending`; its `indices` gain `UNIQUE(schedule_id, occurrence_on, asset_id)` and `(schedule_id, occurred_on DESC)`; the `maintenance_schedule` foreign key with `onDelete = SET_NULL`.
- `app/.../data/room/Migrations.kt` — `MIGRATION_5_6`.
- `app/.../di/AppGraph.kt` — `SCHEMA_VERSION = 6`; `MIGRATION_5_6` added to `addMigrations(...)` at `AppGraph.kt:88`. **No repository field is added here by this brief** (see Ordering). **Amended at review (2026-09-22):** see master §18.17 — the three minimal adapters and fields were written here after all (brief self-contradiction ruled R1).
- `app/.../data/room/JournalMappers.kt` and `app/.../data/room/JournalRepositories.kt` — the three new event fields carried through.
- `core/.../core/model/Journal.kt` — `EventSource` gains `SCHEDULE_QUICK_COMPLETE`, `TODOIST_SYNC`, `TELEMETRY`; `AssetEvent` gains `scheduleId: ScheduleId?`, `occurrenceOn: String?`, `detailsPending: Boolean`.
- `core/.../core/backup/BackupFormat.kt` — the five new DTOs of master plan §3.1, `BackupData`'s three new lists, `AssetEventDto`'s three new fields, and the `toDto()`/`toDomain()` pairs for each.
- `core/.../core/backup/BackupCodec.kt` — `FORMAT_VERSION = 6`; the three lists in `encode`'s `sorted` block and in `counts`; the eager `toDomain()` validation pass; `validateGraph`'s new references.
- `core/.../core/merge/MergePlan.kt` — `MergeTable`'s ten members in master plan §4's order **and its KDoc at `:14-20`, which says "The seven canonical tables" and becomes ten** (master plan §4); `MergeReason`'s seven new members; `MergeWrites`, `MergeSnapshot`, `MergeReport` each gaining `groups`, `schedules`, `closures`; `MergePlan.report()` gaining the three tallies.
- `core/.../core/merge/MergePlanner.kt` — the three new passes in write order, following the existing five-step shape.
- `core/.../core/usecase/ApplyBackupMergePlan.kt` — the three new write loops in `MergeWrites` field order, and the `rebuildAll` seam.
- `core/.../core/usecase/BuildBackupMergePlan.kt`, `ImportBackupReplace.kt`, `ExportBackupSet.kt` — the three new tables read and written.
- `core/.../core/ports/Repositories.kt` — the three **data** ports this brief needs to export, import and plan (see Interfaces). The *behavioural* ports (`ScheduleRepository`'s query methods for the engine) are B02's.
- Existing tests that construct an `AssetEvent`, a `BackupData` or a `MergeSnapshot` positionally: extend with the new fields at their defaults and change **nothing else** about what they assert.

**Untouched:** every `app/src/main/kotlin/.../ui/**`, `api/**`, `nfc/**`, `prefs/**`; `core/.../core/usecase/LogEvent.kt` and `EventCommands.kt` (B02/B03 extend the completion path); `tools/`; `libs/`; `docs/api/v1.md`; `app/schemas/{1..5}.json` (byte-identical); `app/build.gradle.kts` (no version change here — that is B13).

## Interfaces

**Produces, for B02, B03, B06 and B12:**

- The five DTOs with exactly the field sets of master plan §3.1, plus `BackupData`'s three lists and `AssetEventDto`'s three fields.
- The domain data classes of master plan §5.2 and §6, as shapes only.
- DAOs. Each is an `@Dao` interface with, at minimum, the operations the later briefs need — declared here so an adapter author does not add a DAO method mid-brief:

| DAO | operations |
|---|---|
| `MaintenanceGroupDao` | upsert group; upsert/replace member rows; get group with members; all groups; groups for an asset (open windows only, and all windows); delete all |
| `MaintenanceScheduleDao` | upsert schedule; upsert/replace provider rows; get with providers; all; for asset; for group; delete all |
| `OccurrenceClosureDao` | **insert and query only** — no update, no delete (invariant 37). Insert one; list for a schedule ordered by `occurrence_on`; find by `(schedule_id, occurrence_on)`; all |
| `ScheduleStateDao` | upsert one; get one; all; observe all; delete all |
| `ScheduleLocalDeliveryDao` | upsert one; get one; all; delete all |

- `MergeTable`, `MergeReason`, `MergeWrites`, `MergeSnapshot`, `MergeReport` in their extended shapes.
- The rebuild seam. `ApplyBackupMergePlan` gains one collaborator so this brief can prove the total rebuild without depending on B02's engine:

```kotlin
// core/.../core/usecase/ApplyBackupMergePlan.kt — a new constructor parameter
private val rebuildAll: suspend () -> Unit,   // runs INSIDE the apply's transaction, after every write
```

`Plan decision:` the seam rather than a direct call to `ScheduleRecompute`. Master plan §4.3 requires the rebuild to be total and inside the transaction, and B01 gates B02, so B01 cannot reference B02's engine. B01 proves "invoked exactly once, inside the transaction, after the writes"; B02 wires the real implementation and proves it recomputes every schedule.

**Consumes:** nothing from another 1.2 brief. It extends shipped code only.

## Invariants this brief must hold

**6, 7, 32, 37, 42, 62, 63, 64, 65, 66, 67, 69, 71, 72, 80** (master plan §13). 65 ("no notification-delivery state is exported or merged") is the general rule where 64 names the two tables; **one grep discharges B01's half of both** — `schedule_state` and `schedule_local_delivery` absent from `core/.../backup/` — and the brief says so rather than leaving 65 to be inferred. It must not make **17, 18, 26** unholdable — no field this brief adds stores a status word, and `season_reentry`/`season_reentry_offset_days` exist with no reader.

## Test matrix

One test per hazard class. Each must fail without the change it names.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| a new field silently dropped in transit | every new field of every new DTO, and the three new `AssetEventDto` fields, survive `encode` → `decode` with non-default values | a field omitted from a DTO or a mapper comes back at its default and the assertion on the round-tripped value fails |
| DTO field-set drift | for each of the five new DTOs, the serializer descriptor's element names equal the master-plan §3.1 list exactly (count and names) | a field added later, or misnamed, changes the descriptor and the set comparison fails |
| an old archive stops working | a hand-built **format-5** archive decodes and restores with zero groups, zero schedules, zero closures, and its events carrying `scheduleId = null`, `occurrenceOn = null`, `detailsPending = false`; **no schedule is invented** (invariant 62) | a list declared without a default makes the decode throw; a mapper that fabricates a schedule makes the count assertion fail |
| a newer archive read by an older build | a format-6 manifest is refused with `BackupNewerFormat` before any row is read, asserted against the shipped gate (`BackupCodec.kt:150-151`) with `FORMAT_VERSION` held at 5 in the test's expectation (invariant 63) | bumping `FORMAT_VERSION` without the gate lets a 1.1.x build read rows it does not understand |
| counts drift from rows | the manifest's `counts` has exactly the 16 keys, and the five new ones equal the row counts for an archive with nested members and providers | a missing key or a `sumOf` over the wrong list gives the wrong number |
| non-deterministic bytes | encoding the same `BackupData` with its lists and child lists shuffled produces identical bytes; members come out in `(sortOrder, id)` order and providers by `provider` | an unsorted list or a sort on a non-total key makes the two byte arrays differ |
| the migration destroys data | a v5 database seeded with an asset, a definition, a profile, an event with measurements and consumables, a tag and an attachment migrates to v6; **every seeded row reads back field-for-field identical**, and the database then validates for a v6 `AppDatabase` open (D4 §15) | a table recreate that drops a column, reorders one, or forgets a value fails the row comparison; a schema that does not match `6.json` fails the open |
| the migration produces a different schema than a fresh install | the migrated database's columns, primary keys, foreign keys and index names for `asset_event` and all seven new tables equal a fresh v6 install's | an `ADD COLUMN` that does not register the foreign key, or a hand-written index name, differs |
| occurrence idempotence is only a convention | inserting two events with the same `(schedule_id, occurrence_on, asset_id)` fails at the database; inserting many events with `schedule_id`/`occurrence_on` **NULL** all succeed (invariant 32, the NULL-distinct property) | without the unique index the second insert succeeds; with the index declared on the wrong columns the NULL case fails |
| closing twice leaves two rows | inserting two closures with one `(schedule_id, occurrence_on)` fails at the database and the first row is unchanged (invariant 38) | a missing unique index lets both in |
| the closure table becomes mutable | a structural assertion over `OccurrenceClosureDao` and the closure entity: no `@Update`, no `@Delete`, no `updated_at` column, and no `UPDATE`/`DELETE` SQL against `occurrence_closure` anywhere in `app/src/main` (invariant 37) | an `@Update` added for convenience makes the assertion fail |
| membership cannot represent remove-then-re-add | two rows for one `(group, asset)` with different `added_at` insert; two with the same `added_at` fail (invariant 6) | `UNIQUE(group_id, asset_id)` — #55's original sketch — rejects the legitimate second row |
| merge order breaks a reference | the ten `MergeTable` members are in master plan §4's order, asserted as a list; a plan over an archive whose group, schedule, closure and event all arrive together writes them in that order | a member inserted at the end makes a schedule decided before its group and the `OWNER_NOT_AVAILABLE` assertion inverts |
| an unchanged re-import stops being a no-op | an archive re-imported into the phone that produced it plans **all `IDENTICAL`** across the three new tables and the events; and after a completion and a closure have advanced an **un-postponed** schedule, re-importing the *original* archive still plans the schedule row `IDENTICAL` (invariant 69) | a mapper that writes `updated_at` on the schedule row during a termination makes the row differ and the verdict becomes `CONTENT_DIFFERS` — this is the one test that catches master plan §1's conditional-write rule from the data side |
| a changed row is silently overwritten | a locally edited group, schedule and closure each plan `CONFLICT / CONTENT_DIFFERS`, and the plan's `writes` are **empty in every field** (invariants 66, 67) | a `MergeVerdict.UPDATE` or a partial write set makes the emptiness assertion fail |
| the closure's second identity | one test per row of master plan §4.2: `INSERT`; `IDENTICAL`; `CONTENT_DIFFERS`; **`IDENTICAL / CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`** for a local row under a different id that is equal field for field with `dto.copy(id = local.id)`; **`CONFLICT / CLOSURE_DIVERGED`** for a different `closed_on`; **`CONFLICT / CLOSURE_DUPLICATED_IN_ARCHIVE`** for two archive rows claiming one pair; **`OWNER_NOT_AVAILABLE`** for an absent schedule | matching on the row id alone makes the equivalent-local-row case an `INSERT` that then violates the unique index at apply time |
| group and schedule owner checks | a group whose member names an absent asset, and a schedule whose target / meter definition / profile is neither local nor being inserted, are each `OWNER_NOT_AVAILABLE` and not an orphan (invariant 71) | without the reference pass the apply fails on a foreign key with rows already written |
| a schedule with a broken target | an archive schedule with both `assetId` and `groupId` set, and one with neither, are each `CONFLICT / SCHEDULE_TARGET_INVALID` | without the check the row inserts and the domain has an unrepresentable schedule |
| child-row id collisions | a membership row whose `id` is already held by another group's member, and a provider row whose `(schedule_id, provider)` is taken, are each a conflict (`CHILD_ROW_ID_TAKEN`, `CONTENT_DIFFERS` respectively) | the durable-id rule (D-14) is violated and two groups share a member row id |
| **the two membership guards are archive-internal** | `GROUP_MEMBER_WINDOW_TAKEN` and `GROUP_MEMBER_ALREADY_OPEN` are produced from a **crafted archive** that is internally inconsistent — two of its own groups claiming one `(group_id, asset_id, added_at)`, and one group carrying two open windows for one asset — **and** a separate assertion that two phones with genuinely divergent membership produce `CONTENT_DIFFERS` on the **group row** and neither membership code (master plan §4.1, spec revision 4.1) | a test written as a two-phone divergence story asserts a code that path cannot produce, and passes vacuously or fails for the wrong reason |
| an event completing an occurrence twice across devices | two archives recording one member's completion of one occurrence under different event ids collide as `SCHEDULE_OCCURRENCE_TAKEN` | without the new index check the apply fails on the unique index after writing other rows |
| report non-determinism | conflicts across all ten tables come back sorted by table ordinal then id, with the three new tallies present and correct | sorting by id alone, or a missing tally, changes the report a client reads |
| the post-apply rebuild is not total | the `rebuildAll` seam is invoked **exactly once**, **inside** the apply's transaction, and **after** every write loop (master plan §4.3) | a call outside the transaction, or one per table, or none at all, fails the ordering/count assertion |

## Strings

**None.** This brief adds no user-visible string. If an implementer finds one needed, that is a finding for the controller, not a string to invent.

## Ordering

Nothing precedes it. **It gates B02 and B03**, and through them everything else. It **does not** write the Room repository adapters or their `AppGraph` fields: `Plan decision:` the adapter for a port belongs to the brief that declares the port — `ScheduleRepository`/`ScheduleStateRepository` to B02, `GroupRepository`/`ClosureRepository` to B03, `ScheduleLocalDeliveryRepository` to B06 — because that brief knows the port's contract, and spec §7's `(:core)` label is about where the *domain* lives, not about leaving an adapter unowned. B01 declares the DAO operations those adapters call so no adapter author has to reopen this brief.

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips. The review records the per-module counts.
- Structural, anchored:
  - `grep -c 'enum class MergeTable' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → 1, and the member list equals master plan §4's order.
  - `grep -c 'seven canonical tables' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0** (the KDoc now says ten).
  - `grep -nE '\bUPDATE\b' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` finds no `MergeVerdict` member named `UPDATE`.
  - `grep -rnE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?`?occurrence_closure' app/src/main core/src/main` → no match.
  - `grep -rn '@Update\|@Delete' app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/MaintenanceDaos.kt` → no match on the closure DAO.
  - `grep -rnE '^import (android|androidx)\.' core/src/main` → no match (invariant 48 stays true).
  - `grep -rn 'schedule_state\|schedule_local_delivery' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/` → no match (invariant 64).
  - `grep -n 'const val FORMAT_VERSION' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt` → `= 6`, one occurrence; `grep -n 'version = ' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt` → `6`; `grep -n 'SCHEMA_VERSION' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` → `6`.
  - `git diff --stat master -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/1.json ...5.json` → empty; `6.json` exists.
  - `git diff --stat master -- app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/api tools libs docs/api` → empty.
- No connected run: this brief draws nothing.

## Estimated size

Large but single-purpose: seven entities, five DAOs, five DTO pairs, one migration, three planner passes. If the merge passes and the codec work together push the diff past what one review can hold, split at the natural seam — **B01a schema and migration** (`:app`, plus the `:core` data shapes and the `AssetEvent` widening) and **B01b format 6 and the merge** (`:core` only) — and say so in the ledger. The two halves have one interface between them: the DTO field sets of master plan §3.1.
