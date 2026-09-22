# ServiceTag Stage-A migration bundle — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This plan follows `docs/superpowers/planning-policy.md`: it specifies contracts, invariants and the test matrix; implementers author the code and the tests.

**Goal:** A small, public, tested generator that turns a private normalized inventory (assets, child assets, measurement definitions, quick-action profiles, known maintenance history) into a format-5 ServiceTag data archive with deterministic ids, so that the archive can be loaded onto a phone through the 1.1.0 Developer API's `import_merge` (plan first, then apply) and re-loaded idempotently (a second plan is all IDENTICAL).

**Architecture:** One Python package `tools/servicetag-bundle/` (stdlib only at runtime: `json`, `zipfile`, `hashlib`, `uuid`, `datetime`) with three seams — a validated source model, a deterministic id/row mapper, and an archive writer — behind one CLI. The archive is exactly what `BackupCodec` (`core/src/main/kotlin/com/loosecannon/servicetag/core/backup/BackupCodec.kt`) decodes; a JVM conformance test decodes a committed synthetic archive so the two sides cannot drift silently. The loader is the existing MCP tool `import_merge`; this plan adds no app code and no MCP tool.

**Tech Stack:** Python 3.12, `uv`, `pytest` (mirrors `tools/servicetag-mcp/`); Kotlin JVM test in `:core` (JUnit, as the module's existing tests); GitHub Actions job pinned by SHA like the `mcp` job.

**Spec:** the owner's Stage-A rulings of 2026-09-21 (private, outside the repository, in the controller's ledger folder) and the private normalization audit in the owner's documents folder. Neither is copied here. The schema-relevant rulings, in generic form: hierarchy is flat except for explicitly declared child assets; a child asset never implies an NFC tag; the bundle carries **no** NFC tags, **no** attachments and **no** external links; unit counts are facts supplied by the owner in the private source, never invented by tooling.

## Global Constraints

- **Privacy (binding, from the owner).** Nothing private enters git: no household inventory, equipment names, locations, task titles or the private dataset. Every fixture in this repository uses fictional nouns and fictional brands. The private source file and the archives built from it live in the owner's documents folder or gitignored storage; the CLI must work with paths outside the repository. Reports and reviews for this plan contain no private names either.
- **Determinism.** The generator never reads the clock, the environment, or the filesystem beyond the source path: the same source bytes produce the same archive bytes. Every id is a UUIDv5 derived from the source's declared namespace and a stable key; every timestamp comes from the source's `asOf`.
- **The archive is the decoder's.** `data.json` uses exactly the field names and value shapes of the `@Serializable` DTOs in `core/.../backup/BackupFormat.kt` (the decoder rejects unknown keys); the manifest uses exactly the keys `BackupCodec.encode` writes (read them from the source, `BackupCodec.kt` around lines 100–126); `formatVersion` 5, `schemaVersion` 5, `artifactFormatVersion` 1, `artifactCount` 0, `artifactBytes` 0, a non-blank `backupSetId`; `dataSha256` is the lowercase hex SHA-256 of the `data.json` bytes as written.
- **Serialisation pinned.** `data.json` and `manifest.json` are written with `json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)`. Every `Double` field in the DTOs is a Python `float` and every `Int`/`Long` field an `int` (a float in an integral field is a decode failure); `-0.0` is normalised to `0.0`; `asOf` → milliseconds uses integer arithmetic only. Two runs in separate processes with different `PYTHONHASHSEED` must produce identical bytes.
- **Size.** The archive must be at most 4 MiB (the API's import cap, `docs/api/v1.md` §Import-merge and `tools/servicetag-mcp/src/servicetag_mcp/client.py`); `build` refuses a larger one and prints the size; `inspect` prints the size.
- **Untouched:** `app/src/main`, `core/src/main`, `libs/`, `tools/servicetag-mcp/` (the tool count stays 21 and its tests stay byte-identical), `docs/api/v1.md`, `docs/architecture/`, every user-visible app string. The only Kotlin change is one new test class plus one test resource under `core/src/test`.
- **Versioning:** no app version change (the app is untouched). The tool's own version is `0.1.0`.
- **Toolchain:** Python `>=3.12` pinned by `.python-version` = `3.12`; `uv` with a committed `uv.lock`; no runtime dependency outside the standard library; `pytest` as the only dev dependency. Never work in `/tmp`.
- **Repository rules:** one commit per task, single casual subject, no body, no trailers, no attribution; identity from `git log -1 --format=%ae master`; no push (the controller pushes); no adb, emulator or device command from an implementer; `gradlew` invocations only through the sandbox tool the controller names.
- **Hygiene in every tracked file and report:** no device serials or models, no e-mail addresses, no `/home/<user>` paths (write `~`), no real pairing codes.

## Source format (the contract every task shares)

A single JSON document (UTF-8, `.json`). Top level:

| key | type | rule |
|---|---|---|
| `formatVersion` | int | `1` — the source format, not the archive's |
| `namespace` | string | non-empty; the UUIDv5 namespace seed (private, stable across regenerations) |
| `bundleKey` | string | non-empty; distinguishes bundles that share a namespace (`stage-a`) |
| `asOf` | string | ISO-8601 instant with an explicit offset (`Z` or `±HH:MM`), e.g. `2026-09-21T00:00:00Z`; the one clock |
| `tzId` | string | must be in `zoneinfo.available_timezones()` (the decoder does **not** validate zone ids — `BackupFormat.kt` carries `tzId` as a plain string — so a bad zone would import silently; the generator is the only gate); default for events |
| `deferred` | object | optional; any JSON the owner wants to keep beside the data (grouping intent, unsupported schedules); the generator ignores it, never emits it, and `check` reports that it is present |
| `assets` | array | in the order they should appear; each an **asset object** |

**Asset object:** `key` (string, unique across the source, `^[a-z0-9][a-z0-9-]{0,63}$`), `name` (non-blank), and optional `category`, `description`, `notes`, `manufacturer`, `model`, `serialNumber`, `vendor`, `location`, `warrantyNotes` (strings, default `""`); `purchaseOn`, `inServiceOn`, `warrantyExpiresOn` (dates matching `^\d{4}-\d{2}-\d{2}$` **and** a real calendar date — never `date.fromisoformat`, which accepts `20260921` and week dates the decoder rejects); `purchasePriceMinor` (int ≥ 0) with `currency` (required when a price is present; must be in the tool's committed allow-list `currencies.py` of ISO-4217 alphabetic codes that `java.util.Currency` resolves — the shape `^[A-Z]{3}$` alone is not enough, `Money.kt`); `seasonStartMmdd`/`seasonEndMmdd` (`MM-DD` and a real calendar day, e.g. not `02-30`; both or neither); `parent` (another asset's `key`, or absent — a child asset); `definitions`, `profiles`, `events` (arrays, default empty). Never present in the source: `id`, `status`, `retiredOn`, `templateKey`, timestamps — the generator owns them (`status` = `ACTIVE`, `retiredOn` = null, `templateKey` = null).

**Definition object:** `key` (matches `KEY_PATTERN` in `core/.../usecase/DefinitionCommands.kt`: `^[a-z][a-z0-9_]{0,39}$`, unique within its asset), `label` (non-blank), `unit` (default `""`), `valueType` (`NUMBER`｜`TEXT`｜`BOOLEAN`), `decimals` (0–4, default 0), `rangeLow`/`rangeHigh` (numbers, only for `NUMBER`, low ≤ high), `isMeter` (bool, default false), `kind` (`ENTERED`｜`DERIVED`, default `ENTERED`); a `DERIVED` definition is itself `NUMBER` with `isMeter` false, carries `formula` (`PERCENT_DROP`) and `sourceA`/`sourceB` (two *different* definition keys of the same asset, both `ENTERED`, `NUMBER`, neither a meter) — the rules of `core/.../model/Derived.kt` as `BackupCodec.decode` applies them.

**Profile object:** `key` (`^[a-z0-9][a-z0-9-]{0,63}$`, unique within its asset), `name` (non-blank), `eventKind` (one of `MAINTENANCE, INSPECTION, MEASUREMENT, TREATMENT, INCIDENT, REPLACEMENT, SEASON_START, SEASON_END, NOTE, CUSTOM`), `defaultTitle` (default = `name`), `fields` (array of `{definition: <key>, required: bool}` — each an `ENTERED` definition of the same asset, no definition listed twice), `consumables` (array of `{key, name, defaultQuantity: number|null, unit}`, keys unique within the profile).

**Event object:** `key` (`^[a-z0-9][a-z0-9-]{0,63}$`, unique within its asset), `kind` (as above), `occurredOn` (ISO date), `title` (non-blank), optional `notes`, `profile` (a profile key of the same asset), `tzId` (overrides the source default), `values` (object: definition key → value; the definition must be `ENTERED` and of the same asset; a `NUMBER` value is a JSON number, `TEXT` a non-blank string (the decoder refuses a blank text value, `Journal.kt`), `BOOLEAN` a boolean), `consumables` (array of `{key, name, quantity: number, unit}`, keys unique within the event — a declared key, never an array position, so reordering never changes an id).

## Id derivation (the contract every task shares)

`NS = uuid5(uuid.NAMESPACE_URL, "servicetag-bundle:" + namespace)`; every id is `uuid5(NS, key)` with these keys, verbatim:

| row | key |
|---|---|
| backup set | `set:<bundleKey>` |
| asset | `asset:<assetKey>` |
| definition | `definition:<assetKey>/<definitionKey>` |
| profile | `profile:<assetKey>/<profileKey>` |
| profile field | `profile-field:<assetKey>/<profileKey>/<definitionKey>` |
| profile consumable | `profile-consumable:<assetKey>/<profileKey>/<consumableKey>` |
| event | `event:<assetKey>/<eventKey>` |
| measurement | `measurement:<assetKey>/<eventKey>/<definitionKey>` |
| consumable usage | `consumable-usage:<assetKey>/<eventKey>/<consumableKey>` |

Events carry `source` = `IMPORT` and `sourceRef` = `<assetKey>/<eventKey>`, so the app's unique index on `(source, source_ref)` also detects a re-import of the same history under a different namespace. `sortOrder` is the 0-based position in the source array for assets' children (definitions, profiles, profile fields, profile consumables, event consumables); a **measurement's** `sortOrder` is its definition's `sortOrder` (the `values` object has no order of its own, so JSON key order must never decide it). `archivedAt` is null on definitions and profiles; a profile's `templateKey` is null; a measurement's `unit` is its definition's unit; `BOOLEAN` values are `valueNum` `1.0`/`0.0` with `valueText` null (the shape `Measurement.shapeMatches` accepts). `createdAt` = `updatedAt` = `asOf` in epoch milliseconds, on every row. Everything in this paragraph is content the merge compares: change any of it between two generations and a re-import plans CONTENT_DIFFERS.

## Invariants (each has a test in the matrix)

1. Same source bytes → byte-identical archive across processes (zip entry order, every `ZipInfo`'s `date_time` = 1980-01-01 00:00:00, `compress_type` DEFLATED, `create_system` 3, `external_attr` 0o644 << 16, empty `extra`; compact sorted-key JSON); the source tree contains no clock, environment, random or `uuid4` call (a structural grep).
2. A different `namespace` → every id differs; a different `bundleKey` with the same namespace → the same row ids, a different `backupSetId`.
3. The generated `data.json` decodes with `BackupCodec.decode` (proved by the JVM test on the committed fixture) and every DTO key set equals the decoder's exactly.
4. No NFC tag, attachment or external link is ever emitted (`nfcTags`, `externalLinks`, `attachments` are empty arrays).
5. Every validation failure names the offending path (`assets[3].definitions[1].key`) and no archive is written.
6. The CLI writes exactly one file, at the output path it was given, creates no directories, and writes nothing on failure.

---

### Task 1: Package skeleton and the validated source model

**Files:**
- Create: `tools/servicetag-bundle/pyproject.toml`, `.python-version`, `uv.lock`, `.gitignore` (`__pycache__/`, `*.pyc`, `.pytest_cache/`, `.venv/`, `*.zip`, `private/`, `out/`), `README.md` (purpose, the source format table above, the privacy rule, CLI usage — the CLI itself arrives in Task 3)
- Create: `tools/servicetag-bundle/src/servicetag_bundle/__init__.py`, `source.py`, `currencies.py` (the ISO-4217 allow-list as a plain tuple, with the one-line rule for adding a code)
- Test: `tools/servicetag-bundle/tests/test_source.py`, `tests/conftest.py` (a helper that builds a minimal valid source dict with fictional nouns and lets a test mutate one path)

**Interfaces:**
- Produces: `load_source(path: Path) -> Source` and `parse_source(obj: dict) -> Source`; frozen dataclasses `Source`, `Asset`, `Definition`, `Profile`, `ProfileField`, `ProfileConsumable`, `Event`, `ConsumableUse` mirroring the source format table with defaults applied; `class SourceError(ValueError)` carrying `path: str` (the JSON path of the failure) and a message. Validation is complete here so later tasks can assume a consistent model.

**Test matrix** (each test must fail without the validation it names):
- accepts the minimal valid source; defaults applied (`category` `""`, `decimals` 0, `kind` `ENTERED`, `defaultTitle` = `name`, `tzId` inherited).
- rejects, each with the exact JSON path in `SourceError.path`: wrong `formatVersion`; blank `namespace`; `asOf` without an offset; unknown top-level key; an asset key that violates the pattern; duplicate asset keys; `parent` naming an unknown key; a parent cycle (a→b→a); a price without a currency; a negative price; a malformed date; a season with one bound only; a season value that is not `MM-DD`; a definition key violating `KEY_PATTERN`; duplicate definition keys within one asset; `rangeLow` on a `TEXT` definition; `rangeLow > rangeHigh`; `decimals` 5; a `DERIVED` definition without sources, or whose source is `DERIVED`, or not `NUMBER`, or of another asset; a profile field naming a `DERIVED` definition or another asset's definition; an event `profile` of another asset; an event value for a `DERIVED` definition; a `NUMBER` value given as a string; a `BOOLEAN` value given as a number; an unknown `eventKind`; an unknown key inside any object (strict everywhere); a `DERIVED` definition that is `TEXT`, or a meter, or whose two sources are the same key, or whose source is a meter; a blank `TEXT` event value; a currency of the right shape but not in the allow-list; `02-30` as a season bound; `20260921` and `2026-W38-1` as dates; a `tzId` not in `zoneinfo.available_timezones()`; duplicate profile keys, duplicate event keys, duplicate consumable keys within a profile or an event; a profile listing one definition twice; `asOf` with a non-`Z` offset accepted and converted correctly; a `deferred` object of arbitrary shape accepted and reported.
- a source with two assets where the child precedes the parent is accepted (order is not a constraint on the source).

- [ ] Write the failing tests from the matrix; run `uv run --frozen pytest` and see them fail on the missing module.
- [ ] Implement `source.py`; run the suite green.
- [ ] Commit.

### Task 2: Deterministic ids and the row mapping

**Files:**
- Create: `tools/servicetag-bundle/src/servicetag_bundle/ids.py`, `rows.py`
- Test: `tests/test_ids.py`, `tests/test_rows.py`

**Interfaces:**
- Consumes: the Task 1 model.
- Produces: `namespace_of(source: Source) -> uuid.UUID`; `row_id(ns: uuid.UUID, key: str) -> str` (lowercase hyphenated); `build_rows(source: Source) -> BackupData` where `BackupData` is a plain `dict` with exactly the keys `assets`, `nfcTags`, `externalLinks`, `measurementDefinitions`, `eventProfiles`, `assetEvents`, `attachments`, each a list of plain dicts whose keys are exactly the decoder's DTO fields (write every field, including nulls and defaults). Assets are emitted parents-first (a stable topological order that preserves source order among siblings).

**Test matrix:**
- the id-key table above, one assertion per row kind, computed against the documented `uuid5` derivation (the test recomputes it independently with `uuid.uuid5`);
- same source twice → identical `build_rows` output; different `namespace` → no id in common; different `bundleKey` → identical rows; reordering an event's `consumables` array changes no id; reordering the keys of a `values` object changes nothing;
- typing: every integral DTO field is an `int` and every `Double` field a `float` in the rows (one test walks every row);
- **DTO field sets:** for each table, `set(row.keys())` equals a constant list transcribed from `BackupFormat.kt` — assets 24 fields, nfc tags 12, external links 8, definitions 18, profiles 12 with nested `fields` 4 and `consumables` 5, events 15 with nested `measurements` 6 and `consumables` 5, attachments 15 (verified by the plan review);
- value shapes: a `NUMBER` value lands in `valueNum` (a `float`, `-0.0` normalised) with `valueText` null and the definition's `unit`; `TEXT` in `valueText`; `BOOLEAN` as `valueNum` `1.0`/`0.0` with `valueText` null; a measurement's `sortOrder` equals its definition's;
- events: `source` `IMPORT`, `sourceRef` `<assetKey>/<eventKey>`, `occurredTime` null, `tzId` from the event or the source; `profileId` resolved or null;
- children after parents; `parentAssetId` resolved to the parent's id; `status` `ACTIVE`; timestamps equal `asOf` millis everywhere; `sortOrder` = position;
- `nfcTags`, `externalLinks`, `attachments` are `[]`.

- [ ] Write the failing tests; run; implement; run green; commit.

### Task 3: The archive writer and the CLI

**Files:**
- Create: `tools/servicetag-bundle/src/servicetag_bundle/archive.py`, `cli.py`; add `[project.scripts] servicetag-bundle = "servicetag_bundle.cli:main"` to `pyproject.toml`; extend `README.md` with the three commands
- Test: `tests/test_archive.py`, `tests/test_cli.py`

**Interfaces:**
- Consumes: `build_rows`, `namespace_of`, `row_id`.
- Produces: `write_archive(source: Source, out: Path) -> Manifest` (a dict of the manifest as written) and `archive_bytes(source: Source) -> bytes`; CLI `servicetag-bundle check SOURCE` (validate only; exit 0/1; errors as `path: message` lines on stderr), `servicetag-bundle build SOURCE OUT` (refuses to overwrite unless `--force`; prints the counts and `backupSetId`), `servicetag-bundle inspect ARCHIVE` (prints the manifest's counts and versions without decoding rows). `appVersion` in the manifest = `servicetag-bundle/0.1.0`.

**Test matrix:**
- `archive_bytes` in two separate subprocesses run with different `PYTHONHASHSEED` values → identical bytes; the zip has exactly two entries in the order `manifest.json`, `data.json`, each `ZipInfo` with `date_time` (1980, 1, 1, 0, 0, 0), `compress_type` DEFLATED, `create_system` 3, `external_attr` 0o644 << 16 and empty `extra`; a structural test greps `src/` for `time.`, `datetime.now`, `datetime.today`, `os.environ`, `random`, `uuid4` and finds nothing;
- size: a source that renders over 4 MiB is refused by `build` (the test monkeypatches the limit down rather than building 4 MiB) and `inspect` prints the size of a real archive;
- the manifest: `formatVersion` 5, `schemaVersion` 5, `artifactFormatVersion` 1, `artifactCount` 0, `artifactBytes` 0, `backupSetId` = `row_id(ns, "set:<bundleKey>")`, `createdAt` = `asOf` millis, `dataSha256` = sha256 of the `data.json` entry bytes (recomputed by the test from the zip), `counts` = exactly the keys `BackupCodec.encode` writes, with the right numbers for a source that has nested rows (profile fields, consumables, measurements counted individually);
- `data.json` parses back and equals `build_rows(source)`;
- `check` on an invalid source exits 1 with the path on stderr and creates nothing; `check` on a source with `deferred` says so; `build` on an invalid source writes nothing; `build` writes exactly one file at the given path and does not create missing parent directories (exit 1 with a message instead); `build` without `--force` refuses an existing output; `inspect` prints the counts and the size of an archive it just built.

- [ ] Write the failing tests; run; implement; run green; commit.

### Task 4: Decoder conformance — the committed synthetic fixture and the JVM test

**Files:**
- Create: `tools/servicetag-bundle/fixtures/synthetic-estate.json` (fictional nouns and brands only: a handful of top-level assets, one child asset, one `DERIVED` definition, one profile with a field and a consumable, events with `NUMBER`, `TEXT` and `BOOLEAN` values and a consumable usage — enough to exercise every decoder rule the generator claims to satisfy)
- Create: `core/src/test/resources/stage-a-synthetic-estate.zip` (built from the fixture by the tool; committed)
- Create: `core/src/test/kotlin/com/loosecannon/servicetag/core/backup/StageABundleConformanceTest.kt`
- Test: `tools/servicetag-bundle/tests/test_golden.py`
- Modify: `.github/workflows/ci.yml` — add a `bundle` job identical in shape to the `mcp` job (same pinned action SHAs, `working-directory: tools/servicetag-bundle`, cache glob on its `uv.lock`)

**Interfaces:**
- Consumes: the Task 3 CLI to build the fixture archive.
- Produces: the committed archive that both sides test against.

**Test matrix:**
- JVM: `BackupCodec.decode(resource bytes)` succeeds; the decoded counts per table equal the fixture's (assert the numbers); one asset's `parentAssetId` resolves to the child's parent; one event's measurement carries the expected value; `manifest.formatVersion` 5 and `backupSetId` non-blank; and, parsing `data.json` from the resource as a JSON tree, every object's key set equals the element names of the matching DTO's `serializer().descriptor` (top-level tables and nested `fields`/`consumables`/`measurements`) — so a DTO field added later with a default cannot drift past the generator unnoticed. (Tampering is already covered by `BackupCodecTest`; do not duplicate it.)
- Python golden: `archive_bytes(load_source(fixture))` is compared to the committed resource at the **payload level**, not as raw zip bytes — the same entry names in the same order; identical **decompressed** bytes per entry; identical `ZipInfo` `date_time`, `compress_type`, `create_system`, `external_attr` and `extra`; and `sha256(data.json bytes)` equal to the committed manifest's `dataSha256` (which is what the decoder checks). Raw bytes are not compared across machines because DEFLATE output may differ between zlib builds (this workstation links zlib-ng, CI stock zlib) while decoding identically; byte identity (Invariant 1) is a same-machine, cross-process guarantee. The test **fails** (never skips) when the resource is missing — so a generator change that alters output fails CI until the fixture is deliberately regenerated and both files committed together. The README documents that regeneration step. The fixture's `namespace` is a fictional literal.
- CI: the `bundle` job runs `uv run --frozen pytest` in the tool's directory.

- [ ] Build the fixture and the archive; write both tests; run the Python suite and `:core:test` (through the controller's sandbox tool if the shell refuses `gradlew`); commit.

### Task 5: The load runbook

**Files:**
- Modify: `tools/servicetag-bundle/README.md` — a "Loading a bundle" section
- Modify: `README.md` — one sentence under the automation-API subsection pointing at the tool

**Content the section must carry (prose, no private data):** the sequence `check` → `build` → MCP `import_merge(path, plan_only=True)` → what to verify in the report (`applicable` true, `conflicts` empty, the `insert` tally per table equal to `inspect`'s counts, `skipped` 0 for a first load) → `import_merge(path)` → `status` counts grew by exactly the inserted rows → a second `import_merge(path, plan_only=True)` shows `insert` 0 and `identical` equal to the previous inserts; the mapping between the archive's eleven manifest counts and the report's seven tallies (`assets`, `definitions`, `profiles`, `links`, `tags`, `events`, `attachments`): nested rows (profile fields and consumables, measurements, consumable usages) travel inside their parent rows and have no tally of their own — verify them by reading one imported row back (`get_asset`, `list_profiles`, `list_events`); that `duplicateCandidates` are hints and never block; what a CONFLICT means and that nothing was written; that changing the source's `namespace` after a load makes every row a new id and every event a CONFLICT on its `(source, sourceRef)` key — the namespace is permanent once loaded; that the same archive can be loaded on another phone later and will plan as IDENTICAL/INSERT per row; that NFC tags are bound on the phone afterwards, never carried by the bundle.

- [ ] Write it; commit.

### Task 6 (controller-run): proofs and the release-level gate

1. Unit gate from scratch: `./gradlew :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest --rerun-tasks --console=plain` → BUILD SUCCESSFUL; `:core` gains exactly one class; `app` count unchanged at 316/42.
2. `cd tools/servicetag-bundle && uv run --frozen pytest` green; `cd tools/servicetag-mcp && uv run --frozen pytest` still 113; `grep -c '^@mcp.tool' tools/servicetag-mcp/src/servicetag_mcp/server.py` still 21; `git diff --stat <base> HEAD -- app/src/main core/src/main libs tools/servicetag-mcp docs/api` empty.
3. Structural: `python3 -c "import tomllib;..."` confirms no runtime dependency in the tool's `pyproject.toml`; the zip built from the fixture has two entries; the JVM resource equals the tool's output (`cmp`).
4. End-to-end on `emulator-5554` (never a phone here): clean install of the current debug build; Developer API screen open; through a connected MCP client: `import_merge(fixture archive, plan_only=True)` → applicable, `insert` tallies equal the fixture's counts, 0 conflicts, 0 skipped; `status` unchanged; `import_merge(fixture archive)` → applicable, `status` grew by exactly the inserted rows; `import_merge(plan_only=True)` again → `insert` 0, all `identical`; edit one imported asset through `update_asset`, re-plan → exactly one `CONFLICT` with reason `CONTENT_DIFFERS`, apply refused with the report as data and `status` unchanged; `pm clear` after.
5. Hygiene over the whole range: no private nouns (the controller greps the private audit's row names against the diff, privately), no e-mail, no home path, no serial; the UUID grep excludes the binary fixture and the fixture's own synthetic ids; a real `backupSetId` is never pasted anywhere; single-subject commits; one author identity.
6. Push, CI green on the exact tip (both Python jobs and the unit job). No release tag: the app is unchanged.

**Then Stage A itself (controller, private, not part of this plan's git range):** author the private source in the owner's folder from the audit and the owner's real unit counts; `check`; `build`; load on the development phone through the MCP with the sequence in the runbook; record counts only in the ledger; the owner reviews the result on the phone.
