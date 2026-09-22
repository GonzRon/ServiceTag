# ServiceTag #53 — MCP empty-body wording and read-validation before a replacement write

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. This plan follows `docs/superpowers/planning-policy.md`: it specifies; the implementer authors the code and the tests.

**Goal:** Close issue #53 with two separate, narrow fixes in `tools/servicetag-mcp/`: (1) an empty-body HTTP refusal is reported by its status and reason phrase, never with a manufactured application error code; (2) `save_profile`'s read-modify-replace path validates the shape of what it read before it writes, so a response the server does not fully understand can never become a replacement write.

**Architecture:** No app change, no new tool, no contract change. Fix 1 lives in the client's error rendering; fix 2 adds two small shape helpers beside the existing `_field` and uses them in the profile overlay (and in the definition overlay where the same read pattern exists). Both are proven through `MCPServer.call_tool`, the path a real client request takes.

**Spec:** issue #53 and its discussion, narrowed by the owner (2026-09-22): the proportionate fix, not a structured retry-classification framework; validate the structures and values the overlay depends on, tolerate unrelated extra response keys; the load-bearing assertion is that no `POST /v1/profiles` is sent for any malformed read.

## Global Constraints

- Only `tools/servicetag-mcp/**` changes. The tool count stays 21; `pyproject.toml` bumps the package version `1.1.0` → `1.1.1` and nothing else; `uv.lock` unchanged unless the version bump requires a re-lock (it does not).
- Every error the agent sees stays bounded and serial-free: never echo response bodies or values; name the key, the list index and the reason only.
- No app-side behaviour is assumed beyond what the code shows: framing refusals (`HttpWire.kt`) carry no body; the profile save on the phone validates before writing and writes atomically (`SaveProfile.kt`, the Room `@Transaction`).
- Repository rules: two commits (one per fix), each a single casual subject, no body, no trailers, no attribution; identity from `git log -1 --format=%ae master`; no push; no adb, emulator or gradle commands; hygiene (no e-mail, no home path, no serial) in every file and report.

## Invariant

**The MCP server never turns a response it does not fully understand into a replacement write.** For every malformed read in the matrix below, the tool raises a `ToolError` that names the path and the reason, and the fake server records zero `POST /v1/profiles` requests.

---

### Task 1: Empty-body refusals report status and reason, never a fake code

**Files:** Modify `src/servicetag_mcp/client.py` (`_detail` and the `ApiError` message it feeds); Modify `tests/test_client.py` and `tests/test_sdk_boundary.py`; Modify `pyproject.toml` (version `1.1.1`).

**Contract.** An envelope error keeps its current form `<status> <code>: <message> (<problems>)`. An empty-body status renders as `<status> <reason phrase>: <hint>` with no invented code: 413 → `413 Payload Too Large: the payload is larger than the API accepts`; 405 → `405 Method Not Allowed: the API does not take that method on that path`; 400 with no body → `400 Bad Request: the request could not be framed`; any other empty-body status → `<status> <reason phrase from http.HTTPStatus if it has one, else the status alone>: the phone answered with no body`. The word `unknown` no longer appears in any rendered message. Non-JSON bodies keep their existing mapping.

**Test matrix** (each must fail without the change): through `MCPServer.call_tool` — an empty 413, an empty 405 and an empty 400 each surface with exactly the wording above; an empty body with a status outside the three has the reason phrase or the bare status and the hint; an envelope 422 with problems is unchanged; the string `unknown` is absent from every rendered message in the suite (one assertion over the collected messages). The pre-existing test that asserted the old `413 unknown` wording is updated, not deleted.

### Task 2: `save_profile` validates what it read before it writes

**Files:** Modify `src/servicetag_mcp/server.py` (two helpers beside `_field`; the `save_profile` overlay; the same helper applied to `save_definition`'s list read if it iterates a list); Modify `tests/test_sdk_boundary.py` (or a new `tests/test_read_validation.py`).

**Contract.** `_list_field(row, key, *, of) -> list` raises `ToolError` unless the value is a JSON array, naming `<of>.<key>` and "was not a list". `_entry(items, index, *, of) -> dict` raises unless the element is an object, naming `<of>[<index>]`. Required entry values are read with the existing `_field`, but the `of` passed down carries the index (`the quick action's fields[2]`). The overlay tolerates extra keys in the response; it depends only on: `fields[i].definitionId` (non-empty string) and `fields[i].required` (boolean); `consumables[i].id`, `.name`, `.unit` (strings) and `.defaultQuantity` (number or null). A valid empty list is valid and proceeds to the write.

**Test matrix** (each through `MCPServer.call_tool`, each asserting the message names the path and that **zero `POST /v1/profiles`** were recorded): `fields` is `null`; `fields` is `{}`; `fields` is `""`; `fields` is `["x"]` (an entry that is not an object); `fields` is `[{}]` (missing `definitionId`); `fields[0].required` is `"yes"` (wrong type); the same six for `consumables` (with `defaultQuantity: "1"` as the wrong-type case); a response with extra unknown keys on the profile and on each entry is accepted and the write is sent with the kept values; `fields: []` and `consumables: []` are valid and the write is sent. Plus: a malformed read never raises anything but `ToolError` (assert `type(exc) is ToolError` through the boundary).

### Task 3 (controller-run): proofs, merge, close

`uv run --frozen pytest` green in the worktree; tool count 21; `grep -c unknown src/servicetag_mcp/client.py` shows only the docstring history if any (no rendered message); the branch fast-forwards or rebases cleanly onto `master`; CI `mcp` job green on the merged tip; issue #53 closed with a comment that names the two commits and states the invariant; the contributor's comment answered in the same comment.
