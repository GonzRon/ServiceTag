# #83 — the MCP `update_schedule` keeps the LOCAL row in step with `reminders_enabled`

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md` (one task review; controller inspection for
> mechanical fixes). One brief, one implementer. Owner ruling 2026-09-25: planned narrowly from
> issue #83's body; no server PATCH change, no reminder-architecture change, no app version bump.

**Goal:** since 1.4.1 a schedule created through the API, the MCP or the loader with reminders off
stores `[LOCAL, enabled=false]`. The MCP `update_schedule` overlays every unsupplied command key from
the stored row, so `update_schedule(schedule_id, reminders_enabled=True)` without `providers`
re-sends `[LOCAL, enabled=false]` verbatim and the phone stores reminders on with LOCAL disabled — the
`SCHEDULE_PROVIDER_DISABLED` shape the one-tap repair never sweeps. After this brief the MCP mirrors
the app's editor: when `reminders_enabled` is supplied and `providers` is neither supplied nor
cleared, every stored `LOCAL` row is sent with `enabled = reminders_enabled`.

**Spec:** issue #83 (its "Fix (next release)" section); the 1.4.1 architecture ruling in
`docs/superpowers/plans/2026-09-25-servicetag-1.4.1/rulings.md` (`remindersEnabled = true` normally
implies an enabled LOCAL row, `false` a disabled one).

## Global constraints

- **Client-side only.** `tools/servicetag-mcp/**`. The app, `/v1`'s full-replace PATCH semantics,
  `docs/api/v1.md` and the loader are untouched.
- **The overlay's other rules stand:** a supplied `providers` list is sent as given; `providers` in
  `clear_fields` still clears to `[]`; a `None` argument still means "keep the row's value"; the
  vendored `command_shapes` key list is unchanged.
- Only `LOCAL` rows are touched, and only their `enabled`; a row for any other provider name is sent
  as stored. A stored row set with no `LOCAL` row is sent as stored (the mirror adds nothing — the
  create default, not the update, decides that shape).
- Commits by `git -c user.name=GonzRon -c user.email="$(git log -1 --format=%ae master)"`, one casual
  single-line subject each, no body, no trailers, no attribution. No e-mail, no home path, no serial in
  any file.
- Every changed behaviour is shown RED first by a named mutation; the report says which.
- No device. `uv run --frozen pytest` in `tools/servicetag-mcp` is the gate.

## Task 1 — the mirror

**Files:** modify `tools/servicetag-mcp/src/servicetag_mcp/server.py` (`update_schedule`, after
`_overlay_command` returns and before the PATCH; its docstring gains one sentence);
`tools/servicetag-mcp/README.md` (one sentence beside the `update_schedule` paragraph);
`tools/servicetag-mcp/tests/test_maintenance_tools.py`.

**Contract.** Let `body` be the overlaid command. If `reminders_enabled is not None` and
`providers is None` and `"providers"` is not in `clear_fields`, then
`body["providers"] = [ {**p, "enabled": reminders_enabled} if p.get("provider") == "LOCAL" else p
for p in body["providers"] ]`. Otherwise `body` is unchanged. The PATCH is sent as before. Nothing
else about the tool changes; the tool count stays 56; the argument guard's schema is unchanged.

**Invariant (new):** through the MCP, a schedule's stored `LOCAL` row never disagrees with the
`reminders_enabled` the same call sent, unless the caller supplied or cleared `providers` themselves.

**Test matrix (pytest, `test_maintenance_tools.py`; the fake phone stores `providers`):**

| hazard | test | RED mutation |
|---|---|---|
| reminders on re-sends a disabled row | `update_schedule_turning_reminders_on_enables_the_local_row` — stored `[LOCAL disabled]`, `reminders_enabled=True` → the PATCH body carries `[LOCAL enabled]` | drop the mirror |
| the mirror for off | `update_schedule_turning_reminders_off_disables_the_local_row` — stored `[LOCAL enabled]`, `reminders_enabled=False` → `[LOCAL disabled]` | mirror only `True` |
| the mirror over-reaches | `update_schedule_with_an_explicit_providers_list_sends_it_as_given` — `reminders_enabled=True`, `providers=[{"provider":"LOCAL","enabled":False}]` → sent as given | mirror over a supplied list |
| clearing wins | `update_schedule_clearing_providers_sends_an_empty_list_even_with_reminders_on` — `reminders_enabled=True`, `clear_fields=["providers"]` → `[]` | mirror after the clear |
| an unrelated update | `update_schedule_without_reminders_enabled_leaves_the_row_alone` — `title=...` only, stored `[LOCAL disabled]` → `[LOCAL disabled]` | mirror whenever `providers` is unsupplied |
| a non-LOCAL row | `update_schedule_mirrors_only_local_rows` — stored `[LOCAL disabled, OTHER disabled]`, `reminders_enabled=True` → `[LOCAL enabled, OTHER disabled]` | touch every row |
| the guard | the existing `test_argument_guard.py` count and schema cases stay green (no signature change) | — |

## Gate

- [ ] `uv run --frozen pytest` in `tools/servicetag-mcp`: zero failures; the count moves by exactly the
      six new cases (332 → 338).
- [ ] `git diff --stat master..HEAD -- app core docs tools/servicetag-schedules tools/servicetag-bundle`
      → empty; `grep -cE '^@mcp.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → 56.
- [ ] Hygiene; gitlink `7e0377a`; `git status` clean.

## This brief must NOT

Change the API, the app, the loader or `v1.md`; change `_overlay_command` itself or the vendored
key list; add or rename a tool argument; touch the create path; bump any version; run anything on a
device.
