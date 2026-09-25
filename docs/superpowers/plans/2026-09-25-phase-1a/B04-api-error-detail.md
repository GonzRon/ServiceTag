# B04 — readable first-problem detail for the 1.1.0 validation families (#52)

**Read first:** plan.md §2, §3, §5, §6 and §8 (#52); `audit/audit-65-52.md` (the #52 half); issue #52's rewritten body.
**Lane:** fourth. It may run beside B03 (plan.md §2).
**Blocked on:** the owner's review of plan.md §5 (sentences C1–C40).

## Goal

The four 1.1.0 validation families — `asset_validation`, `event_validation`, `definition_validation` and `profile_validation` — still answer a 422 with one generic `message`, with `field: null`, and with `problems` in the domain's `toString` form. This brief keeps all of that wire and makes it readable:

- the envelope's `message` and `field` describe the **first** problem, using the sentences and keys in plan.md §5;
- the 1.4 malformed-value rows (`season_validation`, `condition_validation`) gain the `field` their problems already carry;
- the MCP keeps `field` on `ApiError` and shows it in the `ToolError` text.

**Unchanged:**
- `code`;
- `problems`: still a `List<String>`, byte-identical;
- the envelope's keys;
- every 1.2, 1.3 and 1.4 message.

## Files

**Create**
- `app/src/main/kotlin/com/loosecannon/servicetag/api/ValidationRefusals.kt`. It holds the four mappers, each returning the existing `Refusal(code, message, field)` type (`ApiJson.kt:494`).
- `app/src/test/kotlin/com/loosecannon/servicetag/api/ValidationRefusalsTest.kt`.

**Modify**
- `app/.../api/ApiJson.kt`
  - the four family arms of `mapDomainFailure` (lines 214–229 at the base);
  - the `field` fills in `seasonRefusal` (line 529) and `conditionRefusal` (lines 551–553);
  - the `ApiErrorDetail` KDoc.
- `app/src/test/.../api/ApiRouterTest.kt`: one route case per family.
- `app/src/test/.../api/SeasonHealthRoutesTest.kt`: the `field` assertions.
- `docs/api/v1.md`: `## Errors` and its subsections only.
- `tools/servicetag-mcp/src/servicetag_mcp/client.py`
- `tools/servicetag-mcp/src/servicetag_mcp/server.py`
- `tools/servicetag-mcp/tests/test_client.py`
- `tools/servicetag-mcp/tests/test_sdk_boundary.py`
- `tools/servicetag-mcp/README.md`: one sentence.

**Untouched**
- `core/**`: the problem types' `toString` *is* the wire.
- Every handler.
- `ui/**` and `LoopbackApiServer.kt`: B03's files.
- `v1.md` outside `## Errors`.
- `tools/servicetag-mcp/pyproject.toml` and `uv.lock`: no version bump.
- `tools/servicetag-bundle` and `tools/servicetag-schedules`: neither reads `problems`.

## Interfaces

**Produces**

```kotlin
internal fun assetRefusal(problem: AssetProblem): Refusal
internal fun eventRefusal(problem: FieldProblem): Refusal
internal fun definitionRefusal(problem: DefinitionProblem): Refusal
internal fun profileRefusal(problem: ProfileProblem): Refusal
```

Each is a `when` over its sealed type with **no `else`**. The nested sealed types get their own no-`else` `when` as well: `Season.Problem` inside `AssetProblem.Season`, and `DerivedProblem` inside `DefinitionProblem.Derived`. A problem added later is then a compile error, not an undocumented refusal. The single exception is the `String`-typed `which` of `Season.Problem.BadDate`, which ends in the unreachable arm the C8 note in plan.md §5 describes.

**Family arms.** Each family arm becomes `unprocessable(e.problems.firstOrNull()?.let(::xRefusal) ?: Refusal(<family code>, <old generic sentence>), e.problems.map { it.toString() })`. This is the 1.4 shape, with the old sentence kept only as the unreachable fallback.

**MCP**
- `ApiError(status, code, message, problems, field: str | None = None)`, with the new argument keyword-defaulted so existing constructions still work.
- `_detail` returns `field`: a string when the envelope carries a non-null string, otherwise `None`. A missing key reads as `None`, never as an error.
- `ToolError` text follows C40 in plan.md §5: `"<status> <code>: <message>"`, then `" [field=<field>]"` only when `field` is not `None`, then `" (<problems>)"` as today.

## Contracts

- **K1.** `code` is the family code for every problem, exactly as today.
- **K2.** `problems` is `e.problems.map { it.toString() }`, unchanged. No sentence and no `field` ever enters it.
- **K3.** `message` and `field` describe the first problem only. The domain's collection order stays as it is, so "first" is deterministic.
- **K4.** Messages are plan.md §5's, verbatim once reviewed. They name a key or a rule, never a value the caller sent.
- **K5.** The source-id `DerivedProblem`s (C29–C33) answer `field: null`: the problem does not say whether `sourceAId` or `sourceBId` sent the id.
- **K6.** An event `BadDate` or `BadTime` with a null `definitionId` maps to `occurredOn` or `occurredTime`. With a non-null `definitionId` it maps to `values` (not produced today).
- **K7.** Season `BadDate(field)` and condition `BadDate`, `BadTime` and `BadTimeZone(field)` set `field = problem.field`. Their messages stay as they are. `BothOrNeither` stays `null`.
- **K8.** A client that ignores `field` and treats `message` as opaque sees no change. The MCP's text for a refusal with no `field` is byte-identical to today's.

## `docs/api/v1.md` (Errors only)

- **The `field` paragraph** (base lines 703–705) keeps the 1.4 codes. It adds that the four 1.1.0 families and the 1.4 malformed-value rows fill `field` too, and that `field` is `null` where a refusal is not about exactly one key. It then states plan.md §5's `field` rule: one key, the first of a pair, or `null` for an id without its key.
- **The 422 row** (base line 715) says that `problems` lists every problem by the domain's own name, and that `code`, `message` and `field` describe the first. This replaces "names each bad field", which the event dates contradict.
- **A new subsection, `### The 1.1.0 validation families`,** is placed before `### The maintenance codes (1.2.0)`. It holds one table row per C1–C37, with the columns status, `code`, problem, `field` and message. Each row is written in a fixed shape (`| 422 | \`asset_validation\` | \`NameRequired\` | \`name\` | … |`) so that the test can anchor on it. The fallback sentences are listed and marked unreachable.
- **The 1.4 paragraph** (base lines 860–867) adds that these rows now carry `field`.
- No release number is written anywhere; the release plan adds it.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a problem left generic or given the wrong key | `ValidationRefusalsTest` · `everyAssetProblem`, `everyEventProblem`, `everyDefinitionProblem` (every `DerivedProblem` included), `everyProfileProblem`. One instance per leaf, asserting the exact §5 message and `field`. | map one leaf to its family's generic sentence, or to a neighbour's key |
| the source ids given a guessed key | `ValidationRefusalsTest` · `aSourceIdProblemNamesNoKey` | `field = "sourceAId"` |
| the event dates unnamed | `ApiRouterTest` · `anEventsBadDateAndTimeNameTheirFields`, over the wire | `field = "values"` |
| the wire changes beyond message and field | `ApiRouterTest` · `aValidationFailureIs422AndNamesTheProblems` (extended), plus one route case per family: 422, the family `code`, the §5 message, the §5 `field`, and `problems` equal to today's exact strings (for example `listOf("NameRequired")`, `listOf("BadDate(definitionId=null)")`) | put the message into `problems`, or change `code` |
| the fallback throws | `ValidationRefusalsTest` · `aRefusalNamingNoProblemFallsBackToTheFamilySentence`, which calls `mapDomainFailure` with an empty list | drop the `?:` |
| the 1.4 rows still null | `SeasonHealthRoutesTest` · `aMalformedValueKeepsTheShippedValidationShape`, extended: `seasonStartMmdd`, `occurredTime` and `tzId` in `field`; `BothOrNeither` → null; messages unchanged | leave `field` null |
| the document drifts | `ValidationRefusalsTest` · `theApiDocumentListsEveryFamilyRow`: anchored `^\| 422 \| …` patterns, one per C1–C37 row, and no remaining "`problems` names each bad field" | delete a row |
| the MCP drops `field` | `test_client.py` · `test_an_error_body_keeps_its_field`, `test_a_missing_or_null_field_is_None` | `_detail` ignores `field` |
| the `ToolError` text changes shape | `test_sdk_boundary.py` · `test_a_422_survives_the_sdk_boundary_with_its_field` (`[field=name]` present) and `test_a_refusal_without_a_field_reads_exactly_as_before` (a full-string equality) | always print the bracket |

Existing pins stay green unchanged: `ApiRouterTest:429`, `SeasonHealthRoutesTest` 257–273 and 322…498, `LegacyFormTest:153`, `MaintenanceRoutesTest` 370–371, 451 and 894, `CommandShapesGoldenTest`, and `ReferenceRoutesTest.theApiDocumentAgreesWithTheRouter`. If any of them has to change, a contract was broken, and the implementer stops and reports.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain`: zero failures, zero skips, with counts recorded. Every new case must appear in the XML.
- `cd tools/servicetag-mcp && uv run --frozen pytest`: all green, with the count recorded.
- `git diff <base> -- core app/src/main/kotlin/com/loosecannon/servicetag/ui tools/servicetag-mcp/pyproject.toml tools/servicetag-mcp/uv.lock` must be empty.
- Anchored: `git grep -nE '"the (asset|event|reading|quick action) was refused"' -- app/src/main` must print exactly 4 lines, the fallbacks.
- `git diff <base> -- docs/api/v1.md` must touch only lines inside `## Errors`. The reviewer checks the hunk headers.

## Strings

No app UI text. The contract sentences are plan.md §5's C1–C40, verbatim once reviewed. If a sentence has to change, stop and report; do not rephrase.

## Must NOT

- turn `problems` into objects, add a key to the envelope, or change any `code`;
- change a `:core` problem type or its `toString`;
- change a 1.2, 1.3 or 1.4 message;
- edit `v1.md` outside `## Errors`, or add a release number to it;
- bump `pyproject.toml`, the app version or `uv.lock`;
- touch B03's files.

## Review focus

- Each `when` really has no `else`.
- Every `problems` pin is byte-identical.
- The §5 table, the code and `v1.md` agree row for row.
- The MCP's no-`field` text is unchanged.

## Size

Small: four pure mappers, four one-line arms and two `field` fills, a doc table, and about thirty lines of MCP change with their tests.
