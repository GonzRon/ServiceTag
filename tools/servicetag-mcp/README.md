# servicetag-mcp

A workstation MCP server for ServiceTag's local automation API. It forwards a port to the phone,
takes the pairing code the phone shows, and exposes one tool per `/v1` endpoint.

The contract it speaks is `docs/api/v1.md` in this repository. Read that for the shapes, the status
codes and the limits; this file is about running the thing.

## What it needs

- Python 3.12 and [uv](https://docs.astral.sh/uv/).
- `adb` on `PATH` (or `SERVICETAG_ADB` pointing at it), and the phone connected with USB debugging
  on. The serial comes from `SERVICETAG_ADB_SERIAL`; nothing in this repository names a device.
- ServiceTag 1.1.0 or later on the phone, with **Settings > Utilities > Developer API** open. The
  listener exists only while that screen is open, and the pairing code is new every time it opens.
  The seventeen maintenance tools need **1.2.0 or later**; on an older build their routes are not
  there and every call answers 404.

## Using it

1. Open **Settings > Utilities > Developer API** on the phone and leave it open.
2. Call the `pair` tool with the eight-character code the screen shows.
3. Call anything else. The first call runs `adb forward tcp:17337 tcp:17337` itself.

Leaving the screen, locking the phone or switching apps stops the listener; every call after that
fails until the screen is open again and `pair` has been called with the new code.

## Claude Code MCP configuration

Put this in your MCP configuration and replace `<serial>` with the serial `adb devices` prints for
your phone:

```json
{
  "mcpServers": {
    "servicetag": {
      "command": "uv",
      "args": ["--directory", "tools/servicetag-mcp", "run", "--frozen", "servicetag-mcp"],
      "env": {
        "SERVICETAG_ADB_SERIAL": "<serial>"
      }
    }
  }
}
```

`--directory` is relative to wherever the MCP client is started; use an absolute path to this
directory if that is not the repository root.

## Environment

| variable | what it does |
|---|---|
| `SERVICETAG_ADB_SERIAL` | the device serial `adb forward` is aimed at. Required unless `SERVICETAG_API_BASE_URL` is set. |
| `SERVICETAG_API_BASE_URL` | talk to this URL directly and **never run `adb`**. For a forward you set up yourself, and what the test suite uses. |
| `SERVICETAG_ADB` | where `adb` is, if it is not on `PATH`. |

## The tools

Thirty-eight: `pair` plus one per API operation.

**Assets, readings, quick actions and the journal** — `pair`, `status`, `list_assets`, `get_asset`,
`create_asset`, `update_asset`, `create_component`, `retire_asset`, `archive_asset`,
`list_definitions`, `save_definition`, `archive_definition`, `list_profiles`, `save_profile`,
`archive_profile`, `list_events`, `log_event`, `update_event`, `delete_event`,
`list_tag_bindings`, `import_merge`.

**Maintenance (needs ServiceTag 1.2.0)** — `list_groups`, `get_group`, `list_asset_groups`,
`create_group`, `update_group`, `archive_group`, `list_schedules`, `get_schedule`,
`create_schedule`, `update_schedule`, `pause_schedule`, `archive_schedule`, `postpone_schedule`,
`complete_schedule`, `close_round`, `list_closures`, `list_due`.

An unknown argument to any tool is rejected before the tool body runs — never silently ignored —
so a mistyped field name can't be read as absent and quietly change what the call does.

### Editing an existing row: two layers, and they are not the same thing

The Android API's own writes (`PATCH /v1/assets/{id}`, `POST /v1/definitions`,
`POST /v1/profiles`, `PATCH /v1/groups/{id}`, `PATCH /v1/schedules/{id}`) are each a **full
replacement** — every field on the wire is what the row ends up with. `update_asset`,
`save_definition`/`save_profile` on an edit, `update_group`, `update_schedule` and
`postpone_schedule` add **partial-edit convenience** on top of that: the tool reads the row's
current fields first, overlays only the arguments you actually supplied, and submits the complete
replacement for you. Nothing about calling these tools requires stating every field.

An **omitted** argument and one sent explicitly as **`null`** both leave the current value alone —
the same thing, on purpose: an MCP client that bridges to strict function calling sends `null` for
every optional argument its caller did not set, and if `null` meant "clear", a one-field rename
from such a client would silently wipe everything else. A supplied, non-null value replaces the
current one; for a text field an explicit `""` is simply that value. Clearing a field by *name*
rather than by value is `clear_fields`, e.g. `clear_fields=["vendor"]` — see each tool's own
docstring for which fields are clearable and what "cleared" means for each (`""` for a text field,
`null` for a nullable one).

`update_event` is the one edit tool with **no overlay**: `list_events` reports a logged event's
readings as typed measurements, not as the definition-id-to-text map `update_event` writes, so
reconstructing one from the other would risk silently reformatting a value. Every one of its
arguments is required — the call always replaces the whole event, and there is no default that
could clear something by omission. `complete_schedule` and `close_round` are the same, for a
sharper reason: each records a **new fact**, and there is nothing about a new fact to inherit from
a row, so overlaying one would invent provenance.

#### What each overlay tool can clear, and what "cleared" means

| tool | `clear_fields` accepts | cleared to | never clearable |
|---|---|---|---|
| `update_asset` | `category`, `description`, `notes`, `manufacturer`, `model`, `serial_number`, `vendor`, `location`, `warranty_notes` | `""` | `name` |
| | `purchase_on`, `in_service_on`, `purchase_price_minor`, `currency`, `warranty_expires_on`, `parent_asset_id`, `season_start_mmdd`, `season_end_mmdd` | `null` | |
| `save_definition` | `unit` | `""` | `label`, `kind`, `value_type`; `key` (blank already means "keep") |
| | `range_low`, `range_high`, `formula`, `source_a_id`, `source_b_id` | `null` | |
| `save_profile` | — (its fields are text or lists, where `""` and `[]` already clear) | | |
| `update_group` | `description` | `""` | `name` |
| | `members` | `[]` — **every open membership is closed** | |
| `update_schedule` | `description` | `""` | `title`; `time_basis`, `season_behavior`, `completion_mode` (pass the new value); `lead_days`, `reminders_enabled` (pass `0`/`false`); `postponed_due_on` and `status`, which have their own tools |
| | `providers` | `[]` | |
| | `target_asset_id`, `target_group_id`, `time_interval`, `time_unit`, `anchor_on`, `meter_definition_id`, `meter_interval`, `anchor_meter`, `meter_lead`, `season_reentry`, `season_reentry_offset_days`, `profile_id` | `null` | |
| `postpone_schedule` | `postponed_due_on` | `null` — the occurrence goes back to what the rule says | |

Three of those rows are worth reading twice.

**`postpone_schedule`.** `postponed_due_on=None` **cannot** clear a postponement — under the rule
above it means "leave alone" — even though the wire route does accept a literal `null` for exactly
that. `clear_fields=["postponed_due_on"]` is the only way to say it from here.

**`update_group`'s `members`.** Left alone, every currently open membership is kept, each re-sent by
its own `id` so its window and its `addedAt` do not move. Supply a list and it replaces the
membership wholesale: every open window your list omits is **closed**, and **no row is ever
deleted**, so every past round still knows who it obliged. Include a window's `id` to keep it, leave
the `id` off to **add** that asset — and an add for an asset that already has an open window is
refused, because the caller meant "keep it". Closing *every* membership is
`clear_fields=["members"]`, by name, because an omitted list means "leave alone".

**`update_schedule`'s two target ids.** Exactly one of them may be set and the overlay keeps
whichever the schedule already has, so moving a schedule from an asset to a group means supplying
the new target **and** clearing the old one in the same call.

### `import_merge`

Takes a local path to a `ServiceTag-data-*.zip` of format **1–6** and merges it into the phone. A
format-6 archive carries the maintenance groups, the schedules and the occurrence closures as well;
an older one simply has none of them. **It plans before it writes**, and it never overwrites or
deletes anything:

- a row whose id is not on the phone is **inserted**, with its UUID preserved exactly;
- a row whose id is there and whose content is identical is a **no-op**;
- a row whose id is there and whose content differs is a **conflict** — and **one conflict anywhere
  means nothing at all is written**;
- an NFC tag is matched on `(payloadFormat, payloadKey)` as well as on its row id, so the same
  physical tag cannot end up bound to two different assets;
- an attachment row is written only when its bytes are already in the phone's attachment folder;
- a closure is matched on `(schedule_id, occurrence_on)` as well as on its row id, so two phones
  that closed the same round on the same day merge cleanly and a genuine disagreement about *when*
  a round was closed is a conflict for a person.

The report carries a `{insert, identical, conflict, skipped}` tally for each of **ten** tables —
`assets`, `groups`, `definitions`, `profiles`, `schedules`, `closures`, `links`, `tags`, `events`,
`attachments`.

The tool asks for the plan and applies it only when the plan has no conflicts. Pass
`plan_only=True` to stop after the plan. Either way the result has `applicable` and, when it is
false, a `conflicts` list naming each one by table, id and a stable reason code, in a deterministic
order. Resolving conflicts is a later release (issue #44's interactive slice); 1.1.0 merges what is
unambiguous and refuses the rest safely.

There is deliberately **no** tool for a wipe, a replace-import, an export, an NFC write, an NFC
bind or an attachment's bytes: the API has no route for any of them. Nor is there one that **deletes
a schedule, a group, a membership row or a closure**, or that **amends a closure** — a closure is
immutable exported history, and one that could be rewritten could rewrite a schedule's past. There
is no **snooze** tool either: the snooze is device-local delivery state, not canonical data, and it
has no route. And `log_event` cannot record a completion — `complete_schedule` is the only path,
because two completion paths would let reminder state and history diverge.

## Tests

```bash
cd tools/servicetag-mcp && uv run pytest
```

They run against a stdlib HTTP server on `127.0.0.1` and never touch a device or run `adb`. CI runs
`uv run --frozen pytest` in the `mcp` job of `.github/workflows/ci.yml`.
