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

Twenty-one: `pair` plus one per API operation.

`pair`, `status`, `list_assets`, `get_asset`, `create_asset`, `update_asset`, `create_component`,
`retire_asset`, `archive_asset`, `list_definitions`, `save_definition`, `archive_definition`,
`list_profiles`, `save_profile`, `archive_profile`, `list_events`, `log_event`, `update_event`,
`delete_event`, `list_tag_bindings`, `import_merge`.

### `import_merge`

Takes a local path to a format-5 `ServiceTag-data-*.zip` and merges it into the phone. **It plans
before it writes**, and it never overwrites or deletes anything:

- a row whose id is not on the phone is **inserted**, with its UUID preserved exactly;
- a row whose id is there and whose content is identical is a **no-op**;
- a row whose id is there and whose content differs is a **conflict** — and **one conflict anywhere
  means nothing at all is written**;
- an NFC tag is matched on `(payloadFormat, payloadKey)` as well as on its row id, so the same
  physical tag cannot end up bound to two different assets;
- an attachment row is written only when its bytes are already in the phone's attachment folder.

The tool asks for the plan and applies it only when the plan has no conflicts. Pass
`plan_only=True` to stop after the plan. Either way the result has `applicable` and, when it is
false, a `conflicts` list naming each one by table, id and a stable reason code, in a deterministic
order. Resolving conflicts is a later release (issue #44's interactive slice); 1.1.0 merges what is
unambiguous and refuses the rest safely.

There is deliberately **no** tool for a wipe, a replace-import, an export, an NFC write, an NFC
bind or an attachment's bytes: the API has no route for any of them.

## Tests

```bash
cd tools/servicetag-mcp && uv run pytest
```

They run against a stdlib HTTP server on `127.0.0.1` and never touch a device or run `adb`. CI runs
`uv run --frozen pytest` in the `mcp` job of `.github/workflows/ci.yml`.
