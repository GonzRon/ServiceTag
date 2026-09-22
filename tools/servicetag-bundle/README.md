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

This is Task 1 of the Stage-A bundle plan: the package skeleton and the validated source model
(`servicetag_bundle.source`). It reads and fully validates a source document into a tree of frozen
dataclasses; it does not yet derive ids or write an archive. **The CLI arrives in Task 3** —
for now this package is a library, exercised by its test suite.

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
