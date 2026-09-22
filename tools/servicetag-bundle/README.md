# servicetag-bundle

Turns a private normalized inventory (assets, child assets, measurement definitions, quick-action
profiles, known maintenance history) into a ServiceTag format-5 backup archive with deterministic
ids, so the archive can be loaded onto a phone through the 1.1.0 Developer API's `import_merge`
(plan first, then apply) and re-loaded idempotently — a second plan against the same source is all
`CONTENT_IDENTICAL`.

This package is stdlib-only at runtime (`json`, `zipfile`, `hashlib`, `uuid`, `datetime`): no
third-party dependency ships with the built tool. It never reads the clock, the environment, or
the filesystem beyond the one source path it is given — the same source bytes always produce the
same archive bytes.

## Status

Through Task 4 of the Stage-A bundle plan: the package skeleton, the validated source model
(`servicetag_bundle.source`), deterministic ids and row mapping (`servicetag_bundle.ids`,
`servicetag_bundle.rows`), the archive writer plus CLI (`servicetag_bundle.archive`,
`servicetag_bundle.cli`), and decoder conformance against the real Kotlin `BackupCodec`. It reads
and fully validates a source document, derives every row's id, and writes a format-5 backup
archive with a matching manifest — byte-identical across processes for the same source. A
committed synthetic fixture (`fixtures/synthetic-estate.json`, fictional nouns and brands only)
is built into a committed archive that both a Python golden test and a JVM test
(`core/src/test/kotlin/.../backup/StageABundleConformanceTest.kt`) exercise from their own side.

## Decoder conformance and regenerating the fixture archive

`core/src/test/resources/stage-a-synthetic-estate.zip` is a format-5 backup archive built from
`fixtures/synthetic-estate.json` by this tool's own CLI, committed so the JVM's
`BackupCodec.decode` and this package's Python golden test (`tests/test_golden.py`) both exercise
the exact same bytes. The Python test never compares raw zip bytes against it across machines
(DEFLATE output depends on the zlib build the interpreter links against), only the decompressed
payload of each entry, the pinned `ZipInfo` fields, and the `data.json` hash the decoder actually
checks — see `tests/test_golden.py`'s module docstring.

**Whenever the generator's output changes** (a field added to a row, an id derivation rule
changed, a new table), regenerate the resource from the fixture and commit both files together in
the same change:

```
uv run servicetag-bundle build fixtures/synthetic-estate.json \
    ../../core/src/test/resources/stage-a-synthetic-estate.zip --force
```

Then run both suites (`uv run --frozen pytest` here, and `:core:test` on the Kotlin side) before
committing — a stale resource fails the golden test and, separately, the JVM conformance test's
DTO-key-set check, rather than passing on drifted data.

## The CLI

Installed as `servicetag-bundle` (`uv run servicetag-bundle ...` from this directory, or
`uv tool install .` elsewhere). Three subcommands:

```
servicetag-bundle check SOURCE
```

Validates `SOURCE` only — writes nothing. Exits 0 if it's a valid source document, printing
whether it carries a `deferred` payload; exits 1 with `path: message` on stderr (the JSON path of
the first thing wrong) if it doesn't.

```
servicetag-bundle build SOURCE OUT [--force]
```

Validates `SOURCE`, then writes a format-5 backup archive to `OUT` and prints its `backupSetId`
and table counts. Refuses to overwrite an existing `OUT` unless `--force` is given; refuses to
create `OUT`'s parent directory; refuses an archive over the 4 MiB import cap. Writes nothing on
any failure.

```
servicetag-bundle inspect ARCHIVE
```

Prints `ARCHIVE`'s manifest — format/schema/app versions, `backupSetId`, table counts, and the
archive's size on disk — without decoding any row.

## The source format

A single JSON document (UTF-8, `.json`). Top level:

| key | type | rule |
|---|---|---|
| `formatVersion` | int | `1` — the source format, not the archive's |
| `namespace` | string | non-empty; the UUIDv5 namespace seed (private, stable across regenerations) |
| `bundleKey` | string | non-empty; distinguishes bundles that share a namespace (`stage-a`) |
| `asOf` | string | ISO-8601 instant with an explicit offset (`Z` or `±HH:MM`), e.g. `2026-09-21T00:00:00Z`; the one clock |
| `tzId` | string | must be in `zoneinfo.available_timezones()`; default for events |
| `deferred` | object | optional; any JSON the owner wants to keep beside the data; the generator ignores it, never emits it, and a later `check` command reports that it is present |
| `assets` | array | in the order they should appear; each an **asset object** |

**Asset object:** `key` (string, unique across the source, `^[a-z0-9][a-z0-9-]{0,63}$`), `name`
(non-blank), and optional `category`, `description`, `notes`, `manufacturer`, `model`,
`serialNumber`, `vendor`, `location`, `warrantyNotes` (strings, default `""`); `purchaseOn`,
`inServiceOn`, `warrantyExpiresOn` (dates matching `^\d{4}-\d{2}-\d{2}$` **and** a real calendar
date — never `date.fromisoformat`, which accepts `20260921` and week dates the decoder rejects);
`purchasePriceMinor` (int ≥ 0) with `currency` (required when a price is present; must be in the
tool's committed allow-list `currencies.py` of ISO-4217 alphabetic codes that `java.util.Currency`
resolves — the shape `^[A-Z]{3}$` alone is not enough); `seasonStartMmdd`/`seasonEndMmdd`
(`MM-DD` and a real calendar day, e.g. not `02-30`; both or neither); `parent` (another asset's
`key`, or absent — a child asset); `definitions`, `profiles`, `events` (arrays, default empty).
Never present in the source: `id`, `status`, `retiredOn`, `templateKey`, timestamps — the
generator owns them.

**Definition object:** `key` (`^[a-z][a-z0-9_]{0,39}$`, unique within its asset), `label`
(non-blank), `unit` (default `""`), `valueType` (`NUMBER`｜`TEXT`｜`BOOLEAN`), `decimals` (0–4,
default 0), `rangeLow`/`rangeHigh` (numbers, only for `NUMBER`, low ≤ high), `isMeter` (bool,
default false), `kind` (`ENTERED`｜`DERIVED`, default `ENTERED`); a `DERIVED` definition is itself
`NUMBER` with `isMeter` false, carries `formula` (`PERCENT_DROP`) and `sourceA`/`sourceB` (two
*different* definition keys of the same asset, both `ENTERED`, `NUMBER`, neither a meter).

**Profile object:** `key` (`^[a-z0-9][a-z0-9-]{0,63}$`, unique within its asset), `name`
(non-blank), `eventKind` (one of `MAINTENANCE, INSPECTION, MEASUREMENT, TREATMENT, INCIDENT,
REPLACEMENT, SEASON_START, SEASON_END, NOTE, CUSTOM`), `defaultTitle` (default = `name`), `fields`
(array of `{definition: <key>, required: bool}` — each an `ENTERED` definition of the same asset,
no definition listed twice), `consumables` (array of `{key, name, defaultQuantity: number|null,
unit}`, keys unique within the profile).

**Event object:** `key` (`^[a-z0-9][a-z0-9-]{0,63}$`, unique within its asset), `kind` (as above),
`occurredOn` (ISO date), `title` (non-blank), optional `notes`, `profile` (a profile key of the
same asset), `tzId` (overrides the source default), `values` (object: definition key → value; the
definition must be `ENTERED` and of the same asset; a `NUMBER` value is a JSON number, `TEXT` a
non-blank string, `BOOLEAN` a boolean), `consumables` (array of `{key, name, quantity: number,
unit}`, keys unique within the event).

## Privacy

Nothing private enters git: no household inventory, equipment names, locations, task titles, or
the private dataset. Every fixture in this repository uses fictional nouns and fictional brands.
The private source file and the archives built from it live in the owner's documents folder or
gitignored storage — never in this repo (see `.gitignore`) — and later the CLI works with paths
outside the repository.

## Development

```
uv run --frozen pytest
```
