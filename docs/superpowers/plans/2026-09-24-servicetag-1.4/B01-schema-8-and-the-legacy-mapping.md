# B01 — schema 8, the domain shapes, the legacy mapping and #64

**Read first:** master plan §1 (constraints), §3 (schema 8), §4 (the legacy mapping), §14 (invariants), §15 (RED-first).
**Spec:** §8.1, §8.2, §4.1, §4.3 ("Rule fields"), §3.3, §5.1, §6.1; issue #64.
**Wave 1, lane A, single lane.** Nothing precedes it; it gates every other brief.

## Goal

Land the 1.4 data contract and nothing else of 1.4's behaviour: the domain shapes of master §3.6, the `LegacySeasonMapping` object and its golden cases file, Room schema **8** with `MIGRATION_7_8` (five asset columns, the 12-step recreate of `maintenance_schedule`, `schedule_state` recreated as derived, three new tables) and the exported `8.json`, the three new repository ports with their Room adapters and in-memory fakes, and **one deliberate behaviour change — #64**: `rule_changed_at` is written only by a rule change and is the pin floor. Because removing three schedule fields breaks every reader at once, this brief also makes the whole tree compile with **behaviour-preserving substitutions**, listed below site by site, so that status, reminders, the API, the editor and the format-7 codec answer exactly as 1.3 did on every row 1.3 could hold. Backup format stays **7** here (B03 owns format 8); no route, no string and no screen changes.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/model/SeasonModel.kt` — `SeasonMode`, `SeasonAction`, `SeasonActivation`, `SeasonInputs`, and `fun Asset.seasonInputs(activations: List<SeasonActivation>): SeasonInputs`.
- `core/.../core/model/ServicePolicy.kt` — `ServicePolicy`, `PolicyPhase`, `PolicyReason`, `LegacySeasonMapping` (master §4's signature).
- `core/.../core/model/Condition.kt` — `OperationalCondition`, `AssetCondition`.
- `core/.../core/model/Health.kt` — `HealthSubjectKind`, `HealthDriver`, `HealthAggregation`, `HealthSubject`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/SeasonHealthEntities.kt` — the three `@Entity` classes of master §3.4.
- `app/.../data/room/dao/SeasonHealthDaos.kt` — `SeasonActivationDao`, `AssetConditionDao`, `HealthSubjectDao`.
- `app/.../data/room/SeasonHealthMappers.kt`, `app/.../data/room/SeasonHealthRepositories.kt`.
- `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/8.json` (generated, committed).
- `docs/api/legacy-season-mapping.json` — the golden cases (master §4).
- Tests: `core/src/test/kotlin/com/loosecannon/servicetag/core/model/LegacySeasonMappingTest.kt`; `core/src/test/.../core/usecase/SaveScheduleRuleFieldTest.kt`; `app/src/test/kotlin/com/loosecannon/servicetag/data/room/Migration7To8Test.kt`; `app/src/test/.../data/room/SeasonHealthDaoConstraintTest.kt`.

**Modify**

- `core/.../core/model/Asset.kt` — five fields with defaults (`seasonMode = YEAR_ROUND`, both blackout fields null, `healthAggregation = WORST`, `healthPrimarySubjectId = null`).
- `core/.../core/model/Maintenance.kt` — `MaintenanceSchedule` and `ScheduleState` per master §3.6; `SeasonBehavior` stays, documented as the legacy mapping's input only.
- `core/.../core/model/Ids.kt` — `HealthSubjectId`.
- `core/.../core/ports/Repositories.kt` — the three ports (Interfaces).
- `core/.../core/schedule/ScheduleRecompute.kt`, `ScheduleStatus.kt` — compile-through (below) and the pin floor.
- `core/.../core/usecase/RecomputeSchedules.kt` — constructor gains `activations: SeasonActivationRepository`; `inputsFor` builds `SeasonInputs` for an asset target (activation rows read only when the asset is MANUAL).
- `core/.../core/usecase/SaveSchedule.kt`, `ScheduleCommands.kt` — #64 and the command fields.
- `core/.../core/reminders/BuildReminderSubjects.kt` — compile-through only.
- `core/.../core/backup/BackupFormat.kt` — compile-through only (format 7 unchanged on the wire).
- `core/.../core/usecase/ExportBackupSet.kt`, `ImportBackupReplace.kt`, `BuildBackupMergePlan.kt`, `ApplyBackupMergePlan.kt` — constructors gain the three repositories, held unused for B03 (master §3.7).
- `app/.../data/room/entities/AssetEntity.kt`, `entities/MaintenanceEntities.kt`, `AppDatabase.kt` (version 8, three entities, three DAO accessors), `Migrations.kt` (`MIGRATION_7_8`), `Mappers.kt`, `MaintenanceMappers.kt`.
- `app/.../di/AppGraph.kt` — `SCHEMA_VERSION = 8`; `MIGRATION_7_8` in `addMigrations`; three repository fields; the widened constructors of `RecomputeSchedules` and the four backup use cases.
- `app/.../api/MaintenanceDtos.kt` — compile-through only.
- `app/.../ui/maintenance/ScheduleEditViewModel.kt`, `ScheduleEditScreen.kt` — compile-through only.
- Test sources that construct a `MaintenanceSchedule`, `ScheduleState`, `Asset` or a schedule entity, or name a removed field — mechanical edits only: `core/src/test/.../testing/{MaintenanceFixtures,InMemoryRepositories}.kt`, `app/src/test/.../testing/{ScheduleFixtures,FakeGraph}.kt`, `app/src/test/.../api/MaintenanceFixtures.kt`, and the tests listed under "Behaviour preserved". `ScheduleStructuralTest.theDeferredReentryColumnsAreStoredAndNeverRead` is **deleted** (spec §11 retires inv. 26; B02 adds inv. 104's check). `VersionAgreementTest.theSchemaAndTheFormatAreBothSeven` becomes "schema 8, format 7" (B03 moves the format half).

**Untouched:** every `ui/**` file but the two schedule-editor files; `api/**` but `MaintenanceDtos.kt`; `core/.../core/merge/**` logic; `BackupCodec.kt` (`FORMAT_VERSION` stays 7); `app/schemas/.../{1..7}.json`; `tools/`; `libs/`; `docs/api/v1.md`; `app/build.gradle.kts`.

## Interfaces

**Produces** (master §3.6 is the authority for the shapes):

```kotlin
interface SeasonActivationRepository {        // insert and query only
    suspend fun insert(row: SeasonActivation)
    suspend fun forAsset(assetId: AssetId): List<SeasonActivation>   // (occurredOn, createdAt, id)
    suspend fun all(): List<SeasonActivation>
    fun observeForAsset(assetId: AssetId): Flow<List<SeasonActivation>>
}
interface ConditionRepository {               // insert and query only
    suspend fun insert(row: AssetCondition)
    suspend fun forAsset(assetId: AssetId): List<AssetCondition>     // (occurredOn, occurredTime nulls first, createdAt, id)
    suspend fun all(): List<AssetCondition>
    fun observeForAsset(assetId: AssetId): Flow<List<AssetCondition>>
}
interface HealthSubjectRepository {           // configuration: upsert, no delete
    suspend fun upsert(subject: HealthSubject); suspend fun get(id: HealthSubjectId): HealthSubject?
    suspend fun forAsset(assetId: AssetId): List<HealthSubject>      // (sortOrder, id)
    suspend fun forSchedule(id: ScheduleId): List<HealthSubject>; suspend fun all(): List<HealthSubject>
    fun observeForAsset(assetId: AssetId): Flow<List<HealthSubject>>
}
```

`LegacySeasonMapping.toPolicy/toLegacy` (master §4) and the golden file. `ScheduleRecompute.rebuild(..., season: SeasonInputs? = null)` replaces the `season: SeasonWindow?` parameter (the `schedule.SeasonWindow` data class goes). The in-memory fakes of the three ports in `InMemoryRepositories.kt`, honouring the same orderings.

**Consumes:** shipped code only.

### The migration (master §3.1–§3.4, exactly)

Order: the five asset `ADD COLUMN`s and the CALENDAR update; the `maintenance_schedule` recreate, row by row through `LegacySeasonMapping.toPolicy(behavior, reentry, offset, hasTimeRule = time_interval IS NOT NULL)` with `rule_changed_at = updated_at`; drop and recreate `schedule_state` empty with its `actionable_due_on` index; create the three tables (`health_subject` after the schedule recreate, because it references it). Every entity default must equal its migration `DEFAULT`. **No `updated_at` anywhere is written by the migration.**

### #64 (the one behaviour change)

`SaveSchedule` sets `ruleChangedAt = now` on a create and on an edit where `ruleChanged(before, after)` is true, and **keeps the stored value otherwise**; `updatedAt` keeps being stamped on every save (it is bookkeeping, not the floor). `ruleChanged` compares exactly the shipped rule fields (`timeInterval`, `timeUnit`, `timeBasis`, `anchorOn`, `meterDefinitionId`, `meterInterval`, `anchorMeter`) — **no season or policy field**. The postponement is cleared only on a rule change, as today. `ScheduleRecompute.pinFloor` reads `ruleChangedAt` in the owner's zone (the shipped `zone` argument).

### Compile-through, site by site (behaviour-preserving)

| site | substitution |
|---|---|
| `ScheduleRecompute.rebuild` | `policyPhase = DORMANT` exactly when `servicePolicy ≠ CONTINUOUS`, the inputs are CALENDAR with both `MM-DD` set, and `!Season.inSeason(start, end, today)` — the negation of 1.3's `seasonActive`, half-set window read as in season; `actionableDueOn = effectiveDueOn`; `policyReason = NONE`; `quiet = false` |
| `statusOf` | `!state.seasonActive` → `state.policyPhase == DORMANT` |
| `BuildReminderSubjects` | `seasonBehavior == FOLLOW_ASSET` → `servicePolicy ≠ CONTINUOUS` (in `subjectStateOf`, `seasonReentryOn`, `ruleFactsOf`); `!state.seasonActive` → `policyPhase == DORMANT`. The `MM-DD` re-entry derivation stays until B02 |
| `ScheduleCommand` | `seasonBehavior`/`seasonReentry`/`seasonReentryOffsetDays` → `servicePolicy: ServicePolicy = CONTINUOUS`, `policyOffsetDays: Int? = null`; `scheduleProblems` answers `SeasonFollowsAssetOnGroupTarget` for any non-CONTINUOUS policy on a group target |
| `BackupFormat` | `MaintenanceScheduleDto` keeps its format-7 fields; `toDomain` maps them through `toPolicy` with `ruleChangedAt = updatedAt`; `toDto` writes `toLegacy`'s triple (PRE_SERVICE is unreachable in this tree and throws `IllegalStateException`); `AssetDto.toDomain` derives `seasonMode` CALENDAR iff both `MM-DD` are set; `Asset.toDto` omits the new fields |
| `api/MaintenanceDtos.kt` | `ScheduleCommandRequest`'s three legacy fields go through `toPolicy(hasTimeRule = timeInterval != null)` into the command; `ScheduleStateDto.seasonActive = policyPhase == ACTIVE` |
| schedule editor | the two shipped options map "Pause with the asset's season" ↔ `IN_SERVICE_AT_START` offset 0 and "Remind me year round" ↔ `CONTINUOUS`; the strings stay until B08 replaces the question |

## Invariants this brief must hold

**87** (the #64 fix), **88** (migration half), **89**, **107**, **108**, **109** (schema half: no FK on any `event_id`, `baseline_profile_id` or `health_primary_subject_id`), **111** (no health column or table exists), **125** (migration half: row for row, no `updated_at` moves), **127** (the DAO half: no update or delete path for a fact). It must not make **15, 16, 17, 18** unholdable, and it **retires 26** (spec §11).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the migration loses a row or a field | `Migration7To8Test` · `everyRowSurvivesFieldForField` — a v7 fixture (assets with and without a pair, a component, schedules IGNORE and FOLLOW_ASSET with null, `AT_START`, `RESUME_CLAMPED` and `04-01` re-entries, offsets 5, 400 and null, a meter-only FOLLOW_ASSET, a group schedule, providers, a closure, a completion event linked by `schedule_id`, a local delivery row) reads back identical in every shipped column | omit `postponed_due_on` from the copy |
| the schema differs from a fresh install | `Migration7To8Test` · `theMigratedSchemaEqualsAFreshVersion8` — columns, defaults, primary keys, the four schedule FKs, every index name, `schedule_state`'s `actionable_due_on` index, the three new tables | give `season_mode`'s entity no `defaultValue` |
| CALENDAR iff both `MM-DD` | `Migration7To8Test` · `anAssetIsCalendarExactlyWhenBothMonthDaysWereSet` (both, one, none) | `OR` for `AND` in the update |
| a timestamp moves | `Migration7To8Test` · `noUpdatedAtMovesAndRuleChangedAtIsSeededFromIt` | seed from `created_at` |
| the migration's mapping drifts | `Migration7To8Test` · `everyGoldenCaseMigratesToItsPolicy` (each case of the golden file as a row) | map `RESUME_CLAMPED` to AT_START |
| the recreate orphans a child | `Migration7To8Test` · `foreignKeyCheckIsCleanAndChildrenStillReferenceTheSchedule` — `foreign_key_check` empty; deleting a schedule after the migration still cascades its providers, closures, state and delivery rows and sets the event's `schedule_id` null | drop the FK clause from the new table |
| derived state carried over | `Migration7To8Test` · `scheduleStateIsRecreatedEmpty` | copy the old rows |
| the table itself is wrong | `LegacySeasonMappingTest` · `everyGoldenCaseMaps` and `theReverseRoundTripsEveryRepresentablePolicy` | offset bound `0..366`; reverse CONTINUOUS to FOLLOW_ASSET |
| a fact can be edited | `SeasonHealthDaoConstraintTest` · `factDaosAreInsertAndQueryOnly` — no `@Update`, no `@Delete`, no `UPDATE`/`DELETE` query text on either fact DAO, no `updated_at` column on either fact entity | add `@Delete` to the condition DAO |
| an event link is a hard FK | `SeasonHealthDaoConstraintTest` · `eventLinksAreSoft` — a condition and an activation naming a non-existent event insert; deleting a real linked event leaves both rows byte-identical | declare the FK `SET_NULL` |
| the cascades are wrong | `SeasonHealthDaoConstraintTest` · `anAssetTakesItsFactsAndSubjectsWithIt` and `aScheduleTakesItsSubjectWithIt` | `RESTRICT` on the subject's schedule FK |
| UNKNOWN reappears | `SeasonHealthDaoConstraintTest` · `conditionHasExactlyThreeValues` | add a member |
| **#64** | `SaveScheduleRuleFieldTest` · `aNonRuleEditMovesNothing` — never-terminated FIXED, anchor 10 Jan, created 1 Jan; title, policy and lead edits on 1 Mar leave `computedDueOn` 10 Jan, `ruleChangedAt` and a postponement unchanged (**RED on today's tree**) | stamp `ruleChangedAt` on every save |
| #64, the other side | `SaveScheduleRuleFieldTest` · `aRuleEditFloorsOnTheEditDateAndClearsThePostponement` and `aCreateStampsRuleChangedAtWithCreatedAt` | drop `anchorOn` from `ruleChanged` |

**Behaviour preserved (shape-only rows, master §15.3).** The shipped `ScheduleStatusTest`, `ScheduleRecomputeTest`, `D5WorkedExamplesTest`, `ScheduleOperationsTest`, `BuildReminderSubjectsTest`, `BackupFormat6Test`, `BackupFormat7Test`, `MergePlannerMaintenanceTest`, `ImportBackupMergeTest`, `DueReadModelTest`, `MaintenanceRoutesTest`, `MaintenanceCommandShapeTest`, `ScheduleEditViewModelTest`, `ReminderHealthCheckTest` and the connected classes below stay green with **only** field renames and fixture updates; the ledger lists every edited test and why, and the review diff-reads each. A 1.3 client's FOLLOW_ASSET create and read round-trip (`MaintenanceRoutesTest`) is unchanged.

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` → success.
- Connected, one class per invocation (`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`): `com.loosecannon.servicetag.ui.maintenance.ScheduleEditorTest`, `…ui.maintenance.ScheduleOperationsTest`, `…ui.maintenance.ScanSheetTest`, `…ui.maintenance.MaintenanceShellTest`, `…ui.dashboard.DashboardAttentionTest`, `…reminders.QuickActionDeviceProofTest`, `…ui.AssetModelDeviceProofTest`.
- Anchored: `grep -c 'version = 8' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt` → 1; `grep -c 'SCHEMA_VERSION = 8' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` → 1; `grep -c 'FORMAT_VERSION = 7' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt` → 1; `grep -rnE '\bseason(Behavior|Reentry|ReentryOffsetDays)\b' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → no output; `test -f app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/8.json`; `git diff --stat <base> -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/7.json docs/api/v1.md tools libs` → empty.

## Strings

**None.** The two shipped editor strings stay exactly as they are; B08 replaces them.

## Must NOT

- change any status, reminder, API or editor answer beyond #64;
- bump `FORMAT_VERSION`, add a DTO field, or change a wire shape;
- copy the mapping table into SQL, or read `seasonReentry*` anywhere but `LegacySeasonMapping`;
- give a fact DAO an update or delete, give a fact entity `updated_at`, or declare an FK on a soft link;
- touch `ExternalLink*`, the `externalLinks` array, or `app/schemas/.../{1..7}.json`;
- add a string, a screen, a route or a use case.

## Size

The largest brief: roughly 800–1,100 changed lines including tests, most of them mechanical. It cannot be split, because a half-landed shape change does not compile; the review reads the compile-through table against the diff first.
