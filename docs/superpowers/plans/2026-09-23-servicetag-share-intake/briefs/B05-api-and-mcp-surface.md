# B05 — the reference routes, the five codes, and the three MCP tools

**Read first:** the master plan's §1 (global constraints), §5 (the use cases behind the routes), §7
(the `/v1` additions and the MCP tools), §9 (invariants), §10 (file map), §18.2, §18.3 and
§18.10–§18.13. Those sections are the contract; this brief is the work. **Spec:**
`docs/superpowers/specs/2026-09-23-servicetag-share-intake.md` §3.3, §6, §8, Ruling D-12.
**Report:** `.superpowers/sdd/2026-09-23-servicetag-share-intake/B05-report.md`.

## Purpose

Give the reference domain an automation surface from day one (D-12 A): three routes, one response
DTO reused from the backup format, five `UPPER_SNAKE` codes, one new `/v1/status` count, three MCP
tools under the shipped conventions, and the four `docs/api/v1.md` edits that keep the document
true. **No `DELETE`, and no bytes, ever.** This brief runs in wave 3 lane B and **never asks for the
emulator** — every case here is JVM or pytest.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/api/ReferenceDtos.kt` — the two request types, in the style of `api/MaintenanceDtos.kt`. **Responses reuse `AssetReferenceDto`** from `core.backup` — one schema, not two, exactly as `ApiDtos.kt`'s own header states.
- `app/src/main/kotlin/com/loosecannon/servicetag/api/ReferenceHandlers.kt` — the handler class and its `referenceHandlersFor(graph)` factory, in the style of `api/MaintenanceHandlers.kt` and reached from `ApiHandlers` as `handlers.references.*`.
- `app/src/test/kotlin/.../api/ReferenceRoutesTest.kt`.
- `tools/servicetag-mcp/tests/test_reference_tools.py`.

**Modify**

- `app/.../api/ApiRouter.kt` — three rows (see Interfaces), and the `route` KDoc's path-shape and method-row counts.
- `app/.../api/ApiJson.kt` — the `mapDomainFailure` arms for the reference failures, and one `referenceProblemCode(problem: ReferenceProblem)` `when` in the style of `scheduleProblemCode` (`ApiJson.kt:340`) — **exhaustive, so a problem added later is a compile error here** rather than a refusal with a code nobody documented.
- `app/.../api/ApiHandlers.kt` — the `references` collaborator, and `"assetReferences"` in `status()`'s `counts` map.
- `app/.../api/ApiDtos.kt` — the `StatusResponse.counts` KDoc's table list gains `references`. **`MergeReportResponse` is B01's** (master plan §18.3) and is already done by the time this brief runs; if it is not, that is a finding for the controller, not a fix here.
- `app/.../di/AppGraph.kt` — the reference handlers wired into `ApiHandlers`.
- `app/src/test/kotlin/.../api/ApiRouterTest.kt` — `theDestructiveUseCasesHaveNoRoute` extended with the reference paths.
- `docs/api/v1.md` — the four edits, plus a new **"The reference codes (1.3.0)"** subsection.
- `tools/servicetag-mcp/src/servicetag_mcp/server.py` — `TOOL_NAMES` 38 → **41** with a `# 1.3 —` comment in the style of the `# 1.2 —` one, and the three `@mcp.tool()` functions.
- `tools/servicetag-mcp/README.md` — the tool count and the new tools, if it names either.

**Untouched:** every `:core` file (B01's and B02's) — this brief adds **no** use case and **no**
domain rule, and a refusal it finds missing is a finding for the controller, never a check added
here; `app/.../share/**` and the manifest (B03); `app/.../ui/**` (B04); `LoopbackApiServer`,
`HttpWire`, `PairingCode` and the pairing flow; `ApiJson`'s `ignoreUnknownKeys = false` and
`encodeDefaults = true`; `MAX_BODY_BYTES` and `MAX_IMPORT_BYTES`; the `_StrictMCPServer` guard and
`_forbid_unknown_arguments` **except** for the count it is passed; `tools/servicetag-bundle/`,
`tools/servicetag-schedules/`; every tombstone; `app/build.gradle.kts` and `docs/versioning.md`
(B06).

## Interfaces

**Consumes:** B01's `AssetReferenceDto`, `AssetReference`, `ReferenceId`, `ReferenceRepository`;
B02's `AddReference`, `AddReferenceCommand`, `UpdateReference`, `UpdateReferenceCommand`,
`RemoveReference` (**not routed** — see below), `ReferenceProblem`, `ReferenceResult`.

**The three rows, in `ApiRouter.route`'s `when`:**

| shape | method | handler |
|---|---|---|
| `rest.size == 3 && rest[0] == "assets"` → `"references" to "GET"` | `GET` | `handlers.references.listForAsset(rest[1])` — the **ninth** `/v1/assets/{id}/…` sub-resource |
| `rest == listOf("references")` | `POST` | `handlers.references.create(request)`; any other verb → `notAllowed` |
| `rest.size == 2 && rest[0] == "references"` | `PATCH` | `handlers.references.update(rest[1], request)`; any other verb → `notAllowed` |

So `/v1/assets/{id}/references` answers **404** for a verb it does not take (it is inside the
sub-resource `when`, whose `else` is `notFound`), while `/v1/references` and `/v1/references/{id}`
are **405** path shapes. That asymmetry is the shipped convention and the `v1.md` edit records it.

**Requests** (declared in `:app`, plain fields, `@Serializable`):

```kotlin
@Serializable
internal data class CreateReferenceRequest(
    val assetId: String,
    val kind: String,
    val uri: String,
    val displayName: String,
    val description: String = "",
)

/** `uri`, `assetId` and `kind` are absent, so each is an UNKNOWN FIELD here (I-1, I-6). */
@Serializable
internal data class UpdateReferenceRequest(
    val displayName: String? = null,
    val description: String? = null,
)
```

`kind` is accepted on create because spec §6 lists it in the body — the request shape is a subset of
the response shape, as the shipped commands are — but it is **advisory and never authoritative**:
the handler passes no kind to `AddReference`, which derives it from the scheme (spec §3.2). A `kind`
that disagrees with the derivation is simply not honoured; it is **not** a 422, because nothing in
the domain can hold the caller's answer. The handler's KDoc says so in one sentence.

`null` on the PATCH means **unchanged**, the shipped convention: the handler reads the stored row,
overlays the non-null fields, and calls `UpdateReference` with the result.

**Success shapes.** `GET` → 200 `{references: [AssetReferenceDto]}` ordered by `displayName`; `POST`
→ 201 `{reference: AssetReferenceDto}`; `PATCH` → 200 `{reference: AssetReferenceDto}`.

**Error mapping** — the whole of it, and the only place a `ReferenceProblem` becomes a status:

| problem / exception | status | code |
|---|---|---|
| absent row on `PATCH`; `ReferenceProblem.NoSuchReference` | 404 | `NO_SUCH_REFERENCE` |
| absent `assetId` on `POST`; `ReferenceProblem.OwnerMissing` | 404 | `no_such_asset` *(the shipped 1.1.0 code, reused verbatim)* |
| `NotALink`, `UriTooLong`, `BlankName` | 422 | `REFERENCE_URI_INVALID` for the first two, `REFERENCE_NAME_REQUIRED` for the third |
| `SchemeBlocked`, **and `UnknownSchemeNeedsConfirmation`** | 422 | `REFERENCE_SCHEME_BLOCKED` |
| `DuplicateUri` | 409 | `REFERENCE_URI_TAKEN` |
| `Unchanged` | 200 | the stored row, unchanged — not an error |

**`UnknownSchemeNeedsConfirmation` maps to `REFERENCE_SCHEME_BLOCKED`** because spec §6 states that
code is "a refusal, never a confirmation, over the API": there is nobody on the wire to confirm, and
the API never sets `confirmedUnknownScheme` (master plan §18.2). The handler's KDoc says so, and the
matrix has a case for it.

**`REFERENCE_NAME_REQUIRED` is RULED and ships definitively.** §6's original list named four codes
and none for a blank `displayName`; the controller closed that in the spec's Amendments block
(`spec:578`): **422 `REFERENCE_NAME_REQUIRED`** ("a reference needs a name"), documented in
`docs/api/v1.md` beside the other reference codes, surfaced by MCP as any other error. It is not a
finding, not conditional, and not to be folded into `REFERENCE_URI_INVALID` — which would be wrong
on its face, the URI being valid. **Five codes, not four** (master plan §7).

The other two mappings above are plan decisions the release review checks without reading this
brief: `OwnerMissing` → the shipped `lower_snake` `no_such_asset` rather than a new `UPPER_SNAKE`
twin (§18.10), and `Unchanged` → **200 with the stored row**, because a no-op update deliberately
writes nothing and does not move `updated_at` (§18.13).

**The MCP tools**, under the shipped conventions — `None` means unchanged, unknown arguments
rejected by `_StrictMCPServer`, every error path a real `ToolError` carrying the code:

```python
@mcp.tool()
def list_references(asset_id: str) -> dict[str, Any]: ...
@mcp.tool()
def add_reference(asset_id: str, uri: str, display_name: str, description: str | None = None) -> dict[str, Any]: ...
@mcp.tool()
def update_reference(reference_id: str, display_name: str | None = None, description: str | None = None) -> dict[str, Any]: ...
```

There is **no `clear_fields`** on either write tool: `display_name` cannot be cleared (the app
requires it non-blank) and `description` is cleared **by value**, `description=""`, since it is a
non-null `TEXT` column with an empty default — the same reason `unit` is sent as `""` in
`save_definition`. The `update_reference` docstring says so explicitly, in the style of the shipped
clearing notes. `_validate_clear_fields` is not called and no `_REFERENCE_CLEARABLE_FIELDS` constant
is added.

**The four `docs/api/v1.md` edits**, each exact:

1. the import-merge rows' "format **1–6**" → **1–7** at **both** sites — the endpoint table row at `v1.md:143` ("a data archive of format **1–6**") and "The additive merge import" at `:455` ("**format 1–6**"). The two spell the emphasis differently, which is why the gate greps the bare `1–6` and not one asterisk placement;
2. the 405 row's "The eight `/v1/assets/{id}/…` sub-resources" → **nine**;
3. the merge-report sentence's "Each of the ten tables" → **eleven**, and that sentence's field list gains `references` in **write-order position** (last, after `attachments`), with a clause naming format 7 in the style of the format-6 clause;
4. a new **"The reference codes (1.3.0)"** subsection after "The maintenance codes (1.2.0)", carrying **all five** codes — the four of spec §6 **and `REFERENCE_NAME_REQUIRED`** — plus one row recording that **`/v1/references/{id}` takes no `DELETE`**, and one clause noting that an absent `assetId` answers the shipped `no_such_asset` (§18.10).

Plus one line in **"What has no endpoint, deliberately"**: deleting a reference, beside attachments
and closures — "the API adds and amends, the phone removes".

## Invariants this brief must hold

**I-1** (`uri` is an unknown field on the PATCH, so a request naming it is a **400** from the strict
decoder, not a silent ignore); **I-2** (the API never sets `confirmedUnknownScheme`, so a blocked
*or* unknown scheme is a refusal); **I-3** (no endpoint accepts or returns a file, and none names a
locator, a sha256 or a size); **I-5** (nothing here reads or exposes a tombstone — `v1.md`'s refusal
of anything at all for `externalLinks` is unchanged); **I-6** (`assetId` is an unknown field on the
PATCH); **I-7** (the duplicate is a 409 and never an overwrite).

## Test matrix

One test per hazard class. Each must fail without the change it names. Kotlin cases in
`ReferenceRoutesTest` and `ApiRouterTest`; Python cases in `test_reference_tools.py`.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the list route is missing or unordered | `GET /v1/assets/{id}/references` is 200 with the rows ordered by `displayName`, and an asset with none gives `{"references": []}` | a handler that returns insertion order makes a client's diff unstable |
| create is not wired | `POST /v1/references` is **201** with the full nine-field DTO, and the row is readable back through the list route | a route added to the `when` but not to the handler compiles and 404s |
| create returns the wrong status | the response is 201 and not 200, matching every other shipped create | a 200 breaks a client branching on status |
| amend changes the wrong fields | `PATCH /v1/references/{id}` with `displayName` only leaves `description`, `uri`, `assetId`, `kind`, `scheme` and `createdAt` untouched and moves `updatedAt` | an overlay that sends every field back re-writes values the caller never named |
| `null` means blank | `PATCH` with `{"description": null}` leaves the stored description; `{"description": ""}` clears it | treating `null` as a value is the bug the shipped `_overlay` convention exists to prevent |
| **the URI is amendable** | `PATCH` with `uri` is a **400** naming the field, and so are `assetId` and `kind` — one case each (I-1, I-6) | `ignoreUnknownKeys = true`, or a widened request type, makes a rename silently rewrite a link |
| an unknown field on create | `POST` with `provenance` is a 400 naming it | the same, on the other command |
| a reference is deletable | `DELETE /v1/references/{id}` and `DELETE /v1/assets/{id}/references` each answer 405 or 404 as the shape dictates, and `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` covers both | a `DELETE` added for symmetry makes the API able to erase history the phone is meant to own |
| a verb on the sub-resource | `POST /v1/assets/{id}/references` is **404**, while `GET /v1/references` is **405** | collapsing the two shapes contradicts the shipped convention the `v1.md` edit records |
| each new code is unreachable | one case per code, **five**: 404 `NO_SUCH_REFERENCE` on an unknown id; 422 `REFERENCE_URI_INVALID` on a 2,049-character URI and on non-URI text; 422 `REFERENCE_SCHEME_BLOCKED` on `javascript:`; 409 `REFERENCE_URI_TAKEN` on a duplicate; **422 `REFERENCE_NAME_REQUIRED` on a `displayName` that is blank after trimming, on both `POST` and `PATCH`** | a code documented and never emitted is a lie in `v1.md`; and a blank name answered as `REFERENCE_URI_INVALID` names the wrong field |
| **an unknown scheme is confirmed over the wire** | `POST` with `zotero://select/items/0` is **422 `REFERENCE_SCHEME_BLOCKED`** and writes nothing | a handler that sets `confirmedUnknownScheme = true` gives the API a confirmation spec §6 refuses it |
| a new problem gets no code | `referenceProblemCode` is an exhaustive `when` over `ReferenceProblem`, asserted by a case that maps every member | a `when` with an `else` branch ships a new refusal as `unknown` |
| an absent owner is a 500 | `POST` with an unknown `assetId` is 404 `no_such_asset` | an unmapped `OwnerMissing` becomes the catch-all 500 |
| `/v1/status` loses a count | `status()`'s `counts` carries `assetReferences` with the right number, and every shipped key is still present | a count added to the wrong map, or a key renamed, breaks a client's readiness check |
| the merge report loses the table | `POST /v1/import-merge/plan` with a **format-7** archive is 200 and its report carries a `references` tally in write-order position | B01's field not serialised means a client cannot see a skip |
| a format-7 archive is refused by this build | the same archive is accepted, and a format-8 manifest is 409 `archive_newer_format` | a `FORMAT_VERSION` not carried through leaves the API refusing what the app can read |
| the MCP tool count drifts | `TOOL_NAMES` has **41** entries, `len(mcp._tool_manager.list_tools()) == 41`, and the module imports without the guard raising | a tool added without updating `TOOL_NAMES` makes `_forbid_unknown_arguments` raise at import — which is the intended loud failure, and the case pins it |
| an MCP write swallows an error | each of the three tools, given a phone answer carrying an error body, raises a `ToolError` whose message carries the **code** — one case per tool | an SDK exception that is not a `ToolError` is wrapped as "Error executing tool" and the model learns nothing |
| an MCP tool accepts an unknown argument | `add_reference(..., provenance="x")` and `update_reference(..., uri="x")` are each refused naming the argument, and the refusal names no value | the guard not covering a new tool is a silent hole |
| `None` means blank in MCP | `update_reference(reference_id=…, description=None)` sends **no** `description`; `description=""` sends the empty string | a tool that sends `None` as `null` clears a field the caller meant to leave alone |
| a read tool trusts the phone | `list_references` given a non-list answer, or a row missing a field, raises a `ToolError` naming what was wrong — the shipped `_field` / `_list_field` conventions | an unvalidated read hands the model a `KeyError` traceback |
| the document drifts from the router | a case asserting `docs/api/v1.md` no longer says "ten tables", no longer says "format **1–6**", says "nine" for the `/v1/assets/{id}/…` sub-resources, and carries each of the new codes by name — **anchored patterns**, so a comment naming one cannot satisfy it | a document that contradicts the router is a support cost paid by someone who cannot check it |

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` — green, with `ReferenceRoutesTest` counted and `ApiRouterTest` still green with its extended case.
- `cd tools/servicetag-mcp && uv run --frozen pytest` — green, with `test_reference_tools.py` counted and `test_argument_guard.py` still green.
- `grep -c '^@mcp\.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` → **41**.
- `grep -c 'ten tables' docs/api/v1.md` → **0**; **`grep -c '1–6' docs/api/v1.md` → 0** — the bare string, because the document spells the emphasis two ways (`:143` and `:455`) and a pattern pinned to one asterisk placement would leave the other stale and still report 0.
- `grep -c 'REFERENCE_NAME_REQUIRED' docs/api/v1.md` → **≥ 1**; `grep -c 'eight `/v1/assets/{id}/…` sub-resources' docs/api/v1.md` → **0**.
- `git diff --stat $BASE..HEAD -- core/src/main app/src/main/AndroidManifest.xml app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/share` → empty. **`$BASE` is this lane's base commit** (master §1.15): B05 runs in wave 3, so the release branch's base would already carry B01's and B02's `core/src/main` work and this gate would fail by construction.
- **No emulator is requested at any point.** Report the `:app` and pytest deltas.

## This brief must NOT

Add a `DELETE` route for a reference, or any route that accepts or returns bytes. Add a use case, a
domain rule, a validation or a refusal in `:app` — a missing refusal is a **finding for the
controller**, and B02 owns the fix. Relax `ignoreUnknownKeys`. Add `uri`, `assetId` or `kind` to the
PATCH request. Set `confirmedUnknownScheme`. Add a `clear_fields` argument or a
`_REFERENCE_CLEARABLE_FIELDS` constant. Touch `_StrictMCPServer` or `_forbid_unknown_arguments`
beyond the count. Expose anything at all for the `externalLinks` tombstones. Request the emulator.
Change `app/build.gradle.kts`, `docs/versioning.md` or `README.md` (B06). Introduce a user-visible
string — API error text is not user-visible, and no `error.message` here may name a URI, a scheme,
an authority or a path.
