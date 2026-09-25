# B11 — the MCP tools and the schedules loader, in lockstep

**Read first:** master plan §1, §4 (the mapping and its golden file), §11 (the wire contract), §12 (this brief's contract), §14.
**Spec:** §9.3 ("MCP (RS-1)", "Lockstep components"), §9.4, Appendix A ("MCP", "Loader"); `tools/servicetag-mcp/README.md`, `tools/servicetag-schedules/README.md`.
**Wave 7, lane A,** beside B12. **After B09** (the wire and `docs/api/command-shapes.json` exist).

## Goal

Ship the workstation half of 1.4 with the app, so no version pairing silently loses state. The MCP server gains **fourteen tools** over B09's routes (fifty-five in all), keeps the three season arguments of `create_schedule` and `update_schedule` as **deprecated arguments** that the API translates and refuses by the same codes, refuses to mix them with the new ones before any HTTP, and stops enumerating fields in its two overlay editors — `update_schedule` and `update_asset` overlay **every command key** the golden shapes file lists. A 1.4 server refuses to write to an app whose schema is older than 8. The Stage-B loader keeps writing `season_behavior` through the deprecated argument and now compares a manifest's legacy value, mapped by spec §4.1, with the row's `servicePolicy`, so the loaded manifest keeps re-planning **IDENTICAL**.

## Files

**Modify (MCP)**

- `tools/servicetag-mcp/src/servicetag_mcp/server.py` — the fourteen tools; `create_schedule` / `update_schedule` / `archive_schedule` changes; `update_asset` and `update_schedule` driven by the vendored key lists; `_SCHEDULE_NULLABLE_CLEARABLE` gains `policy_offset_days` (keeping `season_reentry`, `season_reentry_offset_days`); `TOOL_NAMES` and `_forbid_unknown_arguments(expected_count=55)`; the schema check; the `get_schedule`/`list_due` docstrings name `DEFERRED` and the new state fields.
- `tools/servicetag-mcp/src/servicetag_mcp/command_shapes.py` (new) — the command key lists **vendored into the package** (master dec. 48, ruled on M15): the `asset`, `schedule` and `healthSubject` entries of `docs/api/command-shapes.json` (keys, legacy keys, action flags, `rowToCommand`) as Python constants. **Nothing reads a repository file at runtime**; pytest proves the copy equal to the golden.
- `tools/servicetag-mcp/src/servicetag_mcp/client.py` — only if the cached status needs a home there.
- `tools/servicetag-mcp/README.md` — "Fifty-five"; a "Seasons, condition and health (needs ServiceTag 1.4.0)" list; the deprecated arguments and the mixed refusal; the clearable table's new rows; `import_merge` reading formats **1–8** and reporting **fourteen** tables (the stale "1–6" and "ten" corrected).
- Tests: `tools/servicetag-mcp/tests/test_season_health_tools.py` (new), `test_schedule_forms.py` (new), `test_command_shapes.py` (new); `test_argument_guard.py`, `test_tools.py`, `test_maintenance_tools.py` (extended).

**Modify (loader)**

- `tools/servicetag-schedules/src/servicetag_schedules/legacy_mapping.py` (new) — `to_policy(season_behavior, season_reentry=None, season_reentry_offset_days=None, has_time_rule=True) -> tuple[str, int | None]`, spec §4.1's table.
- `…/phone.py` — `Schedule` gains `service_policy` and `policy_offset_days`, read from the row's `servicePolicy` and `policyOffsetDays`; the derived `seasonBehavior` is no longer read.
- `…/plan.py` — `_schedule_rule` maps the manifest's `season_behavior` through `to_policy` (the manifest has no re-entry, so FOLLOW_ASSET → IN_SERVICE_AT_START / 0 with a time rule); `_phone_schedule_rule` uses the row's policy and offset. The group-target check on the manifest's `seasonBehavior` stays.
- `apply.py` and `manifest.py` — **unchanged** (`apply.py:95-105` already sends `season_behavior` as an argument).
- `tools/servicetag-schedules/README.md` — one paragraph: the re-plan compares through spec §4.1; the manifest contract is unchanged.
- Tests: `tools/servicetag-schedules/tests/test_legacy_mapping.py` (new); `test_plan.py`, `test_phone.py`, `conftest.py` (row fixtures carry `servicePolicy`, `policyOffsetDays` and the derived triple).

**Untouched:** every Kotlin source and document outside the two tool trees; `tools/servicetag-bundle/`; the manifest schema and `tests/fixtures/estate-manifest.json` (fictional, unchanged).

## Interfaces

**Consumes:** B09's routes, codes and `ScheduleRowResponse`; `docs/api/command-shapes.json` (B09) and `docs/api/legacy-season-mapping.json` (B01), **read only by tests** (the package carries its own vendored copy of the first).

**Produces:** the tool surface below. Argument names are snake_case mirrors of the wire (`service_policy` ↔ `servicePolicy`), the shipped convention.

| tool | method and path | overlay |
|---|---|---|
| `get_season(asset_id)` | `GET /v1/assets/{id}/season` | — |
| `start_season(asset_id, occurred_on, event_id)` · `end_season(…)` | `POST …/season` with `action` START / END | **none** — every argument explicit, `None` allowed where the wire allows `null` |
| `set_season_mode(asset_id, season_mode, season_start_mmdd=None, season_end_mmdd=None, manual_phase=None)` | `POST …/season-mode` | none (a command) |
| `set_maintenance_break(asset_id, blackout_start_mmdd, blackout_end_mmdd)` | `POST …/maintenance-break` | none; both `None` clears |
| `list_conditions(asset_id)` · `record_condition(asset_id, condition, occurred_on, occurred_time, tz_id, reason, event_id)` | `GET` / `POST …/conditions` | **none** for `record_condition` |
| `get_health(asset_id)` · `list_health_subjects(asset_id)` · `list_attention()` | the three reads | — |
| `set_health_policy(asset_id, health_aggregation, health_primary_subject_id=None)` | `POST …/health-policy` | none |
| `create_health_subject(asset_id, name, kind, driver, nominal_until_days, warning_from_days, critical_from_days, schedule_id=None, baseline_profile_id=None, weight=None, sort_order=None)` | `POST /v1/health-subjects` | — |
| `update_health_subject(subject_id, …, clear_fields=None)` | `PATCH /v1/health-subjects/{id}` | every `healthSubject` key but `assetId`; clearable: `schedule_id`, `baseline_profile_id` |
| `archive_health_subject(subject_id, archived=True)` | `POST …/{id}/archive` | — |

### The schedule tools (spec §9.3, RS-1)

- `create_schedule` and `update_schedule` gain `service_policy` and `policy_offset_days`; `update_schedule` and `archive_schedule` gain `unlink_health_subject`.
- **A deprecated argument** (`season_behavior`, `season_reentry`, `season_reentry_offset_days`) makes the body the **legacy form** — only legacy keys, never `servicePolicy`/`policyOffsetDays`; `update_schedule` overlays it on the row's derived triple. **No deprecated argument** makes it the **1.4 form** — `servicePolicy`/`policyOffsetDays` overlaid from the row, and no legacy key. **The MCP never translates**; the API does, and refuses with the same codes (`LEGACY_WRITE_CANNOT_REPRESENT` arrives as a `ToolError` carrying it).
- **A deprecated and a 1.4 argument together** (including via `clear_fields`) → `ToolError` carrying `LEGACY_AND_CURRENT_FIELDS_MIXED`, **before any HTTP**.
- **The overlays stop enumerating fields:** `update_schedule` and `update_asset` build the body from every key the vendored `command_shapes` lists for their command (the schedule's `rowToCommand` renames included), overlay the supplied arguments, and apply `clear_fields`. `update_asset` keeps the season pair (the one compatibility input) and `_ASSET_NULLABLE_CLEARABLE` keeps it.
- **The schema check.** Before any non-`GET` call (except the read-only `/v1/import-merge/plan`), the server confirms `/v1/status.schemaVersion ≥ 8`, once per pairing, cached; an older app gets a `ToolError` carrying `APP_SCHEMA_TOO_OLD` and nothing is sent (master §20.25). Reads keep working against a 1.3 app.

## Invariants this brief must hold

**127** (half: no tool amends or deletes a condition or activation, deletes a subject or writes a health value), **128** (half: deprecated arguments translated only by the API; mixed refused), **129** (half: no tool this brief adds names an installed component or assembly, or reads stock), and the loader half of lockstep (the Stage-B manifest re-plans IDENTICAL).

## Test matrix

All MCP tests run against the stdlib HTTP server fixture; no device, no `adb`.

| hazard | test (file · case) | RED mutation |
|---|---|---|
| a tool dropped or unguarded | `test_tools.py` · `test_tool_names_are_fifty_five`; `test_argument_guard.py` · every new tool rejects an unknown argument before the body runs | omit `list_attention` from `TOOL_NAMES` |
| a destructive tool | `test_season_health_tools.py` · `test_no_tool_deletes_or_amends_a_fact_or_subject` (no `delete_*`, `update_condition`, `update_activation`, no health write) | add `delete_health_subject` |
| a fact overlaid | `test_season_health_tools.py` · `test_start_end_and_record_condition_send_exactly_their_arguments` (no preceding `GET`) | read the row first |
| each route's shape | `test_season_health_tools.py` · one case per tool: method, path, body keys, `ToolError` carrying the server's code | send `occurredOn` as `occurred_on` |
| **the deprecated arguments** | `test_schedule_forms.py` · `test_a_deprecated_argument_sends_a_legacy_body_verbatim` (no `servicePolicy` key, values untranslated), `test_new_arguments_send_the_14_form`, `test_a_422_from_a_legacy_body_is_a_tool_error_with_its_code` | translate in Python |
| **the mixed refusal** | `test_schedule_forms.py` · `test_mixing_is_refused_before_any_http` (also through `clear_fields`) | let the API refuse it |
| **the overlay enumerates** | `test_command_shapes.py` · `test_update_schedule_and_update_asset_submit_exactly_the_vendored_keys` (both forms), `test_a_new_vendored_key_is_carried_without_tool_changes` | keep the hard-coded field list |
| **the vendored copy drifts** (M15) | `test_command_shapes.py` · `test_the_vendored_key_lists_equal_the_golden_file` (reads `docs/api/command-shapes.json` from the repository root, test-time only) | drop a key from the copy |
| the flag | `test_schedule_forms.py` · `test_unlink_health_subject_on_update_and_archive` | drop it from the body |
| clearing | `test_maintenance_tools.py` · `test_policy_offset_days_is_clearable_and_none_leaves_it` | treat `None` as clear |
| **the schema check** | `test_season_health_tools.py` · `test_writes_refuse_an_app_older_than_schema_8`, `test_reads_still_work`, `test_the_status_is_read_once_per_pairing` | check only new tools |
| **the loader's mapping** | `tools/servicetag-schedules/tests/test_legacy_mapping.py` · `test_every_golden_case` (against `docs/api/legacy-season-mapping.json`) | map `MM-DD` re-entry to CONTINUOUS |
| **re-plan IDENTICAL** | `test_plan.py` · `test_a_loaded_ignore_manifest_replans_identical_against_14_rows`, `test_follow_asset_matches_at_start_zero`, `test_a_policy_difference_is_a_conflict` | compare against the derived `seasonBehavior` |
| the phone snapshot | `test_phone.py` · `test_schedule_reads_service_policy_and_offset` | read `seasonBehavior` |
| the writer unchanged | `test_apply.py` · the shipped cases green **unchanged** (`season_behavior` still sent) | — (shape-only) |

## Edge cases

- **`update_schedule` with no argument at all** sends the 1.4 form with every value the row already has — an idempotent PATCH that, by B01's #64 rule, moves no pin and clears no postponement.
- **`create_schedule` with neither policy nor deprecated argument** sends neither key; the API reads that as the legacy form with `seasonBehavior` IGNORE, which maps to CONTINUOUS — the same answer as the 1.4 default.
- **`clear_fields=["season_reentry"]`** is a deprecated argument: it makes a legacy-form body, and mixing it with `service_policy` is the mixed refusal.
- **A new pairing** (a new code) drops the cached schema answer and reads `/v1/status` again, so switching phones cannot reuse a stale check.
- **`import_merge(plan_only=True)`** against a 1.3 app is allowed (the plan writes nothing); the apply is refused by the schema check.
- **`start_season` on a CALENDAR asset** surfaces the app's `SEASON_NOT_MANUAL` as a `ToolError`; the tool does not pre-check.
- **The loader against a PRE_SERVICE row** for a manifest entry marked IGNORE answers CONFLICT with a reason naming the policy difference; it never writes.

## Gate

- `cd tools/servicetag-mcp && uv run --frozen pytest` → green; count recorded.
- `cd tools/servicetag-schedules && uv run --frozen pytest` → green; count recorded.
- `cd tools/servicetag-bundle && uv run --frozen pytest` → green and `git diff --stat <base> -- tools/servicetag-bundle` → empty.
- Anchored: `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **55**; `grep -rnE '(open|read_text|read_bytes|Path)\(.*command-shapes' tools/servicetag-mcp/src` → no output (no runtime read; a provenance comment naming the file cannot match); inv. 129: `git diff <base> -- tools/servicetag-mcp/src | grep -niE '^\+.*\b(assembly|assemblies|installed_?component|stock)\b'` → no output; `grep -ci 'forty-one' tools/servicetag-mcp/README.md` → 0; `grep -c 'Fifty-five' tools/servicetag-mcp/README.md` → ≥ 1; `grep -rnE '^def (delete_health_subject|update_condition|delete_condition|update_activation)\b' tools/servicetag-mcp/src/servicetag_mcp/server.py` → no output; `git diff --stat <base> -- app core docs/api tools/servicetag-schedules/src/servicetag_schedules/apply.py tools/servicetag-schedules/src/servicetag_schedules/manifest.py` → empty.

## Strings

**None user-visible.** Docstrings and the READMEs are developer-facing; every tool that records a fact says in its docstring that the row can never be amended or deleted.

## Must NOT

- translate a legacy argument in Python (the MCP forwards; only the loader's **comparison** maps);
- send a mixed body, or let `None` mean "clear";
- add a tool that amends or deletes a condition or activation, deletes a subject, or writes a health value;
- change the manifest contract or `apply.py`'s writes;
- touch any Kotlin, `docs/api/`, or the bundle tool.

## Review focus

- No Python code maps a legacy argument onto a policy for sending; the only Python mapping is the loader's comparison, and it is proved against the golden file.
- The overlays read their key lists from the vendored `command_shapes` module, which a test proves equal to `docs/api/command-shapes.json`; no field name list remains in the two update tools.
- The fifty-five tools and the README agree.

## Size

Medium: fourteen thin tools, two reworked overlays, one small Python mapping. Split seam if needed: **B11a** the MCP; **B11b** the loader.

## Carry-forward from B09's review (controller, 2026-09-25)

- Gate exception: this brief MAY edit the MCP overlay paragraph of `docs/api/v1.md` (and nothing else under `docs/api`), which still describes 1.3's MCP; the diff under `docs/api` is limited to that paragraph and the gate says so.
