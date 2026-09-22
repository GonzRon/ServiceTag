# servicetag-bundle

Turns a private normalized inventory (assets, child assets, measurement definitions, quick-action
profiles, known maintenance history) into a ServiceTag format-5 backup archive with deterministic
ids, so the archive can be loaded onto a phone through the 1.1.0 Developer API's `import_merge`
(plan first, then apply) and re-loaded idempotently — a second plan against the same source is all
`IDENTICAL`.

This package is stdlib-only at runtime (`json`, `zipfile`, `hashlib`, `uuid`, `datetime`): no
third-party dependency ships with the built tool. It never reads the clock, the environment, or
the filesystem beyond the one source path it is given — on the same machine, the same source bytes
always produce the same archive bytes (see the cross-machine caveat below).

## Status

Complete through Task 5 of the Stage-A bundle plan: the package skeleton, the validated source
model (`servicetag_bundle.source`), deterministic ids and row mapping (`servicetag_bundle.ids`,
`servicetag_bundle.rows`), the archive writer plus CLI (`servicetag_bundle.archive`,
`servicetag_bundle.cli`), decoder conformance against the real Kotlin `BackupCodec`, and the load
runbook (see "Loading a bundle" below). It reads and fully validates a source document, derives
every row's id, and writes a format-5 backup archive with a matching manifest — byte-identical
across processes for the same source. A committed synthetic fixture
(`fixtures/synthetic-estate.json`, fictional nouns and brands only) is built into a committed
archive that both a Python golden test and a JVM test
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
changed, a new table) **or the fixture itself changes**, regenerate the resource from the fixture
and commit both files together in the same change. A fixture whose shape changes (a different
number of assets, definitions, events, …) also needs the hardcoded per-table counts in
`StageABundleConformanceTest` updated to match.

```
uv run servicetag-bundle build fixtures/synthetic-estate.json \
    ../../core/src/test/resources/stage-a-synthetic-estate.zip --force
```

Then run both suites (`uv run --frozen pytest` here, and `:core:test` on the Kotlin side) before
committing — a stale resource fails the golden test and the JVM test's per-table counts; the DTO
key-set check is a separate, narrower guard that catches a DTO gaining a field without any
generator change, not staleness in the resource itself.

## The CLI

Installed as `servicetag-bundle` (`uv run servicetag-bundle ...` from this directory, or
`uv tool install .` elsewhere). Three subcommands:

```
servicetag-bundle check SOURCE
```

Validates `SOURCE` only — writes nothing. Exits 0 if it's a valid source document, printing
whether it carries a `deferred` payload; exits 1 on stderr if it doesn't, always naming `SOURCE` —
`SOURCE: message` when the file itself can't be read or parsed, `SOURCE: path: message` for a
validation failure, where `path` is the JSON path of the first thing wrong.

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

## Loading a bundle

`check`, `build` and `inspect` only ever touch the archive on disk; nothing reaches a phone until
it is handed to the 1.1.0 Developer API's `import_merge` tool
(`tools/servicetag-mcp/src/servicetag_mcp/server.py`), which speaks `POST
/v1/import-merge/plan` and `/apply` (`docs/api/v1.md` §"The additive merge import"). That screen
has to be open and paired first — see `tools/servicetag-mcp/README.md`.

**The sequence for a first load:**

1. `servicetag-bundle check SOURCE`, then `servicetag-bundle build SOURCE OUT`.
2. `servicetag-bundle inspect OUT` and note its eleven `counts`: `assets`, `nfcTags`,
   `externalLinks`, `measurementDefinitions`, `eventProfiles`, `assetEvents`, `profileFields`,
   `profileConsumables`, `measurements`, `consumableUsages`, `attachments`.
3. `import_merge(OUT, plan_only=True)` and check the plan before doing anything else:
   - `applicable` is `true` and `conflicts` is empty.
   - Each of the report's seven tallies — `assets`, `definitions`, `profiles`, `links`, `tags`,
     `events`, `attachments`, each `{insert, identical, conflict, skipped}` — has `insert` equal
     to the matching `inspect` count (the mapping is below) and `skipped` at `0`.
4. `import_merge(OUT)` (no `plan_only`) to apply it.
5. `status` and confirm its `counts` (the same seven table names) grew by exactly the rows the
   plan inserted.
6. `import_merge(OUT, plan_only=True)` again: every table's `insert` is now `0`, and `identical`
   equals what `insert` was on the first plan — the same archive never has anything left to add.

**Manifest counts to report tallies.** The manifest carries eleven counts; the report carries
seven, because a profile's fields and consumables, and an event's measurements and consumable
usages, travel inside their parent row and have no tally of their own:

| manifest `counts` key | report tally | notes |
|---|---|---|
| `assets` | `assets` | |
| `measurementDefinitions` | `definitions` | |
| `eventProfiles` | `profiles` | `profileFields` and `profileConsumables` are nested inside each profile row — verify them by reading the imported profiles back with `list_profiles` (or `get_asset` for the asset the profile belongs to) |
| `assetEvents` | `events` | `measurements` and `consumableUsages` are nested inside each event row — verify them by reading the imported events back with `list_events` |
| `nfcTags` | `tags` | always `0` in a bundle this tool builds — the source format has no way to describe a tag, and NFC tags are never carried by the archive; they get bound to an asset on the phone afterward, outside this tool and outside the merge import entirely |
| `externalLinks` | `links` | always `0` — the pre-split link tombstones have no representation in this source format |
| `attachments` | `attachments` | always `0` — this tool never emits an attachment row (see `rows.py`'s `build_rows`), so on one of its own archives the merge's `ATTACHMENT_STORE_NOT_CONFIGURED` skip path never has a row to act on; that code exists for archives that do carry attachment rows, such as the phone's own backup, not for a Stage-A bundle |

Because `nfcTags`, `externalLinks` and `attachments` are always `0` on an archive this tool builds,
the `tags`, `links` and `attachments` tallies in every plan and every apply report are all-zero
too — there is nothing there to insert, match, conflict over or skip.

**What a `CONFLICT` means.** A conflict on a table means at least one row in the archive collides
with what the phone already has — most often the id is already there and some field differs
(`CONTENT_DIFFERS`), but it can also be a collision on a unique key such as a definition's key on
its asset or an event's `(source, sourceRef)` pair. A single conflict anywhere in the plan makes
`applicable` false, and applying such a plan writes **nothing at all** — the report tells you what
would have happened, not what did.

**`duplicateCandidates` are hints, never a block.** An entry there means an incoming asset and a
local one share a non-blank manufacturer, model and serial number — worth a look, but it never
appears in `conflicts` and never stops an apply.

**The namespace is permanent once a bundle has been loaded onto a phone.** Every row's id is a
UUIDv5 of the source's `namespace` and the row's own key, so changing `namespace` and rebuilding
gives every row a new id — but an event's `sourceRef` is just `"<asset key>/<event key>"`, with no
namespace in it, so the *same* `(source, sourceRef)` pair is still on the phone from the earlier
load under the old namespace. The result is a conflict (`EVENT_SOURCE_REF_TAKEN`) on every event,
and the plan is not applicable. Treat `namespace` as fixed from the moment its first bundle is
loaded anywhere.

**A source edit after a load can't be re-applied by merge.** Editing the source and rebuilding
without changing `namespace` reproduces the same ids for the rows that didn't move, but with the
edited field values — which is exactly `CONTENT_DIFFERS`, a conflict, on every changed row. A
merge only ever inserts; it has no update verdict. Make the correction on the phone itself with
the ordinary edit tools (`update_asset`, `save_definition`, `save_profile`, `update_event`, …), or
get the source right before its first load.

**The same archive loaded on a different phone later** plans exactly as it would on an empty
phone: every row `INSERT`, nothing `IDENTICAL` yet, because that phone has never seen these ids.
Loading a bundle twice onto two phones is safe and expected — the deterministic ids are what make
it idempotent per phone, not shared across phones.

## The source format

A single JSON document (UTF-8, `.json`). Top level:

| key | type | rule |
|---|---|---|
| `formatVersion` | int | `1` — the source format, not the archive's |
| `namespace` | string | non-empty; the UUIDv5 namespace seed (private, stable across regenerations) |
| `bundleKey` | string | non-empty; distinguishes bundles that share a namespace (`stage-a`) |
| `asOf` | string | ISO-8601 instant with an explicit offset (`Z` or `±HH:MM`), e.g. `2026-09-21T00:00:00Z`; the one clock |
| `tzId` | string | must be in `zoneinfo.available_timezones()`; default for events |
| `deferred` | object | optional; any JSON the owner wants to keep beside the data; the generator ignores it, never emits it, and the `check` command reports that it is present |
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
gitignored storage — never in this repo (see `.gitignore`) — and the CLI works with paths outside
the repository.

## Development

```
uv run --frozen pytest
```
