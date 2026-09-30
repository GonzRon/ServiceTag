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

**ServiceTag 1.4 changes the comparison, not the contract.** The manifest still says
`seasonBehavior`, and `apply` still writes it through the MCP's deprecated `season_behavior`
argument, which the app translates. A 1.4 phone stores a **service policy** instead and reports 1.3's
`seasonBehavior` only as a derived projection, so the re-plan reads each row's `servicePolicy` and
`policyOffsetDays` and compares them with the manifest's value mapped by spec §4.1
(`legacy_mapping.to_policy`: `IGNORE` is `CONTINUOUS`, `FOLLOW_ASSET` is `IN_SERVICE_AT_START` at 0)
— which keeps a loaded manifest re-planning `IDENTICAL`. A row whose policy no manifest value maps to
(`PRE_SERVICE`, a non-zero offset, `IN_SERVICE_RESUME_CLAMPED`) is a `CONFLICT` whose reason names
both policies, and this tool never changes it. The mapping is held to the repository's golden
`docs/api/legacy-season-mapping.json` by `tests/test_legacy_mapping.py`. It needs the lockstep
`servicetag-mcp`, which writes only to ServiceTag 1.4.0 or later — and the loader checks the same
thing first: `phone.snapshot` reads the MCP's `status` before anything else and refuses a phone whose
`schemaVersion` is missing or below 8, so `plan` and `apply` both print that one line and exit `2`
without reading a row.

**1.4.1 (issue #80): `apply` sends the reminder provider itself.** Every schedule it creates carries
the row the app's own editor writes — one `LOCAL` provider, enabled exactly when `remindersEnabled`
is — instead of leaving `providers` to the app's default, which before ServiceTag 1.4.1 stored none.
The manifest has no provider key, and the re-plan never compares providers or `updatedAt`, so a row
loaded before 1.4.1 and one since repaired by `repair_schedule_providers` both re-plan `IDENTICAL`.

**#92: an optional `replacements` list.** `manifestVersion` stays 1, and a manifest without the key
loads and plans exactly as before (no replace tool is called). Each entry replaces one asset through
the MCP's `replace_asset`, the phone's own Replace:

```json
"replacements": [{"key": "pump-2026", "predecessor": "Example pump", "retiredOn": "2026-09-30",
                  "successor": {"name": "Example pump", "model": "B-2", "purchaseOn": "2026-09-28"},
                  "carry": {"season": false, "manualPhase": null, "setup": true, "notes": false},
                  "schedules": ["Flush the pump"], "scheduleStartOn": "2026-10-01",
                  "groups": ["Pool kit"], "moveTags": ["front plate"]}]
```

`key`, `predecessor` (an exact asset name) and `successor.name` are required; the other successor
keys are the replace draft's (`category`, `manufacturer`, `model`, `serialNumber`, `purchaseOn`,
`inServiceOn`, `purchasePriceMinor`, `currency`, `vendor`, `location`, `warrantyExpiresOn`,
`warrantyNotes`, `parentAssetId`). Nothing is ticked or defaulted unless the manifest says so:
`scheduleStartOn`, `retiredOn` and `manualPhase` are sent only when given. Each schedule title, group
name and tag label must match exactly one row of the phone's replace offer, and only the ids are sent;
each tag moves only when its own label is named, and a move re-targets the binding without any NFC
write.

`phone.snapshot` reads every asset carrying the predecessor's name, archived and retired included,
and each one's succession and successor. Then, for the one candidate, it reads the offer and the
phone's own plan (`plan_only`). `plan.plan` identifies the predecessor through the succession, never
by the name alone, since a successor usually keeps the old name. It decides in this order:

- A namesake that is itself a successor is never a candidate.
- **IDENTICAL** comes first: the one remaining namesake is already replaced, and the successor
  matches the name **and every successor field the manifest gives**, as the phone stores them:
  trimmed, a blank value as absent, and `category` by the phone's category key (case, whitespace
  and invisible characters aside, as `CategoryKey` rules). This is stricter than the MCP's name-only
  answer. Carried schedules, groups and tags are not compared.
- A replaced one whose successor differs in any field the manifest gives is **CONFLICT** ("replaced
  by something else"), as is a held one. A replacement cannot be undone.
- **CREATE** only when the one candidate is not replaced, its offer is eligible, every name resolves,
  and the phone's plan is clean.
- Everything else is **ERROR**: none, more than one (a same-named successor of a differently named
  asset counts), only successors, a name that does not resolve, or a plan problem, whose code the
  reason carries.

**A manifest may not both replace an asset and name it in `schedules` or `groups`.** Such a
replacement plans **ERROR** while it would be a `CREATE`: after it, the name finds the successor (a
retired asset is never resolved), a group that held the old asset keeps it as an open member, and
carried schedules take the new start date, so the manifest could never re-plan `IDENTICAL`. Replace
first, in a manifest of its own, then load the successor's schedules in another. Once a replacement
reads `IDENTICAL`, remove its entry. Give `category`, `location` and `parentAssetId` explicitly: the
loader never applies the phone form's prefill.

`apply` writes replacements last, one call per fresh `CREATE`. The MCP plans again and applies only
its own clean plan with that plan's digest, so this tool never handles a digest. The phone may
answer `REPLACE_STALE`, `ASSET_ALREADY_REPLACED`, `IDENTICAL` or `UNKNOWN`. Each of these is printed
as a note naming the entry key, and none is retried. The closing re-plan re-reads the succession.
The phone's replace routes need ServiceTag at schema 16. An older app is refused by the MCP, and the
CLI prints that one line and exits 2.

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

**Exit codes, both subcommands:** `0` — the plan is clean (`plan`) or the final re-plan is entirely `IDENTICAL` (`apply`); `1` — the plan holds a `CONFLICT` or `ERROR`, or `apply` refused because the phone changed between the plan and the write (nothing written); `2` — a phone-side failure before or during the run: pairing refused (a stale code — reopen the Developer API screen and pass the new one), the device absent, or a write refused by the phone (the message names the entry key; earlier writes in that run stand, so re-run `plan` to see them as `IDENTICAL`).

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
