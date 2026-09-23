# servicetag-schedules

Plans and applies a **schedule manifest** — the owner's deferred maintenance cadences, classified
against the 1.2 schedule model — onto a phone through the 1.1.0/1.2.0 Developer API's maintenance
tools (`create_group`, `create_schedule`), idempotently, with a plan the owner can read before
anything is written. See
`docs/superpowers/plans/2026-09-23-servicetag-stage-b/plan.md` for the manifest contract and the
eight resolution invariants this tool implements, and
`docs/superpowers/plans/2026-09-23-servicetag-stage-b/B01-schedules-loader.md` for this package's
own shape.

This package never edits, archives or deletes anything that already exists on the phone. It never
prints a pairing code, a serial or an asset id — only counts, decisions, manifest keys and reasons.

## How it works

1. `manifest.load` reads a manifest JSON document and validates its **shape** — types, enum
   membership, well-formed dates, unknown keys rejected — the same way `servicetag_bundle.source`
   validates a backup source.
2. `phone.snapshot` reads the phone's current assets, profiles, groups and schedules through the
   sibling `servicetag-mcp` package's in-process client (`mcp.Client(servicetag_mcp.server.mcp)`),
   into a plain `Inventory`.
3. `plan.plan(manifest, inventory)` is **pure** — no I/O — and decides, entry by entry, `CREATE`,
   `IDENTICAL`, `CONFLICT` or `ERROR`, following the plan's eight invariants: exact-name asset and
   profile resolution (ambiguous or missing is `ERROR`, and propagates to whatever depends on it),
   group identity by name among non-archived groups, schedule identity by `(target, title)`, the
   narrower rule a group-targeted schedule must satisfy, and the two duplicate checks (a manifest
   key reused, or two entries resolving to the same identity).
4. `apply.apply(manifest, plan, client)` refuses unless the plan is **clean** (no `CONFLICT` or
   `ERROR` anywhere — one problem anywhere means nothing is written), then creates groups before
   schedules, re-snapshotting the phone first so every id it writes with is the one there right now
   rather than one implied by an older read. It returns counts plus a re-plan, which should come
   back all `IDENTICAL`.

## The manifest contract (v1)

```json
{"manifestVersion": 1, "asOf": "YYYY-MM-DD",
 "groups":    [{"key": "k", "name": "…", "description": "…"|null, "members": [{"asset": "<exact asset name>"}, …]}],
 "schedules": [{"key": "k", "title": "…", "target": {"asset": "<exact asset name>"} | {"group": "<manifest group key>"},
                "description": "…"|null,
                "time": {"interval": N, "unit": "DAY|WEEK|MONTH|YEAR", "basis": "FIXED|COMPLETION", "anchorOn": "YYYY-MM-DD"},
                "leadDays": N, "completionMode": "QUICK|FORM", "profile": "<exact profile name on that asset>"|null,
                "seasonBehavior": "IGNORE"|"FOLLOW_ASSET", "remindersEnabled": true|false,
                "tags": ["…"], "source": {"todoistId": "…"|null, "cadence": "…"|null}}]}
```

`manifest.load` enforces the shape only (unknown keys are errors, every required key must be
present, every date and enum must be well-formed). A duplicate `key` among groups or among
schedules loads without complaint — that is one of `plan.plan`'s invariants (both entries come back
`ERROR`), not a load-time failure, because a load-time error would stop before a caller could see
which entries the problem touches.

## The CLI

Installed as `servicetag-schedules` (`uv run servicetag-schedules ...` from this directory).

```
servicetag-schedules plan MANIFEST --code CODE [--serial SERIAL]
```

Pairs, snapshots the phone, computes the plan, and prints one line per entry (`kind`, `key`,
`decision`, `reason`) plus the summary counts. Writes nothing. Exits 0 iff the plan is clean.

```
servicetag-schedules apply MANIFEST --code CODE [--serial SERIAL]
```

Does the same plan-and-print first; if it is not clean, refuses and exits 1 without writing
anything. If it is clean, applies it, prints the counts created, then re-plans and prints that too.
Exits 0 only when the final re-plan is entirely `IDENTICAL`.

`--serial` overrides `SERVICETAG_ADB_SERIAL`, exactly as `servicetag-mcp` reads it — nothing in this
repository names a device.

## Privacy

Nothing private enters git. `tests/fixtures/estate-manifest.json` uses fictional asset names and
cadences only. The real manifest — the owner's classified Todoist migration — lives in the owner's
own documents folder and is passed to the CLI by path, never committed here.

## Development

```
uv sync --frozen
uv run --frozen pytest
```
