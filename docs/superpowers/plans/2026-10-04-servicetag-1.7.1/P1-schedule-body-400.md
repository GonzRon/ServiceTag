# P1 — #94: the schedule command body's 500 becomes the shipped 400

**Read first:** plan.md §1 (#94), §3 invariant 2, §9; `rulings.md` R171-2; #94's body.
**Lane:** A, with P2, branched from `<base>` = `5cd878c`. **State:** executed in its own commit on the branch, before the plan's; closed by controller inspection plus the automated gates (no string, under fifty lines of production change).
**Blocked on:** nothing.

## Goal

`POST /v1/schedules` and `PATCH /v1/schedules/{id}` answer **400 `bad_request`** — never 500 — for a body with a key and no value, through the same path every other malformed body takes, with the decoder's own message and nothing written. Every shipped 415 and 400 on these routes is unchanged byte for byte: the media-type check still comes first, a non-object body still carries the map decoder's message, and the typed decode, the mixed-form 422, the `PRE_SERVICE` 422 and the `"providers": null` 400 all run after the raw read exactly as before.

## Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/api/ScheduleForms.kt` — `read` takes its raw object from a new private `readObject(request)`: `request.decode(JsonElement.serializer())` (the 415, then the element parser's 400 for a key with no value or a missing comma), an object returned as it is, and a non-object handed to `request.decode(JsonObject.serializer())`, which refuses every non-object with the message it always carried. The trailing `throw ApiFailure.badRequest(…)` is unreachable and says so. `read`'s KDoc names the 400 and #94.
- `app/src/test/kotlin/com/loosecannon/servicetag/api/MaintenanceRoutesTest.kt` — one case, `aMalformedScheduleBodyIs400OnCreateAndPatchAndWritesNothing`, placed before the 1.4.1 repair section.
- `docs/api/v1.md` — one paragraph under **Deprecated inputs (1.4.0)**, after the three form bullets: the strict read, the 500 → 400, the missing comma already a 400, the non-object unchanged.

**Untouched:** `ApiJson.kt` (`decodeOr400`'s catch stays `SerializationException`, R171-2); every other handler; `MaintenanceDtos.kt`; the router; the MCP; the schedules loader.

## Test matrix (the one case; every row on both routes)

| body | expected | fails without the change by |
|---|---|---|
| `{"title":}`, `{"title": }`, `{"providers":}` | 400 `bad_request` | the map decoder's bare `IllegalArgumentException`, mapped to 500 `internal` |
| `{"title":"Filter change" "targetAssetId":"<asset>"}`, `{"providers":null "title":"Filter change"}` | 400 `bad_request` | nothing — already the 400; pinned so the element parser can never be swapped for a lenient one |
| `[]`, `"x"`, `null`, `1` | 400 `bad_request`, and for `[]` the message contains `Expected start of the object` | a cast or a 500 if the non-object fallback were dropped |
| after all of the above | `graph.schedules.all()` equals its value before | a write on a refused body |

Existing cases that must stay green, named: `patchWithNullProvidersIsStillA400` (the `null` 400 is read off the object after `readObject`), every `LegacyFormTest` and `LegacyMappingAgreementTest` case (the form is still decided off the raw object), `MaintenanceCommandShapeTest` (the typed decode is unchanged), `CommandShapesGoldenTest`.

## What the probe showed (kotlinx-serialization 1.9.0, the catalog's version)

| body | `JsonObject.serializer()` (before) | `JsonElement.serializer()` (after) |
|---|---|---|
| `{"name":}` | `IllegalArgumentException: Value must follow key in a map…` → **500** | `JsonDecodingException` → 400 |
| `{"name":"a" "notes":"b"}` | `JsonDecodingException: Expected comma after the key-value pair` → 400 | `JsonDecodingException` → 400 |
| `[]`, `"x"`, `null`, `1` | `JsonDecodingException: Expected start of the object '{'…` → 400 | parses; handed back to the map decoder → the same 400 |
| `{}` / `{"name":"a"}` | object | object |

## Gate

- `./gradlew :app:testDebugUnitTest --tests '*MaintenanceRoutesTest*' --tests '*LegacyFormTest*' --tests '*MaintenanceCommandShapeTest*' --console=plain`, then CI's whole command: zero failures, zero skips. **Planning-environment note (plan.md §9):** the `:app` module could not be built where this brief was executed (Google's Maven host and the toolchain download are unreachable there), so CI's run on the branch is the proof of record; the controller does not merge on the probe alone.
- `git grep -nE 'JsonObject\.serializer\(\)' app/src/main` → exactly one line, inside `readObject`.
- `git grep -nE '^\*\*Strict JSON on the schedule command \(#94, 1\.7\.1\)\.\*\*' docs/api/v1.md` → 1.
- Hygiene; `git status` clean.

## Must NOT

Widen `decodeOr400`; change any status, code or message on any other route; touch the typed decode or the form rules; add a string.
