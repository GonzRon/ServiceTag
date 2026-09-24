# B06 — condition history, health configuration and the link guard

**Read first:** master plan §1, §9 (condition), §10.1 (subjects, policy, the guard, `SaveAssetSettings`), §14, §15.
**Spec:** §5 (all), §6.1, §6.5 (the policy command), §6.8, §9.2 (the codes), §10.4 (the one-write editor save); inv. 81–83, 86, 107–110, 120, 121, 126, 130.
**Wave 4, lane A, single lane** (it edits `SaveSchedule`, `ArchiveSchedule`, `AppGraph` and the shared fakes). **After B04 and B05.** Gates B07, B08, B10.

## Goal

The write side of #61. An auditable **condition history** — one use case that inserts one immutable row and nothing else, the pure derivation of *current* and *since*, and the "Mark operational?" offer that writes only when accepted. The **health configuration** — subjects saved and archived under spec §6.1's refusals, the asset's aggregation and primary set through their own command — with no threshold ever defaulted. The **schedule-side link guard**, so no schedule edit or archive strands a subject unless the request says to archive it too. `SaveAssetSettings`, the composite the asset editor uses to save the asset, its season, its break and its health policy in **one** transaction. And `CrossConceptWriteTest`, which proves for every 1.4 use case exactly which tables it may write.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/condition/ConditionHistory.kt` — `ConditionHistory.of(rows)`: `current`, `since`, `ordered`.
- `core/.../core/usecase/ConditionCommands.kt` — `ConditionCommand`, `ConditionProblem`, `ConditionValidation`.
- `core/.../core/usecase/RecordCondition.kt`; `core/.../core/usecase/OperationalOffer.kt` — `operationalOfferFor(...)` and `AcceptOperationalOffer` (signatures under Interfaces).
- `core/.../core/usecase/HealthCommands.kt` — `HealthSubjectCommand`, `HealthPolicyCommand`, `HealthProblem`, `HealthValidation`, `HealthScheduleTaken`, `HealthSubjectIsPrimary`, `NoSuchHealthSubject`, `ScheduleDrivesHealthSubject`.
- `core/.../core/usecase/SaveHealthSubject.kt`, `ArchiveHealthSubject.kt`, `SetHealthPolicy.kt`, `SaveAssetSettings.kt`.
- Tests under `core/src/test/.../core/`: `condition/ConditionHistoryTest`, `usecase/RecordConditionTest`, `usecase/OperationalOfferTest`, `usecase/HealthSubjectCommandTest`, `usecase/HealthPolicyCommandTest`, `usecase/ScheduleHealthLinkGuardTest`, `usecase/SaveAssetSettingsTest`, `usecase/CrossConceptWriteTest`.

**Modify**

- `core/.../core/usecase/SaveSchedule.kt` — `run(id, cmd, unlinkHealthSubject: Boolean = false)`; the guard; constructor gains `HealthSubjectRepository` and the asset lookup it already has.
- `core/.../core/usecase/ArchiveSchedule.kt` — `run(id, archived, unlinkHealthSubject: Boolean = false)`; the guard.
- `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` — `recordCondition`, `acceptOperationalOffer`, `saveHealthSubject`, `archiveHealthSubject`, `setHealthPolicy`, `saveAssetSettings`; `saveSchedule` and `archiveSchedule` re-wired.
- `app/src/test/.../testing/FakeGraph.kt` — **the same six fields and the two re-wirings, mirrored** (master §1; B09, B10, B12 and B14 read them there).
- `core/src/test/.../testing/InMemoryRepositories.kt` — as the new use cases need.

**Untouched:** the engine (B02), the health engine (B05), `core/backup/**`, `core/merge/**`, `api/**` (B09 maps the refusals), every screen (B08, B10, B12 draw them), `PostponeSchedule.kt`, `PauseSchedule.kt`.

## Interfaces

**Consumes from B01:** `AssetCondition`, `OperationalCondition`, `HealthSubject` and its enums, `ConditionRepository`, `HealthSubjectRepository`. **From B04:** `SetSeasonMode`, `SetMaintenanceBreak`, the season refusals, `UpdateAsset`, `CreateAsset`. Shipped: `EventRepository`, `ProfileRepository`, `EventKind`.

**Produces** (for B07, B08, B09, B10, B12):

```kotlin
data class ConditionCommand(val condition: OperationalCondition, val occurredOn: String? = null,
    val occurredTime: String? = null, val tzId: String, val reason: String = "", val eventId: EventId? = null)
class RecordCondition { suspend fun run(assetId: AssetId, cmd: ConditionCommand): AssetCondition }
data class HealthSubjectCommand(val name: String, val kind: HealthSubjectKind, val driver: HealthDriver,
    val scheduleId: ScheduleId? = null, val baselineProfileId: ProfileId? = null,
    val nominalUntilDays: Int?, val warningFromDays: Int?, val criticalFromDays: Int?,
    val weight: Int = 1, val sortOrder: Int? = null)
class SaveHealthSubject {
    suspend fun create(assetId: AssetId, cmd: HealthSubjectCommand): HealthSubject
    suspend fun update(id: HealthSubjectId, cmd: HealthSubjectCommand): HealthSubject   // never changes asset
}
class ArchiveHealthSubject { suspend fun run(id: HealthSubjectId, archived: Boolean): HealthSubject }
data class HealthPolicyCommand(val healthAggregation: HealthAggregation, val healthPrimarySubjectId: HealthSubjectId? = null)
class SetHealthPolicy { suspend fun run(assetId: AssetId, cmd: HealthPolicyCommand): Asset }
data class AssetSettingsCommand(val asset: AssetCommand, val seasonMode: SeasonModeCommand,
    val maintenanceBreak: BreakCommand, val healthPolicy: HealthPolicyCommand)
class SaveAssetSettings { suspend fun run(id: AssetId?, cmd: AssetSettingsCommand): Asset }
fun operationalOfferFor(current: AssetCondition?, event: AssetEvent): Boolean
class AcceptOperationalOffer { suspend fun run(assetId: AssetId, event: AssetEvent): AssetCondition }
```

**Plan decision:** a subject's create and edit are two methods, so "a subject never changes asset" is unrepresentable in the domain; the API's PATCH has no `assetId` key (B09). The thresholds are nullable in the command **only** so that "missing" is a refusal with a code rather than a type error — no default exists anywhere.

### The rules

1. **`RecordCondition`** — `occurredOn` defaults to today; later than today → `CONDITION_DATE_IN_FUTURE`; `reason` trimmed, ≤ 500 characters (`CONDITION_REASON_TOO_LONG`), empty allowed; an `eventId` of another asset or none → `FOREIGN_EVENT`; `occurredTime` (`HH:MM`) and `tzId` in the shipped validation shape; a missing asset is the shipped `NoSuchAsset`. It writes **one row, no asset column, no recompute**.
2. **`ConditionHistory`** — master §9: order `(occurredOn, occurredTime nulls first, createdAt, id)`; `current` = the last; `since` = the first row of the last run of equal values; no rows → `current == null` (S4 on screen). Backdated rows sort into place; nothing is ever edited.
3. **The operational offer** — `operationalOfferFor` is true after a completion or a `MAINTENANCE` / `REPLACEMENT` event on an asset whose current condition is DOWN or DEGRADED; `AcceptOperationalOffer.run` writes OPERATIONAL dated `max(event date, current row's date)` with `eventId` set, through `RecordCondition`. Declining writes nothing.
4. **Subjects** — master §10.1's refusals, collected: name blank or over 60 after trim → `HEALTH_SUBJECT_NAME_REQUIRED` (**ruled, dec. 33:** the spec's one name code; the problem carries the 1–60 limit so B09's message states it and its `field` is `name`); thresholds missing or not `0 ≤ t1 < t2 < t3 ≤ 36500` → `HEALTH_THRESHOLDS_INVALID`; AGE with a schedule, or MAINTENANCE_OVERDUE without one or with a baseline profile → `HEALTH_DRIVER_MISMATCH`; the schedule absent, a group's, another asset's **or archived** → `FOREIGN_SCHEDULE` (master dec. 45, ruled: the spec's code set is closed; the message says "archived" for that case); meter-only → `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`; a baseline profile foreign or not REPLACEMENT → `PROFILE_NOT_A_REPLACEMENT`; weight outside 1–10 → `HEALTH_WEIGHT_OUT_OF_RANGE`. Then (409) the schedule already driving another non-archived subject → `HealthScheduleTaken`. Create appends `sortOrder` when none is given.
5. **Archive and restore** — archiving the asset's TRACK_ONE primary → 409 `HealthSubjectIsPrimary`. **Create, update and restore each re-run the whole link validation of rule 4** — the schedule exists, is this asset's, is asset-targeted, has a time rule, is not archived, and drives no other non-archived subject — so the path "archive a driving schedule with `unlinkHealthSubject`, then restore the subject" is refused (`FOREIGN_SCHEDULE`) and no non-archived subject ever names an archived, retargeted or rule-less schedule locally (spec §6.1's closing guarantee; dec. 17's NOT TRACKED stays merge-only). A race — an API or MCP write archiving, retargeting or de-ruling the schedule between the editor's check and its tap — still reaches `FOREIGN_SCHEDULE` or `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`; the use case writes nothing, and B10 reloads its picker and Restore state and draws no sentence.
6. **`SetHealthPolicy`** — TRACK_ONE needs a primary that is a non-archived subject of this asset; any other aggregation takes none; else `HEALTH_PRIMARY_INVALID`. Unchanged writes nothing.
7. **The link guard** (inv. 130) — while a non-archived subject names the schedule: a save that removes the time rule, or changes the target to another asset or to a group, or an archive, throws `ScheduleDrivesHealthSubject(subjectId, name)` **unless** `unlinkHealthSubject` is true, in which case the subject is archived **in the same transaction** — or, when that subject is the TRACK_ONE primary, `HealthSubjectIsPrimary` (master §20.14). The flag with nothing to unlink is a no-op. A deleted schedule cascades its subject (no route deletes one).
8. **`SaveAssetSettings`** — validates all four parts against the **final** state first (the strands rule compares the boundary kind before the save with the kind after mode **and** break both apply), then writes asset, mode, break and policy in one `uow.write`; any refusal writes nothing. The asset part keeps the stored `MM-DD` pair (the season-mode part is authoritative), so the legacy translation is a no-op here. Unchanged parts write nothing (B04's rule). `id == null` creates the asset, template seeding included, in the same transaction.

## Invariants this brief must hold

**Accountable** (master §14): **81, 83, 86** (`CrossConceptWriteTest`), **107, 108, 110, 120, 121** (thresholds; templates are B10's UI), **130**. **Halves:** **82** (no configuration use case writes a health value), **126** (condition writes never touch the asset row).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| current or since wrong | `ConditionHistoryTest` · `currentIsTheLatestByTheOrderingKey` (a null time sorts first; `createdAt` breaks a same-day tie), `sinceIsTheStartOfTheLatestRun`, `aBackdatedCorrectionSortsIntoPlace`, `noRowIsNotRecorded` | nulls last |
| the write does more | `RecordConditionTest` · `writesOneRowAndNothingElse` (recording store: no asset column, no recompute) | bump the asset's `updatedAt` |
| refusals | `RecordConditionTest` · `aFutureDateTooLongAReasonAndAForeignEventAreRefused` (empty reason accepted; exactly 500 accepted) | `<` for `≤` on 500 |
| back to operational | `RecordConditionTest` · `returningToOperationalInsertsOneRowAndLeavesEarlierRowsByteIdentical` (inv. 110) | overwrite the DOWN row |
| the offer applies itself | `OperationalOfferTest` · `anOfferOnlyAfterCompletionMaintenanceOrReplacementOnDownOrDegraded`, `acceptingDatesTheRowMaxOfEventAndCurrentAndLinksTheEvent`, `decliningWritesNothing` | write on the predicate |
| a subject stored wrong | `HealthSubjectCommandTest` · one case per refusal of rule 4, each naming its code; `thresholdsAreRequiredWithNoDefault` (inv. 121) | give the three thresholds a default |
| a subject names an archived schedule (I8) | `HealthSubjectCommandTest` · `anArchivedScheduleCannotBeLinked` (create and update refused with `FOREIGN_SCHEDULE`, the message naming it archived) and `restoreRechecksTheWholeLink` (archive a driving schedule with the flag, restore the subject: refused; a retargeted and a de-ruled schedule likewise) | re-check only `HealthScheduleTaken` on restore |
| a schedule drives two subjects | `HealthSubjectCommandTest` · `aScheduleDrivesAtMostOneNonArchivedSubject` (create, edit and restore; an archived subject does not hold it; inv. 120) | ignore archived status the wrong way |
| a subject changes asset | `HealthSubjectCommandTest` · `anEditKeepsTheAsset` | copy an asset id from the command |
| the primary | `HealthPolicyCommandTest` · `trackOneNeedsANonArchivedPrimaryOfThisAsset`, `archivingThePrimaryIs409` | allow an archived primary |
| **the link guard** | `ScheduleHealthLinkGuardTest` · `removingTheTimeRuleRetargetingOrArchivingIsRefused`, `theFlagArchivesTheSubjectInTheSameTransaction` (a later failure in the same write leaves both rows untouched), `theFlagOnThePrimaryIs409`, `theFlagWithNothingToUnlinkIsANoOp`, `aTitleOrPolicyEditIsNotGuarded` | guard only the archive |
| the editor save is partial | `SaveAssetSettingsTest` · `allFourPartsWriteInOneTransactionOrNone` (a strands refusal from the break leaves the asset, mode and policy unchanged), `strandsIsJudgedOnTheCombinedResult`, `anUnchangedSaveWritesNothing`, `aCreateWithManualWritesItsFirstRow` | apply the asset part before validating the break |
| **a use case writes across concepts** | `CrossConceptWriteTest` · `eachUseCaseWritesOnlyItsOwnTables` — against a recording store: `RecordCondition` and `AcceptOperationalOffer` → `asset_condition` only; `RecordSeasonActivation` and `AcceptSeasonOffer` → the activation plus derived state; `SetSeasonMode` → asset, one activation on a switch into MANUAL, derived state; `SetMaintenanceBreak` → asset, derived state; `SetHealthPolicy` → asset; the subject use cases → `health_subject`; the guarded schedule writes → schedule, subject, derived state; **none** writes `asset_event` or `occurrence_closure`, and only the two condition writers write `asset_condition` (inv. 81–83, 86) | let `SetSeasonMode` write a SEASON_START event |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` → success. No connected run.
- Anchored: `grep -rnE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?.?asset_condition\b' app/src/main core/src/main` → no output; `grep -rnE 'fun (delete|remove)' core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/HealthCommands.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/RecordCondition.kt` → no output; `git diff --stat <base> -- core/src/main/kotlin/com/loosecannon/servicetag/core/schedule core/src/main/kotlin/com/loosecannon/servicetag/core/health app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui` → empty.

## Strings

**None user-visible.** Refusals carry codes and data; B10 and B12 draw S125, S126, S135, S137, S140 and S141, and S19/S20 for the offer.

## Must NOT

- derive, default or infer a condition, or store the current one in a column;
- edit or delete a condition row;
- default a threshold, or fill one from a template (templates are B10's, after confirmation);
- let a schedule edit archive, retarget or de-rule a driving schedule silently;
- let a subject change asset;
- write a condition from a completion, an event, a scan, a status or health;
- change the engine or a screen.

## Size

Medium: seven small use cases, one pure history, two guarded edits, one composite. No split expected; if needed, **condition** (rules 1–3) and **health configuration** (rules 4–8) are independent halves.
