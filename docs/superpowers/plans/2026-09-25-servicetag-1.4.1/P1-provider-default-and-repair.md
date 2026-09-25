# P1 — the provider default and the repair (#80, prevention and existing data)

**Read first:** plan.md §1–§3, §5, §6, §8; `rulings.md` R2–R5 and the architecture ruling; #80's body (Owner ruling: API create default; Prevention 1–4; Existing-data repair; acceptance 1–10).
**Lane:** A, wave 1, branched from master `a8717c2`; P3 runs beside it and shares no file.
**Blocked on:** nothing — this brief adds no user-visible string.

## Goal

**Prevention (R4).** A `/v1` create that omits `providers` stores what the app's editor would have stored: one `LOCAL` row with `enabled = remindersEnabled`. An explicit `[]` stays empty; an explicit list is validated as today; PATCH keeps its full-replace omission semantics. The MCP `create_schedule` and the Stage-B loader send the LOCAL row explicitly instead of relying on the server.

**Repair (R2, R3, R5).** One canonical use case, `RepairScheduleProviders`, over the non-archived rows: plan (writes nothing) and apply (re-plans, then adds exactly `LOCAL enabled=true` to every ACTIVE, reminders-on, providerless schedule, moving nothing but `providers` and `updatedAt`). Exposed as two `/v1` routes and one MCP tool with a plan-by-default. P2 will call the same use case from Reminder Health; nothing in this brief draws a screen.

## Files

**Create**
- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/RepairScheduleProviders.kt` — plan.md §3 verbatim.
- `core/src/test/kotlin/com/loosecannon/servicetag/core/usecase/RepairScheduleProvidersTest.kt`.

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/api/MaintenanceDtos.kt` (`ScheduleCommandRequest.providers` at `:298` becomes `List<ScheduleProviderRequest>? = null`; `toCommand` at `:329` learns whether it is a create), `ScheduleForms.kt` (only if the create/update fact travels through `Parsed`), `MaintenanceHandlers.kt` (`createSchedule` `:167-171`, `updateSchedule` `:180-184`; the two repair handlers, here or in a new `RepairHandlers.kt`), `ApiRouter.kt` (two path constants beside `IMPORT_MERGE_PLAN_PATH` `:10` and the dispatch), `ApiDtos.kt` or `MaintenanceDtos.kt` (the two response DTOs), `ApiHandlers.kt` (construction), `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` (`val repairScheduleProviders` after `saveSchedule` `:542-544`, built from `schedules`, `uow`, `clock`).
- `docs/api/v1.md`: the intro (`:1-6`, "1.4.1 adds…" one clause), the endpoints table (two rows in a new `### Repairs (1.4.1)` subsection after `### Deprecated inputs (1.4.0)` `:630`), the schedule body paragraph (`:401-412`, the create-default sentence), nothing under `## Errors`.
- `tools/servicetag-mcp/src/servicetag_mcp/server.py` (`create_schedule` `:1308-1390`; the new tool beside `import_merge` `:1021`), `tools/servicetag-mcp/README.md` (the tool inventory and the "needs 1.4.1 or later" line), tests `test_maintenance_tools.py`, `test_argument_guard.py` (`:51`, 55 → 56), `test_command_shapes.py` if it pins a create body.
- `tools/servicetag-schedules/src/servicetag_schedules/apply.py` (`:95-105`), tests `test_apply.py`, `test_plan.py`.
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/api/MaintenanceRoutesTest.kt`, `CommandShapesGoldenTest.kt` if it pins the request shape, `core/src/test/kotlin/com/loosecannon/servicetag/core/reminders/BuildReminderSubjectsTest.kt`, the backup codec's round-trip test class under `core/src/test/.../backup/`.

**Untouched:** `ScheduleCommands.kt`, `SaveSchedule.kt`, `MergePlanner.kt`, `BackupFormat.kt`, `Migrations.kt`, `MaintenanceEntities.kt`, `ReminderHealthCheck.kt`, everything under `app/…/ui/`, the manifest, `tools/servicetag-bundle/`.

## Interfaces

The use case is plan.md §3, names binding. The adapters:

```kotlin
// MaintenanceDtos.kt
val providers: List<ScheduleProviderRequest>? = null                 // was `= emptyList()`
fun ScheduleCommandRequest.toCommand(form: …, creating: Boolean): ScheduleCommand
//   creating && providers == null  -> listOf(ScheduleProviderRow(ProviderId.LOCAL.name, enabled = remindersEnabled))
//   !creating && providers == null -> emptyList()                      // PATCH: unchanged full replace
//   else                           -> providers.map { ScheduleProviderRow(it.provider, it.enabled) }

@Serializable data class ProviderRepairResponse(val matched: Int, val repairable: Int, val skipped: Int, val repaired: Int, val schedules: List<ProviderRepairRowResponse>)
@Serializable data class ProviderRepairRowResponse(val id: String, val title: String, val outcome: String, val reason: String?)
```

```python
# server.py — create_schedule, before _body(...):
#   if providers is None: providers = [{"provider": "LOCAL", "enabled": True if reminders_enabled is None else reminders_enabled}]
@mcp.tool()
def repair_schedule_providers(plan_only: bool = True) -> dict[str, Any]: ...
```

```python
# apply.py:95-105 — one more key in the create args:
#   "providers": [{"provider": "LOCAL", "enabled": schedule.reminders_enabled}]
```

## Contracts

- **Create truth table** (`POST /v1/schedules`): key absent → `[LOCAL, enabled = remindersEnabled]` for both values of `remindersEnabled` (and for its own default, `true`); `"providers": null` → the same; `[]` → no row; a list → that list, `UNKNOWN_PROVIDER` as today. **PATCH:** absent or `null` → `[]`, byte-for-byte today's behaviour, pinned by a test.
- **Decoding:** `ApiJson` (`ApiJson.kt:81-85`) leaves `explicitNulls` at its default, so a nullable field with a `null` default decodes an absent key and a `null` literal alike; the test proves both.
- **Routes:** `POST /v1/repairs/schedule-providers/plan` and `/apply`, body `{}`; 200 with `ProviderRepairResponse`; a non-POST is the router's 405; a wrong content type its 415; a body with any key is the existing malformed-body refusal (`ignoreUnknownKeys = false`). `plan` performs no write (a read-only proof as `ApiReadsWriteNothingTest` does it). `apply` calls `RepairScheduleProviders.apply()` once and reports it. Neither runs a reminder sweep (plan.md §3).
- **The use case** exactly as plan.md §3: universe `listedForDue()`; matched = reminders on with nothing enabled; repairable = ACTIVE and empty; skip reasons `PAUSED` then `PROVIDERS_DISABLED`; apply re-plans inside `uow`, writes through `schedules.upsert(row.copy(providers = …, updatedAt = clock.nowMillis()))`, in (title, id) order, and returns the report; idempotent.
- **MCP:** `repair_schedule_providers()` posts the plan; `plan_only=False` posts the apply; the JSON comes back unchanged; any error is a `ToolError` with the API's `code`/`message` as the other tools raise it; the argument guard publishes `additionalProperties: false`. `create_schedule` documents `providers=[]` as the way to store no provider.
- **Loader:** the args to `create_schedule` carry the LOCAL row derived from the manifest's `remindersEnabled`; the manifest's key set (`manifest.py:35-38`) is unchanged; `plan.py`'s comparison tuple (`:146-160`) stays provider-blind so a re-plan is IDENTICAL against a providerless phone and a repaired one alike.
- **Docs, `v1.md`:** the create-default sentence under the schedule body: *Omitted `providers` on a create (1.4.1): the row gets one `LOCAL` provider with `enabled` equal to `remindersEnabled` — what the app's editor writes; send `"providers": []` to store none. On a PATCH an omitted `providers` still means an empty set: a full replace replaces everything.* The repairs subsection: the two routes, the body, the response fields, the predicate in words, "plan writes nothing; apply re-plans and writes; a second apply repairs nothing", and that neither route runs a reminder sweep.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| archived rows repaired | `RepairScheduleProvidersTest` · `theUniverseIsTheNonArchivedRows`: an ARCHIVED providerless row is in no entry | drop `listedForDue()` |
| the predicate | `RepairScheduleProvidersTest` · `thePredicateMatrix`: ACTIVE+on+empty → repairable; PAUSED+on+empty → `PAUSED`; ACTIVE+on+`[LOCAL disabled]` → `PROVIDERS_DISABLED`; PAUSED+on+`[LOCAL disabled]` → `PAUSED`; ACTIVE+off+empty → absent; ACTIVE+on+`[LOCAL enabled]` → absent | flip any branch |
| apply moves more than it should | `RepairScheduleProvidersTest` · `applyChangesOnlyProvidersAndUpdatedAt`: `after == before.copy(providers = listOf(LOCAL enabled), updatedAt = after.updatedAt)` and `after.updatedAt == clock`; a row with a profile, a postponement and a non-zero `ruleChangedAt` | copy with any other field |
| not idempotent | `RepairScheduleProvidersTest` · `applyThenPlanIsEmpty`, and a second apply writes nothing (write count) | — |
| a plan that writes | `RepairScheduleProvidersTest` · `planWritesNothing` (a recording repository) | write in `plan` |
| writes outside the transaction | `RepairScheduleProvidersTest` · `applyWritesInsideOneUnitOfWork` (a recording `UnitOfWork`; every upsert inside one `transaction`) | write outside `uow` |
| order | `RepairScheduleProvidersTest` · `entriesAreOrderedByTitleThenId` | — |
| still not a subject | `BuildReminderSubjectsTest` · `aRepairedRowIsALocalSubject`: before the repair absent, after present | — |
| restore re-synthesises | the codec test · `aRepairedRowRoundTripsItsLocalRow`; and `aProviderlessRowStaysProviderless` | — |
| the create default | `MaintenanceRoutesTest` · `createWithoutProvidersStoresLocalFromRemindersEnabled` (true and false), `createWithNullProvidersDoesTheSame`, `createWithEmptyProvidersStoresNone`, `createWithAListKeepsIt` | restore `= emptyList()` |
| PATCH drifts | `MaintenanceRoutesTest` · `patchWithoutProvidersStillReplacesWithNone` (pins today) | derive on PATCH |
| the routes | `MaintenanceRoutesTest` · `theRepairPlanWritesNothingAndCounts` (three shapes seeded; `43/…` counts by shape; a GET afterwards unchanged), `theRepairApplyThenReplanIsZero`, `theRepairRefusesABodyWithKeys`, `theRepairRoutesAreDocumented` (the router's documented-routes check) | — |
| MCP default | `test_maintenance_tools.py` · `create_schedule_sends_local_by_default`, `…_follows_reminders_enabled_false`, `…_sends_an_explicit_empty_list` | drop the default |
| MCP repair | `test_maintenance_tools.py` · `repair_schedule_providers_plans_by_default`, `…_applies_when_asked`, `…_raises_tool_error` ; `test_argument_guard.py` 56 | — |
| loader | `test_apply.py` · the args carry `providers` for both `remindersEnabled` values; `test_plan.py` · a phone row with `providers: []` and one with LOCAL both plan IDENTICAL, `updatedAt` never read | drop the key |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain`: zero failures, zero skips; the report names the counts before and after.
- `uv run --frozen pytest` in `tools/servicetag-mcp` and in `tools/servicetag-schedules`.
- Anchored greps: `git grep -nE 'RepairAction\.Automatic\(' -- core app/src/main` → the same lines as at the base; `git grep -nE '"/v1/repairs/schedule-providers/(plan|apply)"' app/src/main` → 2; `grep -cE '^@mcp.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → 56; `git grep -n providers -- tools/servicetag-schedules/src` → the one `apply.py` line.
- `git diff <base> -- core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ScheduleCommands.kt core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/SaveSchedule.kt core/src/main/kotlin/com/loosecannon/servicetag/core/merge core/src/main/kotlin/com/loosecannon/servicetag/core/backup app/src/main/kotlin/com/loosecannon/servicetag/data app/src/main/kotlin/com/loosecannon/servicetag/reminders app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/AndroidManifest.xml` → empty.
- No connected class is needed (no `ui/` or `reminders/` change); no device at all.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Strings

None. Nothing in this brief is drawn.

## Must NOT

- change PATCH omission semantics, the core command's defaults, or any validation;
- make the repair a `RepairAction.Automatic`, or call `RecomputeSchedules`, a reminder sweep or raw SQL from the use case;
- add a manifest key, a schema change, a DTO field in the backup format, or a merge rule;
- touch a phone, or any device;
- add a user-visible string.

## Review focus

The absent / `null` / `[]` distinction surviving the decoder; the apply's re-plan inside the same unit of work as its writes; the `copy` carrying every field of `MaintenanceSchedule` (`Maintenance.kt:101-132`); the MCP default never diverging from the server's; the plan route provably read-only.

## Size

Medium to large: one use case, two routes, one MCP tool, three one-line adapters, docs, and the matrix.
