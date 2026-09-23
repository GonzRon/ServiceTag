# Stage B — load the deferred maintenance cadences onto the development phone (plan)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. This plan
> **specifies** (contracts, invariants, tests, proofs); the implementer authors the code
> (`docs/superpowers/planning-policy.md`).

**Goal:** the owner's deferred maintenance cadences (Todoist migration Section A, classified against the 1.2 schedule model) become real `MaintenanceSchedule` and `MaintenanceGroup` rows on the development phone, loaded through the shipped automation API, idempotently, with a plan the owner can read before anything is written.

**Architecture:** one new workstation tool, `tools/servicetag-schedules/`, reads a **private manifest** (JSON, kept in the owner's folder, never in this repository), resolves every reference against the phone through the MCP server's client (`servicetag_mcp.server`, the same in-process `Client(s.mcp)` the release proofs use), computes a **plan** (per entry: `CREATE` · `IDENTICAL` · `CONFLICT` · `ERROR`), and **applies** only a plan with zero `CONFLICT`/`ERROR`, groups before schedules. A second plan after an apply is all `IDENTICAL`. The tool never edits, archives or deletes anything that exists.

**Tech stack:** Python 3.12, `uv` (frozen lock), `pytest`; dependency on the sibling `servicetag-mcp` package by path; the MCP tools `pair`, `list_assets`, `list_profiles`, `list_groups`, `list_schedules`, `create_group`, `create_schedule`. No new app code.

**Spec:** `docs/api/v1.md` (the group and schedule commands, their 422 rows, the group-target narrowing) and `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.1 (D-27 pin), §2.4 (open instant). The classification itself is owner data: `~/Documents/Architecture and Design Review/ServiceTag Android/stage-b/` (private).

## Global constraints

- Private data never enters this repository: fixtures are synthetic (fictional assets, fictional cadences); the real manifest lives in the owner's folder and is passed by path.
- Commits: identity GonzRon, single casual subject, no attribution, no `/home` paths, no owner names.
- `libs/nfc-tag-core` gitlink stays `7e0377a`; nothing under `app/`, `core/` or `libs/` changes.
- The tool prints counts, decisions, manifest keys, the names the manifest itself carries, and reasons — never a pairing code, a serial or an id.
- Exactly one brief: **B01** `B01-schedules-loader.md`. Owner (controller) work, not a brief: the classification FINAL and the manifest.

## Manifest contract (v1)

```json
{"manifestVersion": 1, "asOf": "YYYY-MM-DD",
 "groups":    [{"key": "k", "name": "…", "description": "…"|null, "members": [{"asset": "<exact asset name>"}, …]}],
 "schedules": [{"key": "k", "title": "…", "target": {"asset": "<exact asset name>"} | {"group": "<manifest group key>"},
                "description": "…"|null,
                "time": {"interval": N, "unit": "DAY|WEEK|MONTH|YEAR", "basis": "FIXED|COMPLETION", "anchorOn": "YYYY-MM-DD"},
                "leadDays": N, "completionMode": "QUICK|FORM", "profile": "<exact profile name on that asset>"|null,
                "seasonBehavior": "IGNORE"|"FOLLOW_ASSET", "remindersEnabled": true|false,
                "tags": ["…"], "source": {"todoistId": "…"|null, "cadence": "…"|null}}]}
```

Resolution rules (invariants):
1. An asset reference matches **exactly one** non-archived, non-retired asset by exact name (top-level or component); zero or several → `ERROR` for that entry (and for every entry depending on it).
2. A profile reference matches exactly one non-archived profile **of that asset** by exact name; `FORM` requires a profile; `QUICK` may carry one on an asset target — the completion event then takes the profile's event kind and keeps the profile link (`CompleteSchedule`), which is why the manifest attaches the operation's profile even where it has no fields. (Amended at the first real plan, 2026-09-23: the original "`QUICK` forbids one" was stricter than the product; only a group target forbids a profile, invariant 5.)
3. A group entry's identity on the phone is its **name** among non-archived groups: absent → `CREATE`; present with the same open member set → `IDENTICAL`; present with a different member set → `CONFLICT` (the tool never edits memberships).
4. A schedule entry's identity is **(target, title)** among non-archived schedules: absent → `CREATE`; present with the same rule (`timeInterval`, `timeUnit`, `timeBasis`, `anchorOn`, `leadDays`, `completionMode`, `profileId`, `seasonBehavior`) → `IDENTICAL`; present with any rule difference → `CONFLICT`. `description`, `remindersEnabled`, `providers` are not identity and not compared.
5. A group-targeted schedule is validated **before** any call: `completionMode` `QUICK`, no profile, `seasonBehavior` `IGNORE`, the group has ≥ 1 member (in the manifest or on the phone); otherwise `ERROR`.
6. Manifest keys are unique; two schedules with the same (target, title) in one manifest → `ERROR`.
7. `apply` refuses to write anything if the plan holds any `CONFLICT` or `ERROR` (the merge's rule: one problem anywhere, nothing written); it creates groups first, then schedules, and re-plans at the end, asserting all `IDENTICAL`.
8. Dates: `anchorOn` is ISO and is sent verbatim (D-27 pins the first occurrence from it; the tool never "catches up" or moves an anchor).

## Tests (synthetic fixtures only)

`tests/test_plan.py`: each decision for groups and schedules (1–4 above, one test per rule branch); ambiguity (two assets with one name) → `ERROR`; missing profile → `ERROR`; group-target narrowing (5) all four branches → `ERROR`; duplicate keys / duplicate identities (6). `tests/test_apply.py` with a fake client: refuses on any `CONFLICT`/`ERROR`; creates groups before schedules; a manifest group referenced by a schedule is resolved to the id the fake returned; re-plan after apply is all `IDENTICAL`; a second apply writes nothing. Around 20 tests; one per hazard.

## Proofs (controller)

1. `uv run --frozen pytest` green in the tool and in CI (a `schedules` job like `bundle`).
2. Synthetic manifest against a clean `emulator-5554` install of the 1.2.0 debug build: `plan` → N `CREATE`; `apply` → written; `plan` → all `IDENTICAL`; a mutated copy (one anchor changed) → exactly one `CONFLICT` and `apply` refuses.
3. The real manifest on the development phone (never production): `plan` reviewed (counts + keys only), `apply`, re-`plan` all `IDENTICAL`; `list_due` and the Maintenance tab show the loaded work; the owner folder gets the plan output.

## Not in Stage B

Season windows, re-entry and service policies (#14, #60): every seasonal row loads with `seasonBehavior` `IGNORE` and a `seasonal-example` tag. Supplies (#15), parts (#47), meters (no meter definitions exist), one-off items, cadences the owner has not set: excluded and listed in the classification FINAL. The production phone.
