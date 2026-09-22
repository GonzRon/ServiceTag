# B12 — the `/v1` additions and the MCP tools

**Read first:** the master plan's §1, §9 (the `/v1` additions), §10 (the MCP tools), §4 (the merge, for the report changes) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §4.1, §4.2, §4.3; rulings D-19, D-25; `docs/api/v1.md`; `tools/servicetag-mcp/README.md`.

## Purpose

Put the whole 1.2 domain behind the loopback API and the workstation MCP server, **additively and at version 1**: nineteen new routes including `close-round` and the read-only closure history, the three commands, the derived-state read projection, `/v1/due`, the extended merge report, and **seventeen** new MCP tools with the `clear_fields` audit that the null-means-unchanged convention makes necessary. It is also the brief that keeps two documents honest — `docs/api/v1.md`'s "seven tables" prose and the MCP README's tool count both become wrong the moment this brief lands. Nothing destructive is added, and `POST /v1/events` still cannot create a completion.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/api/MaintenanceDtos.kt` — the three request shapes and the new response envelopes. Requests are declared here and nowhere else, with plain `String`/`Int`/`Double?` fields, exactly as `api/ApiDtos.kt:30-41` requires; **responses reuse the backup format's own row DTOs**.
- `app/.../api/MaintenanceHandlers.kt` — the handlers.
- `tools/servicetag-mcp/src/servicetag_mcp/` — the seventeen tools added to `server.py`, and whatever `client.py` needs for the new paths.
- Tests: `app/src/test/kotlin/.../api/MaintenanceRoutesTest.kt`, `MaintenanceCommandShapeTest.kt`; `tools/servicetag-mcp/tests/test_maintenance_tools.py`, and rows added to `tests/test_argument_guard.py`.

**Modify**

- `app/.../api/ApiRouter.kt` — the new path shapes in the explicit `when` over segments (`ApiRouter.kt:63-120`), keeping the 64 KiB cap and the one-request-per-connection discipline. The class KDoc's route arithmetic (`ApiRouter.kt:57-62`) is updated.
- `app/.../api/ApiHandlers.kt` — `/v1/status`'s `counts` (`ApiHandlers.kt:104-119`) gains `groups`, `schedules`, `closures`.
- `app/.../api/ApiDtos.kt` — the merge report mirror gains the three tallies and the new reason codes travel as their names.
- `docs/api/v1.md` — the nineteen rows, the three commands, the new status codes, the `/v1/due` item shape, the `ScheduleStateDto` paragraph, and **the "Each of the seven tables" prose at `docs/api/v1.md:195`**, which becomes ten.
- `tools/servicetag-mcp/README.md` — **`README.md:58`'s "Twenty-one"** becomes **thirty-eight**; the tool list; the `clear_fields` documentation per nullable argument; `import_merge` now reading a format-6 archive.
- `app/src/test/kotlin/.../api/ApiRouterTest.kt` — `theDestructiveUseCasesHaveNoRoute` (`docs/api/v1.md:202-211`) gains the new paths.

**Untouched:** `core/**` in its entirety — every rule this brief enforces is B02's or B03's use case, called, not reimplemented; `app/.../ui/**`; `app/.../data/**`; `reminders/**`; `nfc/**`; `libs/`; `tools/servicetag-bundle/` (its tests must stay green and byte-identical).

## Interfaces

**Consumes from B01:** the five DTOs, `BackupData`'s lists, the extended `MergeReport`. **From B02:** `SaveSchedule`, `CompleteSchedule`, `PostponeSchedule`, `PauseSchedule`, `ArchiveSchedule`, `statusOf`, `Today`, the schedule command and its problem types. **From B03:** `SaveGroup`, `ArchiveGroup`, `CompleteGroupMembers`, `CloseRound`, `GroupOccurrence`, the group command and its problem types. **From B08:** `DueReadModel.items()` — `/v1/due` is **that** projection, so the API and the app order identically and `rank` means the same thing.

**Produces:** the wire contract of master plan §9 and the tool set of §10. Nothing in-repo consumes it; the consumers are the owner's MCP client and the release proofs.

**The route table, the three commands and the status split are master plan §9's and are the authority.** Restated here only as the eight rules an implementer will otherwise get wrong:

1. **Request shape ⊂ response shape** (`docs/api/v1.md:145-154`). No `id`, `status`, `createdAt`, `updatedAt`, `addedAt`, `removedAt` or `archivedAt` appears in any of the three commands.
2. **`status` is not stored and is therefore in no command.** `GET /v1/schedules/{id}` returns `{schedule, state, status, computedForOn}`, with `computedForOn` echoing the `today` used. **`ScheduleStateDto` is a derived read projection, deliberately outside `BackupData`** — no command accepts it and no archive carries it.
3. **A `PATCH /v1/groups/{id}` that omits a member soft-removes it.** It stamps `removed_at` and **never deletes a row**. A member sent **without an `id`** for an asset that already has an open row is **422 `MEMBER_ALREADY_OPEN`** (spec revision 4.1) — the caller meant "keep it", and the way to say that is to send its `id`.
4. **The status split:** 422 for a command describing something that cannot exist — `SCHEDULE_TARGET_INVALID`, no rule side, a meter rule or `FOLLOW_ASSET` on a group target, a group-targeted schedule on an **empty group**, `CLOSED_ON_OUT_OF_RANGE`, `MEMBER_ALREADY_OPEN`. 409 for a refusal about state — `SCHEDULE_OCCURRENCE_TAKEN`, `OCCURRENCE_CLOSED`, and `close-round`'s `CLOSE_NOT_SUPPORTED`, `OCCURRENCE_ALREADY_CLOSED`, `OCCURRENCE_ALREADY_COMPLETE`, `OCCURRENCE_NOT_CLOSEABLE`.
5. **`POST …/complete` is the only route that writes a completion event**, and `POST /v1/events` **cannot**: its command has no `scheduleId` and no `source`, and this brief does not add them.
6. **No snooze endpoint** — under D-13 the snooze is not canonical data — and **no route deletes or amends a closure**.
7. **Both `…/archive` routes return the row (200)**, matching `docs/api/v1.md:119`, not 204.
8. **`/v1/due`'s item shape** is master plan §9.3's, with **every field present and defaults encoded** (`docs/api/v1.md:105-106`), a group-targeted schedule as **one** item with its two member counts, counted **once**.

**The MCP layer** is master plan §10's table. The three rules that carry the risk:

- **`update_group`'s member list**: an overlay that treats an omitted `members` as "leave alone" makes closing every membership impossible, so that is **`clear_fields=["members"]`**; a supplied list replaces wholesale, soft-removing every member it omits.
- **`postpone_schedule` is the sharpest nullable case**: under the null-means-unchanged rule (`README.md:77-84`), `postponed_due_on=None` **cannot** clear a postponement, so clearing is **`clear_fields=["postponed_due_on"]`** even though the wire route accepts a literal `null`.
- **`complete_schedule` and `close_round` have no overlay** — like `update_event` (`README.md:86-90`), every argument is explicit, because each is a new fact and nothing about it can be inherited from a row. **`close_round`'s docstring states plainly that it records that a round ended without claiming the outstanding members were serviced, and that the row can never be amended or deleted.**

`Plan decision:` MCP argument names are **snake_case mirrors** of the camelCase wire fields (`postponed_due_on` ↔ `postponedDueOn`), which is the shipped convention; the docstrings name the wire field so a caller reading `docs/api/v1.md` can map them.

**Route → use case, so no handler re-implements a rule.** Every write route calls exactly one use case; the read routes call a repository or `DueReadModel`. A handler that validates anything the use case already validates is a finding.

| route | calls | owner |
|---|---|---|
| `POST`/`PATCH /v1/groups[/{id}]` | `SaveGroup` | B03 |
| `POST /v1/groups/{id}/archive` | `ArchiveGroup` | B03 |
| `GET /v1/groups`, `/v1/groups/{id}`, `/v1/assets/{id}/groups` | `GroupRepository` | B03 |
| `GET /v1/groups/{id}/schedules`, `/v1/assets/{id}/schedules`, `/v1/schedules` | `ScheduleRepository` | B02 |
| `POST`/`PATCH /v1/schedules[/{id}]` | `SaveSchedule` | B02 |
| `GET /v1/schedules/{id}` | `ScheduleRepository` + `ScheduleStateRepository` + `statusOf` + `Today` | B02 |
| `POST /v1/schedules/{id}/pause` \| `/archive` | `PauseSchedule` \| `ArchiveSchedule` | B02 |
| `POST /v1/schedules/{id}/complete` | `CompleteSchedule` for an asset target, **`CompleteGroupMembers`** for a group target (the command's `assetId` names the member) | B02 / B03 |
| `POST /v1/schedules/{id}/postpone` | `PostponeSchedule` | B02 |
| `POST /v1/schedules/{id}/close-round` | **`CloseRound`** | B03 |
| `GET /v1/schedules/{id}/closures` | `ClosureRepository.forSchedule` | B03 |
| `GET /v1/due` | **`DueReadModel.items()`** | B08 |
| `GET /v1/status` | the repositories, for the three new counts | B01 |

## Invariants this brief must hold

**8, 38, 39, 43, 76, 77, 78, 79, 80** (master plan §13) at the wire boundary, and it must not weaken **1, 2, 3, 27, 66, 67** — every one of those is enforced by the use case this brief calls, and the tests prove the route surfaces the refusal rather than re-implementing the rule.

## Test matrix

Proportionate per the owner's ruling: **one representative case per status class**, plus **each 409 and 422 that carries domain meaning, by name**, plus **one parameterised assertion** for 404/405/415 reusing `ApiRouterTest`'s existing shape.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| status classes | one representative 200, one 201, one 404, one 422 and one 409 over the new surface | a handler that returns 200 on a create, or 404 for a validation failure, is a contract break a client branches on |
| **the named refusals** | one test per code, asserting the status **and** the code: `SCHEDULE_OCCURRENCE_TAKEN` (409), `OCCURRENCE_CLOSED` (409), `CLOSE_NOT_SUPPORTED` (409), `OCCURRENCE_ALREADY_CLOSED` (409), `OCCURRENCE_ALREADY_COMPLETE` (409), `OCCURRENCE_NOT_CLOSEABLE` (409), `SCHEDULE_TARGET_INVALID` (422), `CLOSED_ON_OUT_OF_RANGE` (422), `MEMBER_ALREADY_OPEN` (422), and an empty-group schedule (422) | a generic 500 or an unnamed 400 leaves an automation client unable to tell "already done" from "broken" |
| 404 / 405 / 415 | **one parameterised** assertion over the new paths, reusing the shipped shape: a wrong verb on a top-level path is 405, on an `/v1/…/{id}/…` sub-resource is 404, and the wrong content type is 415 | per-path duplication is the permutation testing the proportionality ruling forbids, and omitting it lets a typo route silently |
| an unknown field accepted | each of the three commands rejects an unknown field with a 400 **naming it** (`docs/api/v1.md:95`) | a misspelled field read as absent makes a full-replace PATCH silently clear a value |
| **full replace misread as partial** | one test per PATCH: a `PATCH /v1/schedules/{id}` omitting an optional field **clears** it, and a `PATCH /v1/groups/{id}` omitting a member **soft-removes** it — its row still exists with `removed_at` stamped, and no row was deleted (invariant 8) | implementing the group PATCH as a delete destroys the history every past occurrence's required set is derived from |
| request ⊄ response | one assertion over **all three** commands: a row read from a `GET` and sent straight back is a 400, and the same row minus the documented identity and bookkeeping keys is accepted (`docs/api/v1.md:145-154`) | a command that accepts `id` or `createdAt` lets a client rewrite identity |
| `ScheduleStateDto` leaking into the format | a structural assertion: no `ScheduleStateDto` field appears in `BackupData`, and no command accepts one | the "one schema on purpose" paragraph (`:98-103`) reads as an instruction to add it, and then derived state starts travelling in archives |
| `/v1/due`'s shape | the item carries **every** field of master plan §9.3 with defaults encoded; a group-targeted schedule is **one** item with `membersRequired`/`membersComplete`; `rank` is dense and ascending (D-15) | an omitted field forces a client to distinguish absent from default, which `:105-106` promises it never has to |
| **a completion through the wrong door** | `POST /v1/events` cannot set `scheduleId`, `occurrenceOn`, `detailsPending` or `source` — each is an unknown field — so it **cannot** create a completion (invariant 76, #50's "must not invent a second completion path") | two completion paths let reminder state diverge from history |
| a form completion fabricating data | `POST …/complete` on a **FORM** schedule with no `values` creates the event with **`detailsPending = 1`**; with `values` it does not | silently completing without the structured data loses the reading the profile exists to capture |
| a backdated completion refused | any **valid past** `occurredOn` is accepted (D-25); a future one is refused | rejecting a backdated completion makes the API unable to produce data the app can, which is what D-25 forbids |
| `closedOn` bounds at the wire | the occurrence's **open date** and **today** are accepted; the day before and tomorrow are each 422 `CLOSED_ON_OUT_OF_RANGE`; omitting it defaults to today (invariant 78) | an unbounded date on an immutable row permanently moves a schedule's future |
| **something destructive added** | `theDestructiveUseCasesHaveNoRoute` extended: `DELETE /v1/schedules/{id}`, `DELETE /v1/groups/{id}`, `DELETE /v1/schedules/{id}/closures`, `DELETE`/`PATCH /v1/schedules/{id}/closures`, and a snooze path each answer **404 or 405** (invariants 43, 76) | a convenience delete is a permanent contract, and a closure delete would destroy exported history |
| the merge report's new tables | `import_merge` accepts a **format-6** archive and the report carries `groups`, `schedules` and `closures` tallies plus the new reason codes by name; `applicable` still governs (§4.2) | a client enumerating seven table keys silently drops three, which is why the prose at `:195` is on the edit list |
| **the `clear_fields` hole** | one test per nullable argument across all seventeen tools: an explicit `None` **leaves the value alone**, and the documented `clear_fields` name clears it — with `postpone_schedule` and `update_group`'s `members` asserted by name (§4.3) | under the null-means-unchanged rule a nullable argument with no `clear_fields` entry is a value that can be set and never unset |
| an unknown MCP argument | each new tool rejects an unknown argument **before the tool body runs** (`README.md:65-66`) | a mistyped field read as absent changes what an overlay edit submits |
| an overlay reconstructing a fact | `complete_schedule` and `close_round` require **every** argument and perform **no** read-overlay-replace | overlaying a new fact onto a row invents provenance |
| a destructive tool added | a structural assertion: no `delete_group`, `delete_schedule`, `delete_closure` or snooze tool exists, and the tool count is exactly **38** (`README.md:111-113`) | the tool count is the one cheap check that catches a tool added without a route |
| **the two documents drifting** | a structural assertion: `docs/api/v1.md` no longer says "seven tables", and `tools/servicetag-mcp/README.md` no longer says "Twenty-one" | both sentences become false the moment this brief lands, and a released contract document that lies is worse than a missing one |

## Strings

**None user-visible.** Error codes, field names and tool names are contract, not copy; `problems` carries the domain's own problem names, exactly as the shipped mapping does (`docs/api/v1.md:229`). Docstrings and the two documents are developer-facing and need no ratification — but `close_round`'s docstring sentence about not claiming the members were serviced is **required content**, not optional prose.

## Ordering

**After B01, B02, B03 and B08** (it needs the DTOs, both sets of use cases and `DueReadModel`). It does **not** need B14 or B09: the API is a peer of the UI, not a consumer of it. Runs in lane A of wave 7 beside **B10**.

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Python: `cd tools/servicetag-mcp && uv run --frozen pytest` → green, counts recorded; `cd tools/servicetag-bundle && uv run --frozen pytest` → green and **unchanged**.
- Structural, anchored:
  - `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **38**.
  - `grep -ci 'twenty-one' tools/servicetag-mcp/README.md` → **0**; `grep -c 'seven tables' docs/api/v1.md` → **0**.
  - `grep -rniE '\bdef (delete_group|delete_schedule|delete_closure|snooze)' tools/servicetag-mcp/src/servicetag_mcp/server.py` → no match.
  - `grep -n 'rest == listOf("events")' -A 6 app/src/main/kotlin/com/loosecannon/servicetag/api/ApiRouter.kt` shows the shipped event routes unchanged; `grep -rn 'scheduleId\|occurrenceOn\|detailsPending' app/src/main/kotlin/com/loosecannon/servicetag/api/ApiDtos.kt` shows them in **no** event request shape.
  - `grep -rn 'ScheduleStateDto' core/src/main/kotlin/com/loosecannon/servicetag/core/backup` → no match.
  - `grep -rnE '(DELETE|PATCH).*closures' app/src/main/kotlin/com/loosecannon/servicetag/api/ApiRouter.kt` → no match.
  - `git diff --stat master -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/data tools/servicetag-bundle libs` → **empty**.
- **No device run in the brief's own gate.** The live MCP-to-phone proof is the controller's (master plan §16.6).

## Estimated size

Large: nineteen routes, three commands, seventeen tools and two documents. If it exceeds what one review holds, split at **B12a the `/v1` routes, the commands and `docs/api/v1.md`** / **B12b the seventeen MCP tools, the `clear_fields` audit and the README** and say so in the ledger; the interface between them is the route table of master plan §9.1, and the Python half cannot be reviewed before the Kotlin half is merged.
