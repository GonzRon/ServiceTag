# B09 — the `/v1` surface: seasons, condition, health, attention and the two schedule forms

**Read first:** master plan §1, §4 (the mapping and its golden file), §11 (the whole `/v1` contract), §14, §15.
**Spec:** §9.1, §9.2, §9.3, §8.3 (the response exception), §4.1, §12.1 rows 125 and 128; `docs/api/v1.md`.
**Wave 6, lane A,** beside B10. **After B03, B04, B06 and B07.** Gates B11.

## Goal

Put 1.4 behind the loopback API **at version 1, compatibly**. Fourteen new method-and-path rows (master §11.1), the attention list, the widened schedule state, `/v1/due` and `/v1/status`, format 8 on import, and the schedule command's **two forms** — the 1.4 form by key presence, the legacy form translated only through `LegacySeasonMapping`, refused by name where it cannot represent 1.4 state and 422 when mixed — so a 1.3 client keeps working on every row it can describe and gets a named 422 on the rest, never a silent reset. Every write route calls exactly one use case; every read route reads a read model; nothing destructive is added. The contract document and the golden command shapes are kept honest in the same change.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/api/SeasonHealthDtos.kt` — the request shapes (`SeasonModeRequest`, `BreakRequest`, `ActivationRequest`, `ConditionRequest`, `HealthPolicyRequest`, `HealthSubjectCreateRequest`, `HealthSubjectUpdateRequest`), the responses (`SeasonResponse`, `ConditionsResponse`, `HealthResponse` and its nested shapes, `SubjectListResponse`, `SubjectResponse`, `AttentionResponse`). `ScheduleRowResponse` is **B03's** (wave 2) and is consumed here, not created.
- `app/.../api/SeasonHealthHandlers.kt` — `SeasonHealthHandlers(graph)`, reached as `handlers.seasonHealth.*`.
- `app/.../api/ScheduleForms.kt` — the key-presence classifier and the legacy translation (rules below).
- `docs/api/command-shapes.json` — master §11.6's content.
- Tests under `app/src/test/kotlin/com/loosecannon/servicetag/api/`: `SeasonHealthRoutesTest`, `LegacyFormTest`, `LegacyMappingAgreementTest`, `CommandShapesGoldenTest`, `AttentionAndHealthShapeTest`, `ApiReadsWriteNothingTest`.

**Modify**

- `app/.../api/ApiRouter.kt` — the new path shapes in the explicit `when`; the class KDoc's route arithmetic.
- `app/.../api/ApiHandlers.kt` — `seasonHealth` collaborator; `/v1/status` counts `seasonActivations`, `assetConditions`, `healthSubjects`; the asset PATCH surfaces B04's legacy-pair refusals.
- `app/.../api/MaintenanceDtos.kt` — `ScheduleCommandRequest` gains `servicePolicy`, `policyOffsetDays`; B01's interim translation replaced by `ScheduleForms`; `ScheduleStateDto` gains `actionableDueOn`, `policyReason`, `policyPhase`, `quiet`; `DueItemDto` gains `actionableDueOn`, `policyReason`, `quiet`. The response side (`ScheduleRowResponse` in every schedule-bearing response) already landed with B03.
- `app/.../api/MaintenanceHandlers.kt` — create and PATCH through `ScheduleForms`; the `unlinkHealthSubject` flag on PATCH and archive; `GET /v1/schedules/{id}` state through `readState`.
- `app/.../api/ApiJson.kt` — `ApiErrorDetail.field: String? = null`; every new refusal mapped to its code (master §11.5) and `field` (master §11.3, `HEALTH_SUBJECT_NAME_REQUIRED` → `name` included); that code's message states the 1–60 limit, so an over-long name is not read as a missing one (ruled, dec. 33); an archived schedule's `FOREIGN_SCHEDULE` message says archived (dec. 45).
- `app/.../api/ApiDtos.kt` — the merge report mirror's three tallies.
- `docs/api/v1.md` — see "The document".
- `app/src/test/.../api/ApiRouterTest.kt` (`theDestructiveUseCasesHaveNoRoute`), `MaintenanceRoutesTest.kt` — extended; `MaintenanceCommandShapeTest.kt` — **B03's temporary subtraction of `servicePolicy` and `policyOffsetDays` is removed**, because the command now carries both.

**Untouched:** `core/**` main sources (every rule is a use case's, called); `ui/**`; `data/**`; `di/AppGraph.kt` (the handlers read graph fields through their `(graph)` constructors); `tools/**` (B11); `libs/`.

## Interfaces

**Consumes:** the use cases of B04 (`SetSeasonMode`, `SetMaintenanceBreak`, `RecordSeasonActivation`, `GetAssetSeason`, `UpdateAsset`'s pair refusals, `SaveSchedule` policy refusals), B06 (`RecordCondition`, `ConditionHistory`, `SaveHealthSubject`, `ArchiveHealthSubject`, `SetHealthPolicy`, the guarded `SaveSchedule`/`ArchiveSchedule`), B07 (`DueReadModel`, `AttentionReadModel`, `AssetHealthReadModel`, `readState`), B03 (the DTOs, format 8, the merge tallies, **`ScheduleRowResponse`**), B01 (`LegacySeasonMapping`, the golden file). The `FakeGraph` mirrors of every use case above are the producing briefs' (master §1); this brief adds none.

**Produces:** the wire contract of master §11 and `docs/api/command-shapes.json`. Consumers: B11, the owner's MCP client, the release proofs.

### Route → use case (no handler re-implements a rule)

| route | calls |
|---|---|
| `GET /v1/assets/{id}/season` | `GetAssetSeason` |
| `POST /v1/assets/{id}/season` | `RecordSeasonActivation`, then `GetAssetSeason` |
| `POST …/season-mode` · `…/maintenance-break` · `…/health-policy` | `SetSeasonMode` · `SetMaintenanceBreak` · `SetHealthPolicy` |
| `GET` / `POST …/conditions` | `ConditionRepository` + `ConditionHistory` / `RecordCondition` |
| `GET …/health` | `AssetHealthReadModel.forAsset` |
| `GET …/health-subjects`, `GET /v1/health-subjects/{id}` | `HealthSubjectRepository` |
| `POST /v1/health-subjects` · `PATCH …/{id}` · `POST …/{id}/archive` | `SaveHealthSubject.create` · `.update` · `ArchiveHealthSubject` |
| `GET /v1/attention` | `AttentionReadModel.items` |
| `POST`/`PATCH /v1/schedules[/{id}]`, `POST …/archive` | `ScheduleForms` → `SaveSchedule` / `ArchiveSchedule` (with the flag) |

### The two schedule forms (`ScheduleForms`, spec §9.3)

1. **Classify from the raw JSON object before the typed decode:** a `servicePolicy` or `policyOffsetDays` key (an explicit `null` counts) → 1.4 form; none → legacy form; a legacy key (`seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays`) **and** a 1.4 key → 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`, nothing written.
2. **1.4 form:** `servicePolicy` omitted → CONTINUOUS; `policyOffsetDays` omitted **or null** → 0 when the policy is IN_SERVICE_AT_START (spec §4.2: "0 by default"; **Plan decision:** a cleared value takes the default, as v1's full replace clears to it), else null; the use case refuses the rest (PRE_SERVICE with no offset is `POLICY_OFFSET_INVALID`).
3. **Legacy form:** 1.3's defaults (`seasonBehavior` omitted → IGNORE), then `LegacySeasonMapping.toPolicy(..., hasTimeRule = timeInterval != null)` — the **only** translation. A PATCH whose stored schedule is PRE_SERVICE → 422 `LEGACY_WRITE_CANNOT_REPRESENT`, nothing written; any other legacy body translates (a create clobbers nothing). The one documented change: a legacy body omitting `seasonReentry` on a RESUME_CLAMPED schedule becomes AT_START.
4. **Responses** carry B03's `ScheduleRowResponse` — the format-8 row plus `seasonBehavior` (nullable), `seasonReentry`, `seasonReentryOffsetDays` from `LegacySeasonMapping.toLegacy` (master §11.3; all null for PRE_SERVICE). `ruleChangedAt` is response-only.

### Codes (master §11.5; the 422/409 tie-break)

- **422:** `LEGACY_WRITE_CANNOT_REPRESENT`, `LEGACY_AND_CURRENT_FIELDS_MIXED`, `SEASON_POLICY_NEEDS_A_TIME_RULE`, `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, `POLICY_OFFSET_INVALID`, `SEASON_WINDOW_REQUIRED`, `SEASON_WINDOW_FORBIDDEN`, `MANUAL_PHASE_REQUIRED`, `MANUAL_PHASE_FORBIDDEN`, `SEASON_DATE_OUT_OF_RANGE`, `BLACKOUT_COVERS_THE_YEAR`, `CONDITION_DATE_IN_FUTURE`, `CONDITION_REASON_TOO_LONG`, `FOREIGN_EVENT`, `SCHEDULE_DRIVES_HEALTH_SUBJECT`, `HEALTH_SUBJECT_NAME_REQUIRED`, `HEALTH_THRESHOLDS_INVALID`, `HEALTH_DRIVER_MISMATCH`, `FOREIGN_SCHEDULE`, `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`, `PROFILE_NOT_A_REPLACEMENT`, `HEALTH_WEIGHT_OUT_OF_RANGE`, `HEALTH_PRIMARY_INVALID`; a malformed `MM-DD`, `occurredTime` or `tzId` keeps the shipped validation shape.
- **409:** `SEASON_NOT_MANUAL`, `SEASON_ALREADY_STARTED`, `SEASON_ALREADY_ENDED`, `SEASON_MODE_STRANDS_POLICY`, `BREAK_STRANDS_POLICY` (each naming the schedules in `problems`), `PRE_SERVICE_NEEDS_DATES`, `HEALTH_SCHEDULE_TAKEN`, `HEALTH_SUBJECT_IS_PRIMARY`.
- **404:** `NO_SUCH_HEALTH_SUBJECT`, and the shipped `no_such_asset`. `/v1/health-subjects/{id}/archive` answers 404 for a wrong verb; `/v1/health-subjects`, `/v1/health-subjects/{id}` and `/v1/attention` answer 405; each new `/v1/assets/{id}/…` sub-resource answers 404.

### The document (`docs/api/v1.md`)

The new rows and shapes; the codes table for 1.4 with the tie-break stated once; a **"Deprecated inputs"** section (the two forms, the presence rule, the mixed refusal, the two `LEGACY_WRITE_CANNOT_REPRESENT` cases, the RESUME_CLAMPED omission, the nullable `seasonBehavior`); `actionableDueOn` beside `effectiveDueOn` (whose meaning is unchanged); `DEFERRED`; **the postpone/snooze health note** (a postponement may restart maintenance-overdue health from the new actionable date, intended; a snooze never changes health — spec §9.1, O-6); the schedule response's one exception to "a response row is the archive row"; "What has no endpoint" extended (amending or deleting a condition or activation, deleting a health subject, writing a health value); counts; formats **1–8**; the report's **fourteen** tables.

## Invariants this brief must hold

**81** (half: only `POST …/conditions` writes a condition), **89** (half), **105** (API half), **106** (half: both forms), **119** (half: the health response lists criticals and components), **125** (half: the agreement test), **127** (accountable), **128** (accountable), **129** (half: no route this brief adds names an installed component or assembly), **130** (half: the flag on the wire).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| status classes | `SeasonHealthRoutesTest` · `oneOfEach` (200, 201, 404, 405, 409, 422 over the new surface) | answer 200 on a create |
| **every named refusal** | `SeasonHealthRoutesTest` · one case per code above, asserting status **and** code (strands cases also name the schedules; `field` set per master §11.3; the `HEALTH_SUBJECT_NAME_REQUIRED` case asserts `field == "name"` and a message stating the 1–60 limit, for a blank and a 61-character name — dec. 33; the archived-schedule `FOREIGN_SCHEDULE` case's message says archived — dec. 45) | map `BREAK_STRANDS_POLICY` to 422 |
| 404 / 405 / 415 | `SeasonHealthRoutesTest` · `wrongVerbsAndTypesOverTheNewPaths` (one parameterised case) | route `DELETE` on a sub-resource |
| unknown fields | `SeasonHealthRoutesTest` · `everyNewCommandRejectsAnUnknownFieldByName`, `aSubjectPatchNamingAssetIdIs400` | `ignoreUnknownKeys` |
| **the presence rule** | `LegacyFormTest` · `aPolicyKeyOrExplicitNullMakesThe14Form`, `neitherIsTheLegacyFormWith1_3Defaults`, `aMixedBodyIs422AndWritesNothing` | classify after the typed decode |
| **refused, never reset** | `LegacyFormTest` · `aLegacyPatchOnPreServiceIs422AndWritesNothing`, `aLegacyPairChangeOnAManualAssetIs422AndWritesNothing`, `anEqualPairOnAManualAssetIsAccepted` (inv. 128) | reset PRE_SERVICE to CONTINUOUS |
| the documented change | `LegacyFormTest` · `omittingSeasonReentryOnResumeClampedBecomesAtStart` | keep RESUME_CLAMPED |
| the 1.4 defaults | `LegacyFormTest` · `anOmittedOffsetIsZeroForAtStartAnd422ForPreService` | default PRE_SERVICE to −14 |
| the compatibility triple | `LegacyFormTest` · `everyRepresentableRowRoundTripsThroughItsTriple`, `aPreServiceRowReadsNulls` | reverse CONTINUOUS to null |
| **the four paths agree** | `LegacyMappingAgreementTest` · `everyGoldenCaseAgreesAcrossMigrationDecoderAndApi` (each case of `docs/api/legacy-season-mapping.json` through `MIGRATION_7_8` on a v7 row, a format-7 archive decode, and a legacy-form `POST /v1/schedules`: one policy and offset) | a private copy of the table in `ScheduleForms` that maps `MM-DD` re-entries to CONTINUOUS |
| group targets | `LegacyFormTest` · `aGroupTargetIsContinuousOnlyInBothForms` (inv. 106) | skip the check in the 1.4 form |
| the link guard on the wire | `SeasonHealthRoutesTest` · `unlinkHealthSubjectOnPatchAndArchive` | ignore the flag |
| shapes | `AttentionAndHealthShapeTest` · `attentionItemsCarryEveryFieldAndADenseRank`, `theHealthResponseListsCriticalsAndComponentsWithItsCondition` (inv. 119), `theSeasonResponse`, `theConditionsResponseOrderAndCurrent` | drop `parentAssetId` when null |
| derived state | `MaintenanceRoutesTest` · `stateAndDueCarryTheActionableFieldsAndDeferred`, `seasonActiveIsDerivedFromThePhase` | keep `seasonActive` from a column |
| status and import | `MaintenanceRoutesTest` · `statusReports8And8AndThreeNewCounts`, `importMergeReadsFormat8AndReportsFourteenTables` | forget `healthSubjects` |
| reads write | `ApiReadsWriteNothingTest` · `theReadRoutesWriteNothingOverStaleState` (`/v1/due`, `/v1/attention`, `…/health`, `/v1/schedules/{id}`, `…/season`) | persist a derived row |
| a write crosses concepts | `ApiReadsWriteNothingTest` · `eachWriteRouteWritesOnlyItsUseCasesTables` | write a condition from `season-mode` |
| **something destructive** | `ApiRouterTest` · `theDestructiveUseCasesHaveNoRoute` (+ `DELETE`/`PATCH …/conditions`, `DELETE …/season`, `DELETE /v1/health-subjects/{id}`, `POST …/health`) | route a condition delete |
| golden shapes | `CommandShapesGoldenTest` · `everyRequestDtoMatchesCommandShapesJson` | add a field without the file |
| the document drifts | `CommandShapesGoldenTest` · `theContractDocumentNamesFormat8AndFourteenTables` | leave "1–7" |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- Anchored: inv. 129: `git diff <base> -- app/src/main/kotlin/com/loosecannon/servicetag/api | grep -niE '^\+.*\b(assembly|assemblies|installed_?component|stock)\b'` → no output; `grep -c '1–8' docs/api/v1.md` → ≥ 2; `grep -cE '(format \*\*1–7\*\*|\*\*format 1–7\*\*)' docs/api/v1.md` → 0 (the two shipped spellings of the import range); `grep -ci 'the eleven tables' docs/api/v1.md` → 0; `grep -cE '"DELETE"' app/src/main/kotlin/com/loosecannon/servicetag/api/ApiRouter.kt` → 1 (the shipped event delete, and nothing new); `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/data app/src/main/kotlin/com/loosecannon/servicetag/di tools libs` → empty.
- No device run (the live proof is the controller's, master §17).

## Strings

**None user-visible.** Codes, field names and messages are contract; messages never quote a ratified sentence as if it were an API string.

## Must NOT

- translate a legacy input anywhere but through `LegacySeasonMapping`, or reset a 1.4-only field;
- accept a mixed body, or let an explicit `null` mean "absent";
- add a route that amends or deletes a condition or activation, deletes a subject or writes a health value;
- write from a read route, or re-implement a use case's rule in a handler;
- touch `:core`, a screen or `AppGraph`.

## Size

Large: fourteen rows, two forms, one document. Split seam if one review cannot hold it: **B09a** the schedule forms, the widened schedule/due/status shapes and the agreement test; **B09b** the season, condition, health and attention routes; the document is edited by whichever lands second.

## Carry-forward from B03 (controller, 2026-09-24)

- `MergeReport`'s three new tallies (tables 12–14) must reach the wire: `MergeReportResponse` mirrors them in write order and `/v1/status` gains their count keys; `theMergeReportWireMirrorCarriesEveryTallyInWriteOrder` pins fourteen, not eleven (RED: leave one out).

## Carry-forward from B03's review (controller, 2026-09-24)

- `/v1` asset responses already carry the five new asset fields since B01/B03; this brief documents them in `docs/api/v1.md` beside the deprecated season inputs and pins them in the asset-response shape test.
