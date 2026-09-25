# ServiceTag 1.4.0 — seasons, service policy, condition and health: MASTER PLAN

> **For agentic workers.** This plan is written for its one plan review. Execution opens **wave by wave** (§16.3) once the controller authorizes it; a wave opens only when every brief it depends on is merged and green. REQUIRED SUB-SKILL for the controller: `superpowers:subagent-driven-development`, one implementer and one independent reviewer per brief, under the review budget of §1.
>
> This plan follows `docs/superpowers/planning-policy.md`: **it specifies contracts, invariants and test matrices; implementers author the code and the tests.** A fenced block appears only where it pins an interface, a wire shape or an algorithm boundary, and none is meant to be transcribed.

**Goal.** Ship ServiceTag **1.4.0 / versionCode 16** closing **#14** (operating seasons, calendar and manual), **#60** (maintenance service policy and a maintenance break), **#61** (operational condition and derived health) and **#64** (the pin floor moves on a non-rule edit): Room schema **8**, backup format **8**, `/v1` extended compatibly, and the app, `tools/servicetag-mcp` and `tools/servicetag-schedules` released in lockstep.

**The authority.** `docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md` at commit **8963fb3** — revision 2.2 with O-9; strings **S1–S143 RATIFIED**. Evidence for what the code does today: `docs/superpowers/specs/2026-09-24-servicetag-1.4-archaeology.md`. Cite "spec §n", "inv. n", "S-n", "D-n", "Q-n", "O-n" as the spec numbers them. Where the spec and the tree disagree, the spec wins and the owning brief says what to change. **Where this plan chooses something the spec left open it says `Plan decision:` and §20 lists it.** Nothing the owner ruled is re-decided here.

**Architecture in one paragraph.** The seven concepts of spec §2 stay seven: lifecycle (shipped), condition (`asset_condition` facts), health (a pure `:core` function over configuration and history), schedule state (the one recompute), season (asset mode, window and `asset_season_activation` facts), service policy (schedule policy plus the asset's break) and reminder health (shipped, renamed). `:core` gains the season context, the policy engine, the health engine and the new use cases, and stays Android-free. `:app` gains schema 8 and its migration, the read models every surface shares, the new `/v1` routes and the screens. **Derived state has one writer** (`ScheduleRecompute.rebuild`, called only by `RecomputeSchedules`), **reads never write** (inv. 105), and **health is computed at read time and never stored** (inv. 111).

**Tech stack (unchanged).** Kotlin 2.4.20 / AGP 9.4.0, Room 3.0.3 (`androidx.room3`) with KSP and an exported schema, kotlinx-serialization 1.9.0, Compose (BOM 2026.08.00), JUnit 5 in `:core`, JUnit 4 in `:app` unit tests, `androidTest` connected tests, Python 3.12 + `uv` + `pytest`. **No new dependency** in any module.

---

## 1. Global constraints

Binding on every brief. A brief that contradicts this section is wrong.

- **Version.** `versionName` **1.4.0**, `versionCode` **16**, a **MINOR** (D-27). Room schema **7 → 8**. Backup format **7 → 8**. API version stays **1**. Format-7 archives import into 1.4.0 through the legacy mapping (§4). The 12-step recreate of `maintenance_schedule` is the spec's (§3.2). The tombstones — the `external_link` table, `ExternalLinkDto`, the `externalLinks` array, `MergeTable.LINKS` — are **never bumped, re-purposed or dropped**.
- **Lockstep.** The app, `tools/servicetag-mcp` and `tools/servicetag-schedules` ship together. The deprecated season arguments stay accepted through the legacy mapping; the loader reads `servicePolicy` and re-plans IDENTICAL on Stage-B data (§12).
- **Modules.** `:app`, `:core` (pure JVM), `:nfc-core`, `:nfc-android`, `:share-test-sender` (test-only, **untouched by 1.4**). `libs/nfc-tag-core` gitlink stays at `7e0377a`.
- **Strings.** Only **S1–S143**, drawn **by number** and quoted **verbatim** from spec §10.7. No brief introduces a user-visible string, paraphrases one, or changes the case of one. A string a brief finds it needs that §10.7 does not hold is a **finding for the controller**, never a brief's to invent. §19 assigns every S-number to exactly one owning brief. The three retired strings ("Pause with the asset's season", "Remind me year round", "Off means this asset is only in use between two dates each year.") leave the tree.
- **Testing hierarchy (owner ruling 2026-09-23, binding).** JVM first (`:core` engines, codec and merge; `:app` view models, read models, migration and API); **Compose instrumented** tests for screens (`performClick`, `performTextInput`, semantics); **Android contract tests with no navigation** only where a manifest or framework surface is the subject — 1.4 adds none; the existing boundary proofs (`ShareBoundaryTest`) are **untouched**. **No new boundary journey and no UI-driving (`uiautomator`, `adb input`) test anywhere** — `ReleaseProofPolicyTest` enforces it. Instrumented suites run on **`emulator-5554`** only, with `ANDROID_SERIAL` pinned. No test waits on a real delay: every date is an injected `T` (spec §12).
- **Review budget (owner ruling 2026-09-23, binding).** One task review per brief; at most one scoped re-review after batched fixes; mechanical fixes (≲50 lines: comments, ratified wording, renames, test tidies, paths, formatting, simple assertions) close by controller inspection plus the gates; one whole-branch review at the final tip, with at most one scoped follow-up for a substantive release issue.
- **Lanes.** At most **two** implementer lanes, **never two in one checkout**: one git worktree per lane on its own branch cut from the release tip; the controller merges each brief `--no-ff`. **Any two briefs in the same wave touch disjoint files** — every brief's Files section is its exclusive ownership for its wave, including `di/AppGraph.kt`, `ui/nav/ServiceTagRoot.kt`, `ui/nav/Route.kt` and the shared test doubles (`core/src/test/.../testing/InMemoryRepositories.kt`, `app/src/test/.../testing/FakeGraph.kt`). §16.3 shows the pairing that makes this true. **Every field a brief adds to `AppGraph` is mirrored in `FakeGraph` in the same brief** (the shipped one-field-per-use-case mirror, `FakeGraph.kt:167-296`), so a later wave never needs a graph field it does not own.
- **The emulator.** One lane holds `emulator-5554` at a time, requested from and released to the controller. Connected runs name **one class per invocation**: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`. A lane waiting for the device works on its JVM gate.
- **Nothing derived is stored or exported beyond what 1.2 already derives.** `schedule_state` stays derived, never exported and written only by the recompute. No health value, band, score or aggregate exists in any table, column, DTO or archive (inv. 111). No read path writes (inv. 105).
- **A merge only ever inserts.** No `UPDATE` verdict is added; one conflict anywhere writes nothing (spec §8.4).
- **Nothing destructive is added.** No route, MCP tool or UI action amends or deletes a condition or an activation, deletes a health subject, or writes a health value (spec §9.2, inv. 127).
- **The scan never mutates** (spec 1.2 inv. 57): the sheet writes only after an explicit tap inside it.
- **Untouched unless a brief's Files section names it:** `libs/`, `tools/servicetag-bundle/` (its tests green and byte-identical), `share-test-sender/`, `app/src/main/AndroidManifest.xml` (1.4 adds no permission and no component), `core/.../core/nfc/`, `core/.../core/journal/`, `core/.../core/links/`, `core/.../core/references/`, `app/.../share/`, `app/.../nfc/`, `app/.../attachments/`, `app/.../backup/`, `app/.../links/`, `app/schemas/.../{1..7}.json`.
- **Hygiene (every tracked file, report and review).** No e-mail address, no absolute home-directory path (write `~`), no device serial except `emulator-5554`, no pairing code, no private equipment name, location or household noun: fixtures use the spec's fictional F1–F5 (UPS, battery pack, generator, snowblower and mower, hot tub).
- **Repository rules.** One commit per brief task step as the controller directs; a single casual subject line, no body, no trailers, no attribution; author identity from `git log -1 --format=%ae master`; implementers do not push; implementers run Gradle, `adb` and the emulator only through the sandbox the controller names; nothing in `/tmp`.
- **Grep expectations are anchored patterns** (planning policy), written so a comment that names one can never match it.

---

## 2. The seven concepts in the code

| # | Concept | Canonical storage | Derived by | Package | Brief |
|---|---|---|---|---|---|
| C1 | Lifecycle | `asset.status`, `retired_on` (shipped) | `targetInService` (shipped) | `core/schedule/ScheduleStatus.kt` | untouched |
| C2 | Condition | `asset_condition` rows (immutable) | `ConditionHistory` — current, since | `core/condition/` | B06 |
| C3 | Health | `health_subject` rows + events + schedule history | `AssetHealthEngine` at read time | `core/health/` | B05 (engine), B06 (configuration) |
| C4 | Schedule state | schedule, completions, closures | `ScheduleRecompute.rebuild` → `schedule_state`; `statusOf` at read | `core/schedule/` | B02 |
| C5 | Season | `asset.season_mode`, the `MM-DD` pair, `asset_season_activation` rows | `SeasonContext` — phase, cycle start, boundary | `core/schedule/SeasonContext.kt` | B02 (context), B04 (commands) |
| C6 | Service policy | `maintenance_schedule.service_policy`, `policy_offset_days`; `asset.blackout_*` | `ServicePolicyEngine` — actionable date, reason, phase, quiet | `core/schedule/ServicePolicyEngine.kt` | B02 (engine), B04 (commands) |
| C7 | Reminder health | none | `ReminderHealthCheck` on demand (shipped) | `app/reminders/`, `ui/maintenance/ReminderHealth*` | B05 renames only |

**Data flow, one direction only.**

```
asset (mode, window, break) + activation rows ──► SeasonInputs ──► SeasonContext
schedule + events + closures + membership + T + zone ──► ScheduleRecompute.rebuild
      raw due R (computedDueOn) ─► ServicePolicyEngine.evaluate(R|P, O, SeasonContext, T)
      ─► actionableDueOn A, policyReason, policyPhase, quiet ─► schedule_state (one writer)
statusOf(schedule, state, T): PAUSED ▸ INACTIVE_SEASON ▸ NO_DATA ▸ fold(time held→OK, meter) ▸ DEFERRED
health_subject + linked schedule's PolicyInputs + SeasonContext + events ─► AssetHealthEngine (read time)
asset_condition rows ─► ConditionHistory (read time)
read models (DueReadModel, AttentionReadModel, AssetHealthReadModel, scanSheetContent) ─► every surface and /v1
```

**The "—" cells of spec §2's interaction matrix are invariants 81–86 and 95** and are proved once, by `CrossConceptWriteTest` (B06: every 1.4 use case against a recording store) and the policy properties (B02). The four rules above everything (spec §2): nothing derives condition; health never changes condition and condition never pauses a schedule or moves health; the policy moves only time-side actionable dates; **the occurrence key stays `computedDueOn`**.

---

## 3. Contract A — Room schema 7 → 8 (owner: B01)

Spec §8.1–§8.2. Shipped: `AppDatabase` `version = 7`, 19 entities (`app/.../data/room/AppDatabase.kt:39-60`); `AppGraph.SCHEMA_VERSION = 7` (`di/AppGraph.kt:629`); migrations chained at `AppGraph.kt:160-162`; schemas `app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/{1..7}.json`. Column names are snake_case; dates are ISO `YYYY-MM-DD` TEXT; `MM-DD` TEXT; instants epoch-ms INTEGER.

### 3.1 `asset` — five `ADD COLUMN`s

| column | type | notes |
|---|---|---|
| `season_mode` | TEXT NOT NULL DEFAULT `'YEAR_ROUND'` | `YEAR_ROUND` \| `CALENDAR` \| `MANUAL` |
| `blackout_start_mmdd`, `blackout_end_mmdd` | TEXT NULL | both set or both null (use-case rule) |
| `health_aggregation` | TEXT NOT NULL DEFAULT `'WORST'` | `TRACK_ONE` \| `AVERAGE` \| `WEIGHTED` \| `WORST` |
| `health_primary_subject_id` | TEXT NULL | **no FK** (a soft link) |

Then `UPDATE asset SET season_mode = 'CALENDAR' WHERE season_start_mmdd IS NOT NULL AND season_end_mmdd IS NOT NULL`. **`updated_at` is untouched.** The entity's `@ColumnInfo(defaultValue = …)` must equal the migration's `DEFAULT`, or the migrated schema differs from `8.json`.

### 3.2 `maintenance_schedule` — the 12-step recreate

Built as `_new_maintenance_schedule`, filled, the old table dropped, the new renamed, indices recreated — the 2→3 / 3→4 shape (`Migrations.kt:96-97, 165-166`: Room turns foreign keys off for `migrate` and runs `foreign_key_check` after). **Dropped:** `season_behavior`, `season_reentry`, `season_reentry_offset_days`. **Added:** `service_policy` TEXT NOT NULL; `policy_offset_days` INTEGER NULL; `rule_changed_at` INTEGER NOT NULL, seeded from `updated_at`. **Kept exactly:** every other column, the four foreign keys (asset CASCADE, group CASCADE, `measurement_definition` RESTRICT, `event_profile` SET NULL) and the four indices (`(asset_id,status)`, `(group_id,status)`, `meter_definition_id`, `profile_id`). The five tables that reference it (`schedule_provider`, `occurrence_closure`, `schedule_state`, `schedule_local_delivery`, `asset_event.schedule_id`) keep referencing it by name.

**Plan decision:** each row is copied **through `LegacySeasonMapping.toPolicy`** (§4) — read, map, insert — so there is **no SQL `CASE` copy** of the mapping table to drift from the Kotlin one.

### 3.3 `schedule_state` — recreated as derived

Drop and recreate **empty**: every shipped column except `season_active`, plus `policy_phase` TEXT NOT NULL (`ACTIVE`|`DORMANT`), `actionable_due_on` TEXT NULL, `policy_reason` TEXT NOT NULL, `quiet` INTEGER NOT NULL. The sort index moves from `effective_due_on` to **`actionable_due_on`**. The FK to `maintenance_schedule` CASCADE stays. It fills on the next recompute; until then every read derives in memory (§8.6).

### 3.4 Three new tables (created after step 3.2)

```
asset_season_activation   id TEXT PK, asset_id TEXT NOT NULL FK asset CASCADE,
                          action TEXT NOT NULL (START|END), occurred_on TEXT NOT NULL,
                          event_id TEXT NULL (no FK), created_at INTEGER NOT NULL
                          INDEX(asset_id, occurred_on)
asset_condition           id TEXT PK, asset_id TEXT NOT NULL FK asset CASCADE,
                          condition TEXT NOT NULL (OPERATIONAL|DEGRADED|DOWN), occurred_on TEXT NOT NULL,
                          occurred_time TEXT NULL, tz_id TEXT NOT NULL, reason TEXT NOT NULL,
                          event_id TEXT NULL (no FK), created_at INTEGER NOT NULL
                          INDEX(asset_id, occurred_on)
health_subject            id TEXT PK, asset_id TEXT NOT NULL FK asset CASCADE, name TEXT NOT NULL,
                          kind TEXT NOT NULL, driver TEXT NOT NULL,
                          schedule_id TEXT NULL FK maintenance_schedule CASCADE,
                          baseline_profile_id TEXT NULL (no FK), nominal_until_days INTEGER NOT NULL,
                          warning_from_days INTEGER NOT NULL, critical_from_days INTEGER NOT NULL,
                          weight INTEGER NOT NULL DEFAULT 1, sort_order INTEGER NOT NULL,
                          archived_at INTEGER NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                          INDEX(asset_id), INDEX(schedule_id)
```

The two fact tables have **no `updated_at`**, and their DAOs are **insert and query only** — no `@Update`, no `@Delete`, no `UPDATE`/`DELETE` SQL (inv. 89, 107). A row leaves only by its asset's CASCADE. `health_subject` is configuration: upsert and query, **no delete** (no route deletes one; the schedule's and asset's CASCADE are the only removal).

### 3.5 What is not a SQL constraint, and why

Both-or-neither for the `MM-DD` pairs, "pair non-null iff CALENDAR" (inv. 88), "at most one non-archived subject per schedule" (inv. 120), threshold ordering and the offset ranges are **use-case rules with tests**, not `CHECK`s or partial indices: Room cannot declare either on an entity, and one written only into the migration would give an upgraded and a fresh database two schemas for one version (the 1.2 precedent, `core/model/Maintenance.kt` KDoc).

### 3.6 The domain shapes (B01 produces; everyone consumes)

```kotlin
enum class SeasonMode { YEAR_ROUND, CALENDAR, MANUAL }                 // core/model/SeasonModel.kt
enum class SeasonAction { START, END }
data class SeasonActivation(val id: String, val assetId: AssetId, val action: SeasonAction,
    val occurredOn: String, val eventId: EventId?, val createdAt: Long)
data class SeasonInputs(val mode: SeasonMode, val seasonStartMmdd: String?, val seasonEndMmdd: String?,
    val blackoutStartMmdd: String?, val blackoutEndMmdd: String?, val activations: List<SeasonActivation>)
enum class ServicePolicy { CONTINUOUS, IN_SERVICE_AT_START, IN_SERVICE_RESUME_CLAMPED, PRE_SERVICE } // core/model/ServicePolicy.kt
enum class PolicyPhase { ACTIVE, DORMANT }
enum class PolicyReason { NONE, SEASON_START, AFTER_BREAK, BEFORE_SEASON, BEFORE_BREAK, POLICY_INAPPLICABLE, AWAITING_START }
enum class OperationalCondition { OPERATIONAL, DEGRADED, DOWN }        // core/model/Condition.kt
data class AssetCondition(val id: String, val assetId: AssetId, val condition: OperationalCondition,
    val occurredOn: String, val occurredTime: String?, val tzId: String, val reason: String,
    val eventId: EventId?, val createdAt: Long)
enum class HealthSubjectKind { ASSET, PART, MEDIUM }                    // core/model/Health.kt
enum class HealthDriver { AGE, MAINTENANCE_OVERDUE }
enum class HealthAggregation { TRACK_ONE, AVERAGE, WEIGHTED, WORST }
```

`Asset` gains `seasonMode` (default `YEAR_ROUND`), `blackoutStartMmdd`, `blackoutEndMmdd`, `healthAggregation` (default `WORST`), `healthPrimarySubjectId: HealthSubjectId?`. `MaintenanceSchedule` loses `seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays` and gains `servicePolicy: ServicePolicy`, `policyOffsetDays: Int?`, `ruleChangedAt: Long`. `ScheduleState` loses `seasonActive` and gains `policyPhase: PolicyPhase`, `actionableDueOn: String?`, `policyReason: PolicyReason`, `quiet: Boolean`. `HealthSubject` mirrors §3.4 with `id: HealthSubjectId`, `scheduleId: ScheduleId?`, `baselineProfileId: ProfileId?`, `weight: Int`, `archivedAt: Long?`. `SeasonBehavior` stays **only** as the legacy mapping's input type. **Plan decision:** activation and condition ids are plain `String` UUIDs (the `OccurrenceClosure.id` precedent); the subject id is a value class `HealthSubjectId`, because routes, the primary link and the merge name it.

### 3.7 B01 is a shape change that keeps behaviour, plus #64

Removing three schedule fields breaks every reader at once, so **B01 makes the whole tree compile with behaviour-preserving substitutions** (its brief lists each site), and **one deliberate behaviour change: #64** — `SaveSchedule` writes `ruleChangedAt` only when `ruleChanged(before, after)` is true (on a create, `ruleChangedAt = createdAt`), `ruleChanged` no longer lists any season or policy field, and the pin floor reads `ruleChangedAt` in the owner's zone (inv. 87; D-28). Everything else — status, reminders, the API, the editor, the format-7 codec — answers exactly as 1.3 did on every row 1.3 could hold, and the shipped suites prove it by staying green with only mechanical field renames.

**B01 also commits the golden format-7 archive** (dec. 49) while the format-7 codec still exists — the one fixture that proves inv. 125's last clause after B03 removes format-7 encoding.

**Plan decision:** B01 also widens the constructors that B02 and B03 would otherwise have to wire in `AppGraph` — `RecomputeSchedules` (the activation repository) and `ExportBackupSet`, `ImportBackupReplace`, `BuildBackupMergePlan`, `ApplyBackupMergePlan` (the three new repositories) — so wave 2's two lanes touch no shared file (§16.3). `RecomputeSchedules` uses its new parameter at once (it builds `SeasonInputs`); the four backup use cases hold theirs for B03.

---

## 4. Contract B — the legacy mapping (owner: B01)

Spec §4.1. **One object** serves the 7→8 migration, the format ≤7 decoder, the API's deprecated inputs and — through the API — the MCP's deprecated arguments.

| `seasonBehavior` | `seasonReentry` | → `servicePolicy` | → `policyOffsetDays` |
|---|---|---|---|
| `IGNORE`, or absent | anything | `CONTINUOUS` | null |
| `FOLLOW_ASSET` | null, `AT_START`, or unrecognised (e.g. an `MM-DD`) | `IN_SERVICE_AT_START` | the old offset if 0–365 **and** a time rule exists, else 0 |
| `FOLLOW_ASSET` | `RESUME_CLAMPED` | `IN_SERVICE_RESUME_CLAMPED` | null |

It **normalises and never refuses** (none of these values ever had behaviour). An **unrecognised `seasonBehavior` name** is not in the table: it stays what 1.3 made it — a 400 unknown enum on the API, `BackupCorrupt` in an archive — and the migration never sees one because the column was written from the enum.

**The reverse projection** (spec §9.1, "§4.1 reversed"), which the API reports as the derived compatibility triple:

| `servicePolicy` | → `seasonBehavior` | `seasonReentry` | `seasonReentryOffsetDays` |
|---|---|---|---|
| `CONTINUOUS` | `IGNORE` | null | null |
| `IN_SERVICE_AT_START` | `FOLLOW_ASSET` | `AT_START` | the offset |
| `IN_SERVICE_RESUME_CLAMPED` | `FOLLOW_ASSET` | `RESUME_CLAMPED` | null |
| `PRE_SERVICE` | null | null | null |

**Plan decision:** AT_START reverses to an explicit `"AT_START"` and its offset, so the triple round-trips through the forward table to the same policy and offset for every representable schedule (a meter-only AT_START schedule's offset is always 0, so it round-trips too).

```kotlin
object LegacySeasonMapping {                                  // core/model/ServicePolicy.kt
    data class Policy(val servicePolicy: ServicePolicy, val policyOffsetDays: Int?)
    data class Legacy(val seasonBehavior: SeasonBehavior?, val seasonReentry: String?, val seasonReentryOffsetDays: Int?)
    fun toPolicy(behavior: SeasonBehavior?, reentry: String?, offsetDays: Int?, hasTimeRule: Boolean): Policy
    fun toLegacy(policy: ServicePolicy, offsetDays: Int?): Legacy
}
```

**The agreement proof.** **Plan decision:** B01 commits the cases as a golden fixture, `docs/api/legacy-season-mapping.json` — an array of `{seasonBehavior, seasonReentry, seasonReentryOffsetDays, hasTimeRule, servicePolicy, policyOffsetDays}` covering every row above, the offset edges (−1, 0, 365, 366, null) with and without a time rule, and an `MM-DD` re-entry. `LegacySeasonMappingTest` (B01) runs the object over it; B01's migration test runs each case through the migration; B03's decoder test runs each through a format-7 archive; **B09's `LegacyMappingAgreementTest` runs every case through all three Kotlin paths and the API legacy form in one test** (spec §4.1 "one test proves all four agree"); the MCP never translates (it forwards deprecated arguments in a legacy-form body), which B11's pytest proves, and the loader's own Python comparison is checked against the same file by B11's pytest.

---

## 5. Contract C — backup format 8 (owner: B03)

Spec §8.3. Shipped: `FORMAT_VERSION = 7` (`core/.../backup/BackupCodec.kt:55`), a strict `Json` (no `ignoreUnknownKeys`, `encodeDefaults = true`), a newer archive refused before any row is read (`:175-176`), eager `toDomain()` validation and `validateGraph`.

- **New lists on `BackupData`**, each defaulting to empty, sorted by `id`, validated eagerly and refused (`BackupCorrupt`) when their asset is absent from the file: `seasonActivations: List<SeasonActivationDto>`, `assetConditions: List<AssetConditionDto>`, `healthSubjects: List<HealthSubjectDto>`. A subject's `scheduleId`, when set, must resolve inside the file (its FK is real). Soft links — `eventId`, `baselineProfileId`, `healthPrimarySubjectId` — are **not** validated.
- **DTO field sets (the wire names every other contract uses):** `SeasonActivationDto{id, assetId, action, occurredOn, eventId, createdAt}`; `AssetConditionDto{id, assetId, condition, occurredOn, occurredTime, tzId, reason, eventId, createdAt}`; `HealthSubjectDto{id, assetId, name, kind, driver, scheduleId, baselineProfileId, nominalUntilDays, warningFromDays, criticalFromDays, weight, sortOrder, archivedAt, createdAt, updatedAt}`. `AssetDto` gains `seasonMode`, `blackoutStartMmdd`, `blackoutEndMmdd`, `healthAggregation`, `healthPrimarySubjectId`. `MaintenanceScheduleDto` **loses** `seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays` and gains `servicePolicy`, `policyOffsetDays`, `ruleChangedAt`. **The new fields carry no defaults**, so a format-8 archive that omits one is corrupt.
- **Reading formats 1–7.** **Plan decision:** the decoder upgrades a format ≤7 `data.json` **as a JSON tree before the strict decode** — each schedule's legacy triple through `LegacySeasonMapping.toPolicy` (with `hasTimeRule = timeInterval != null`) and `ruleChangedAt = updatedAt`; each asset's `seasonMode` = `CALENDAR` iff both `MM-DD` are set, else `YEAR_ROUND`, no break, `WORST`, no primary; the three new lists absent. **Reading format 8:** strict decode against the format-8 DTOs, so a legacy season field in a format-8 schedule is an unknown key and therefore `BackupCorrupt` (inv. 124). The upgraded tree and a native format-8 file decode through **one** DTO set.
- **Encoding.** Always format 8; the legacy triple is never written. `counts` gains `seasonActivations`, `assetConditions`, `healthSubjects`. The `external_link` tombstones stay byte-for-byte. Nothing derived is exported (1.2 inv. 64 extended to health).
- **Compatibility.** 1.4 reads formats 1–8; 1.3.x refuses format 8 with `BackupNewerFormat` before reading a row (inv. 63's shape, asserted against the shipped gate). A format-7 export taken before the upgrade merges IDENTICAL row for row after it (inv. 125, §6).

---

## 6. Contract D — merge tables 12–14 (owner: B03)

Spec §8.4. `MergeTable` gains, **after `REFERENCES`**: `SEASON_ACTIVATIONS`, `CONDITIONS`, `HEALTH_SUBJECTS` (fourteen members; the KDoc's "eleven canonical tables" becomes fourteen). `MergeWrites`, `MergeSnapshot` and `MergeReport` gain `seasonActivations`, `conditions`, `healthSubjects`. `ApplyBackupMergePlan` writes them in that order, inside its transaction, before the shipped total rebuild.

| # | Table | Identity | Verdicts and reasons |
|---|---|---|---|
| 12 | `SEASON_ACTIVATIONS` | row id | INSERT; IDENTICAL; CONFLICT `CONTENT_DIFFERS`; CONFLICT `OWNER_NOT_AVAILABLE` (asset) |
| 13 | `CONDITIONS` | row id | as 12 |
| 14 | `HEALTH_SUBJECTS` | row id **plus** the non-archived `schedule_id` | as 12, owner = asset **or** schedule; **`HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW`** → **SKIPPED** (a local non-archived subject under another id drives that schedule); **`HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`** → CONFLICT (two non-archived archive rows claim one schedule) |

Facts (12–13) are immutable, so a re-import is IDENTICAL and two phones' rows are two inserts. **Soft links are never owners**: a condition or activation naming an absent event still inserts. START, END and condition writes never touch the asset row; a divergence in mode, break or health policy is `CONTENT_DIFFERS` on `ASSETS` (inv. 126). There is still no `UPDATE`, one conflict writes nothing, and every schedule is rebuilt after apply. The `/v1` merge report gains the three tallies under the same names. **#44** must later remap `asset_id` in all three tables (a seam, no work).

---

## 7. Contract E — the season model and its commands (owners: B02 context, B04 commands)

### 7.1 `SeasonContext` (B02)

```kotlin
enum class SeasonPhase { IN_SEASON, OUT_OF_SEASON }                    // core/schedule/SeasonContext.kt
enum class BoundaryKind { SEASON, BREAK, NONE }
data class Boundary(val on: LocalDate, val kind: BoundaryKind)
fun boundaryKindOf(mode: SeasonMode, hasBreak: Boolean): BoundaryKind  // CALENDAR→SEASON; else break→BREAK; else NONE
class SeasonContext private constructor(/* inputs */) {
    companion object { fun of(inputs: SeasonInputs): SeasonContext }
    val mode: SeasonMode; val boundaryKind: BoundaryKind
    fun phaseAt(d: LocalDate): SeasonPhase
    fun cycleStartAt(d: LocalDate): LocalDate?          // spec §3.1; null for YEAR_ROUND and an ended MANUAL
    fun latestCycleStartOnOrBefore(d: LocalDate): LocalDate?
    fun seasonEndAt(d: LocalDate): LocalDate?           // the last day of the span containing d (CALENDAR only)
    fun inBreak(d: LocalDate): Boolean
    fun firstAllowedAfter(d: LocalDate): LocalDate      // d itself when allowed
    fun preServiceBoundary(r: LocalDate): Boundary?     // spec §4.3; never a manual START
}
```

Phase (spec §3.1): YEAR_ROUND always IN_SEASON; CALENDAR is `Season.inSeason(window, d)` (inclusive, wrapping, 02-29 read as 02-28 in common years — the shipped predicate, unchanged); MANUAL is the action of the latest activation with `occurred_on ≤ d`, ordered `(occurredOn, createdAt, id)`, START in season from its day, END out of season from its day, **no row → OUT_OF_SEASON** (totality). Activation rows are read **only** in MANUAL mode (inv. 90). A future manual START is never predicted and never a boundary; a recorded START is the real cycle start (inv. 99, O-5). **The break** wraps, is inclusive, reads 02-29 as 02-28 in common years, and is the same predicate for every consumer.

### 7.2 The commands (B04)

| Use case | Command | Refusals | Writes |
|---|---|---|---|
| `SetSeasonMode` | `SeasonModeCommand(seasonMode, seasonStartMmdd?, seasonEndMmdd?, manualPhase: SeasonPhase?)` | 422 `SEASON_WINDOW_REQUIRED`, `SEASON_WINDOW_FORBIDDEN`, `MANUAL_PHASE_REQUIRED`, `MANUAL_PHASE_FORBIDDEN`, bad `MM-DD` (shipped shape); 409 `SEASON_MODE_STRANDS_POLICY` naming the schedules | asset row; on a switch **into** MANUAL exactly one START/END row dated today, same transaction (inv. 92); recompute for the asset |
| `SetMaintenanceBreak` | `BreakCommand(blackoutStartMmdd?, blackoutEndMmdd?)` | 422 both-or-neither, bad `MM-DD`, `BLACKOUT_COVERS_THE_YEAR`; 409 `BREAK_STRANDS_POLICY` naming the schedules | asset row; recompute |
| `RecordSeasonActivation` | `ActivationCommand(action, occurredOn?, eventId?)` | 409 `SEASON_NOT_MANUAL`, `SEASON_ALREADY_STARTED`, `SEASON_ALREADY_ENDED`; 422 `SEASON_DATE_OUT_OF_RANGE` (after today, or before the latest row's date), `FOREIGN_EVENT` | one activation row, **no asset column** (inv. 126); recompute |
| `UpdateAsset` / `CreateAsset` / component create | the shipped `AssetCommand` | the legacy pair (§11.4): an equal pair leaves the season alone; a different pair becomes CALENDAR(window) or YEAR_ROUND **through `SetSeasonMode`'s rules**; a different pair on MANUAL is 422 `LEGACY_WRITE_CANNOT_REPRESENT`; the strands check applies | as today |
| `SaveSchedule` (validation) | `ScheduleCommand` + `servicePolicy`, `policyOffsetDays` | 422 `POLICY_OFFSET_INVALID`, `SEASON_POLICY_NEEDS_A_TIME_RULE`, `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`; 409 `PRE_SERVICE_NEEDS_DATES` | as today |

**The strands rule** (spec §3.2, §4.4; RS-4, O-3): a season-mode or break change is refused when it **removes or re-kinds** the boundary of an asset carrying any PRE_SERVICE schedule — that is, when the kind before is not NONE and the kind after differs from it. **Adding a boundary where none existed** (NONE → BREAK, NONE → SEASON) repairs a merged boundary-less schedule and is **allowed** (plan decision 44, ruled). Changing a CALENDAR window, or a break's dates while one stays set, keeps the kind and is allowed. **Plan decision:** the check counts **every** PRE_SERVICE schedule targeting the asset **whatever its lifecycle** (active, paused or archived), because an archived one can be restored and "commands refuse to reach" the boundary-less state (spec §4.3 totality).

**Plan decision — `BLACKOUT_COVERS_THE_YEAR`:** refused when **some year, common or leap, has no allowed day** under the break predicate of §7.1.

**The offset** (spec §4.2): AT_START 0–365 (the API's 1.4 form defaults an omitted or null key to 0 — master §20.31); PRE_SERVICE −365…−1, required; others null. A meter-only schedule may be CONTINUOUS or IN_SERVICE_* with offset 0 only (O-7); PRE_SERVICE or a non-zero offset on it is `SEASON_POLICY_NEEDS_A_TIME_RULE`. A group target is CONTINUOUS only (inv. 106). PRE_SERVICE on an asset whose boundary kind is NONE is 409 `PRE_SERVICE_NEEDS_DATES` (the remedy is the asset).

**The season view** (B04, pure): `SeasonView(seasonMode, window, phase, nextBoundaryOn, blackout pair, inBreak, activations, computedForOn)`. **Plan decision — `nextBoundaryOn`:** CALENDAR in season → `seasonEndAt(T)`; CALENDAR out of season → `cycleStartAt(T)`; YEAR_ROUND and MANUAL → null (a MANUAL boundary is never predicted, Q-6).

**The event offer** (B04; spec §3.3, inv. 93): after a `SEASON_START` or `SEASON_END` event on a MANUAL asset in the opposite phase at `T`, `seasonOfferFor(asset: Asset, activations: List<SeasonActivation>, event: AssetEvent, today: LocalDate): SeasonAction?` (pure) answers START or END; `AcceptSeasonOffer.run(assetId: AssetId, event: AssetEvent, action: SeasonAction): SeasonActivation` (dec. 51) accepts; it writes through `RecordSeasonActivation` with the event's date clamped to `[latest row's date, T]` and `eventId` set. Nothing writes unless accepted.

---

## 8. Contract F — the actionable-due pipeline (owner: B02)

### 8.1 Inputs

`rebuild(schedule, events, closures, membership, today, zone, season: SeasonInputs? = null)`. **R** = `computedDueOn` (unchanged arithmetic, the occurrence key). **P** = `postponedDueOn`. **O** = `lastTerminationEffectiveOn`, or for a never-terminated schedule the date of `ruleChangedAt` in `zone` (D-28, spec §4.3). A group target and an asset with no inputs get `season = null`, which the engine reads as "no season, no break".

### 8.2 The policy engine

```kotlin
data class PolicyInputs(val policy: ServicePolicy, val offsetDays: Int?, val rawDueOn: LocalDate?,
                        val postponedDueOn: LocalDate?, val openedOn: LocalDate)
data class PolicyOutcome(val actionableOn: LocalDate?, val reason: PolicyReason,
                         val phase: PolicyPhase, val quiet: Boolean,
                         val quietUntil: LocalDate?)            // season.firstAllowedAfter(at) when quiet
object ServicePolicyEngine {                                   // core/schedule/ServicePolicyEngine.kt
    fun evaluate(inputs: PolicyInputs, season: SeasonContext?, at: LocalDate): PolicyOutcome
}
fun ScheduleRecompute.policyInputsOf(schedule: MaintenanceSchedule, state: ScheduleState, zone: ZoneId): PolicyInputs
```

`evaluate` implements spec §4.3's `policyDue` **as normative**, applied to `P ?: R`: CONTINUOUS gives it unchanged; IN_SERVICE_* uses `cycleStartAt(at)` (AT_START moves a date only when it is earlier than `s + offset`; RESUME_CLAMPED gives `max(date, s)`; an ended MANUAL gives `null` with `AWAITING_START`), then moves a date inside the break to the first allowed day after it (`AFTER_BREAK`); PRE_SERVICE computes the boundary from `R`, keeps an allowed `R ≤ s − margin`, else takes the latest day of the pre-season window on or before `s − margin`, else the window's first day, else — only when the window is empty — **the day before the break** (spec §12.1's literal "whole-gap break → the day before it"), with the opened-before guard (`O < point`) and the reason `BEFORE_SEASON` / `BEFORE_BREAK` / `AFTER_BREAK`; no boundary gives `P ?: R` with `POLICY_INAPPLICABLE`. A postponement is re-entered and moved out of a break like a raw due, **never earlier** (inv. 101). `phase` is DORMANT exactly when the policy is IN_SERVICE_* and `season.phaseAt(at)` is OUT_OF_SEASON. `quiet` ⇔ policy ≠ CONTINUOUS and `season.inBreak(at)`, and then `quietUntil` is the first allowed day after that break (null otherwise). A meter-only schedule (`rawDueOn == null`) yields `actionableOn = null` with its phase and quiet still computed (O-7). **No rule reads a category, template or name** (inv. 98).

### 8.3 What `rebuild` materialises

`computedDueOn` (unchanged), `effectiveDueOn = P ?: R` (1.2's meaning, inv. 10), and from `evaluate(at = today)`: `actionableDueOn`, `policyReason`, `policyPhase`, `quiet`. `rebuild` stays pure and idempotent in its inputs including `season` and `zone` (inv. 15, 16).

### 8.4 `statusOf` and DEFERRED

Order (spec §4.5): **PAUSED** (lifecycle) ▸ **INACTIVE_SEASON** (`policyPhase == DORMANT`) ▸ **NO_DATA** ▸ the worst-of fold, where the time side is measured against **`actionableDueOn`** (not `effectiveDueOn`) and a **held** time side counts as OK; if the fold answers OK and the time side is held, the status is **DEFERRED**. Held ⇔ `policyReason == AFTER_BREAK` ∧ `T ≥ (P ?: R) − leadDays` ∧ `T < actionableDueOn`. A crossed meter threshold is never moved, deferred or masked: it reads DUE (inv. 95, 103). `DueStatus` gains **`DEFERRED`, appended last** so the fold's `OK < DUE_SOON < DUE < OVERDUE` ordinals are untouched; `countsAsDue` and `notifies` are false for it. **Plan decision:** append rather than insert, because `statusOf` folds by ordinal.

### 8.5 Reminder subjects (spec §4.7)

`BuildReminderSubjects` stops deriving seasons (`BuildReminderSubjects.kt:138-143, 224-231` go) and maps engine output: ARCHIVED → `Withdrawn`; PAUSED → `Parked(null)`; DORMANT → `Parked(actionableDueOn)` (null for MANUAL); DEFERRED → `Parked(actionableDueOn)`; quiet with a notifying status → `Parked(quietUntil)`; otherwise `Active`. **`quietUntil` is never a column:** the builder asks `RecomputeSchedules.quietUntil(schedule): LocalDate?`, which evaluates the policy in memory for today (plan decision 50) — so the builder still touches no `SeasonContext` and no break column, and the park date, part of the content hash (`ContentHash.kt:55`), is deterministic. `RuleFacts.seasonal = servicePolicy ≠ CONTINUOUS` — false before and after on real data, so no content hash moves. **No reminder code evaluates a season, break or policy** (inv. 104; anchored grep).

### 8.6 Reads never write (spec §4.7, inv. 105)

```kotlin
suspend fun RecomputeSchedules.readState(schedule: MaintenanceSchedule): ScheduleState
```

**Plan decision:** one accessor for every read path — the stored row when its `computedForOn == today`, else `stateOf(schedule)` derived in memory; **never an upsert**. `DueReadModel` (today: `DueReadModel.kt:192` falls back only for a **missing** row), `BuildReminderSubjects`, `/v1/schedules/{id}`, `/v1/due`, `/v1/attention`, the health route, the dashboard, asset detail and scan routing all read through it, **and so does the delivery seam `scheduleStateReader`** (`AppGraph.kt:230`), which `ScheduleDeliveryFacts` and `ReminderHealthCheck` read: B07 wires it to `readState` (plan decision 47), because between sweeps a stored row can be stale even though `reconcileAll` rebuilds first (`ReminderReceivers.kt:237-239`). Only the daily job, a recompute after a write, and an explicit rebuild persist. Spec 1.2 inv. 23's between-rebuilds exception and D-4's `T`-independent sort key are gone (D-29).

### 8.7 Postpone and snooze (O-6, inv. 131)

`PostponeSchedule` is unchanged: it writes `postponed_due_on`, no rule column, no `updated_at`, no event. The engine passes `P` through the policy, so a postponement **may restart** maintenance-overdue health from the new actionable date, with earlier lateness not retained; there is no postponement journal. Snooze stays device-local (`schedule_local_delivery`), has no endpoint, and **no health or policy code reads it**.

---

## 9. Contract G — condition (owner: B06)

Spec §5. `RecordCondition.run(assetId, ConditionCommand(condition, occurredOn?, occurredTime?, tzId, reason = "", eventId?))` → `AssetCondition`. `occurredOn` defaults to today and may not be later (422 `CONDITION_DATE_IN_FUTURE`); `reason` ≤ 500 characters (422 `CONDITION_REASON_TOO_LONG`), empty allowed; `eventId` must name an event on the same asset (422 `FOREIGN_EVENT`); malformed `occurredTime`/`tzId` use the shipped validation shape; absent asset → the shipped 404. **It writes one row and nothing else** — no asset column, no recompute (inv. 81, 83, 126).

`ConditionHistory` (pure): **current** = the latest row by `(occurredOn, occurredTime nulls first, createdAt, id)`; **since** = the earliest row of the latest run of equal values; no row → "Condition not recorded" (S4). No column stores the current value (inv. 107); no UNKNOWN exists (inv. 108). A correction is a new row; returning to OPERATIONAL inserts one row and leaves every earlier row byte-identical (inv. 110). A dangling `eventId` reads S24 and is never a merge owner (inv. 109).

**The operational offer** — `operationalOfferFor(current: AssetCondition?, event: AssetEvent): Boolean` (pure) and `AcceptOperationalOffer.run(assetId: AssetId, event: AssetEvent): AssetCondition` (dec. 51): after a completion, or a `MAINTENANCE` or `REPLACEMENT` event, on an asset whose current condition is DOWN or DEGRADED, offer "Mark operational?"; accepting writes OPERATIONAL dated `max(event date, current row's date)` with `eventId` set; "Not yet" writes nothing (spec §5.4). **Plan decision:** a group completion offers once per completed member asset that is DOWN or DEGRADED, one dialog at a time. Offers are an app affordance only; an API write never offers.

---

## 10. Contract H — health (owners: B05 engine, B06 configuration)

### 10.1 Subjects and their guard (B06)

`SaveHealthSubject.create(assetId, cmd)` and `SaveHealthSubject.update(id, cmd)` over `HealthSubjectCommand(name, kind, driver, scheduleId?, baselineProfileId?, nominalUntilDays?, warningFromDays?, criticalFromDays?, weight = 1, sortOrder?)` — two methods, so a subject cannot change asset (§20.34); the thresholds are nullable only so that "missing" is a coded refusal. Refusals (spec §6.1, §9.2): 422 `HEALTH_SUBJECT_NAME_REQUIRED` (1–60 after trim), `HEALTH_THRESHOLDS_INVALID` (not `0 ≤ t1 < t2 < t3 ≤ 36500`, or any missing — **no default**, inv. 121), `HEALTH_DRIVER_MISMATCH` (AGE naming a schedule; MAINTENANCE_OVERDUE without one), `FOREIGN_SCHEDULE` (another asset's, a group's, absent, **or archived** — plan decision 45; the message says "archived" for that case), `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE` (meter-only), `PROFILE_NOT_A_REPLACEMENT`, `HEALTH_WEIGHT_OUT_OF_RANGE` (1–10); 409 `HEALTH_SCHEDULE_TAKEN` (the schedule drives another non-archived subject, inv. 120); 404 `NO_SUCH_HEALTH_SUBJECT`. An edit never changes `assetId`. **Plan decision:** a baseline profile on another asset is `PROFILE_NOT_A_REPLACEMENT` too — the spec's one bullet ("foreign or is not a REPLACEMENT") names one code.

`ArchiveHealthSubject.run(id, archived)`: archiving the TRACK_ONE primary is 409 `HEALTH_SUBJECT_IS_PRIMARY` (S137); **create, update and restore each re-run the whole link validation** (the schedule exists, is this asset's, is asset-targeted, has a time rule, is not archived, and drives no other non-archived subject), so no non-archived subject ever names an archived, retargeted or rule-less schedule locally (spec §6.1's closing guarantee). `SetHealthPolicy.run(assetId, HealthPolicyCommand(healthAggregation, healthPrimarySubjectId?))`: 422 `HEALTH_PRIMARY_INVALID` when TRACK_ONE names no primary, or the primary is foreign or archived, or a primary is sent with another aggregation.

**The link guard** (D-30, RS-3, inv. 130): `SaveSchedule` and `ArchiveSchedule` gain an action flag `unlinkHealthSubject: Boolean = false`. While a non-archived subject depends on the schedule, a save that removes the time rule or changes the target asset, or an archive, is 422 `SCHEDULE_DRIVES_HEALTH_SUBJECT` naming the subject — unless the flag is set, which archives the subject **in the same transaction**. **Plan decision:** when that subject is the asset's TRACK_ONE primary, the flag path is 409 `HEALTH_SUBJECT_IS_PRIMARY` too — the remedy is the aggregation first, exactly as for a direct archive.

**The composite editor save.** `SaveAssetSettings.run(id: AssetId?, AssetSettingsCommand(asset: AssetCommand, seasonMode: SeasonModeCommand, maintenanceBreak: BreakCommand, healthPolicy: HealthPolicyCommand))` validates all four, then writes them **in one `uow.write`** (spec §10.4); any refusal writes nothing. `id == null` creates the asset in the same transaction. **Plan decision:** a composite use case in `:core` (B06), so the editor holds no transaction logic.

### 10.2 The engine (B05)

```kotlin
object HealthScore { fun score(x: Long, t1: Int, t2: Int, t3: Int): Int; fun band(score: Int): HealthBand } // core/health/
enum class HealthBand { NOMINAL, WARNING, CRITICAL }
object HealthClock {
    fun countedDays(link: PolicyInputs, season: SeasonContext?, kind: HealthSubjectKind,
                    today: LocalDate): Long           // link.policy says whether MEDIUM restarts
}
object AssetHealthEngine {
    fun evaluate(asset: Asset, subjects: List<HealthSubject>, links: Map<ScheduleId, LinkedSchedule>,
                 events: List<AssetEvent>, profileExists: (ProfileId) -> Boolean,
                 season: SeasonContext?, today: LocalDate): AssetHealthResult
}
data class LinkedSchedule(val schedule: MaintenanceSchedule, val inputs: PolicyInputs)
```

- **Score** (spec §6.3): the piecewise formula in **exact integer arithmetic** (rational, then a ceiling; no floating point), so WARNING starts exactly at `t2`, CRITICAL exactly at `t3`, and the score never rises as `x` grows (inv. 117). Bands: CRITICAL ≤ 25, WARNING ≤ 60, else NOMINAL.
- **AGE** (spec §6.4): `x` = days from the latest `REPLACEMENT` event on the asset (by `EventChronology`), restricted to `baselineProfileId` when set, to `T`; none → NOT TRACKED with S100; a `baselineProfileId` whose profile no longer exists → NOT TRACKED with S101, **never a widened filter** (inv. 113). Season, policy and pause are ignored.
- **MAINTENANCE_OVERDUE** (spec §7.1): `x` = counted days. A day `d` counts when the policy phase on `d` is ACTIVE, `d > A(d)` — `ServicePolicyEngine.evaluate(link, season, at = d).actionableOn` with today's configuration and postponement — and, for a MEDIUM subject on an IN_SERVICE schedule only, `d ≥ latestCycleStartOnOrBefore(T)` (no restriction when there is none). **Plan decision (ruled):** `d` ranges over `min(O, P ?: R) < d ≤ T`; the predicate `d > A(d)` bounds it from below. (`R < O` is real: a never-terminated COMPLETION schedule anchored before its rule-change date, including a 1.3 row whose `updated_at`, now `rule_changed_at`, moved after its anchor.) The span implementation is tested against a **day-by-day oracle** (inv. 112, 114–116). No meter side contributes (Q-2). **Owner ruling (2026-09-24):** the range is an evaluation bound only. After a postponement, MAINTENANCE_OVERDUE counted lateness is zero until the policy-derived postponed actionable date has passed; no day before that actionable date contributes to health. `A(d)` is therefore always evaluated with today's postponement, never as it stood on `d`.
- **Not tracked** (spec §6.8, §7.1): MEDIUM on IN_SERVICE and dormant at `T` → S105; linked schedule PAUSED → S106; linked schedule with no time rule or on another asset (a merge) → S142; **Plan decision:** linked schedule ARCHIVED (merge-only, because the guard refuses it locally) → NOT TRACKED with **no driver line** (S98 alone), since S106 and S142 would both be false.
- **Driver lines** (data, rendered by B12): AGE → `Replaced(on, ageDays)` (S99), `NoReplacement` (S100), `ProfileRemoved` (S101). MAINTENANCE_OVERDUE → `x == 0`: `Postponed(title, to = P)` (S143) while the occurrence is postponed and not late against its new date, else `UpToDate(title)` (S103); `x > 0`: `Overdue(title, x)` (S102), followed by `Grace(t1)` (S104) while `x ≤ t1`.
- **Aggregation** (spec §6.5) over non-archived subjects **with a value**: WORST min; TRACK_ONE the primary's value; AVERAGE `floor(mean)`; WEIGHTED `floor(Σw·s/Σw)`; no contributor → NOT TRACKED; a dangling or archived primary falls back to WORST and flags S138. **Plan decision:** TRACK_ONE whose primary is untracked answers NOT TRACKED (no fallback — the primary exists). `critical` lists every CRITICAL contributor whatever the aggregation (inv. 119). Health is never stored (inv. 111) and is a pure function of its inputs (inv. 82).

---

## 11. Contract I — `/v1` (owner: B09)

### 11.1 New routes (spec §9.1)

| method | path | body | success |
|---|---|---|---|
| GET | `/v1/assets/{id}/season` | — | 200 `SeasonResponse{seasonMode, seasonStartMmdd, seasonEndMmdd, seasonPhase, nextBoundaryOn, blackoutStartMmdd, blackoutEndMmdd, inBreak, activations, computedForOn}` |
| POST | `/v1/assets/{id}/season` | `{action, occurredOn?, eventId?}` | 201 `{activation, season}` |
| POST | `/v1/assets/{id}/season-mode` | `{seasonMode, seasonStartMmdd?, seasonEndMmdd?, manualPhase?}` | 200 `{asset, season}` |
| POST | `/v1/assets/{id}/maintenance-break` | `{blackoutStartMmdd, blackoutEndMmdd}` | 200 `{asset, season}` |
| POST | `/v1/assets/{id}/health-policy` | `{healthAggregation, healthPrimarySubjectId?}` | 200 `{asset}` |
| GET / POST | `/v1/assets/{id}/conditions` | — / `{condition, occurredOn?, occurredTime?, tzId, reason?, eventId?}` | 200 `{conditions, current}` / 201 `{condition, current}` |
| GET | `/v1/assets/{id}/health` | — | 200 `{assetId, computedForOn, condition, aggregation, aggregate, subjects, critical, components}` |
| GET | `/v1/assets/{id}/health-subjects` | — | 200 `{subjects}` |
| POST | `/v1/health-subjects` | subject command | 201 `{subject}` |
| GET / PATCH | `/v1/health-subjects/{id}` | — / subject command without `assetId` (full replace) | 200 `{subject}` |
| POST | `/v1/health-subjects/{id}/archive` | `{archived}` | 200 `{subject}` |
| GET | `/v1/attention` | — | 200 `{items}` |

**Plan decision — the nested shapes the spec names only by key:** `conditions` is oldest first by the current-ordering key; `current` is the row or null. In the health response, `condition` is `{condition, since, reason, occurredOn, eventId}` or null; `aggregate` is `{score, band, fallback}` or null (NOT TRACKED); each `subjects` item is `{subjectId, name, kind, driver, scheduleId, score, band, trackedDays, notTracked}` (`notTracked` one of `NO_REPLACEMENT`, `PROFILE_REMOVED`, `OUT_OF_SEASON`, `SCHEDULE_PAUSED`, `LINK_INVALID`, `SCHEDULE_ARCHIVED`, or null); `critical` is a list of `{subjectId, name, score}`; `components` is a list of `{assetId, name, condition, reason, occurredOn}` for every DOWN or DEGRADED in-service component at any depth. Every field present, `null` when absent. `…/health-subjects/{id}/archive` answers **404** for a wrong verb (a sub-resource, like `/v1/schedules/{id}/…`); `/v1/health-subjects`, `/v1/health-subjects/{id}` and `/v1/attention` answer 405.

### 11.2 The attention item (spec §9.1)

```
{kind (CONDITION|HEALTH), section (ATTENTION|UPCOMING), assetId, parentAssetId, condition,
 reason, occurredOn, healthSubjectId, subjectName, band, score, rank}
```

Rows cover in-service assets and components. `rank` is dense in the order DOWN, DEGRADED, independent CRITICAL, independent WARNING; within a group by asset name, then id. **Plan decision — "independent":** a subject whose driver is **AGE**; a MAINTENANCE_OVERDUE subject never yields an attention item because it rides its schedule's row (spec §10.2 "Overdue-driven health rides its schedule row").

### 11.3 Changed shapes

- **Schedule command:** gains `servicePolicy`, `policyOffsetDays`; keeps `seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays` as deprecated inputs. `PATCH /v1/schedules/{id}` and `POST …/archive` accept the action flag `unlinkHealthSubject` (not a column). `ruleChangedAt` is response-only.
- **Schedule response row.** **Plan decision:** responses carry the backup row **plus** the derived compatibility triple (`seasonBehavior` nullable, `seasonReentry`, `seasonReentryOffsetDays`, from §4's reverse projection) as one flat object, an API-only `ScheduleRowResponse` — **introduced by B03 in wave 2**, the moment the format-8 DTO loses the triple, so `/v1` never serves a schedule without it. That is the single, documented exception to v1's "a response row is the archive row" (spec §8.3 forbids the triple in format 8; §9.1 requires it in responses).
- **`ScheduleStateDto`** gains `actionableDueOn`, `policyReason`, `policyPhase`, `quiet`; keeps `effectiveDueOn` (1.2 meaning) and `seasonActive = policyPhase == ACTIVE`. **`/v1/due`** items gain `actionableDueOn`, `policyReason`, `quiet`; `status` may be `DEFERRED`; order and `rank` follow `actionableDueOn`.
- **`/v1/status`**: `schemaVersion` 8, `backupFormatVersion` 8, `counts` + `seasonActivations`, `assetConditions`, `healthSubjects`. Import-merge reads formats 1–8.
- **The error envelope** gains `field: String?` (spec §9.2). **Plan decision:** set to the offending body key for the 1.4 422s that name one key (`POLICY_OFFSET_INVALID` → `policyOffsetDays`, `MANUAL_PHASE_*` → `manualPhase`, `SEASON_WINDOW_*` → `seasonStartMmdd`, `SEASON_DATE_OUT_OF_RANGE` / `CONDITION_DATE_IN_FUTURE` → `occurredOn`, `CONDITION_REASON_TOO_LONG` → `reason`, `FOREIGN_EVENT` → `eventId`, `HEALTH_THRESHOLDS_INVALID` → `criticalFromDays`, `HEALTH_WEIGHT_OUT_OF_RANGE` → `weight`, `HEALTH_PRIMARY_INVALID` → `healthPrimarySubjectId`, `HEALTH_SUBJECT_NAME_REQUIRED` → `name`, whose message states the 1–60 limit so an over-long name is not misread as a missing one); null everywhere else.

### 11.4 Two forms and one translation (spec §9.3; Q-9)

- **Presence decides the form**, read from the raw JSON object before the typed decode: `servicePolicy` or `policyOffsetDays` present (an explicit `null` counts) → the **1.4 form**; neither → the **legacy form**, with 1.3's omitted-field defaults (`seasonBehavior` → IGNORE); any legacy key **and** any 1.4 key → 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`, writing nothing.
- **The legacy form translates only through `LegacySeasonMapping`** (inv. 128). It is **refused, never reset**, in exactly two cases: a schedule PATCH on a stored PRE_SERVICE schedule, and a different asset pair on a MANUAL asset — each 422 `LEGACY_WRITE_CANNOT_REPRESENT`, writing nothing. A create clobbers nothing. The one documented behaviour change: a legacy body omitting `seasonReentry` on a RESUME_CLAMPED schedule now becomes AT_START.
- **The asset command keeps 1.3's exact shape**; condition is never in it (#61 AC 9); its pair is the only compatibility input (§7.2).

### 11.5 Codes

**The 422/409 tie-break (RN-8):** a 422 means the remedy is to change **this body**; a 409 means the remedy is to change **another row first**. The whole 1.4 list is spec §9.2's, restated in B09 by status; the four the controller named are **422 `LEGACY_WRITE_CANNOT_REPRESENT`**, **422 `SCHEDULE_DRIVES_HEALTH_SUBJECT`** (the remedy is the flag in this body), **409 `SEASON_MODE_STRANDS_POLICY`** and **409 `BREAK_STRANDS_POLICY`** (the remedy is the schedule's policy — another row). 404 `NO_SUCH_HEALTH_SUBJECT` joins the shipped `no_such_asset`. **No endpoint** amends or deletes a condition or activation, deletes a health subject, or writes a health value; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` gains those paths.

### 11.6 The golden command shapes

**Plan decision (spec §9.4):** B09 commits `docs/api/command-shapes.json`, one object per command — `asset`, `schedule` (`{keys, legacyKeys, actionFlags, rowToCommand: {assetId: targetAssetId, groupId: targetGroupId}}`), `healthSubject`, `seasonMode`, `maintenanceBreak`, `healthPolicy`, `condition`, `activation` — each listing its request keys. A JVM test asserts each request DTO's serializer descriptor equals its entry; B11 vendors the key lists its overlays need into the Python package, and its pytest asserts the copy equal to this file and every MCP overlay submits exactly those keys (dec. 48).

---

## 12. Contract J — MCP and the schedules loader (owner: B11)

- **Fourteen new tools** (spec §9.4): `get_season`, `start_season`, `end_season`, `set_season_mode`, `set_maintenance_break`, `list_conditions`, `record_condition`, `get_health`, `set_health_policy`, `list_health_subjects`, `create_health_subject`, `update_health_subject`, `archive_health_subject`, `list_attention`. **Fifty-five** in total; `TOOL_NAMES` and `_forbid_unknown_arguments(expected_count=…)` agree. Unknown arguments rejected; `null` means unchanged; `clear_fields` clears by name; every error is a `ToolError` carrying its code. `start_season`, `end_season` and `record_condition` have **no overlay** (every argument explicit, like `close_round`).
- **`create_schedule` / `update_schedule`** gain `service_policy`, `policy_offset_days`, and keep `season_behavior`, `season_reentry`, `season_reentry_offset_days` as **deprecated arguments**. A deprecated argument sends a **legacy-form** body (the API translates and refuses with the same codes); mixing it with a 1.4 argument is a `ToolError` carrying `LEGACY_AND_CURRENT_FIELDS_MIXED` before any HTTP. `update_schedule` and `archive_schedule` gain `unlink_health_subject`. `_SCHEDULE_NULLABLE_CLEARABLE` gains `policy_offset_days` and keeps its two deprecated members.
- **`update_schedule` and `update_asset` stop enumerating fields:** they overlay **every command key the row reports**, from key lists **vendored into the Python package** (plan decision 48); pytest asserts them equal to `docs/api/command-shapes.json`, and nothing reads a repository file at runtime. With no deprecated argument, `update_schedule` sends the 1.4 form (overlaying `servicePolicy`/`policyOffsetDays` from the row); with one, it sends the legacy form overlaid on the row's derived triple.
- **Write tools refuse while `/v1/status.schemaVersion < 8`.** **Plan decision:** the check runs once per pairing and is cached; the refusal is a `ToolError` carrying `APP_SCHEMA_TOO_OLD`.
- **The loader** keeps writing `season_behavior` through the deprecated argument (`apply.py:95-105`); `phone.py` reads `servicePolicy` and `policyOffsetDays`; `plan.py` compares a manifest's legacy value **mapped by §4.1** with the row's policy and offset (`_schedule_rule` / `_phone_schedule_rule`); `manifest.py` unchanged. The Stage-B manifest re-plans IDENTICAL against 1.4 rows.
- **README** count becomes fifty-five, a "Seasons, condition and health (needs ServiceTag 1.4.0)" list, `import_merge` reading formats **1–8** and **fourteen** tables (fixing the stale "1–6" and "ten").

---

## 13. Contract K — surfaces (owners: B07 read models, B08, B10, B12–B14)

### 13.1 Shared read models (B07, `:app`, JVM-tested)

- **`DueReadModel`** reads states through `readState`; `DueItem` gains `actionableDueOn`, `policyReason`, `policyPhase`, `quiet`, `seasonMode` of the target (null for groups), `dormantUntil` (the next season start of a dormant CALENDAR row, else null) and `health: SubjectBandFact?` (the band of the subject this schedule drives, for "rides its schedule row"). `AttentionSection` becomes `ATTENTION, UPCOMING, CURRENT, DEFERRED, OUT_OF_SEASON`; DEFERRED → the Deferred section (S93). The total order sorts on `actionableDueOn`.
- **`AttentionReadModel.items()`** — §11.2's items, in-service assets and components only.
- **`AssetHealthReadModel.forAsset(assetId)`** — the engine's result plus the current condition and the DOWN/DEGRADED in-service components at any depth: the one source for the health route, the scan sheet and asset detail.
- **`scanSheetContent(items: List<DueItem>, condition: ConditionView?, components: List<ComponentCondition>, health: AssetHealthResult?)`** (the spec's `subjects` arrive inside `health`) — spec §10.1's one predicate: `opens` ⇔ any D-18a item, or the asset DOWN/DEGRADED, or any in-service component DOWN/DEGRADED; health lines are passengers and **never open the sheet alone** (O-8, inv. 123); the content lists condition, components, CRITICAL subjects and the aggregate when WARNING or CRITICAL, then the maintenance items or "Nothing due" (S139). `ScanSheetOffer.has` is `scanSheetContent(...).opens`.

### 13.2 Dashboard (B13)

ATTENTION: DOWN assets and components ▸ schedule rows ▸ DEGRADED assets and components ▸ independent CRITICAL subjects. UPCOMING: DUE SOON rows ▸ independent WARNING subjects. CURRENT ▸ **Deferred** ▸ OUT OF SEASON. Components promoted with the parent named (1.2 inv. 75 extended); in-service rows only. **Filters.** **Plan decision:** the shipped "Maintenance status" picker gains DEFERRED (its option text is the status word, S92, like the seven shipped options); a new row of four condition chips — S8, S10, S12, S26 — multi-select, none selected meaning no condition filter (so no "All …" string is needed). A schedule row passes when it matches the status picker (if set) **and** its asset's condition matches a selected chip (if any); a group row has no condition and passes only when no chip is selected; an asset row passes when **no status is selected or at least one condition chip is**, and it matches the chips (if any) — so asset rows hide only when **only** a status is selected (spec §10.2, ruled); category and search apply to both kinds.

### 13.3 Scan sheet (B12), asset detail (B14), editors (B08, B10)

As spec §10.1, §10.3, §10.4: the scan sheet's seven blocks in order; "Change condition" (three options, S14, S15 defaulting to today, past dates allowed, Cancel writes nothing); asset detail's condition badge, Condition, Health and Season sections; the asset editor's "Operating season", "Maintenance break", "Health subjects" and "Combine health by" saved through `SaveAssetSettings`; the health subject editor with empty thresholds and the confirmed templates; the schedule editor's question per spec §10.4's table with its offset fields, helpers, warnings and the link-guard dialog.

**Plan decision — the why-line** (S85–S91, spec §10.5): one line per row, by precedence **DORMANT** (S89 with `dormantUntil` for CALENDAR; S90 for MANUAL) ▸ **DEFERRED** (S85, date = `actionableDueOn`) ▸ **quiet** (S91) ▸ reason `BEFORE_SEASON` (S86) / `BEFORE_BREAK` (S87) / `SEASON_START` (S88, date = `P ?: R`) ▸ none. `POLICY_INAPPLICABLE` draws no why-line on a row; the editor shows S77 (B08).

### 13.4 D12 presentation (B12)

Spec §10.6 and O-2: operational/nominal in the cool-blue family; DEGRADED/warning in the warning/amber family with **DEGRADED on its own token, distinguishable from Due and Due soon**; DOWN/critical in the error family; not recorded / not tracked neutral; DEFERRED season-inactive grey. Word, icon and position each distinguish every state without colour; `ContrastTest` covers each new token pair; a JVM grayscale test proves no two states share both word and icon. Literal token values are design-token choices under the shipped contrast requirements.

---

## 14. Cross-cutting invariants → owning brief

Each invariant has **one accountable owner** — the brief where it is enforced and whose review answers for it — and may have halves: another layer's share ("half") or a surface that cannot violate it ("UI"). A brief's own invariant list and this table agree in both directions.

| inv. | invariant (short) | accountable | halves and UI |
|---|---|---|---|
| 81 | only an explicit condition write inserts a condition row | B06 | B09 (wire); B12 (UI) |
| 82 | health writes nothing | B05 | B06, B07 |
| 83 | condition changes nothing but its own history | B06 | — |
| 84 | every policy yields CONTINUOUS's `computedDueOn` | B02 | — |
| 85 | a completion's `occurrence_on` is `computedDueOn` | B02 | — |
| 86 | no policy, phase, break or activation writes an event, closure, schedule column or second occurrence | B06 (`CrossConceptWriteTest`) | B02 (engine), B04 (commands) |
| 87 | `rule_changed_at` moves only on a rule change; non-rule edits move nothing and clear no postponement | B01 | — |
| 88 | the `MM-DD` pair is non-null iff CALENDAR | B04 | B01 (migration), B03 (decoder) |
| 89 | activation rows immutable | B01 | B09 (no route); B14 (UI) |
| 90 | MANUAL phase is the latest row's action ≤ d; rows read only in MANUAL | B02 | — |
| 91 | a repeated START/END, a future date or one before the latest row is refused and writes nothing | B04 | — |
| 92 | a switch into MANUAL writes exactly one row; `manualPhase` elsewhere refused | B04 | B10 (UI) |
| 93 | no event changes a phase; the offer writes only when accepted | B04 | B12 (UI), B14 (UI) |
| 94 | dormancy creates no backlog; re-entry leaves one occurrence | B02 | — |
| 95 | the policy moves time-side dates only; meter-only is phase-only; PRE_SERVICE / non-zero offset on it refused; a crossed threshold is DUE | B02 | B04 (refusals) |
| 96 | CONTINUOUS: `actionableDueOn == P ?: R` | B02 | — |
| 97 | AT_START only moves an earlier date; RESUME_CLAMPED `max`; no drag-back | B02 | — |
| 98 | the pre-service rule, the guard, no inference | B02 | — |
| 99 | manual START never a boundary; YEAR_ROUND break boundary; PRE_SERVICE refused without one; no strands | B02 | B04 (refusals) |
| 100 | season start never completes, resets or hides pre-service work | B02 | B05 (clock) |
| 101 | a postponement goes through the policy, never earlier, never OVERDUE on the first in-season day | B02 | — |
| 102 | `quiet` ⇔ policy ≠ CONTINUOUS ∧ T in break; late work stays late and quiet | B02 | — |
| 103 | DEFERRED after the fold, never notifies, never due, never masks a meter | B02 | B07 (section); B13 (UI) |
| 104 | no reminder code evaluates a season, break or policy | B02 | — |
| 105 | no read path writes `schedule_state` or health | B07 (`ReadPathsWriteNothingTest`) | B02 (`readState`), B09 (API reads) |
| 106 | a group target is CONTINUOUS only | B04 | B09 (both forms); B08 (UI) |
| 107 | conditions immutable; current by the ordering key; no column | B06 | B01 (DAO); B14 (UI) |
| 108 | no UNKNOWN stored | B06 | B01 (enum) |
| 109 | condition and activation `event_id` are soft links, never owners | B01 | B03 (merge) |
| 110 | returning to OPERATIONAL inserts one row, earlier rows byte-identical | B06 | B14 (UI) |
| 111 | no health value stored or exported | B05 | B01 (schema), B03 (format) |
| 112 | MAINTENANCE_OVERDUE follows the actionable date via counted days; no meter | B05 | — |
| 113 | AGE baseline; a deleted profile leaves NOT TRACKED | B05 | — |
| 114 | MEDIUM on IN_SERVICE untracked while dormant, counts from the latest cycle start; others freeze and carry | B05 | — |
| 115 | pre-service lateness from the point, never reset; break days count for late work only | B05 | — |
| 116 | counted days use the actionable date as postponed | B05 | — |
| 117 | score non-increasing; WARNING at `t2`, CRITICAL at `t3` exactly | B05 | — |
| 118 | an untracked subject is excluded and shown NOT TRACKED, never 100 | B05 | B12 (UI), B14 (UI) |
| 119 | every CRITICAL contributor and DOWN/DEGRADED component on every health surface; no DOWN asset's health without its condition | B07 | B09 (health route); B12, B13, B14 (UI) |
| 120 | a schedule drives ≤ 1 non-archived subject; a subject never changes asset | B06 | B03 (merge); B10 (UI) |
| 121 | no default threshold, margin or break date; a template fills fields only after confirmation | B06 | B04 (break); B08, B10 (UI) |
| 122 | a DOWN/DEGRADED in-service asset or component reaches ATTENTION with no schedule and names its parent | B07 | B13 (UI) |
| 123 | the scan sheet opens only by `scanSheetContent` | B07 | B12 (UI) |
| 124 | format ≤7 decodes through §4.1 with empty lists and `ruleChangedAt = updatedAt`; a legacy field in format 8 is corrupt | B03 | — |
| 125 | migration and decoder agree row for row and move no `updated_at`; a pre-upgrade format-7 archive merges IDENTICAL (proved on B01's committed golden archive) | B03 | B01 (migration, the golden archive), B09 (agreement test) |
| 126 | activations and conditions merge only as INSERT, IDENTICAL or CONTENT_DIFFERS; their writes never change the asset row | B03 | B04, B06 (use cases) |
| 127 | no route or tool amends or deletes a condition or activation; condition in no asset command | B09 | B01 (DAO), B11 (tools) |
| 128 | legacy inputs translate only via §4.1, never change 1.4-only state (422), a mixed body is 422 | B09 | B04 (asset pair), B11 (arguments) |
| 129 | nothing names an installed component or assembly; nothing reads stock | B01 (schema) | B09 (routes), B11 (tools) — each runs §17's grep in its gate; B15 asserts it at release |
| 130 | the schedule-side link guard and its flag | B06 | B09 (wire); B08 (UI) |
| 131 | snooze never changes health; postpone restarts it from the new actionable date; no journal | B05 | B07 (the snooze half) |
**Note on 119 (controller, 2026-09-26):** it applies to the UNFILTERED dashboard; with a status-only filter a DOWN asset's own row may be hidden while its schedule row's health line still shows (B13 review M-1).


**Spec 1.2 invariants restated by spec §11:** **10** (`effectiveDueOn` keeps its meaning) B02; **16** (`rebuild` pure including the season input) B02; **17** (one writer, kept by 105) B02; **22** (INACTIVE_SEASON, PAUSED and DEFERRED never notify; nothing notifies while quiet) B02; **23** (a season, activation or break boundary may change status with no history change; the between-rebuilds exception is gone) B02, B07; **25** becomes 87 (B01); **26** is retired (B01 deletes `ScheduleStructuralTest.theDeferredReentryColumnsAreStoredAndNeverRead`; 84 and 104 replace it in B02); **27** becomes 106 (B04); **47** (DEFERRED and quiet are PARKED) B02; **57** (the scan never mutates) B12; **62–64** extended to format 8 and health B03. **No invariant is unassigned.**

**The owner's rulings → accountable brief** (spec §0; M4):

| ruling | accountable | also |
|---|---|---|
| Q-1 no household values; templates confirm-to-use | B06 (inv. 121) | B04, B08, B10 (UI) |
| Q-2 meter-driven health out | B05 | — |
| Q-3 opened-before guard; Q-4 season objective, break constraint; Q-6 no predicted START | B02 | B04 (refusals) |
| Q-5 independent health rows; criticals always visible | B07 | B13 (UI) |
| Q-7 D12 colour families | B12 | — |
| Q-8 no #47 column | B01 (inv. 129) | B09, B11; B15 (release grep) |
| Q-9 `/v1` compatible; legacy inputs; loud refusals | B09 | B04, B11, B15 |
| O-1 S1–S142 ratified; O-9 S143 | §19 (every S-number one owner) | B12 (S143) |
| O-2 families confirmed | B12 | — |
| O-3 YEAR_ROUND break boundary; strands as 409 | B02 (boundary) | B04 (409s) |
| O-4 pull only with no window left; O-5 AT_START and manual START | B02 | — |
| O-6 snooze never improves health; postpone restarts it | B05 | B07, B09, B12 |
| O-7 meter-only phase-only | B02 | B04 |
| O-8 health never opens the scan sheet alone | B07 | B12 (UI) |

---

## 15. The RED-first rule

Every behaviour a brief adds or changes is shown **red before it is green**:

1. Each test-matrix row names the **mutation** that makes it fail — a line deleted, a comparison inverted, a branch removed, a field left at its default. The implementer writes the test, runs it against the tree **without** the change (or with the named mutation re-applied), records the failing assertion in the ledger, then implements.
2. The review checks **one recorded RED per matrix row** and re-applies at least one mutation per brief itself.
3. **Shape-only rows** (B01's compile-through, B05's rename, and B03's edits to the shipped format 1–7 pins — `StageABundleConformanceTest`, `BackupFormat6Test`) are exempt by name: their proof is the shipped suite staying green with only mechanical edits, and the brief lists which shipped tests changed and why.
4. A test that cannot be made red by any plausible mutation is vacuous and is a review finding.

---

## 16. Briefs, dependency graph and waves

### 16.1 The fifteen briefs

| brief | file | owns |
|---|---|---|
| B01 | `B01-schema-8-and-the-legacy-mapping.md` | domain shapes, the legacy mapping and its golden file, **the committed golden format-7 archive**, Room 8 + `MIGRATION_7_8` + `8.json`, the three fact/config ports and Room adapters, #64, compile-through, the constructor pre-wiring |
| B02 | `B02-season-and-policy-engine.md` | `SeasonContext`, `ServicePolicyEngine`, `rebuild`'s policy fields, `statusOf` + DEFERRED, `readState`, reminder subjects, the retired inv. 26 replaced |
| B03 | `B03-backup-format-8-and-merge.md` | format 8, the ≤7 upgrade, merge tables 12–14, export/replace/merge use cases, **`ScheduleRowResponse`** (the `/v1` schedule row), **the connected `Format7RestoreContractTest`** |
| B04 | `B04-season-break-and-policy-commands.md` | `SetSeasonMode`, `SetMaintenanceBreak`, `RecordSeasonActivation`, the legacy asset pair, schedule policy validation, `SeasonView`, the season event offer |
| B05 | `B05-health-engine.md` | score, clock, AGE, aggregation, driver lines, `AssetHealthEngine`; the reminder-health rename |
| B06 | `B06-condition-and-health-configuration.md` | `RecordCondition`, `ConditionHistory`, the operational offer, subject/policy use cases, the link guard, `SaveAssetSettings`, `CrossConceptWriteTest` |
| B07 | `B07-read-models.md` | `DueReadModel` changes, `AttentionReadModel`, `AssetHealthReadModel`, `scanSheetContent` |
| B08 | `B08-schedule-editor.md` | the policy question, offsets, helpers and warnings, the link-guard dialogs on the editor and the schedule detail |
| B09 | `B09-api-v1.md` | every other `/v1` change (the command side, the two forms, the new routes), `docs/api/v1.md`, `command-shapes.json`, the agreement test |
| B10 | `B10-asset-and-health-subject-editors.md` | the asset editor's season, break, subjects and combine; the subject editor |
| B11 | `B11-mcp-and-schedules-loader.md` | the fourteen tools, deprecated arguments, overlays, the loader, the README |
| B12 | `B12-condition-and-health-on-screen.md` | D12 tokens and icons, words and badges, the scan sheet, Change condition, Mark operational, the offers |
| B13 | `B13-dashboard-and-maintenance-rows.md` | dashboard sections, rows, filters, the why-lines (S85–S91) |
| B14 | `B14-asset-detail.md` | the condition badge, Condition, Health and Season sections, the asset list's phase |
| B15 | `B15-version-docs-and-release.md` | 1.4.0 / 16, documents, the release runbook additions |

### 16.2 Dependency graph

```
B01 ──┬─► B02 ──┬─► B04 ──► B06 ──┬─► B07 ──┬─► B09 ──► B11
      │         │                  │         ├─► B12 ──┬─► B13
      │         └─► B05 ───────────┘         │         └─► B14
      │                                      └─► (B13, B14 read it)
      └─► B03 ─────────────────────────────────► B09
B04 + B06 ─► B08, B10          everything ─► B15
```

B01 gates everything. B02 gates B04 (boundary kinds, `SeasonContext`) and B05 (the per-day evaluation). B06 gates B07 (condition and configuration), B08 (the link guard) and B10 (`SaveAssetSettings`). B07 gates B09, B12, B13, B14. B09 gates B11. B12 gates B13 and B14 (tokens, words, badges, the Change condition sheet).

### 16.3 Waves and file-set separation

**The pairing rule:** only one brief per wave edits `di/AppGraph.kt` and `testing/FakeGraph.kt`; only one edits `ui/nav/*`; neither lane edits `core/src/test/.../testing/InMemoryRepositories.kt` in a two-lane wave unless the table says so. B01's constructor pre-wiring (§3.7) is what lets wave 2 run two `:core` lanes.

| wave | lane A | lane B | device first | file-set separation |
|---|---|---|---|---|
| 1 | **B01** | — | B01 | single lane: the shape change touches every module |
| 2 | **B02** | **B03** | B03 (`Format7RestoreContractTest`) | `core/schedule/**`, `usecase/RecomputeSchedules.kt`, `core/reminders/BuildReminderSubjects.kt`, `ui/maintenance/DueItemRow.kt`, `ui/components/ServiceTagIcons.kt` vs `core/backup/**`, `core/merge/**`, the four import/export use cases, `app/api/{ScheduleRowResponse,MaintenanceDtos,MaintenanceHandlers}.kt` (the response side), `MaintenanceCommandShapeTest.kt`, `MaintenanceRoutesTest.kt`, `StageABundleConformanceTest.kt`, `BackupFormat6Test.kt`, `VersionAgreementTest.kt`, `app/build.gradle.kts` (one `androidTest` assets line), `app/src/androidTest/.../backup/Format7RestoreContractTest.kt`. Neither edits `AppGraph` or the shared fakes; B02 touches no `api/**` |
| 3 | **B04** | **B05** | neither | `usecase/{SetSeasonMode,SetMaintenanceBreak,RecordSeasonActivation,SeasonCommands,SeasonView,SeasonEventOffer,AssetCommands,UpdateAsset,CreateAsset,SaveSchedule,ScheduleCommands}.kt`, `AppGraph`, `FakeGraph`, `InMemoryRepositories` vs `core/health/**`, `core/reminders/ReminderPort.kt`, `app/reminders/{LocalReminderProvider,ReminderHealthCheck}.kt`, `ui/maintenance/{ReminderHealth*,HealthSummary,MaintenanceViewModel}.kt`, `ui/dashboard/{DashboardViewModel,DashboardScreen}.kt`, `ui/nav/ServiceTagRoot.kt` |
| 4 | **B06** | — | neither | single lane: it edits `SaveSchedule`, `ArchiveSchedule`, `AppGraph` and the shared fakes |
| 5 | **B07** | **B08** | B08 | `ui/maintenance/{DueReadModel,AttentionReadModel,ScanSheetContent,MaintenanceSheetViewModel,DueItemRow}.kt`, `ui/health/AssetHealthReadModel.kt`, `AppGraph`, `FakeGraph` vs `ui/maintenance/{ScheduleEdit*,ScheduleDetail*}.kt` |
| 6 | **B09** | **B10** | B10 | `app/api/**`, `docs/api/**` vs `ui/asset/{AssetEditScreen,AssetViewModels}.kt`, `ui/health/HealthSubjectEdit*.kt`, `ui/nav/*` |
| 7 | **B11** | **B12** | B12 | `tools/servicetag-mcp/**`, `tools/servicetag-schedules/**` vs `ui/theme/SemanticColors.kt`, `ui/components/ServiceTagIcons.kt`, `res/drawable/ic_*` (new), `ui/condition/**`, `ui/health/{HealthWords,HealthBadge}.kt`, `ui/maintenance/{MaintenanceSheet,MaintenanceSheetViewModel,CompletionFlow}.kt`, `ui/journal/EventEntry*.kt`, `res/values/plurals.xml`, `AppGraph`, `FakeGraph` |
| 8 | **B13** | **B14** | B13, then B14 | `ui/dashboard/**`, `ui/maintenance/{DueItemRow,WhyLines,MaintenanceScreen,MaintenanceViewModel}.kt`, `AppGraph`, `FakeGraph` vs `ui/asset/{AssetDetailScreen,AssetViewModels,AssetMaintenanceSections,AssetsScreen}.kt` (reads graph fields only) |
| 9 | **B15** | — | none | single lane by design |

**Two lanes is a ceiling, not a target**; a wave whose lane B is not ready runs single. A brief that finds it needs a line in a file the other lane owns **asks the controller** rather than editing it.

---


**Carry-forwards from B01's review (controller, 2026-09-24):** `MaintenanceDaos.kt` (`observeAll` ORDER BY) joins B02's file set; B03's round trip keeps `ruleChangedAt` independent of `updatedAt`; B04 removes B01's `calendar()` fixture helper once the legacy-pair rule lands. Each brief carries the row.

## 17. Release acceptance and controller proofs

The standing runbook is `docs/release-proofs.md` (R1–R7); B15 adds the 1.4 lines to it. Everything below runs at the **exact final tip**, by the controller.

1. **Every brief merged** with its one task review and any scoped re-review closed; the **whole-branch review** clean (scope, strings, hygiene, untouched list, evidence).
2. **Strings:** every user-visible string added or changed is one of S1–S143, verbatim; a string not in spec §10.7 is a **release blocker**; the three retired strings are absent. **The `one` forms of the plurals resource** (S99's `<age>` "1 day"; S102 "… is 1 day overdue") are ratified inflections of S99 and S102 (controller ruling, dec. 29), not new strings.
3. **Numbers agree:** `versionName = "1.4.0"`, `versionCode = 16`, `AppDatabase` version 8, `AppGraph.SCHEMA_VERSION = 8`, `BackupCodec.FORMAT_VERSION = 8`, `/v1/status` echoing 8 and 8 (`VersionAgreementTest`, inside R1).
4. **R1** unit gate from scratch — `:nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest --rerun-tasks`: zero failures, zero skips; counts equal the controller's baseline at the release base plus each brief's recorded delta.
5. **R2** the whole connected suite on `emulator-5554` with the preserved set staged: zero failures, zero skips; the new Compose classes present.
6. **R3** the three Python suites: `servicetag-mcp` (the tool count **55**), `servicetag-bundle` (green, **byte-identical**), `servicetag-schedules`.
7. **R4** `ShareBoundaryTest` alone and unchanged; **R5** the manifest contracts unchanged (1.4 adds no permission and no exported component) plus `VersionAgreementTest`; **R6** hygiene over `<base>..HEAD`.
8. **Format-7 import proof** — inside R2: B03's connected contract class `Format7RestoreContractTest` (in-process `importBackupReplace` and `exportBackupSet`, no navigation) over **B01's committed golden format-7 archive** (fictional: CALENDAR and YEAR_ROUND assets; IGNORE and FOLLOW_ASSET schedules with null, `AT_START`, `RESUME_CLAMPED` and `MM-DD` re-entries and out-of-range offsets): it restores with equal counts, CALENDAR iff both `MM-DD` were set, no break, §4.1's mapping on every schedule, `ruleChangedAt = updatedAt` and empty new lists; exported again it is format 8 and plans IDENTICAL against the store it came from. The JVM half is B03's `Format7ImportIdentityTest` over the same file.
9. **R7 — the one signed-APK upgrade smoke,** on the development phone, with **no export and no owner action** (controller ruling, Concern 2): `adb install -r` of the verified 1.4.0 over the installed 1.3.0, in place, code **15 → 16**, the same UID and `firstInstallTime`. Data survival is proven three ways: **(i)** `/v1/status` counts and a **per-table hash** of every list route, read through the loopback API before and after the install (the Developer API screen opened by one `adb shell am start`, as in 1.3.0's R7); each object is hashed over its **1.3 key set** only, because 1.4 responses gain keys (`AssetDto` +5, the schedule row + `servicePolicy`/`policyOffsetDays`/`ruleChangedAt`) and the derived triple keeps the 1.3 schedule keys comparable; schedules are compared after passing the pre-install row's legacy triple through the round trip `toLegacy(toPolicy(…))` — a stored triple that was not already normal (an offset of 400, an `MM-DD` re-entry, an IGNORE row with a re-entry) reads back normalised — and every other 1.3 key verbatim; the shipped counts and hashes are identical, the three new count keys are 0, `schemaVersion` and `backupFormatVersion` are 8; **(ii)** the loader's re-plan IDENTICAL (item 10), which checks every schedule's policy through §4.1; **(iii)** R2's `PreservedSetRestoreTest` (format 5 → schema 8) on the emulator. Spec §12.3's per-row checks (CALENDAR iff both `MM-DD`, no break, the §4.1 mapping, empty new lists) are read off the same post-install responses.
10. **Stage-B re-plan IDENTICAL:** `servicetag-schedules plan <the owner's manifest>` against the upgraded development phone exits 0 with every entry IDENTICAL.
11. **MCP on the phone:** `status` shows 8/8; one read of each new read tool answers; no write is made to the development phone beyond what the owner authorises.
12. **CI green on the exact commit** (jobs `build`, `mcp`, `bundle`, `schedules`; zero annotations); annotated tag **`servicetag-v1.4.0`**; the release workflow waits at the protected environment for the owner; after approval, verify the artifact (`sha256sum -c`, one signer equal to `RELEASE_CERT_SHA256`, badging `1.4.0 / 16`, not debuggable, the permission set unchanged from 1.3.0).
13. **Tracker** (§18).

**Controller structural proofs** — each an anchored command with its expected answer, run from the repository root; `<base>` is the release branch's base commit:

- MCP tool count — `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **55**
- one `MergeTable`, fourteen members, `REFERENCES` then the three new — `grep -c '^enum class MergeTable' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **1**; the review reads the member list
- no `UPDATE` verdict — `grep -cE '^[[:space:]]*UPDATE[,[:space:]]*$' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → **0**
- `:core` Android-free — `grep -rlE '^import (android|androidx)\.' core/src/main` → no output
- the retired season fields have no engine or reminder reader — `grep -rnE '\bseason(Behavior|Reentry|ReentryOffsetDays)\b' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule core/src/main/kotlin/com/loosecannon/servicetag/core/reminders core/src/main/kotlin/com/loosecannon/servicetag/core/health` → no output
- no reminder code evaluates a season (inv. 104) — `grep -rnE '(Season\.inSeason|SeasonContext|ServicePolicyEngine|blackout(Start|End)Mmdd|season(Start|End)Mmdd)' core/src/main/kotlin/com/loosecannon/servicetag/core/reminders app/src/main/kotlin/com/loosecannon/servicetag/reminders` → no output
- facts immutable — `grep -rnE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?.?(asset_condition|asset_season_activation)\b' app/src/main core/src/main` → no output
- no health value persisted — `grep -rniE '(health_score|health_band|health_state|healthScore|healthBand)' app/src/main/kotlin/com/loosecannon/servicetag/data core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/main/kotlin/com/loosecannon/servicetag/core/merge` → no output
- the numbers, anchored — `grep -cE '^[[:space:]]*version = 8,$' app/src/main/kotlin/com/loosecannon/servicetag/data/room/AppDatabase.kt`, `grep -cE '^[[:space:]]*const val SCHEMA_VERSION = 8$' app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt`, `grep -cE '^[[:space:]]*const val FORMAT_VERSION = 8$' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt`, `grep -cE '^[[:space:]]*versionCode = 16$' app/build.gradle.kts`, `grep -cE '^[[:space:]]*versionName = "1\.4\.0"$' app/build.gradle.kts` → **1** each
- schemas — `test -f app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/8.json` → present; `git diff --stat <base>..HEAD -- app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/1.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/2.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/3.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/4.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/5.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/6.json app/schemas/com.loosecannon.servicetag.data.room.AppDatabase/7.json` → empty
- retired strings — `grep -rnF "Pause with the asset's season" app/src/main`, `grep -rnF 'Remind me year round' app/src/main`, `grep -rnF 'Off means this asset is only in use' app/src/main` → no output each
- inv. 129 — `git diff <base>..HEAD -- app/src/main core/src/main | grep -niE '^\+.*\b(assembly|assemblies|installed_?component|stock)\b'` → no output
- the untouched list — `git diff --stat <base>..HEAD -- libs/ tools/servicetag-bundle/ share-test-sender/ app/src/main/AndroidManifest.xml core/src/main/kotlin/com/loosecannon/servicetag/core/nfc core/src/main/kotlin/com/loosecannon/servicetag/core/journal app/src/main/kotlin/com/loosecannon/servicetag/share` → empty; `bash tools/check-submodule-pin.sh` → `submodule pin ok`
- the tombstones — `git diff --stat <base>..HEAD -- core/src/main/kotlin/com/loosecannon/servicetag/core/model/ExternalLink.kt app/src/main/kotlin/com/loosecannon/servicetag/data/room/entities/ExternalLinkEntity.kt app/src/main/kotlin/com/loosecannon/servicetag/data/room/dao/ExternalLinkDao.kt` → empty
- the stale prose gone — `grep -ci 'forty-one' tools/servicetag-mcp/README.md` → **0**; `grep -cE '(format \*\*1–7\*\*|\*\*format 1–7\*\*)' docs/api/v1.md` → **0**; `grep -ci 'the eleven tables' docs/api/v1.md` → **0**

---

## 18. Issue map

| issue | delivered by | closes when |
|---|---|---|
| **#14** operating seasons (calendar and manual) | B01 (mode column), B02 (phase, re-entry), B04 (commands), B10 (editor), B14 (detail, Start/End) | release verified; AC trace spec §13 #14-1…8 with the named tests |
| **#60** service policy and the break | B02 (engine, DEFERRED, quiet), B04 (commands, strands), B08 (editor), B13 (why-lines, Deferred) | release verified; AC trace #60-1…11 |
| **#61** condition and derived health | B05, B06, B07, B09, B10, B12, B13, B14 | release verified; AC trace #61-1…26 |
| **#64** the pin floor moves on a non-rule edit | **B01** (`rule_changed_at`, `SaveScheduleRuleFieldTest` RED then green) | release verified, with the RED record linked |

Seams, no work (spec §15): #47 (no installed-component row), #44 (append-only facts; `asset_id` remap later), #15 (no stock read), #45/#9 (no provider id; `Parked` carries break suppression), #27 (renamed only). Open and out of scope: #65 (a flaky JVM test, unrelated).

---

## 19. String ownership

Every S-number is drawn by exactly one owning brief; another brief may **use** an owned constant but never redefine it.

| S | owner | S | owner |
|---|---|---|---|
| S1–S20 | B12 (words, Change condition, Mark operational, the offer) | S85–S91 | B13 (the why-lines) |
| S21 | B14 (history) | S92 | B02 (the status word) |
| S22, S23, S25, S27 | B12 | S93 | B07 (the section label) |
| S24 | B14 (dangling link) | S94 | B14 (section) |
| S26 | B13 (chip) | S95–S106 | B12 (bands, driver lines) |
| S28–S38 | B10 (Operating season) | S107 | B14 (footer) |
| S39, S42–S50 | B14 (phase, dialogs, history, calendar lines) | S108–S110 | B12 |
| S40, S41, S51–S53 | B12 (the season offer and its two actions; B14 uses S40, S41) | S111–S136 | B10 (subjects, combine) |
| S54, S56, S57 | B14 (activation refusals) | S137 | B08 (defined beside the link-guard dialog; B10 uses it) |
| S55, S58–S64 | B10 (refusals, Maintenance break) | S138 | B14 (fallback) |
| S65–S84 | B08 (the question, fields, helpers, warnings) | S139 | B12 (scan, empty) |
| | | S140, S141 | B08 (link guard) |
| | | S142, S143 | B12 (driver lines) |

---

## 20. Plan decisions

1. Activation and condition ids are `String`; `HealthSubjectId` is a value class (§3.6).
2. The migration copies schedules through `LegacySeasonMapping`, not SQL `CASE` (§3.2).
3. B01 pre-wires the constructors of `RecomputeSchedules` and the four backup use cases (§3.7).
4. AT_START reverses to `"AT_START"` plus its offset (§4).
5. The mapping cases are a golden file, `docs/api/legacy-season-mapping.json` (§4).
6. Format ≤7 is upgraded as a JSON tree before a strict format-8 decode; the new DTO fields have no defaults (§5).
7. The strands check counts every PRE_SERVICE schedule on the asset whatever its lifecycle (§7.2).
8. `BLACKOUT_COVERS_THE_YEAR` means some common or leap year has no allowed day (§7.2).
9. `nextBoundaryOn` definition (§7.2).
10. `DueStatus.DEFERRED` is appended last (§8.4).
11. One read accessor, `RecomputeSchedules.readState` (§8.6).
12. A group completion offers "Mark operational?" per DOWN/DEGRADED member, one at a time (§9).
13. A foreign baseline profile is `PROFILE_NOT_A_REPLACEMENT` (§10.1).
14. The unlink flag on the TRACK_ONE primary is 409 `HEALTH_SUBJECT_IS_PRIMARY` (§10.1).
15. `SaveAssetSettings` is the composite editor save in `:core` (§10.1).
16. Counted days range over `min(O, P ?: R) < d ≤ T` (§10.2; **changed by the controller's ruling on I7** — `O < d` under-counted a never-terminated COMPLETION schedule anchored before its rule-change date). The range is an evaluation bound only: no day before the postponed actionable date contributes (owner, 2026-09-24).
17. An archived linked schedule (merge-only) shows NOT TRACKED with no driver line (§10.2).
18. TRACK_ONE with an untracked primary is NOT TRACKED, no fallback (§10.2).
19. The nested shapes of the health, conditions and season responses (§11.1).
20. `/v1/health-subjects/{id}/archive` answers 404 for a wrong verb (§11.1).
21. "Independent" health rows are AGE subjects (§11.2).
22. The schedule response is the archive row plus the derived triple, `ScheduleRowResponse` (§11.3), introduced by **B03** in wave 2 (ruling on I4).
23. The `field` envelope names the offending key for the listed codes, `HEALTH_SUBJECT_NAME_REQUIRED` → `name` included (§11.3).
24. `docs/api/command-shapes.json` content (§11.6).
25. The MCP's schema check is cached per pairing and refuses with `APP_SCHEMA_TOO_OLD` (§12).
26. The dashboard's filter semantics, and the status picker drawing S92 (§13.2).
27. The why-line precedence (§13.3).
28. The reminder-health rename covers `HealthScreen`, `HealthFinding`, `Severity` and `HealthViewModel`; `HealthSummary` keeps its name (it is wired in `AppGraph` and I-19 does not name it) (B05).
29. **Changed by the controller's ruling:** `<n>` and `<age>` substitute through an Android plurals resource. The ratified text is the `other` form; the `one` form drops the unit's "s" (S99's `<age>`: "1 day" / "`<n>` days"; S102: "… is 1 day overdue" / the ratified text); S104 is singular-safe as written. No new string: a grammatical inflection of a ratified one (§17.2; B12).
30. On the schedule editor, a stored non-CONTINUOUS policy on an asset with no season and no break shows S77 with the question offering S68 only (B08).
31. In the API's 1.4 form, a `policyOffsetDays` sent as `null` on IN_SERVICE_AT_START takes the default 0, like an omitted key (B09).
32. `RecordSeasonActivation` on a MANUAL asset with no row reads the phase as OUT_OF_SEASON, so END is `SEASON_ALREADY_ENDED` and START is allowed (B04).
33. A health subject name over 60 characters is `HEALTH_SUBJECT_NAME_REQUIRED`, the spec's one name code; the envelope's `field` is `name` and the message states the 1–60 limit (ruled; B06, B09). The editor's 60-character input limit makes it unreachable from the phone (dec. 46).
34. `SaveHealthSubject` has `create(assetId, …)` and `update(id, …)`, so a subject cannot change asset (B06).
35. When the scan sheet opens for any reason, condition included, DUE SOON rows ride along as passengers (B07).
36. Attention ties break by asset name, asset id, subject `sortOrder`, subject id (B07).
37. `DueItem.dormantUntil` carries the next season start of a dormant CALENDAR row, for S89 (B07, B13).
38. On a meter-only schedule the in-service option keeps its asset's label: S67 on CALENDAR and MANUAL, S70 on YEAR_ROUND with a break (B08).
39. The asset editor shows "Health subjects" and "Combine health by" on an existing asset only (B10).
40. An asset drawn as any dashboard row is not repeated in the plain asset list (B13).
41. Condition and season histories on asset detail are newest first (B14).
42. S40 and S41 are owned by B12 (the season offer lands first) and S137 by B08 (the link-guard dialog lands first), so no brief uses a constant a later wave defines (§19).
43. A new schedule starts on S68 (CONTINUOUS), the API's own default; only the pre-service margin starts empty (B08).
44. **(Ruled, M11)** Adding a boundary where none existed (NONE → BREAK, NONE → SEASON) is a repair and is allowed; only removing or re-kinding a boundary a PRE_SERVICE schedule relies on is refused (§7.2; B04, B06).
45. **(Ruled, I8)** Subject create, update and restore re-run the whole link validation; an archived schedule is refused with `FOREIGN_SCHEDULE`, whose message says "archived" (the spec's code set is closed); B10's S122 picker excludes archived schedules (§10.1; B06, B10).
46. **(Ruled, I10)** Editor fields with no ratified refusal sentence are made unreachable by mechanism, never by new words: S71 and S72 take a digits-only filter capped at 365 and Save stays disabled while S71 is 0 (as while it is empty, S83); CALENDAR with one date disables Save with both fields marked required, and the break likewise; a malformed `MM-DD` draws the shipped "Not a real month and day"; thresholds take a digits filter capped at 36,500; weight is a 1–10 stepper; the subject name has a 60-character input limit. Any field that still needs a sentence goes to the controller, who escalates to the owner before that brief's wave (B08, B10).
47. **(Ruled, M12)** B07 wires the delivery seam `scheduleStateReader` to `readState`, so `ScheduleDeliveryFacts` and `ReminderHealthCheck` read fresh state too (§8.6).
48. **(Ruled, M15)** The MCP's command key lists are vendored into the Python package; pytest asserts them equal to `docs/api/command-shapes.json`; nothing reads a repository file at runtime (§12; B11).
49. **(Ruled, I2 + I11)** B01 commits a golden format-7 archive of fictional data under `core/src/test/resources/golden/`, encoded once through the format-7 codec while it still exists by a helper B01 deletes in the same brief, never regenerated. B03's `Format7ImportIdentityTest` (JVM) and its connected `Format7RestoreContractTest` (in R2) both read it; the connected class reaches it through one `androidTest` assets source-directory line in `app/build.gradle.kts` (§17.8).
50. **(I6)** `PolicyOutcome.quietUntil` carries the first allowed day after the break; the reminder builder reads it through `RecomputeSchedules.quietUntil(schedule)`, computed in memory, never a column (§8.5; B02).
51. **(M8)** The two offers are use-case classes with pinned signatures: `AcceptSeasonOffer.run(assetId, event, action)` (B04) and `AcceptOperationalOffer.run(assetId, event)` (B06), each wired in `AppGraph` and mirrored in `FakeGraph` by its brief.
