# B01 — `tools/servicetag-schedules`: plan and apply a schedule manifest through the automation API

Read `docs/superpowers/plans/2026-09-23-servicetag-stage-b/plan.md` first: the manifest contract, the eight invariants, the test list and the proofs are the requirements. This brief adds the shape of the deliverable.

**Files**
- Create `tools/servicetag-schedules/` as a `uv` project mirroring `tools/servicetag-bundle/` (`pyproject.toml` with a frozen `uv.lock`, `README.md`, `src/servicetag_schedules/{__init__,manifest,phone,plan,apply,cli}.py`, `tests/`, a synthetic fixture `tests/fixtures/estate-manifest.json`).
- Dependency on the sibling package: `servicetag-mcp` by path (`../servicetag-mcp`), so the tool talks to the phone exactly as `servicetag_mcp` does (`Client(server.mcp)` in-process; `SERVICETAG_ADB_SERIAL` pins the device; the pairing code is a CLI argument, never printed).
- Modify `.github/workflows/ci.yml`: add a `schedules` job shaped like the `bundle` job (same runner, `uv sync --frozen`, `pytest`).
- Modify `docs/versioning.md` only if it lists per-tool test counts (follow the existing row shape); otherwise leave it.

**Interfaces**
- `manifest.load(path) -> Manifest` — validates the v1 shape strictly (unknown keys are errors, like the app's serializer); every violation is a `ManifestError` naming the entry key.
- `phone.Inventory` — a plain dataclass snapshot: assets (id, name, archived/retired, parent), profiles by asset id (id, name, archived), groups (id, name, archived, open member asset ids), schedules (id, title, target, rule fields, archived). `phone.snapshot(client) -> Inventory` fills it from the MCP tools; tests build it directly.
- `plan.plan(manifest, inventory) -> Plan` — **pure**: no I/O; `Plan.entries` in manifest order, each `(kind: "group"|"schedule", key, decision, reason)`; `Plan.clean` is true iff no `CONFLICT`/`ERROR`; `Plan.summary()` returns counts by decision.
- `apply.apply(manifest, plan, client) -> ApplyResult` — refuses unless `plan.clean`; creates groups then schedules; returns counts and the re-plan.
- CLI: `servicetag-schedules plan <manifest> --code <pairing code>` prints the entry table (kind, key, decision, reason) and the summary; `servicetag-schedules apply <manifest> --code …` prints the same, then the apply counts and the re-plan summary; exit code 0 only when the final plan is all `IDENTICAL`. `--serial` overrides `SERVICETAG_ADB_SERIAL`.

**Wire mapping** (the MCP tools' argument names, `docs/api/v1.md`): group → `create_group(name, description, members=[{"assetId", "sortOrder"}])` with `sortOrder` = manifest order; schedule → `create_schedule(title, target_asset_id | target_group_id, description, time_interval, time_unit, time_basis, anchor_on, lead_days, completion_mode, profile_id, season_behavior, reminders_enabled)`. Read back with `list_groups` (open member = `removedAt` null) and `list_schedules` (`status != ARCHIVED`). Every MCP error (`is_error`) surfaces as a tool failure naming the entry key — never swallowed.

**Tests:** as listed in the plan; pure `plan` tests need no client; `apply` tests use a fake client recording calls in order and returning ids. No real phone, no emulator, no private data in this brief's deliverable.

**Report:** `.superpowers/sdd/2026-09-23-servicetag-stage-b/B01-report.md` — status, commits, test summary (`pytest` count), concerns. You never dispatch subagents; review arrives from the controller.
