# B03 — backup format 8 and merge tables 12–14

**Read first:** master plan §1, §4 (the legacy mapping), §5 (format 8), §6 (the merge), §14, §15.
**Spec:** §8.2, §8.3, §8.4, §4.1; inv. 62–64 (restated), 109, 111, 120, 124–126.
**Wave 2, lane B,** beside B02. **After B01.** Gates B09.

## Goal

Make the archive carry 1.4: backup **format 8** with three new lists, the new asset and schedule fields and the three new count keys; every format 1–7 archive still readable, through **one** legacy mapping, with a pre-upgrade format-7 export merging IDENTICAL after the upgrade; and the additive merge extended by three tables in dependency position — the two immutable fact tables that only ever insert, and the configuration table whose second identity declines rather than conflicts. No engine, no use case outside the four import/export ones, no API.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/backup/LegacyArchive.kt` — the format ≤7 tree upgrade (master §5).
- Tests: `core/src/test/kotlin/com/loosecannon/servicetag/core/backup/BackupFormat8Test.kt`, `…/backup/LegacyArchiveUpgradeTest.kt`, `…/merge/MergePlannerSeasonHealthTest.kt`, `…/usecase/Format7ImportIdentityTest.kt`; `core/src/test/.../testing/Format8Fixtures.kt` (new builders; B01's `MaintenanceFixtures.kt` is not edited).

**Modify**

- `core/.../core/backup/BackupFormat.kt` — the three new DTOs and their `toDto`/`toDomain`; `AssetDto` and `MaintenanceScheduleDto` in their format-8 field sets (master §5); **B01's interim format-7 mapping is removed** from the DTO mappers, because the upgrade now runs on the tree.
- `core/.../core/backup/BackupCodec.kt` — `FORMAT_VERSION = 8`; the version-dispatched decode; the three lists in `encode`'s sorted block, `counts`, the eager validation pass and `validateGraph`.
- `core/.../core/merge/MergePlan.kt` — `MergeTable`'s three members after `REFERENCES` and its KDoc ("fourteen"); `MergeReason`'s two new members; `MergeWrites`, `MergeSnapshot`, `MergeReport` and `report()` each gaining `seasonActivations`, `conditions`, `healthSubjects`.
- `core/.../core/merge/MergePlanner.kt` — the three passes, in write order, in the shipped five-step shape.
- `core/.../core/usecase/ExportBackupSet.kt`, `ImportBackupReplace.kt`, `BuildBackupMergePlan.kt`, `ApplyBackupMergePlan.kt` — use the three repositories B01 already put in their constructors: export reads them inside the read transaction; replace inserts them after assets and schedules (the wipe needs nothing new — `assets.deleteAll()` and `schedules.deleteAll()` cascade them); plan snapshots them; apply writes them after `REFERENCES`, before the shipped `rebuildAll`.
- `app/src/test/kotlin/com/loosecannon/servicetag/VersionAgreementTest.kt` — the format half: 8.
- Existing codec and merge tests that build a `BackupData`, `MergeSnapshot` or `MergeWrites` positionally, or assert an encoded format number — mechanical edits only.

**Untouched:** `AppGraph.kt` (B01 wired the constructors), every `core/schedule/**` and `core/reminders/**` file (B02's, same wave), `InMemoryRepositories.kt`, `MaintenanceFixtures.kt`, `app/**` main sources, `tools/`, `libs/`, `docs/`. **The tombstones** — `ExternalLinkDto`, the `externalLinks` array, `ExternalLink.toDto/toDomain`, `MergeTable.LINKS` — **byte-for-byte**.

## Interfaces

**Consumes from B01:** the domain shapes (master §3.6), `LegacySeasonMapping.toPolicy`, the three repository ports and their in-memory fakes, the widened constructors.

**Produces:**

- The wire names every later brief uses (master §5): `SeasonActivationDto`, `AssetConditionDto`, `HealthSubjectDto`, the widened `AssetDto` and `MaintenanceScheduleDto`, `BackupData.seasonActivations/assetConditions/healthSubjects`, count keys `seasonActivations`, `assetConditions`, `healthSubjects`. **B09's API rows are these DTOs** (plus the schedule's derived triple, master §11.3).
- `MergeTable.SEASON_ACTIVATIONS`, `CONDITIONS`, `HEALTH_SUBJECTS`; `MergeReason.HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW` (on a `SKIPPED`) and `HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE` (on a `CONFLICT`); `MergeReport`'s three tallies — for B09's report mirror.

### The decode, version by version

`decode` reads the manifest, refuses a format above 8 with `BackupNewerFormat` **before any row**, checks the hash, then:

- **format 8** — strict decode into the format-8 DTOs. A legacy season key is an unknown key, so the decode fails and the archive is `BackupCorrupt` (inv. 124); a missing new field fails the same way.
- **formats 1–7** — parse `data.json` to a `JsonObject`, pass it through `LegacyArchive.upgrade(tree, formatVersion)`, then run the **same** strict decode. The upgrade, per schedule object: `LegacySeasonMapping.toPolicy(seasonBehavior absent → null, seasonReentry, seasonReentryOffsetDays, hasTimeRule = timeInterval != null)` into `servicePolicy`/`policyOffsetDays`, removes the three legacy keys, sets `ruleChangedAt = updatedAt`; per asset object: `seasonMode` = `CALENDAR` iff both `MM-DD` are non-null, else `YEAR_ROUND`, both blackout keys null, `healthAggregation = "WORST"`, `healthPrimarySubjectId = null`. An unrecognised `seasonBehavior` name stays unrecognised and the archive is `BackupCorrupt` (1.3's answer).

```kotlin
object LegacyArchive {                 // core/.../core/backup/LegacyArchive.kt
    /** Rewrites a format ≤7 data tree into format 8's shape. Pure; never called for format 8. */
    fun upgrade(tree: JsonObject, formatVersion: Int): JsonObject
}
```

### The merge passes

- **12, 13** — identity by row id; INSERT when absent and the asset is local or being inserted; IDENTICAL when every DTO field matches; `CONTENT_DIFFERS` otherwise; `OWNER_NOT_AVAILABLE` for an absent asset. **`eventId` is never an owner**: a row naming an event that exists nowhere still inserts (inv. 109).
- **14** — identity by row id **and** by the non-archived `scheduleId`. An incoming **non-archived** subject whose schedule is driven by a local non-archived subject under **another** id → **SKIPPED**, `HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW` (it declines, as 1.3's references do, D-18). Two non-archived archive rows claiming one schedule → **CONFLICT**, `HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`. An archived subject claims no schedule identity. `OWNER_NOT_AVAILABLE` for an absent asset **or** schedule. `baselineProfileId` and the asset's `healthPrimarySubjectId` are soft and never owners.
- A divergence in an asset's mode, break or health policy is `CONTENT_DIFFERS` on `ASSETS` with no new code. There is no `UPDATE`; one conflict anywhere empties every write list.

## Invariants this brief must hold

**62, 63, 64** extended to format 8 and to health; **88** (decoder half); **109** (merge half); **111** (nothing derived in the format); **120** (merge half: the second identity); **124**; **125** (decoder half: agrees with the migration's mapping, moves no `updatedAt`, a pre-upgrade format-7 export merges IDENTICAL); **126** (merge half). It must not weaken the shipped merge invariants **66, 67, 69, 71**.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a new field dropped in transit | `BackupFormat8Test` · `everyNewFieldSurvivesARoundTrip` (non-default values in every field of the three DTOs, the asset's five and the schedule's three) | omit `occurredTime` from the condition mapper |
| DTO field-set drift | `BackupFormat8Test` · `theDtoFieldSetsAreTheMasterPlansLists` (serializer descriptors against master §5) | rename `tzId` |
| **an old archive stops working** | `LegacyArchiveUpgradeTest` · `everyGoldenCaseDecodesToItsPolicy` (each case of `docs/api/legacy-season-mapping.json` as a format-7 schedule) and `aFormat7AssetIsCalendarExactlyWhenBothMonthDaysAreSet` and `ruleChangedAtIsUpdatedAtAndTheNewListsAreEmpty` | seed `ruleChangedAt` from `createdAt` |
| formats 1–6 regress | the shipped `BackupCodecTest`, `BackupFormat6Test`, `BackupFormat7Test`, `StageABundleConformanceTest` stay green, the last **unchanged** | — (shape-only) |
| a legacy key accepted in format 8 | `BackupFormat8Test` · `aLegacySeasonFieldInFormat8IsCorrupt` and `aMissingNewFieldInFormat8IsCorrupt` | give the DTO `ignoreUnknownKeys` |
| a newer archive half-read | `BackupFormat8Test` · `formatNineIsRefusedBeforeAnyRow` and, holding the constant at 7 in the expectation, `aSevenBuildRefusesFormatEight` (inv. 63) | move the version check after the decode |
| counts drift | `BackupFormat8Test` · `countsCarryTheThreeNewKeysEqualToTheRows` | count `healthSubjects` from conditions |
| non-deterministic bytes | `BackupFormat8Test` · `shuffledListsEncodeToIdenticalBytes` | leave `assetConditions` unsorted |
| a broken graph imported | `BackupFormat8Test` · `aFactOrSubjectOnAnAbsentAssetIsCorrupt`, `aSubjectOnAnAbsentScheduleIsCorrupt`, `aDanglingEventLinkIsNotCorrupt` | validate `eventId` |
| the tombstones move | `BackupFormat8Test` · `theExternalLinksArrayEncodesAsIn1_3` (a fixture's `externalLinks` bytes equal a 1.3 encoding's) | reorder `ExternalLinkDto` |
| derived state exported | `BackupFormat8Test` · `noDerivedOrHealthFieldIsInTheFormat` (no `schedule_state`, score, band or aggregate field in `BackupData`'s descriptor tree) | add `healthScore` to `HealthSubjectDto` |
| merge order | `MergePlannerSeasonHealthTest` · `theFourteenTablesAreInWriteOrder` | append before `REFERENCES` |
| facts merge | `MergePlannerSeasonHealthTest` · `factsInsertMatchOrDiffer` (INSERT, IDENTICAL, `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE`), `aSoftLinkIsNeverAnOwner` | treat `eventId` as an owner |
| the subject's second identity | `MergePlannerSeasonHealthTest` · `aSubjectForAScheduleHeldLocallyIsSkipped`, `twoArchiveSubjectsForOneScheduleConflict`, `anArchivedSubjectClaimsNoSchedule`, `aSubjectOnAnAbsentScheduleIsOwnerNotAvailable` | match on row id only |
| a fact write reaches the asset row | `MergePlannerSeasonHealthTest` · `recordingStartEndAndAConditionLeavesTheAssetIdentical` (after local START, END and a condition, re-importing the original archive plans the asset IDENTICAL; inv. 126) | stamp the asset's `updatedAt` in the fixture's activation write |
| configuration diverges | `MergePlannerSeasonHealthTest` · `aModeBreakOrHealthPolicyDifferenceIsContentDiffersOnAssets` | skip the new asset fields in the comparison |
| a partial merge | `MergePlannerSeasonHealthTest` · `oneConflictWritesNothing` (every write list empty) | keep the fact inserts |
| the apply | `MergePlannerSeasonHealthTest` · `applyWritesTheThreeTablesInOrderThenRebuildsOnce` | rebuild per table |
| **the upgrade identity** | `Format7ImportIdentityTest` · `aPreUpgradeFormat7ExportMergesIdentical` — a 1.3-shaped store (CALENDAR and YEAR_ROUND assets, IGNORE and FOLLOW_ASSET schedules) exported as format 7, the same store as 1.4 rows mapped by §4.1 with `ruleChangedAt = updatedAt`, then planned: every table IDENTICAL (inv. 125) | map `RESUME_CLAMPED` to AT_START in the upgrade only |
| replace restores 1.4 | `Format7ImportIdentityTest` · `aReplaceRestoreOfFormat8KeepsFactsAndSubjects` | skip the subject insert loop |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `cd tools/servicetag-bundle && uv run --frozen pytest` → green, and `git diff --stat <base> -- tools/servicetag-bundle` → empty.
- Anchored: `grep -c 'FORMAT_VERSION = 8' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt` → 1; `grep -c '^enum class MergeTable' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → 1; `grep -c 'eleven canonical tables' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → 0; `grep -cE '^[[:space:]]*UPDATE[,[:space:]]*$' core/src/main/kotlin/com/loosecannon/servicetag/core/merge/MergePlan.kt` → 0; `grep -rnE '\bseason(Behavior|Reentry)' core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupFormat.kt` → no output (the legacy keys live only in `LegacyArchive.kt`); `git diff --stat <base> -- core/src/main/kotlin/com/loosecannon/servicetag/core/model/ExternalLink.kt app/src/main core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → empty.
- No connected run.

## Strings

**None.** Reason codes and field names are contract, not copy.

## Must NOT

- encode a legacy season field, or read one anywhere but `LegacyArchive`;
- give any new DTO field a default;
- validate a soft link, or make one a merge owner;
- add an `UPDATE` verdict, or write anything when a conflict exists;
- touch the tombstones, `AppGraph`, the engine, or the shared fakes;
- export `schedule_state`, a health value, or any delivery state.

## Size

Medium-large: three DTO pairs, one upgrade function, three planner passes, four use-case edits. Split seam if needed: **B03a** format 8 and the upgrade; **B03b** the merge and the four use cases; the interface is the DTO field sets of master §5.
