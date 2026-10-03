# servicetag-mcp

A workstation MCP server for ServiceTag's local automation API. It forwards a port to the phone,
takes the pairing code the phone shows, and exposes one tool per `/v1` operation: a plan and its apply
share one (`import_merge`, `repair_schedule_providers`, `replace_asset`).

The contract it speaks is `docs/api/v1.md` in this repository. Read that for the shapes, the status
codes and the limits; this file is about running the thing. A refusal reaches the caller as a
`ToolError` reading `<status> <code>: <message>`, then ` [field=<field>]` only when the phone names
the one body key it is about, then the `problems` in parentheses.

## What it needs

- Python 3.12 and [uv](https://docs.astral.sh/uv/).
- `adb` on `PATH` (or `SERVICETAG_ADB` pointing at it), and the phone connected with USB debugging
  on. The serial comes from `SERVICETAG_ADB_SERIAL`; nothing in this repository names a device.
- ServiceTag 1.1.0 or later on the phone, with **Settings > Utilities > Developer API** open. The
  listener exists only while that screen is open, and the pairing code is new every time it opens.
  The seventeen maintenance tools need **1.2.0 or later**, the three reference tools need
  **1.3.0 or later**, the fourteen season, condition and health tools need **1.4.0 or later** and
  `repair_schedule_providers` needs **1.4.1 or later**; on an older build their routes are not there
  and every call answers 404. The two warranty tools need an app whose `schemaVersion` is **11 or
  later**, the five service-case tools one at **12 or later**, the five loan tools one at **13 or
  later**, `get_asset_succession` one at **15 or later**, #92's five attachment tools and two replace
  tools one at **16 or later**, #15's eight supply tools one at **18 or later**, #47's five installed
  component tools one at **19 or later**, and #69's five resource tools, given a supply item or an installed
  component as the owner, one at **20 or later**; each checks it itself (below).
- **Every write needs ServiceTag 1.4.0.** Before its first write under a pairing, the server reads
  `/v1/status` once and refuses to write to an app whose `schemaVersion` is below 8 — a `ToolError`
  carrying `APP_SCHEMA_TOO_OLD`, with nothing sent. The answer is kept for that pairing, and a new
  code reads it again. Reads keep working against an older app, and so does `import_merge` with
  `plan_only=True` (the plan writes nothing); `repair_schedule_providers`' plan, which writes nothing
  either, is not schema-checked for the same reason.
- **The warranty tools need schema 11.** `get_warranty` and `set_warranty_reminder` — the read as well
  as the write — refuse an app whose `schemaVersion` is below 11 the same way, with `APP_SCHEMA_TOO_OLD`
  and nothing sent, from the same one `/v1/status` read per pairing. Every tool but these two, the
  five service-case tools, the five loan tools, the succession tool, #92's seven tools, #15's eight
  supply tools and #47's five installed component tools below keeps the minimum of 8 — and a line tool
  that sends `supplyId` needs 18 for that call.
- **The service-case tools need schema 12.** `list_service_cases`, `get_service_case`,
  `open_service_case`, `update_service_case` and `add_case_entry` — the reads as well as the writes —
  refuse an app whose `schemaVersion` is below 12 the same way, from the same read.
- **The loan tools need schema 13.** `list_loans`, `get_loan`, `lend_asset`, `update_loan` and
  `return_loan` — the reads as well as the writes — refuse an app whose `schemaVersion` is below 13 the
  same way, from the same read.
- **The succession tool needs schema 15.** `get_asset_succession` — a read — refuses an app whose
  `schemaVersion` is below 15 the same way, from the same read.
- **The #92 tools need schema 16.** The five attachment tools (`list_attachments`, `get_attachment`,
  `update_attachment`, `add_attachment` and `materialize_reference`) and `get_replace_offer` /
  `replace_asset` — the reads as well as the writes — refuse an app whose `schemaVersion` is below 16
  the same way, from the same read. #92 moved no schema, so a schema-16 app that predates the routes
  answers `APP_ROUTE_MISSING`.
- **The supply tools need schema 18.** `list_supply_items`, `get_supply_item`, `create_supply_item`,
  `update_supply_item`, `archive_supply_item`, `list_asset_supplies`, `set_asset_supply` and
  `remove_asset_supply` — the reads as well as the writes — refuse an app whose `schemaVersion` is below 18 the
  same way, from the same read. `save_profile`, `log_event`, `update_event` and `complete_schedule` refuse it
  **only when a material line they will send carries a `supplyId` key at all** (`null` included, which an older
  app's strict decoder answers 400 for too); without one they reach any phone they always did.
- **The installed component tools need schema 19.** `list_installed_components`, `add_installed_component`,
  `update_installed_component`, `remove_installed_component` and `replace_installed_component` — the read as
  well as the writes — refuse an app whose `schemaVersion` is below 19 the same way, from the same read, naming
  the feature "installed components".
- **A supply item or installed component owner needs schema 20.** `list_references`, `add_reference`,
  `list_attachments`, `add_attachment` and `materialize_reference` given `supply_item_id` or
  `installed_component_id` — the reads as well as the writes — refuse an app whose `schemaVersion` is below 20 the
  same way, from the same read, naming the feature "supply item and installed component resources". Given
  `asset_id`, each keeps exactly the minimum it had. The minima are therefore 8 for every write, 11 for the warranty
  tools, 12 for the case tools, 13 for the loan tools, 15 for the succession tool, 16 for the #92 tools, 18 for the
  supply tools and a linked line, 19 for the installed component tools, 20 for a supply item or installed
  component owner on the five resource tools, and 21 for `get_season_sync` (#16, Home Assistant season sync).

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

Ninety: `pair` plus one per API operation.

**Assets, readings, quick actions and the journal** — `pair`, `status`, `list_assets`, `get_asset`,
`create_asset`, `update_asset`, `create_component`, `retire_asset`, `archive_asset`,
`list_definitions`, `save_definition`, `archive_definition`, `list_profiles`, `save_profile`,
`archive_profile`, `list_events`, `log_event`, `update_event`, `delete_event`,
`list_tag_bindings`, `import_merge`.

**Maintenance (needs ServiceTag 1.2.0)** — `list_groups`, `get_group`, `list_asset_groups`,
`create_group`, `update_group`, `archive_group`, `list_schedules`, `get_schedule`,
`create_schedule`, `update_schedule`, `pause_schedule`, `archive_schedule`, `postpone_schedule`,
`complete_schedule`, `close_round`, `list_closures`, `list_due`.

`create_schedule` without `providers` sends the row the app's own editor stores — one `LOCAL`
provider, enabled exactly when `reminders_enabled` is — rather than leaving it to the app; pass
`providers=[]` to store none. `update_schedule` mirrors the same rule on an edit: `reminders_enabled`
supplied with `providers` neither given nor cleared sets every stored `LOCAL` row's `enabled` to
match, so turning reminders back on never re-sends a `LOCAL` row this tool left disabled (#83).

**References (needs ServiceTag 1.3.0)** — `list_references`, `add_reference`, `update_reference`.
A reference is a URI — a manual on the web, a note in Joplin — with no bytes of its own, and **exactly one
owner**, fixed for life: an asset, a **supply item** (what a product is — its manual, its data sheet, the maker's
page) or an **installed component** (what one fitted part is — its label, its wiring). `list_references` and
`add_reference` take exactly one of `asset_id`, `supply_item_id` and `installed_component_id`; an argument passed
as `null` is not given, and none or more than one is refused before anything is sent. The create's body carries
that one owner key, and the read lists that owner's own links only. A supply item or installed component owner
needs schema 20 (above); an asset keeps every gate it had. `update_reference` takes no owner: a reference never
moves. `kind` is derived from the URI's scheme and returned read-only, so neither write tool takes
one; nothing **deletes** a reference, because the API adds and amends and the phone removes. Since
#91 an `http` or `https` reference may carry a document `role` — `PURCHASE_INVOICE_OR_RECEIPT`,
`USER_MANUAL` or `SERVICE_MANUAL` — given only by the caller, never guessed: `add_reference` and
`update_reference` take `role`, and `update_reference` clears it by name, `clear_fields=["role"]`,
sent as `"role": null` (its only clearable name; the description is still cleared by `""`). A role
on any other link is `REFERENCE_ROLE_NOT_ALLOWED`. A role given or cleared needs a phone at schema
17, refused below it with `APP_SCHEMA_TOO_OLD` and nothing sent; without one both tools reach any
phone they always did. `list_references` rows carry `role` from schema 17 and never require it.
`materialize_reference` with no `role` copies the reference's own, and `clear_fields=["role"]` saves
the document with none; it keeps its schema-16 gate.

**Seasons, condition and health (needs ServiceTag 1.4.0)** — `get_season`, `start_season`,
`end_season`, `set_season_mode`, `set_maintenance_break`, `list_conditions`, `record_condition`,
`get_health`, `set_health_policy`, `list_health_subjects`, `create_health_subject`,
`update_health_subject`, `archive_health_subject`, `list_attention`. A season is `YEAR_ROUND`,
`CALENDAR` or `MANUAL`, and a `MANUAL` one moves only by a recorded `START` or `END`; the maintenance
break is a stretch of the year no work should land in. Condition (`OPERATIONAL`, `DEGRADED`, `DOWN`)
is recorded, never inferred. Health is **computed at read time and stored nowhere**: the subject
and policy tools write configuration, never a value. `start_season`, `end_season` and
`record_condition` each record a new, immutable fact, so like `close_round` they have no overlay —
every argument is required. Nothing here amends or deletes a condition or an activation, deletes a
health subject, or writes a health value; a subject leaves only by `archive_health_subject`.

**Repairs (needs ServiceTag 1.4.1)** — `repair_schedule_providers`. It finds schedules whose
reminders are on but that nothing delivers (issue #80) and, **only with `plan_only=False`**, gives
each ACTIVE, providerless one a single enabled `LOCAL` provider. The default is the plan, which
writes nothing; the apply plans again on the phone inside its own write, skips a paused schedule and
a disabled provider, and a second apply repairs nothing. Since #77 it never matches a schedule whose
target is no longer maintained on the phone — an archived, retired or transferred-out asset, or a group
that is archived or wholly transferred out. `docs/api/v1.md`'s **Repairs** section is the contract.

**Transferred-out assets (#77)** — no tool. Transfer Packs are made, imported, marked and withdrawn on
the phone only. A write tool (but `import_merge`, whose plan reports `ASSET_TRANSFERRED_OUT`, and the
provider repair, which skips them) that reaches a row of an asset transferred out from the phone — a
category-only `update_asset` or a repeated `archive_asset` included — fails with the phone's **409
`asset_transferred_out`**, whatever the asset's `status` reads, and writes nothing; reads keep working.
`status` counts the transfer records as `transferRecords`. `docs/api/v1.md`'s **Transferred-out assets
(#77)** section is the contract.

**Warranty (needs schema 11)** — `get_warranty`, `set_warranty_reminder`. `get_warranty` answers
the asset's warranty status — `IN_WARRANTY`, `OUT_OF_WARRANTY` or `NOT_RECORDED` — derived on the
phone for today and stored nowhere, with the date and the reminder lead beside it.
`set_warranty_reminder` sets the lead in whole days (1 or more) or clears it with `None`; the asset
needs a warranty date first. The lead is in no asset command, so `update_asset` never sends it: an
edit keeps it while the date stays, and clearing `warranty_expires_on` clears it too. The warning is
the phone's own and has no tool; a lead set here takes effect at the phone's next digest or its
12-hour backstop. `docs/api/v1.md`'s **Warranty reminders (#79)** section is the contract.

**Service cases (needs schema 12)** — `list_service_cases`, `get_service_case`, `open_service_case`,
`update_service_case`, `add_case_entry`. A service case is an asset's record of an outside repair or
warranty claim — provider, case or RMA number, type, coverage, status, tracking and cost — with an
append-only timeline. `open_service_case` is a create sent as given: `type`, `coverage` and
`opened_on` are required, because the phone applies none of its own form's defaults over the API.
`update_service_case` is an overlay (below); it **never sends a status or a `closedOn`** and carries
the stored repair link forward. `add_case_entry` records a new fact, so every argument is required:
a note, a status, or both — and it is the only way a case's status moves (`CLOSED` or `CANCELLED`
closes it, any other status reopens it). Nothing deletes a case or amends an entry.
`docs/api/v1.md`'s **Service cases (#79)** section is the contract.

**Loans (needs schema 13)** — `list_loans`, `get_loan`, `lend_asset`, `update_loan`, `return_loan`. A
loan records that an asset is out with a person or an organisation: who has it, since when, when it is
due back and whether the phone should remind the owner. `lend_asset` is a create sent as given, and
`lent_on` is required — the phone applies none of its lend form's defaults over the API. It lends by
**name only**: a loan answers `contactLinked`, and the Android Contacts link itself is on no answer and
in no argument — only the phone makes one ("Choose from Contacts"). An asset holds one open loan at a
time, and any lifecycle may be lent here. `update_loan` is an overlay (below) over the open loan's terms;
it never sends a borrower, a return date or a link. `return_loan` is the only way a loan ends, and a
returned loan is frozen. The reminder is the phone's own and has no tool; a loan written here posts only
at the phone's next sweep at or after its digest hour, but a returned loan's standing reminder comes down
at the next sweep of any kind, the midnight sweep included. Nothing deletes or relinks a loan.
`docs/api/v1.md`'s **Loans (#72)** section is the contract.

**Asset successions (needs schema 15)** — `get_asset_succession`. The phone's **Replace asset** gives way
from one asset to a different, new one and records the pair as a succession. The tool answers
`{replaces, replacedBy}` for an asset — the succession naming it as the new asset and the one naming it as
the old, each `{id, predecessorAssetId, successorAssetId, replacedOn, createdAt}` or null — and is read
only: a succession is recorded only by a replacement (the phone's Replace asset or, since #92, `replace_asset`,
below), no tool edits or removes one (`import_merge` only inserts an archive's rows, below), and an asset's own
answer carries
no succession field. `status` counts them as `assetSuccessions`. Deleting either asset, on the phone,
deletes its succession; there is no unlink. `docs/api/v1.md`'s **Asset successions (#86)** section is the
contract.

**Attachments and Save as document (#92; needs schema 16)** — `list_attachments`, `get_attachment`,
`update_attachment`, `add_attachment`, `materialize_reference`: one tool per operation — `GET` and `POST
/v1/assets/{id}/attachments`, `GET` and `PATCH /v1/attachments/{id}`, `POST /v1/references/{id}/materialize`.
Since #69 a file, like a link, belongs to exactly one owner — an asset, a supply item or an installed component
(or a journal entry, which no tool here adds to): `list_attachments`, `add_attachment` and `materialize_reference`
take exactly one of `asset_id`, `supply_item_id` and `installed_component_id` (none or more than one is refused
before anything is sent) and read and write that owner's own routes — `GET` and `POST
/v1/supply-items/{id}/attachments` and `/v1/installed-components/{id}/attachments`, and the owner's
`…/references`. A supply item or installed component owner needs schema 20, an asset the 16 below. A document
role goes on an asset's, a supply item's or an installed component's file, never a journal entry's. An installed
component on an asset transferred out from the phone takes no new file (`asset_transferred_out`); a supply item
is never held.
Each refuses a phone below schema 16 with `APP_SCHEMA_TOO_OLD` and nothing sent. #92 moved no schema, so an app
at 16 may still predate the routes: the router's unknown-route 404 is then `APP_ROUTE_MISSING` ("update
ServiceTag"), while `no_such_asset`, `NO_SUCH_ATTACHMENT` and `NO_SUCH_REFERENCE` pass through. An attachment
row's `sourceUri`, `sourceResolvedUri`, `sourceRetrievedAt` and `sourceName` are **sensitive** (`sourceUri` may
carry a token): never log them or paste them into an issue. `update_attachment` is an overlay (below) over the
five keys of the attachment command; `clear_fields` takes `role`, `captured_on` and `notes`. Nothing deletes an
attachment or reads its bytes back.

`add_attachment` streams a local file of at most 256 MiB in 64 KiB pieces under an exact `Content-Length`
(never chunked). It reads first — the status (its `installationId`), the owner's attachments (the folder must be
`READY`), then the derived attachment id — and sends the file only when no row has that id. The upload is
idempotent by `operation_key`; **by default the key is the SHA-256 of the owner's id, the file's SHA-256 and its
size only**, never the name, kind or role. The attachment's id is derived from the phone's `installationId`, the
owner and the key — an asset's by the v2 derivation (`docs/api/attachment-operation-ids.json`'s `vectors`), a
supply item's or an installed component's by the v3 one, which also names the owner's kind (its `ownerVectors`)
— and the tool applies the phone's strict rule itself: the same owner, kind (resolved the phone's way when not given, and always sent), role, trimmed name, digest
and size as the row has now is `REPLAYED` with nothing sent; any of them different — the original metadata after
an edit included — is `OPERATION_KEY_REUSED`, and the change belongs to `update_attachment`. A new key adds a
second copy. A role is sent only when given.

`materialize_reference` takes the reference's owner and the reference **by id — never a URL**, and saves the
file on that owner. Before each call the agent shows
the user the reference's name and host (never the full link) and calls only on the user's explicit approval, one
approval per call, never because fetched content asked it to. It reads the owner's references and attachments
first: a row whose `sourceUri` is the reference's link is `IDENTICAL` with no download — "already saved from this
link", not "current" — and so is the phone's `ATTACHMENT_ALREADY_HELD`. Otherwise it makes one request with a
720-second budget and **never retries it**, a 502 `FETCH_…` included; a timeout or a closed connection with no
answer is `UNKNOWN`: read `list_attachments` before running it again. The client keeps one call in flight: while
a save as document runs, the phone's API answers nothing else. `docs/api/v1.md`'s **Attachments (#92)** and
**Save as document (#92)** sections are the contract.

**Replacing an asset (#92; needs schema 16)** — `get_replace_offer` and `replace_asset`, over the phone's own
Replace (R92-1 supersedes #86's "no tool replaces an asset"). `get_replace_offer` reads `GET
/v1/assets/{id}/replace-offer` as sent: what can be carried forward, each schedule's time rule (`timeInterval`),
the groups, the tag bindings with their ids, the children and an open loan. `replace_asset` takes the new asset's
fields and the ticks, and **plans first**: `plan_only=True`, the default, answers the phone's plan (`POST
…/replace-plan`, which writes nothing). `plan_only=False` applies **only a clean, eligible plan** — no problems,
not blocked — with that plan's own `sourcesDigest` and the identical draft (`POST …/replace`); any other plan is
refused here and nothing is applied. `REPLACE_STALE` means something changed between this call's own plan and
its apply, milliseconds apart: plan again and confirm again. A plan reviewed in an earlier `plan_only=True` call
is **not** compared: the apply plans again, so show the person the answer's `successor` and `succession`, or plan
again right before confirming. A repeat after success is `ASSET_ALREADY_REPLACED`, and IDENTICAL when the
successor carries the requested name; a lost answer is `UNKNOWN` — read `get_asset_succession`, and the same
call is safe to run again. Nothing is defaulted: `retired_on`, `schedule_start_on` and `manual_phase` are sent
only as given. **Tags move by binding id only** (`moved_tag_ids`; an "all", a label or a pattern is refused
here), and a move re-targets the binding row and never writes NFC. `docs/api/v1.md`'s **Replacing an asset
(#92)** section is the contract.

**Supply items (#15; needs schema 18)** — `list_supply_items`, `get_supply_item`, `create_supply_item`,
`update_supply_item`, `archive_supply_item`, `list_asset_supplies`, `set_asset_supply`, `remove_asset_supply`:
one tool per operation — `GET` and `POST /v1/supply-items`, `GET` and `PATCH /v1/supply-items/{id}`, `POST
/v1/supply-items/{id}/archive`, `GET /v1/assets/{id}/supply-items`, `POST /v1/asset-supplies` and `PATCH` (both
`set_asset_supply`) and `DELETE /v1/asset-supplies/{id}`. A **supply item** is one canonical product — a cartridge,
a battery pack, a belt — with its identity (name, category, manufacturer, model, part number, preferred unit,
notes) and an ordered list of generic specifications `{label, value, unit}`; an **asset supply** says which
supply item an asset takes and in what role. Nothing more: no quantity, no fitted position, date or serial, no
file field on the row — since #69 its own files and links are read and added by the resource tools with
`supply_item_id` (above) — and a complete pack and an item inside it are two unrelated supply items. `update_supply_item` sends only
the arguments given, because the phone's `PATCH` is itself the overlay: `""` clears a text field, `[]` removes
every specification, `name` is never blank, and there is no `clear_fields`. A kept specification row is sent with
its `id` and `key` — and its `unit` — as `get_supply_item` answered them, or the phone mints a new row and an
earlier export re-plans the item `CONFLICT`. `set_asset_supply` creates without an `asset_supply_id` and re-roles
with one; the role is cleaned and unique per asset and supply item, exactly. Nothing deletes a supply item — it is
archived, and `archive_supply_item` takes `archived=False` to bring it back — while `remove_asset_supply` removes
an applicability row. A material line names a supply item only by its `supplyId` on `save_profile`, `log_event`,
`update_event` and `complete_schedule`, given by the caller and never inferred from a name; a line carrying the
key at all needs schema 18 (`APP_SCHEMA_TOO_OLD` below it), and a link travels only on a line a call sends — a
`complete_schedule` with `consumables=[]` writes no line, so it names no supply item. `save_profile`'s edit keeps
each line's `supplyId` exactly as it read it. `docs/api/v1.md`'s **Supply items (#15)** and **Asset supplies
(#15)** sections are the contract.

**Installed components (#47; needs schema 19)** — `list_installed_components`, `add_installed_component`,
`update_installed_component`, `remove_installed_component`, `replace_installed_component`: `GET
/v1/assets/{id}/installed-components`, `POST /v1/installed-components`, `PATCH /v1/installed-components/{id}`,
`POST /v1/installed-components/{id}/remove` and `POST /v1/installed-components/{id}/replace`. An **installed
component** is one fitted instance — a battery tray, the pack in it, a membrane in its housing — inside an asset
or inside another installed component of the same asset, one row per instance: current while it has no removal
date, history once it has one. It may name one supply item directly (`supply_id`: this unit is one of these) and
carry an ordered **composition** of `{supplyId, quantity, unit}` entries (this unit is made of these), a
quantity being how many of that SupplyItem one unit is made of — a pack of four of one battery is one entry with
`quantity` 4. It is not a child asset: `create_component` still makes one of those, and the `components` keys
still list them. `update_installed_component` sends only the arguments given, because the phone's `PATCH` is the
overlay: `""` clears the direct link, the install date, the serial or lot or the notes, a given `composition` is
the whole ordered list — each kept entry passed with its `id`, once — and `[]` empties it, `name` is never blank,
and there is no `clear_fields`. An entry's `sortOrder`, as a read answers it, is left off what any tool sends:
the list's order is the order. A remove, and a replace, closes the row and **every current installed component
inside it on the same date, in the same write**, and deletes nothing. `replace_installed_component` gives the new
unit the replaced row's parent and place, and only the link and the composition the call sends — an omitted
`supply_id` or `composition` is none, never the replaced row's; to keep them, read them with
`list_installed_components` and pass them. Nothing is inferred: an installed component names a supply item only
by the id a caller sends, and adding one writes no applicability row and no event. `docs/api/v1.md`'s
**Installed components (#47)** section is the contract.

**Home Assistant season sync (#16; needs schema 21)** — `get_season_sync`: `GET /v1/assets/{id}/season-sync`, read
only. On the phone an asset can follow one Home Assistant on/off entity; this shows that link's non-secret state and
status — the connection's settings, the binding's mode and state, its last valid answer, the latest check that
decided nothing and the last activation it recorded — and never the address, the home Wi-Fi's name or the token.
Linking, the modes, Sync now, stopping and the connection are the phone's alone; while a binding is enabled,
`start_season`, `end_season` and a `set_season_mode` change on that asset answer `SEASON_SYNC_ENABLED` (409).
`docs/api/v1.md`'s **Home Assistant season sync (#16)** section is the contract.

### The schedule's two forms, and the deprecated season arguments

1.4 gives a schedule a **service policy** — `service_policy` (`CONTINUOUS`, `IN_SERVICE_AT_START`,
`IN_SERVICE_RESUME_CLAMPED`, `PRE_SERVICE`) and `policy_offset_days` — on `create_schedule` and
`update_schedule`. Both keep 1.3's `season_behavior`, `season_reentry` and
`season_reentry_offset_days` as **deprecated arguments**:

- **A deprecated argument** makes the call send the API's **legacy form**: those keys only, exactly
  as given (`update_schedule` lays them over the row's derived 1.3 triple), and never
  `servicePolicy`/`policyOffsetDays`. **This server never translates them** — the app does, through
  its one legacy mapping, and refuses with the same codes it gives any client (for example
  `LEGACY_WRITE_CANNOT_REPRESENT` on a legacy edit of a `PRE_SERVICE` schedule), each arriving as a
  `ToolError` carrying the code. `clear_fields` naming `season_reentry` or
  `season_reentry_offset_days` counts as a deprecated argument.
- **No deprecated argument** sends the **1.4 form**: `update_schedule` lays your arguments over the
  row's `servicePolicy` and `policyOffsetDays` and sends no 1.3 key. `create_schedule` with neither
  sends neither, which the app reads as `CONTINUOUS`.
- **Both together** — a deprecated argument and `service_policy`/`policy_offset_days`, `clear_fields`
  included — is refused **here, before any request**, as a `ToolError` carrying
  `LEGACY_AND_CURRENT_FIELDS_MIXED`.

**Changing the policy may take two arguments**, as moving the target does. `update_schedule` keeps
the row's `policyOffsetDays`, and each policy takes only its own range, so moving a schedule that has
an offset to a policy that does not take it (`IN_SERVICE_AT_START` 5 to `CONTINUOUS`, say) needs the
new `policy_offset_days` or `clear_fields=["policy_offset_days"]` in the same call — `service_policy`
alone is refused as `POLICY_OFFSET_INVALID`. A policy change is **not a rule change**: it clears no
postponement and abandons no round. `PRE_SERVICE` needs a time rule and an asset with a calendar
season or a maintenance break to count back from (`PRE_SERVICE_NEEDS_DATES`).

`update_schedule` and `archive_schedule` also take `unlink_health_subject=True`, the API's action
flag: while a live health subject is driven by the schedule, an edit that takes away its time rule or
moves it, or archiving it, is refused as `SCHEDULE_DRIVES_HEALTH_SUBJECT` unless the flag is set.

An unknown argument to any tool is rejected before the tool body runs — never silently ignored —
so a mistyped field name can't be read as absent and quietly change what the call does.

### Editing an existing row: two layers, and they are not the same thing

The Android API's own writes (`PATCH /v1/assets/{id}`, `POST /v1/definitions`,
`POST /v1/profiles`, `PATCH /v1/groups/{id}`, `PATCH /v1/schedules/{id}`,
`PATCH /v1/health-subjects/{id}`, `PATCH /v1/service-cases/{id}`, `PATCH /v1/loans/{id}`) are each a
**full replacement** — every field on the wire is what the row ends up with. `update_asset`,
`save_definition`/`save_profile` on an edit, `update_group`, `update_schedule`, `postpone_schedule`,
`update_health_subject`, `update_service_case` and `update_loan` add **partial-edit convenience**
on top of that: the tool reads the row's current fields first, overlays only the arguments you
actually supplied, and submits the complete replacement for you. Nothing about calling these tools
requires stating every field.

`update_asset`, `update_schedule`, `update_health_subject`, `update_service_case` and `update_loan` do
not keep a field list of their own: they send **every key of the command** as
`src/servicetag_mcp/command_shapes.py` lists it — read off the row, with the schedule's
`assetId`/`groupId` renamed to `targetAssetId`/`targetGroupId`, the case's without `assetId` and
`incidentEventId`, and the loan's without `assetId` and `borrowerName` — and lay your arguments over it.
That module is a vendored copy of five entries of the
repository's `docs/api/command-shapes.json`; nothing reads that file at runtime, and
`tests/test_command_shapes.py` fails the moment the two differ.

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
could clear something by omission. `complete_schedule`, `close_round`, `start_season`,
`end_season`, `record_condition` and `add_case_entry` are the same, for a sharper reason: each records a **new
fact**, and there is nothing about a new fact to inherit from a row, so overlaying one would invent
provenance.

#### What each overlay tool can clear, and what "cleared" means

| tool | `clear_fields` accepts | cleared to | never clearable |
|---|---|---|---|
| `update_asset` | `category`, `description`, `notes`, `manufacturer`, `model`, `serial_number`, `vendor`, `location`, `warranty_notes` | `""` | `name` |
| | `purchase_on`, `in_service_on`, `purchase_price_minor`, `currency`, `warranty_expires_on`, `parent_asset_id`, `season_start_mmdd`, `season_end_mmdd` | `null` | |
| `save_definition` | `unit` | `""` | `label`, `kind`, `value_type`; `key` (blank already means "keep") |
| | `range_low`, `range_high`, `formula`, `source_a_id`, `source_b_id` | `null` | |
| `save_profile` | — (its fields are text or lists, where `""` and `[]` already clear) | | |
| `update_supply_item` | — (the phone's `PATCH` is the overlay: `""` clears a text field, `[]` the specifications) | | `name` |
| `update_installed_component` | — (the phone's `PATCH` is the overlay: `""` clears `supply_id`, `installed_on`, `serial_or_lot` or `notes`, `[]` the composition) | | `name` |
| `update_group` | `description` | `""` | `name` |
| | `members` | `[]` — **every open membership is closed** | |
| `update_schedule` | `description` | `""` | `title`; `time_basis`, `service_policy`, `season_behavior`, `completion_mode` (pass the new value); `lead_days`, `reminders_enabled` (pass `0`/`false`); `postponed_due_on` and `status`, which have their own tools |
| | `providers` | `[]` | |
| | `target_asset_id`, `target_group_id`, `time_interval`, `time_unit`, `anchor_on`, `meter_definition_id`, `meter_interval`, `anchor_meter`, `meter_lead`, `profile_id` | `null` | |
| | `policy_offset_days` (1.4) | `null` — `0` on `IN_SERVICE_AT_START`, no offset otherwise | |
| | `season_reentry`, `season_reentry_offset_days` (deprecated) | `null`, in the legacy form | |
| `postpone_schedule` | `postponed_due_on` | `null` — the occurrence goes back to what the rule says | |
| `update_health_subject` | `schedule_id`, `baseline_profile_id` | `null` | `name`, `kind`, `driver`, the three thresholds (required); `weight`, `sort_order` (pass a value) |
| `update_service_case` | `provider`, `contact`, `case_ref`, `outbound_tracking`, `outbound_carrier`, `return_tracking`, `return_carrier`, `notes` | `""` | `title`, `type`, `opened_on`, `coverage` (required); the status and `closedOn`, which only `add_case_entry` moves |
| | `cost_minor`, `currency`, `resolution_event_id` | `null` — the last removes the repair link | |
| `update_loan` | `notes` | `""` | `lent_on` (required); `reminder_mode` (pass `NONE`); the borrower, the return date and the contact link, which no loan body carries |
| | `due_on` | `null` — no due date; refused while a reminder mode stays, so pass `reminder_mode="NONE"` with it | |

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

Takes a local path to a `ServiceTag-data-*.zip` of format **1–20** and merges it into the phone. A
format-6 archive adds the maintenance groups, the schedules and the occurrence closures, format 7 the
references, format 8 the season activations, the conditions and the health subjects, format 9 the
owner's own categories, format 10 each attachment's document role, format 11 each asset's warranty
reminder lead, format 12 the service cases and their timeline entries, format 13 the loans, format
14 the transfer records and format 15 the asset successions, format 16 each attachment's source provenance, format 17 each reference's document role (an older archive's references are compared without it), format 18 the supply items, the asset supplies and each material line's `supplyId` (an older archive's quick actions and events are compared without the link), format 19 the installed components, each with its composition, format 20 each attachment's and reference's `supplyItemId` and `installedComponentId` (a file or a link on a supply item or an installed component; no new table); an older archive simply has none of them. **It plans before it writes**, and it never overwrites or
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

The report carries a `{insert, identical, conflict, skipped}` tally for each of **twenty-three**
tables — `assets`, `groups`, `definitions`, `profiles`, `schedules`, `closures`, `links`, `tags`,
`events`, `attachments`, `references`, `seasonActivations`, `conditions`, `healthSubjects`,
`categories`, `serviceCases`, `caseEntries`, `loans`, `transfers`, `successions`, `supplyItems`,
`assetSupplies`, `installedComponents`. A season activation, a condition and a case's timeline
entry are immutable facts: each is only ever inserted or found identical. A loan is never updated
either: one returned, re-dated or relinked on one phone after the other received it conflicts, and an
open loan whose asset already holds a different open loan here conflicts as `ASSET_ALREADY_LENT`. A
transfer record (`OUT`, `IN` or `WITHDRAWN`) is an immutable fact too, and a merge never undoes a
transfer: an incoming `IN` or `WITHDRAWN` that would close an `OUT` open on the phone, a row that an
asset transferred out from the phone would own, and a row that would name one of its rows each conflict
as `ASSET_TRANSFERRED_OUT`; an `OUT` that would leave its asset with two open `OUT`s — two phones marked
it, in different packs — conflicts as `TRANSFER_DIVERGED`, resolved only by withdrawing one on the phone.
A Transfer Pack is not a data archive and the phone refuses it here; the `data.zip` inside one merges as
an ordinary archive — no `IN` recorded, nothing replaced — so only the phone's own pack import brings an
asset back.

An asset succession (format 15) is an immutable fact too, only
ever inserted: one whose old asset a succession on the phone already names as an old asset, or whose new
asset one already names as a new asset, conflicts as `SUCCESSION_TAKEN`, one that would close a loop with the phone's successions as `SUCCESSION_CYCLE`, and one
naming an asset transferred out from the phone as `ASSET_TRANSFERRED_OUT`. A merge never retires anything:
a replacement made on another phone that retired its old asset there conflicts on that asset's row when
this phone holds it unretired, and nothing lands — make the replacement on the phone that should keep it.

A supply item (format 18) is matched by its id: the same id with different content — renamed, a specification
changed, archived on one phone only — conflicts, and one conflict refuses the whole archive. An asset supply
that a row on the phone under another id already holds is not inserted twice: it is IDENTICAL
(`ASSET_SUPPLY_HELD_BY_AN_EQUIVALENT_LOCAL_ROW`) when the two agree field for field and SKIPPED
(`ASSET_SUPPLY_HELD_BY_A_LOCAL_ROW`) when only their stamps differ.

An installed component (format 19) is matched by its id, every field and its composition compared: one removed,
replaced, edited or recomposed on one phone since the export conflicts, and is never updated. One that is not
here conflicts as `CHILD_ROW_ID_TAKEN` when an entry's id is held by another installed component, and as
`INSTALLED_COMPONENT_REPLACEMENT_TAKEN` when another installed component already names the row it replaced; the
rows are written parents first, after the assets and the supply items.

The tool asks for the plan and applies it only when the plan has no conflicts. Pass
`plan_only=True` to stop after the plan. Either way the result has `applicable` and, when it is
false, a `conflicts` list naming each one by table, id and a stable reason code, in a deterministic
order. Resolving conflicts is a later release (issue #44's interactive slice); 1.1.0 merges what is
unambiguous and refuses the rest safely.

There is deliberately **no** tool for a wipe, a replace-import, an export, an NFC write, an NFC
bind or reading an attachment's bytes back out: the API has no route for any of them. Nor is there one that **deletes
a schedule, a group, a membership row or a closure**, or that **amends a closure** — a closure is
immutable exported history, and one that could be rewritten could rewrite a schedule's past. There
is no **snooze** tool either: the snooze is device-local delivery state, not canonical data, and it
has no route. And `log_event` cannot record a completion — `complete_schedule` is the only path,
because two completion paths would let reminder state and history diverge. Since 1.4 there is also
no tool that **amends or deletes a condition or an activation**, **deletes a health subject** or
**writes a health value**: facts are appended and never rewritten, a subject leaves only by
archiving, and health is computed at read time. Since #79 there is no tool that **deletes a service
case** or **amends or deletes a timeline entry**, and none but `add_case_entry` moves a case's status.
Since #77 there is no tool that **makes, imports or marks a Transfer Pack**, **withdraws a transfer
record** or lists the records: each is the phone's alone. Since #86 there is no tool that **edits or
removes a succession**: one is recorded only by a replacement (the phone's Replace asset or, since #92,
`replace_asset`), `import_merge` only inserts an archive's rows, and `get_asset_succession` only reads.
Since #15 there is no tool that **deletes a supply item** — one is archived — and none that links a material
line to a supply item by its name. Since #47 there is no tool that **deletes an installed component** — a remove
closes it and keeps it as history — and none that copies a replaced row's link or composition into the new one.

## Tests

```bash
cd tools/servicetag-mcp && uv run pytest
```

They run against a stdlib HTTP server on `127.0.0.1` and never touch a device or run `adb`. CI runs
`uv run --frozen pytest` in the `mcp` job of `.github/workflows/ci.yml`.
