"""The MCP surface: one tool per `/v1` endpoint, and `pair` for the code on the phone's screen.

Nothing here reaches past the API. Every tool is a method, a path and a body — the contract is
`docs/api/v1.md`, and the app's own router is what enforces it.

`@mcp.tool()` is called with parentheses on purpose: the SDK raises a `TypeError` at *import* time
for a bare `@mcp.tool`, which is a server that never starts.

**Every tool calls `_call`, never `device.request` directly.** In `mcp` 2.2.0, any exception a tool
raises that is not itself a `ToolError` (or `ResourceError`, or `MCPError`) is discarded: the SDK
wraps it as `UnexpectedToolError("Error executing tool <name>")` and nothing else reaches the model
(`mcp/server/mcpserver/tools/base.py:208`–`210`). `_call` is the one place that conversion happens,
so it is what makes `NotPaired`'s "read the code again" and `ApiError`'s code/message/problems
visible at all — whether a tool is invoked through the SDK or, as this suite mostly does, directly.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import ValidationError

from .client import ApiError, Device, MAX_IMPORT_BYTES, NotPaired


class _StrictMCPServer(MCPServer):
    """`MCPServer` whose `call_tool` refuses an unknown argument before pydantic ever sees one.

    `_handle_call_tool` — the lowlevel handler a real client request reaches
    (`mcp/server/mcpserver/server.py:428`-`433`) — calls `self.call_tool(...)`, so overriding it
    here is the one place every call passes through, known tool or not, before any HTTP, `adb` or
    tool-body activity. The message names the bad key(s) and the tool's real argument names, and
    never the value that was supplied — unlike the model-level backstop
    `_forbid_unknown_arguments` installs below, whose own raw pydantic refusal would echo it
    (`"... Extra inputs are not permitted ... input_value=..."`), which does not belong in a
    message a model reads back.

    Owner ruling, 2026-09-21: a mistyped argument name must never be silently dropped and read as
    absent — `save_definition(id=...)` where the schema says `definition_id=` would otherwise
    CREATE instead of editing, because `mcp` 2.2.0's per-tool argument model ignores an extra key
    by default (see `_forbid_unknown_arguments`'s docstring).
    """

    async def call_tool(self, name: str, arguments: dict[str, Any], context: Any = None) -> Any:
        arguments = arguments or {}  # G4: `_handle_call_tool` always passes a dict, a direct call
        # might not — refuse rather than crash on `set(None)`.
        tool = self._tool_manager.get_tool(name)
        if tool is not None:
            # G2: built from `alias or name`, exactly the key pydantic itself accepts
            # (`func_metadata.py`'s own mapping) — a parameter whose name shadows a `BaseModel`
            # attribute (`json`, `copy`, `dict`, ...) is renamed to `field_<name>` with `alias=
            # <name>`, and `model_fields` alone would hold the renamed form, not the wire key.
            fields = tool.fn_metadata.arg_model.model_fields
            allowed = {info.alias or field_name for field_name, info in fields.items()}
            unknown = sorted(set(arguments) - allowed)
            if unknown:
                raise ToolError(f"{name} does not accept {unknown}; its arguments are {sorted(allowed)}")
        return await super().call_tool(name, arguments, context)


mcp = _StrictMCPServer("servicetag")

device = Device.from_env()

TOOL_NAMES: tuple[str, ...] = (
    "pair",
    "status",
    "list_assets",
    "get_asset",
    "create_asset",
    "update_asset",
    "create_component",
    "retire_asset",
    "archive_asset",
    "list_definitions",
    "save_definition",
    "archive_definition",
    "list_profiles",
    "save_profile",
    "archive_profile",
    "list_events",
    "log_event",
    "update_event",
    "delete_event",
    "list_tag_bindings",
    "import_merge",
    # 1.2 — the maintenance surface (master plan §10). Seventeen, taking the total to 38.
    "list_groups",
    "get_group",
    "list_asset_groups",
    "create_group",
    "update_group",
    "archive_group",
    "list_schedules",
    "get_schedule",
    "create_schedule",
    "update_schedule",
    "pause_schedule",
    "archive_schedule",
    "postpone_schedule",
    "complete_schedule",
    "close_round",
    "list_closures",
    "list_due",
)
"""Every tool this server offers — `pair` plus one per API operation — written out so a dropped one
is a test failure and not a surprise."""

_IMPORT_TIMEOUT = httpx.Timeout(30.0, read=120.0)
"""A full-phone merge plans and applies inside one Room transaction on the phone; 30s is a
plausible ceiling to hit on a very full archive, so the two import calls get a longer read budget
than everything else (finding 12)."""


def _call(method: str, path: str, **kwargs: Any) -> dict[str, Any]:
    """Every tool's one HTTP call, with `client.py`'s exceptions turned into a message the SDK will
    actually deliver (see the module docstring). `client.py` stays free of any SDK import; this is
    the one seam where that conversion happens.
    """
    try:
        return device.request(method, path, **kwargs)
    except NotPaired as exc:
        raise ToolError(str(exc)) from exc
    except ApiError as exc:
        detail = f"{exc.status} {exc.code}: {exc.message}"
        if exc.problems:
            detail += f" ({', '.join(exc.problems)})"
        raise ToolError(detail) from exc
    except (RuntimeError, OSError, httpx.HTTPError) as exc:
        raise ToolError(str(exc)) from exc
    except ValueError as exc:
        # `response.json()` on a 200 whose body is not JSON raises `json.JSONDecodeError`, a
        # `ValueError` — reachable with no app bug at all: `SERVICETAG_API_BASE_URL` is a
        # documented, user-set variable, and pointing it at anything else that answers 200 with a
        # non-JSON body (or a truncated one) must not crash past this seam either (R1). The
        # message never echoes the body: it is bounded to what a caller needs to fix the setup.
        raise ToolError(
            "the phone's answer was not valid JSON — check SERVICETAG_API_BASE_URL and that the "
            "Developer API screen is open"
        ) from exc


def _path_id(value: str, *, field: str) -> str:
    """URL-quote a path segment and refuse an empty one.

    An empty id would build a path like `/v1/assets/` — which `HttpWire.kt`'s trailing-slash trim
    canonicalises straight to `/v1/assets`, the whole *list* — silently answering with the wrong
    shape instead of the 404 an empty id should mean. Quoting protects the same segment from an id
    containing `/`, `?` or `#`, which would otherwise re-route, truncate or add a query.
    """
    if not value:
        raise ToolError(f"{field} must not be empty")
    return quote(value, safe="")


def _body(**fields: Any) -> dict[str, Any]:
    """Only what the caller actually gave. The app's decoder is strict, and its defaults are right.
    Used for a **create**, where an omitted field is correctly the API's own default — there is no
    existing row for "omitted" to silently overwrite."""
    return {k: v for k, v in fields.items() if v is not None}


def _overlay(current: Any, given: Any) -> Any:
    """The clearing convention for every overlay tool's ordinary arguments: an **omitted** argument
    and an argument explicitly sent as **`null`** are the same thing — both keep the row's current
    value — and any other, non-null value replaces it. They have to be the same thing: an MCP client
    that bridges to strict function calling sends `null` for every optional argument the caller did
    not ask it to set, so if `null` meant "clear", a one-field rename from such a client would wipe
    every other field. Blanking a text field is simply passing `""` as that value; forcing a field
    to nothing more deliberately — a text field to `""` or a nullable field to `null` — by name
    rather than by value is `clear_fields`, below."""
    return current if given is None else given


def _overlay_or_clear(current: Any, given: Any, name: str, to_clear: set[str], *, when_cleared: Any) -> Any:
    """As [_overlay], except a field named in `to_clear` (already validated by
    [_validate_clear_fields]) is forced to `when_cleared` regardless of what was given —
    `to_clear` and a non-null `given` for the same field are mutually exclusive by the time this
    runs. `when_cleared` is `""` for a text field, `None` for a nullable one."""
    if name in to_clear:
        return when_cleared
    return _overlay(current, given)


def _validate_clear_fields(
    clear_fields: list[str] | None, clearable: frozenset[str], supplied: dict[str, Any]
) -> set[str]:
    """The `clear_fields` contract, checked before any HTTP call: every name must be one of
    `clearable`, and a field named here must not also have been given a non-null value — the two
    are different instructions for the same field, and this tool will not guess which one wins."""
    if not clear_fields:
        return set()
    names = set(clear_fields)
    unknown = names - clearable
    if unknown:
        raise ToolError(
            f"clear_fields names a field that cannot be cleared here: {sorted(unknown)}; "
            f"clearable fields are {sorted(clearable)}"
        )
    conflicting = {name for name in names if supplied.get(name) is not None}
    if conflicting:
        raise ToolError(
            f"clear_fields lists a field that was also given a value: {sorted(conflicting)} — "
            "pass the value, or clear it, not both"
        )
    return names


def _field(row: Any, key: str, *, of: str) -> Any:
    """A response field, read defensively (R1). `row[key]` unguarded lets `KeyError`/`TypeError`
    past `_call`'s conversion seam the moment the shape is not what an overlay tool expects — a
    version skew, or `SERVICETAG_API_BASE_URL` pointed at something that answers 200 but is not
    this API. Used for every subscript an overlay tool applies to a response: the top-level
    `"asset"`/`"definitions"`/`"profiles"` wrapper and each field read off the row it finds."""
    try:
        return row[key]
    except (KeyError, TypeError) as exc:
        raise ToolError(
            f"the phone's answer for {of} has no {key!r} field — check that "
            "SERVICETAG_API_BASE_URL (if set) points at the ServiceTag API and that the app is "
            "the version this tool was written for"
        ) from exc


def _list_field(row: Any, key: str, *, of: str) -> list[Any]:
    """[key] off [row], required to already be a JSON array — the check an overlay tool needs
    before it iterates a list it read back and folds it into a full-replacement write (the
    Invariant: a malformed read must never become a write). Reuses [_field] for the missing-key
    case and adds only the list check, naming `<of>.<key>` when the value is present but not one."""
    value = _field(row, key, of=of)
    if not isinstance(value, list):
        raise ToolError(f"{of}.{key} was not a list")
    return value


def _entry(items: list[Any], index: int, *, of: str) -> dict[str, Any]:
    """One element of a list already proven to be a list ([_list_field]), required to be a JSON
    object — an entry that is not one (a bare string, a number, `null`) cannot carry the fields an
    overlay reads off it. Pass the *unindexed* description as [of] (e.g. `"the quick action's
    fields"`); the raised message, and the `of` a caller should pass to any further [_field] read
    on the returned row, both carry the index as `<of>[<index>]`."""
    item = items[index]
    if not isinstance(item, dict):
        raise ToolError(f"{of}[{index}] was not an object")
    return item


def _find_by_id(rows: Any, row_id: str, *, field: str, of: str) -> dict[str, Any]:
    """The row an edit tool needs to overlay onto, from a list read the API already offers."""
    if not isinstance(rows, list):
        raise ToolError(f"the phone's answer for {of} was not a list")
    for row in rows:
        if isinstance(row, dict) and row.get("id") == row_id:
            return row
    raise ToolError(f"no {field} {row_id!r} among that asset's rows")


@mcp.tool()
def pair(code: str) -> str:
    """Hand over the pairing code shown on the phone's Settings > Utilities > Developer API screen.

    The code is new every time that screen opens, so pair again after closing and reopening it.
    """
    device.token = code.strip().upper()
    return "paired"


@mcp.tool()
def status() -> dict[str, Any]:
    """The app's version, the contract version, and a row count per table."""
    return _call("GET", "/v1/status")


@mcp.tool()
def list_assets() -> dict[str, Any]:
    """Every asset: the top-level systems, and each system's components under its own id."""
    return _call("GET", "/v1/assets")


@mcp.tool()
def get_asset(asset_id: str) -> dict[str, Any]:
    """One asset, in the same shape a backup archive carries it."""
    return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}")


@mcp.tool()
def create_asset(
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    purchase_on: str | None = None,
    in_service_on: str | None = None,
    purchase_price_minor: int | None = None,
    currency: str | None = None,
    vendor: str | None = None,
    location: str | None = None,
    warranty_expires_on: str | None = None,
    warranty_notes: str | None = None,
    parent_asset_id: str | None = None,
    season_start_mmdd: str | None = None,
    season_end_mmdd: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset. `template_key` seeds its readings and quick actions, in the same write.

    Every field is optional but `name`: an omitted one is simply the API's own default (blank text,
    or no value at all) for a brand-new row, not something being cleared.

    `season_start_mmdd`/`season_end_mmdd` are `MM-DD`, e.g. `"04-01"` — a month and a day, never a
    year, and never `MMDD` without the dash. The app requires both or neither: set both to give the
    asset a season window, or leave both unset for year-round.
    """
    return _call(
        "POST",
        "/v1/assets",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            manufacturer=manufacturer,
            model=model,
            serialNumber=serial_number,
            purchaseOn=purchase_on,
            inServiceOn=in_service_on,
            purchasePriceMinor=purchase_price_minor,
            currency=currency,
            vendor=vendor,
            location=location,
            warrantyExpiresOn=warranty_expires_on,
            warrantyNotes=warranty_notes,
            parentAssetId=parent_asset_id,
            seasonStartMmdd=season_start_mmdd,
            seasonEndMmdd=season_end_mmdd,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


_ASSET_TEXT_CLEARABLE: frozenset[str] = frozenset({
    "category", "description", "notes", "manufacturer", "model", "serial_number",
    "vendor", "location", "warranty_notes",
})
_ASSET_NULLABLE_CLEARABLE: frozenset[str] = frozenset({
    "purchase_on", "in_service_on", "purchase_price_minor", "currency",
    "warranty_expires_on", "parent_asset_id", "season_start_mmdd", "season_end_mmdd",
})
_ASSET_CLEARABLE_FIELDS: frozenset[str] = _ASSET_TEXT_CLEARABLE | _ASSET_NULLABLE_CLEARABLE
"""Every field but `name` — the app requires it non-blank, so it is never in `clear_fields`."""


@mcp.tool()
def update_asset(
    asset_id: str,
    name: str | None = None,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    purchase_on: str | None = None,
    in_service_on: str | None = None,
    purchase_price_minor: int | None = None,
    currency: str | None = None,
    vendor: str | None = None,
    location: str | None = None,
    warranty_expires_on: str | None = None,
    warranty_notes: str | None = None,
    parent_asset_id: str | None = None,
    season_start_mmdd: str | None = None,
    season_end_mmdd: str | None = None,
    clear_fields: list[str] | None = None,
) -> dict[str, Any]:
    """Edit an asset.

    **Two layers, and they are not the same thing.** The Android app's own `PATCH /v1/assets/{id}`
    is a full replacement — every field on the wire is what the asset ends up with. This tool adds
    partial-edit convenience on top: it reads the asset's current fields first, overlays only the
    arguments you actually supplied, and submits the complete replacement for you. Nothing about
    calling this tool requires stating all eighteen fields.

    An **omitted** argument and one sent explicitly as **`null`** both leave the asset's current
    value alone — the same thing, on purpose: an MCP client that bridges to strict function calling
    sends `null` for every optional argument its caller did not set, and if `null` meant "clear", a
    one-field rename from such a client would silently wipe the other seventeen. A **supplied,
    non-null value replaces the current one** — for a text field (`category`, `description`,
    `notes`, `manufacturer`, `model`, `serial_number`, `vendor`, `location`, `warranty_notes`) an
    explicit `""` is simply that value, setting it empty.

    Clearing by *name* rather than by value is `clear_fields` (e.g. `clear_fields=["vendor"]`,
    `clear_fields=["currency", "parent_asset_id"]`): every field but `name` is clearable — a text
    field is sent as `""`, a nullable field (`purchase_on`, `in_service_on`,
    `purchase_price_minor`, `currency`, `warranty_expires_on`, `parent_asset_id`,
    `season_start_mmdd`, `season_end_mmdd`) as `null`. Clearing `parent_asset_id` is what promotes
    a component to a top-level asset. `name` and `asset_id` can never be cleared — the app requires
    both. Naming an unknown field, or naming one you also passed a value for, is refused before any
    request is made.

    `season_start_mmdd`/`season_end_mmdd` are `MM-DD`, e.g. `"04-01"` — the app requires both or
    neither, so setting or clearing only one of the pair is refused by the app itself (this tool
    does not check it locally). Clear both together to go back to year-round.

    `template_key` is not a parameter here because the app ignores it on an edit — it only seeds a
    *new* asset (`create_asset`, `create_component`).
    """
    supplied = {
        "category": category,
        "description": description,
        "notes": notes,
        "manufacturer": manufacturer,
        "model": model,
        "serial_number": serial_number,
        "purchase_on": purchase_on,
        "in_service_on": in_service_on,
        "purchase_price_minor": purchase_price_minor,
        "currency": currency,
        "vendor": vendor,
        "location": location,
        "warranty_expires_on": warranty_expires_on,
        "warranty_notes": warranty_notes,
        "parent_asset_id": parent_asset_id,
        "season_start_mmdd": season_start_mmdd,
        "season_end_mmdd": season_end_mmdd,
    }
    to_clear = _validate_clear_fields(clear_fields, _ASSET_CLEARABLE_FIELDS, supplied)

    path = f"/v1/assets/{_path_id(asset_id, field='asset_id')}"
    current = _field(_call("GET", path), "asset", of="the asset lookup")

    def text(key: str, given: str | None, name: str) -> str:
        return _overlay_or_clear(_field(current, key, of="the asset"), given, name, to_clear, when_cleared="")

    def nullable(key: str, given: Any, name: str) -> Any:
        return _overlay_or_clear(_field(current, key, of="the asset"), given, name, to_clear, when_cleared=None)

    body = {
        "name": _overlay(_field(current, "name", of="the asset"), name),
        "category": text("category", category, "category"),
        "description": text("description", description, "description"),
        "notes": text("notes", notes, "notes"),
        "manufacturer": text("manufacturer", manufacturer, "manufacturer"),
        "model": text("model", model, "model"),
        "serialNumber": text("serialNumber", serial_number, "serial_number"),
        "purchaseOn": nullable("purchaseOn", purchase_on, "purchase_on"),
        "inServiceOn": nullable("inServiceOn", in_service_on, "in_service_on"),
        "purchasePriceMinor": nullable("purchasePriceMinor", purchase_price_minor, "purchase_price_minor"),
        "currency": nullable("currency", currency, "currency"),
        "vendor": text("vendor", vendor, "vendor"),
        "location": text("location", location, "location"),
        "warrantyExpiresOn": nullable("warrantyExpiresOn", warranty_expires_on, "warranty_expires_on"),
        "warrantyNotes": text("warrantyNotes", warranty_notes, "warranty_notes"),
        "parentAssetId": nullable("parentAssetId", parent_asset_id, "parent_asset_id"),
        "seasonStartMmdd": nullable("seasonStartMmdd", season_start_mmdd, "season_start_mmdd"),
        "seasonEndMmdd": nullable("seasonEndMmdd", season_end_mmdd, "season_end_mmdd"),
    }
    return _call("PATCH", path, json_body=body, content_type="application/json")


@mcp.tool()
def create_component(
    parent_asset_id: str,
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    purchase_on: str | None = None,
    in_service_on: str | None = None,
    purchase_price_minor: int | None = None,
    currency: str | None = None,
    vendor: str | None = None,
    location: str | None = None,
    warranty_expires_on: str | None = None,
    warranty_notes: str | None = None,
    season_start_mmdd: str | None = None,
    season_end_mmdd: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset as a component of another. The path decides the parent, not the body — the
    app ignores a body-level parent on this endpoint, so there is no `parent_asset_id` body field
    to pass here beyond the one naming which asset this is a component of.

    `season_start_mmdd`/`season_end_mmdd` are `MM-DD`, e.g. `"04-01"` — a month and a day, never a
    year. The app requires both or neither: set both together, or leave both unset for year-round.
    """
    return _call(
        "POST",
        f"/v1/assets/{_path_id(parent_asset_id, field='parent_asset_id')}/components",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            manufacturer=manufacturer,
            model=model,
            serialNumber=serial_number,
            purchaseOn=purchase_on,
            inServiceOn=in_service_on,
            purchasePriceMinor=purchase_price_minor,
            currency=currency,
            vendor=vendor,
            location=location,
            warrantyExpiresOn=warranty_expires_on,
            warrantyNotes=warranty_notes,
            seasonStartMmdd=season_start_mmdd,
            seasonEndMmdd=season_end_mmdd,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


@mcp.tool()
def retire_asset(asset_id: str, retired_on: str) -> dict[str, Any]:
    """Retire the asset on a date (`YYYY-MM-DD`). Required, and must not be blank or null — a
    missing, empty or `null` date is refused before any request is made, and nothing is sent.

    This tool is **monotonic**: it can only retire, never un-retire. The app's own API row is
    unchanged (it still answers `retired_on: null` to un-retire), but that half is not exposed here
    in 1.1.0 — un-retire an asset in the app itself.
    """
    if not retired_on:
        raise ToolError("retired_on is required (YYYY-MM-DD); this tool cannot un-retire an asset")
    return _call(
        "POST",
        f"/v1/assets/{_path_id(asset_id, field='asset_id')}/retire",
        json_body={"retiredOn": retired_on},
        content_type="application/json",
    )


@mcp.tool()
def archive_asset(asset_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive an asset. Archive is not delete: tags bound to it still resolve."""
    return _call(
        "POST",
        f"/v1/assets/{_path_id(asset_id, field='asset_id')}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_definitions(asset_id: str) -> dict[str, Any]:
    """The readings defined on one asset, archived ones included."""
    return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/definitions")


_DEFINITION_TEXT_CLEARABLE: frozenset[str] = frozenset({"unit"})
"""`key` is excluded on purpose: blank already means "leave it alone" to the app itself
(`SaveDefinitionRequest.key`'s own doc), so naming it in `clear_fields` could not do anything a
caller would recognise as "clearing". `label`, `kind` and `value_type` are excluded because they
are either required or an enum the app would refuse blank."""
_DEFINITION_NULLABLE_CLEARABLE: frozenset[str] = frozenset({
    "range_low", "range_high", "formula", "source_a_id", "source_b_id",
})
_DEFINITION_CLEARABLE_FIELDS: frozenset[str] = (
    _DEFINITION_TEXT_CLEARABLE | _DEFINITION_NULLABLE_CLEARABLE
)


@mcp.tool()
def save_definition(
    asset_id: str,
    label: str | None = None,
    definition_id: str | None = None,
    key: str | None = None,
    unit: str | None = None,
    kind: str | None = None,
    value_type: str | None = None,
    decimals: int | None = None,
    range_low: float | None = None,
    range_high: float | None = None,
    is_meter: bool | None = None,
    formula: str | None = None,
    source_a_id: str | None = None,
    source_b_id: str | None = None,
    clear_fields: list[str] | None = None,
) -> dict[str, Any]:
    """Create a reading, or edit one by passing `definition_id`.

    **Create** (no `definition_id`): `label` is required; every other field starts from the API's
    own default (`key=""` generates one from the label, `kind="ENTERED"`, `value_type="NUMBER"`,
    `decimals=0`, no range, not a meter). `key` blank is always "leave it alone" to the app itself,
    on a create as well as an edit, so passing `key=""` never sets it blank. `clear_fields` does not
    apply to a create and is refused if given.

    **Edit** (`definition_id` given): the app's own `POST /v1/definitions` write is a full
    replacement of the row, same as `update_asset`'s `PATCH`; this tool gives it the same
    convenience — it reads the asset's current readings (`list_definitions`), finds `definition_id`
    among them, and sends the whole row back with only the fields you passed changed. An **omitted**
    argument and one sent explicitly as **`null`** both keep what the reading already has, the same
    as `update_asset`; a supplied non-null value replaces it. Clearing by *name* rather than by
    value is `clear_fields`: `unit` is sent as `""`; `range_low`, `range_high`, `formula`,
    `source_a_id` and `source_b_id` are nullable and are sent as `null`. `key`, `label`, `kind` and
    `value_type` can never be cleared — blank already means something else for `key` (see above),
    and the rest are required or an enum. Once measurements exist, the app refuses a change to
    `value_type`, `kind` or `key` either way.
    """
    supplied = {
        "unit": unit,
        "range_low": range_low,
        "range_high": range_high,
        "formula": formula,
        "source_a_id": source_a_id,
        "source_b_id": source_b_id,
    }
    if definition_id is None:
        if clear_fields:
            raise ToolError("clear_fields only applies to editing an existing reading (pass definition_id)")
        if label is None:
            raise ToolError("label is required to create a reading (pass definition_id to edit one)")
        body = _body(
            assetId=asset_id,
            key=key,
            label=label,
            unit=unit,
            kind=kind,
            valueType=value_type,
            decimals=decimals,
            rangeLow=range_low,
            rangeHigh=range_high,
            isMeter=is_meter,
            formula=formula,
            sourceAId=source_a_id,
            sourceBId=source_b_id,
        )
    else:
        to_clear = _validate_clear_fields(clear_fields, _DEFINITION_CLEARABLE_FIELDS, supplied)

        rows = _list_field(
            _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/definitions"),
            "definitions",
            of="the definitions list",
        )
        current = _find_by_id(rows, definition_id, field="definition_id", of="the definitions list")

        def text(key: str, given: str | None, name: str) -> str:
            return _overlay_or_clear(
                _field(current, key, of="the reading"), given, name, to_clear, when_cleared=""
            )

        def nullable(key: str, given: Any, name: str) -> Any:
            return _overlay_or_clear(
                _field(current, key, of="the reading"), given, name, to_clear, when_cleared=None
            )

        body = {
            "id": definition_id,
            "assetId": asset_id,
            "key": _overlay(_field(current, "key", of="the reading"), key),
            "label": _overlay(_field(current, "label", of="the reading"), label),
            "unit": text("unit", unit, "unit"),
            "kind": _overlay(_field(current, "kind", of="the reading"), kind),
            "valueType": _overlay(_field(current, "valueType", of="the reading"), value_type),
            "decimals": _field(current, "decimals", of="the reading") if decimals is None else decimals,
            "rangeLow": nullable("rangeLow", range_low, "range_low"),
            "rangeHigh": nullable("rangeHigh", range_high, "range_high"),
            "isMeter": _field(current, "isMeter", of="the reading") if is_meter is None else is_meter,
            "formula": nullable("formula", formula, "formula"),
            "sourceAId": nullable("sourceAId", source_a_id, "source_a_id"),
            "sourceBId": nullable("sourceBId", source_b_id, "source_b_id"),
        }
    return _call("POST", "/v1/definitions", json_body=body, content_type="application/json")


@mcp.tool()
def archive_definition(definition_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a reading. Archived readings leave the forms and keep their history."""
    return _call(
        "POST",
        f"/v1/definitions/{_path_id(definition_id, field='definition_id')}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_profiles(asset_id: str) -> dict[str, Any]:
    """The quick actions defined on one asset, archived ones included."""
    return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/profiles")


@mcp.tool()
def save_profile(
    asset_id: str,
    name: str | None = None,
    event_kind: str | None = None,
    profile_id: str | None = None,
    default_title: str | None = None,
    fields: list[dict[str, Any]] | None = None,
    consumables: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Create a quick action, or edit one by passing `profile_id`.

    **Create** (no `profile_id`): `name` and `event_kind` are required; `fields` is a list of
    `{"definitionId": ..., "required": bool}`, each naming an ENTERED reading of the same asset;
    `consumables` is `{"name", "defaultQuantity", "unit"}`. Both default to empty.

    **Edit** (`profile_id` given): the app's own write is a full replacement, same as
    `update_asset`; this tool reads the asset's current quick actions (`list_profiles`), finds
    `profile_id` among them, and sends the whole row back with only the fields you passed changed.
    An **omitted** argument and one sent explicitly as **`null`** both keep what the quick action
    already has — `fields`/`consumables` included — the same convention `update_asset` uses; pass
    `fields`/`consumables` only when you mean to replace the whole list. There is no `clear_fields`
    here: every field of this row is either text (an explicit `""` already clears it) or a list
    (an explicit `[]` already clears it) — none of them is a *nullable* field the way an asset's
    `currency` is. Each existing consumable's `id` travels with it when kept, which is what keeps
    its identity across the edit; a `consumables` list you pass yourself may include `id` the same
    way.
    """
    if profile_id is None:
        if name is None or event_kind is None:
            raise ToolError(
                "name and event_kind are required to create a quick action (pass profile_id to edit one)"
            )
        body = _body(
            assetId=asset_id,
            name=name,
            eventKind=event_kind,
            defaultTitle=default_title,
            fields=fields,
            consumables=consumables,
        )
    else:
        rows = _field(
            _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/profiles"),
            "profiles",
            of="the profiles list",
        )
        current = _find_by_id(rows, profile_id, field="profile_id", of="the profiles list")

        def typed(value: Any, *, of: str, kind: type, label: str) -> Any:
            if not isinstance(value, kind):
                raise ToolError(f"{of} was not {label}")
            return value

        def kept_fields_from(row: dict[str, Any]) -> list[dict[str, Any]]:
            items = _list_field(row, "fields", of="the quick action")
            kept: list[dict[str, Any]] = []
            for index in range(len(items)):
                entry = _entry(items, index, of="the quick action's fields")
                entry_of = f"the quick action's fields[{index}]"
                definition_id = typed(
                    _field(entry, "definitionId", of=entry_of),
                    of=f"{entry_of}.definitionId", kind=str, label="a string",
                )
                if not definition_id:
                    raise ToolError(f"{entry_of}.definitionId was empty")
                required = typed(
                    _field(entry, "required", of=entry_of),
                    of=f"{entry_of}.required", kind=bool, label="a boolean",
                )
                kept.append({"definitionId": definition_id, "required": required})
            return kept

        def kept_consumables_from(row: dict[str, Any]) -> list[dict[str, Any]]:
            items = _list_field(row, "consumables", of="the quick action")
            kept: list[dict[str, Any]] = []
            for index in range(len(items)):
                entry = _entry(items, index, of="the quick action's consumables")
                entry_of = f"the quick action's consumables[{index}]"
                consumable_id = typed(
                    _field(entry, "id", of=entry_of),
                    of=f"{entry_of}.id", kind=str, label="a string",
                )
                name_value = typed(
                    _field(entry, "name", of=entry_of),
                    of=f"{entry_of}.name", kind=str, label="a string",
                )
                unit = typed(
                    _field(entry, "unit", of=entry_of),
                    of=f"{entry_of}.unit", kind=str, label="a string",
                )
                quantity = _field(entry, "defaultQuantity", of=entry_of)
                quantity_of = f"{entry_of}.defaultQuantity"
                if quantity is not None:
                    quantity = typed(quantity, of=quantity_of, kind=(int, float), label="a number")
                    if isinstance(quantity, bool):
                        raise ToolError(f"{quantity_of} was not a number")
                kept.append({
                    "id": consumable_id,
                    "name": name_value,
                    "defaultQuantity": quantity,
                    "unit": unit,
                })
            return kept

        kept_fields = kept_fields_from(current)
        kept_consumables = kept_consumables_from(current)
        body = {
            "id": profile_id,
            "assetId": asset_id,
            "name": _overlay(_field(current, "name", of="the quick action"), name),
            "eventKind": _overlay(_field(current, "eventKind", of="the quick action"), event_kind),
            "defaultTitle": _overlay(_field(current, "defaultTitle", of="the quick action"), default_title),
            "fields": kept_fields if fields is None else fields,
            "consumables": kept_consumables if consumables is None else consumables,
        }
    return _call("POST", "/v1/profiles", json_body=body, content_type="application/json")


@mcp.tool()
def archive_profile(profile_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a quick action. Events logged through it keep pointing at it."""
    return _call(
        "POST",
        f"/v1/profiles/{_path_id(profile_id, field='profile_id')}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_events(asset_id: str) -> dict[str, Any]:
    """One asset's service record, newest first."""
    return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/events")


@mcp.tool()
def log_event(
    asset_id: str,
    kind: str,
    occurred_on: str,
    tz_id: str,
    title: str | None = None,
    profile_id: str | None = None,
    occurred_time: str | None = None,
    notes: str | None = None,
    values: dict[str, str] | None = None,
    consumables: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Log a maintenance event with its readings and the materials that went in.

    `values` is keyed by definition id, the text a person would type. `consumables` is
    `{"name", "quantity", "unit"}`. `kind` is one of MAINTENANCE, INSPECTION, MEASUREMENT,
    TREATMENT, INCIDENT, REPLACEMENT, SEASON_START, SEASON_END, NOTE, CUSTOM.
    """
    return _call(
        "POST",
        "/v1/events",
        json_body=_body(
            assetId=asset_id,
            profileId=profile_id,
            kind=kind,
            title=title,
            occurredOn=occurred_on,
            occurredTime=occurred_time,
            tzId=tz_id,
            notes=notes,
            values=values,
            consumables=consumables,
        ),
        content_type="application/json",
    )


@mcp.tool()
def update_event(
    event_id: str,
    asset_id: str,
    kind: str,
    occurred_on: str,
    tz_id: str,
    title: str,
    profile_id: str | None,
    occurred_time: str | None,
    notes: str,
    values: dict[str, str],
    consumables: list[dict[str, str]],
) -> dict[str, Any]:
    """Edit a logged event. **This replaces the whole event.**

    Unlike `update_asset`, this tool cannot read the current event back in a shape it could safely
    resend: `list_events` reports a logged event's readings as typed measurements (a number or
    text, already parsed), not as the definition-id-to-text map this endpoint reads — reconstructing
    one from the other would risk silently reformatting a value. So there is no overlay here and no
    default that could clear something by omission: every argument is required. Pass `title=""` or
    `notes=""` for none, `profile_id=None` to unlink the event from its quick action, and
    `values={}` / `consumables=[]` to clear the event's readings or consumables on purpose rather
    than by accident. `asset_id` must be the asset the event already belongs to — an edit never
    re-parents.
    """
    return _call(
        "PATCH",
        f"/v1/events/{_path_id(event_id, field='event_id')}",
        json_body={
            "assetId": asset_id,
            "profileId": profile_id,
            "kind": kind,
            "title": title,
            "occurredOn": occurred_on,
            "occurredTime": occurred_time,
            "tzId": tz_id,
            "notes": notes,
            "values": values,
            "consumables": consumables,
        },
        content_type="application/json",
    )


@mcp.tool()
def delete_event(event_id: str) -> dict[str, Any]:
    """Delete a logged event and its readings. This one is destructive and has no undo."""
    return _call("DELETE", f"/v1/events/{_path_id(event_id, field='event_id')}")


@mcp.tool()
def list_tag_bindings() -> dict[str, Any]:
    """Every NFC tag binding this phone holds. Read-only: the API cannot write or bind a tag."""
    return _call("GET", "/v1/tags")


@mcp.tool()
def import_merge(archive_path: str, plan_only: bool = False) -> dict[str, Any]:
    """Merge a ServiceTag **data** archive into the phone. It plans first, always.

    Takes the local path to a format-5 `ServiceTag-data-*.zip`. The phone decides, per row, whether
    it is new (INSERT), already here and identical (IDENTICAL, a no-op), declined (SKIPPED) or
    contested (CONFLICT) — and **one conflict anywhere means nothing is written at all**. Rows are
    only ever inserted: an id already on the phone is never overwritten and nothing is ever deleted.

    This tool asks for the plan and then applies it **only when the plan has no conflicts**. With
    `plan_only=True`, or when the plan does have conflicts, it stops and returns the plan — whose
    `conflicts` list names each one by table, id and a stable reason code, in a deterministic order.
    Read `applicable` to know which happened.

    Attachment rows are written only when their bytes are already in the phone's attachment folder,
    and skipped otherwise, so a merge can never leave a row pointing at a file that is not there.

    The archive must be at most 4 MiB — the same ceiling the phone itself enforces on both import
    paths — and is checked before anything is read or sent, not after a slow upload. Both calls get
    a 120-second read budget rather than the usual 30, because a full-phone merge plans and applies
    inside one Room transaction on the phone; a timeout here does not mean the merge failed, and
    re-running is safe, because the merge only ever inserts and an identical row is a no-op.
    """
    try:
        size = Path(archive_path).stat().st_size
    except OSError as exc:
        raise ToolError(str(exc)) from exc
    if size > MAX_IMPORT_BYTES:
        raise ToolError(
            f"{archive_path} is {size} bytes; the phone refuses an import archive over "
            f"{MAX_IMPORT_BYTES} bytes"
        )
    try:
        body = Path(archive_path).read_bytes()
    except OSError as exc:
        raise ToolError(str(exc)) from exc
    plan = _call(
        "POST",
        "/v1/import-merge/plan",
        content=body,
        content_type="application/zip",
        timeout=_IMPORT_TIMEOUT,
    )
    if not isinstance(plan, dict):
        # `.get` below would be an `AttributeError` on anything else (R1) — a version skew or a
        # misdirected `SERVICETAG_API_BASE_URL` again, not an app bug.
        raise ToolError("the phone's import-merge plan was not a JSON object — check SERVICETAG_API_BASE_URL")
    if plan_only or not plan.get("applicable", False):
        return plan
    # A 409 from the apply is the report, not an error: the phone re-planned inside its own
    # transaction, found a conflict (or found the destination had moved), and wrote nothing. Read
    # `applicable` — it is false — and `conflicts`.
    return _call(
        "POST",
        "/v1/import-merge/apply",
        content=body,
        content_type="application/zip",
        report_statuses=(409,),
        timeout=_IMPORT_TIMEOUT,
    )


# --- 1.2, the maintenance surface -----------------------------------------------------------------
#
# Seventeen tools over master plan §9's nineteen routes. Everything above's conventions hold
# unchanged: an unknown argument is refused before the body runs, a create sends only what it was
# given, an edit reads the row and overlays only what it was given, an **omitted** argument and an
# explicit **`null`** both mean "leave alone", and clearing is by name through `clear_fields`.
#
# Two tools deliberately have **no** overlay — `complete_schedule` and `close_round` — for
# `update_event`'s reason turned up a notch: each records a **new fact**, and there is nothing about
# a new fact to inherit from a row. Overlaying one would invent provenance.
#
# And there is nothing destructive here at all: no delete-group, no delete-schedule, no
# delete-closure, no snooze. The API has no route for any of them.


@mcp.tool()
def list_groups() -> dict[str, Any]:
    """Every maintenance group, archived ones included, by name.

    A group is a set of assets one job is done across — "every hydrant on the north run". It is not
    an asset, holds no NFC tag and is never part of the asset tree.
    """
    return _call("GET", "/v1/groups")


@mcp.tool()
def get_group(group_id: str) -> dict[str, Any]:
    """One group with its membership windows, in the same shape a backup archive carries it.

    Each member row carries its own durable `id`, the `addedAt` instant its window opened and
    `removedAt` — `null` while it is still running. A removed member's row is **kept**, closed: that
    is what lets every past round still say who it obliged.
    """
    return _call("GET", f"/v1/groups/{_path_id(group_id, field='group_id')}")


@mcp.tool()
def list_asset_groups(asset_id: str) -> dict[str, Any]:
    """The groups one asset is an **open** member of."""
    return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/groups")


@mcp.tool()
def create_group(
    name: str,
    description: str | None = None,
    members: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Create a maintenance group.

    `members` is `[{"assetId": ..., "sortOrder": 0}]`. Each entry opens a membership window stamped
    with the moment of this call — **you cannot set `addedAt` or `removedAt`**, here or anywhere:
    when a member joined and left is a fact the app records, not one a caller writes. An entry may
    carry `sortOrder` to fix the display order; it defaults to 0.

    Every field is optional but `name`: an omitted one is the API's own default for a brand-new row,
    not something being cleared.
    """
    return _call(
        "POST",
        "/v1/groups",
        json_body=_body(name=name, description=description, members=members),
        content_type="application/json",
    )


_GROUP_TEXT_CLEARABLE: frozenset[str] = frozenset({"description"})
_GROUP_LIST_CLEARABLE: frozenset[str] = frozenset({"members"})
_GROUP_CLEARABLE_FIELDS: frozenset[str] = _GROUP_TEXT_CLEARABLE | _GROUP_LIST_CLEARABLE
"""`name` is absent on purpose: the app requires it non-blank, so it can never be cleared."""


@mcp.tool()
def update_group(
    group_id: str,
    name: str | None = None,
    description: str | None = None,
    members: list[dict[str, Any]] | None = None,
    clear_fields: list[str] | None = None,
) -> dict[str, Any]:
    """Edit a group, including who is in it.

    **Two layers, as everywhere above.** `PATCH /v1/groups/{id}` is a full replacement; this tool
    reads the group first, overlays only what you supplied, and submits the whole thing. An omitted
    argument and one sent as `null` both leave the current value alone.

    **The member list is the case to read twice.** Left alone, every currently open membership is
    kept — each one is re-sent by its own `id`, which is what keeps its window and its `addedAt`
    exactly where they are. **Supply a list and it replaces the membership wholesale**: every open
    window your list omits is **closed** (`removedAt` stamped), and nothing is ever deleted, so every
    past round still knows who it obliged. Include an existing window's `id` to keep it; leave the
    `id` off an entry to **add** that asset — and an add for an asset that already has an open window
    is refused by the app, because the caller meant "keep it" and the way to say that is to send its
    `id`.

    **Closing every membership is `clear_fields=["members"]`.** It has to be by name: under the
    null-means-unchanged rule an omitted or `null` `members` means "leave alone", so without a name
    there would be no way to say "nobody is in this group any more" at all. `description` is
    clearable the same way (or simply pass `""`). `name` can never be cleared.

    Removed members come back as **new** rows with new ids if you add them again later — a window
    that closed stays closed, always.
    """
    supplied = {"description": description, "members": members}
    to_clear = _validate_clear_fields(clear_fields, _GROUP_CLEARABLE_FIELDS, supplied)

    path = f"/v1/groups/{_path_id(group_id, field='group_id')}"
    current = _field(_call("GET", path), "group", of="the group lookup")

    def kept_members() -> list[dict[str, Any]]:
        rows = _list_field(current, "members", of="the group")
        kept: list[dict[str, Any]] = []
        for index in range(len(rows)):
            entry = _entry(rows, index, of="the group's members")
            entry_of = f"the group's members[{index}]"
            # A window that has already closed is left out rather than re-sent: the app leaves a
            # closed row alone whether or not its id arrives, and sending one back would read as a
            # claim that a finished membership is still current.
            if _field(entry, "removedAt", of=entry_of) is not None:
                continue
            member_id = _field(entry, "id", of=entry_of)
            asset_id = _field(entry, "assetId", of=entry_of)
            sort_order = _field(entry, "sortOrder", of=entry_of)
            kept.append({"id": member_id, "assetId": asset_id, "sortOrder": sort_order})
        return kept

    body = {
        "name": _overlay(_field(current, "name", of="the group"), name),
        "description": _overlay_or_clear(
            _field(current, "description", of="the group"), description, "description",
            to_clear, when_cleared="",
        ),
        "members": [] if "members" in to_clear else (kept_members() if members is None else members),
    }
    return _call("PATCH", path, json_body=body, content_type="application/json")


@mcp.tool()
def archive_group(group_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a group. Archive is not delete: the group and its schedules are hidden
    and every membership window and past round is kept."""
    return _call(
        "POST",
        f"/v1/groups/{_path_id(group_id, field='group_id')}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_schedules(asset_id: str | None = None, group_id: str | None = None) -> dict[str, Any]:
    """Maintenance schedules: all of them, or one asset's, or one group's.

    With no argument, every schedule on the phone. With `asset_id`, the schedules **targeting that
    asset** — never a group's, even a group that asset belongs to; ask `list_asset_groups` and then
    this tool with `group_id` for those. With `group_id`, that group's. Pass one scope or neither,
    never both.
    """
    if asset_id and group_id:
        raise ToolError("pass asset_id or group_id, not both — they are two different questions")
    if asset_id:
        return _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/schedules")
    if group_id:
        return _call("GET", f"/v1/groups/{_path_id(group_id, field='group_id')}/schedules")
    return _call("GET", "/v1/schedules")


@mcp.tool()
def get_schedule(schedule_id: str) -> dict[str, Any]:
    """One schedule, plus what the engine derives from it.

    Answers `{schedule, state, status, computedForOn}`. `state` is the derived row — due date, due
    meter, current meter, last completion, last termination — and `status` is one of `OK`,
    `DUE_SOON`, `DUE`, `OVERDUE`, `INACTIVE_SEASON`, `PAUSED`, `NO_DATA`, **computed at read time
    and never stored**. `computedForOn` is the local date it was computed for, so you always know
    which day the answer is about.
    """
    return _call("GET", f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}")


@mcp.tool()
def create_schedule(
    title: str,
    target_asset_id: str | None = None,
    target_group_id: str | None = None,
    description: str | None = None,
    time_interval: int | None = None,
    time_unit: str | None = None,
    time_basis: str | None = None,
    anchor_on: str | None = None,
    lead_days: int | None = None,
    meter_definition_id: str | None = None,
    meter_interval: float | None = None,
    anchor_meter: float | None = None,
    meter_lead: float | None = None,
    season_behavior: str | None = None,
    season_reentry: str | None = None,
    season_reentry_offset_days: int | None = None,
    completion_mode: str | None = None,
    profile_id: str | None = None,
    reminders_enabled: bool | None = None,
    providers: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Create a maintenance schedule.

    **Exactly one target**: `target_asset_id` or `target_group_id`, never both and never neither.
    **At least one rule side**: a time rule (`time_interval` + `time_unit` + `anchor_on`) or a meter
    rule (`meter_definition_id` + `meter_interval`), or both — with both, whichever comes first wins.

    `time_unit` is `DAY`｜`WEEK`｜`MONTH`｜`YEAR`. `time_basis` is `FIXED` (the series runs from
    `anchor_on` whenever the work is actually done) or `COMPLETION` (the next one is an interval
    after the last one was finished); it defaults to `FIXED`. `lead_days` is how many days early the
    schedule starts reading DUE SOON. `anchor_on` is ISO `YYYY-MM-DD`; `season_reentry` is `MM-DD`.

    `completion_mode` is `QUICK` (one tap) or `FORM`, which collects a quick action's readings —
    pass `profile_id` for that. `season_behavior` is `IGNORE` or `FOLLOW_ASSET`. `providers` is
    `[{"provider": "LOCAL", "enabled": true}]`; `LOCAL` is the only provider in 1.2.

    **A group target is narrower**, and the app enforces all of it: no meter rule, no `profile_id`,
    `completion_mode` `QUICK` only, `season_behavior` `IGNORE` only — and the group must already
    have a member, or the schedule's first round would oblige nobody.

    `season_reentry` and `season_reentry_offset_days` are stored and not read in 1.2.
    """
    return _call(
        "POST",
        "/v1/schedules",
        json_body=_body(
            title=title,
            targetAssetId=target_asset_id,
            targetGroupId=target_group_id,
            description=description,
            timeInterval=time_interval,
            timeUnit=time_unit,
            timeBasis=time_basis,
            anchorOn=anchor_on,
            leadDays=lead_days,
            meterDefinitionId=meter_definition_id,
            meterInterval=meter_interval,
            anchorMeter=anchor_meter,
            meterLead=meter_lead,
            seasonBehavior=season_behavior,
            seasonReentry=season_reentry,
            seasonReentryOffsetDays=season_reentry_offset_days,
            completionMode=completion_mode,
            profileId=profile_id,
            remindersEnabled=reminders_enabled,
            providers=providers,
        ),
        content_type="application/json",
    )


_SCHEDULE_TEXT_CLEARABLE: frozenset[str] = frozenset({"description"})
_SCHEDULE_LIST_CLEARABLE: frozenset[str] = frozenset({"providers"})
_SCHEDULE_NULLABLE_CLEARABLE: frozenset[str] = frozenset({
    "target_asset_id", "target_group_id",
    "time_interval", "time_unit", "anchor_on",
    "meter_definition_id", "meter_interval", "anchor_meter", "meter_lead",
    "season_reentry", "season_reentry_offset_days", "profile_id",
})
_SCHEDULE_CLEARABLE_FIELDS: frozenset[str] = (
    _SCHEDULE_TEXT_CLEARABLE | _SCHEDULE_LIST_CLEARABLE | _SCHEDULE_NULLABLE_CLEARABLE
)
"""Every nullable field of the command, and nothing else.

`title` is required. `time_basis`, `season_behavior` and `completion_mode` are enums the app would
refuse blank — change one by *passing* the new value. `lead_days` and `reminders_enabled` are not
nullable at all, so `0` and `false` already say what "cleared" would mean. `postponed_due_on` and
`status` are not in this command: they have their own tools (`postpone_schedule`, `pause_schedule`,
`archive_schedule`).

**The two target ids are here for one reason**: exactly one of them may be set, so moving a schedule
from an asset to a group means supplying the new one *and* clearing the old — an overlay would
otherwise keep both and the app would refuse the pair."""


@mcp.tool()
def update_schedule(
    schedule_id: str,
    title: str | None = None,
    target_asset_id: str | None = None,
    target_group_id: str | None = None,
    description: str | None = None,
    time_interval: int | None = None,
    time_unit: str | None = None,
    time_basis: str | None = None,
    anchor_on: str | None = None,
    lead_days: int | None = None,
    meter_definition_id: str | None = None,
    meter_interval: float | None = None,
    anchor_meter: float | None = None,
    meter_lead: float | None = None,
    season_behavior: str | None = None,
    season_reentry: str | None = None,
    season_reentry_offset_days: int | None = None,
    completion_mode: str | None = None,
    profile_id: str | None = None,
    reminders_enabled: bool | None = None,
    providers: list[dict[str, Any]] | None = None,
    clear_fields: list[str] | None = None,
) -> dict[str, Any]:
    """Edit a schedule's rule.

    Reads the schedule first and overlays only what you supplied, so nothing about calling this
    requires stating all twenty fields. An omitted argument and one sent as `null` both leave the
    current value alone; a supplied non-null value replaces it.

    Clearing is by name: `clear_fields=["meter_definition_id", "meter_interval"]` takes the meter
    rule off, `clear_fields=["profile_id"]` unlinks the quick action. `title` can never be cleared,
    and `time_basis`, `season_behavior` and `completion_mode` are changed by passing the new value
    rather than by clearing. `lead_days=0` and `reminders_enabled=false` say themselves what
    clearing them would mean.

    **Moving the target takes two arguments, not one.** Exactly one of `target_asset_id` and
    `target_group_id` may be set, and the overlay keeps whichever the schedule already has — so
    supplying the new one alone would submit both and be refused. Pass the new target and
    `clear_fields` the old one in the same call.

    **What an edit does beyond the fields.** A rule change clears any postponement, **abandons an
    open partially complete group round** — the member completions already recorded stay as truthful
    history and the edited rule opens the next round — and moves a never-terminated schedule's due
    date to the first series date on or after today, so re-anchoring an old schedule does not pin it
    immediately overdue. Pausing, archiving and postponing are **not** here: each is its own tool,
    so an edit can never quietly do one of them.
    """
    supplied = {
        "description": description,
        "target_asset_id": target_asset_id,
        "target_group_id": target_group_id,
        "time_interval": time_interval,
        "time_unit": time_unit,
        "anchor_on": anchor_on,
        "meter_definition_id": meter_definition_id,
        "meter_interval": meter_interval,
        "anchor_meter": anchor_meter,
        "meter_lead": meter_lead,
        "season_reentry": season_reentry,
        "season_reentry_offset_days": season_reentry_offset_days,
        "profile_id": profile_id,
        "providers": providers,
    }
    to_clear = _validate_clear_fields(clear_fields, _SCHEDULE_CLEARABLE_FIELDS, supplied)

    path = f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}"
    current = _field(_call("GET", path), "schedule", of="the schedule lookup")

    def nullable(key: str, given: Any, name: str) -> Any:
        return _overlay_or_clear(
            _field(current, key, of="the schedule"), given, name, to_clear, when_cleared=None,
        )

    def kept_providers() -> list[dict[str, Any]]:
        rows = _list_field(current, "providers", of="the schedule")
        kept: list[dict[str, Any]] = []
        for index in range(len(rows)):
            entry = _entry(rows, index, of="the schedule's providers")
            entry_of = f"the schedule's providers[{index}]"
            kept.append({
                "provider": _field(entry, "provider", of=entry_of),
                "enabled": _field(entry, "enabled", of=entry_of),
            })
        return kept

    body = {
        "title": _overlay(_field(current, "title", of="the schedule"), title),
        # The row reports `assetId`/`groupId`; the command takes `targetAssetId`/`targetGroupId`,
        # because the pair is a choice of target and not two fields of a row (`docs/api/v1.md`).
        "targetAssetId": nullable("assetId", target_asset_id, "target_asset_id"),
        "targetGroupId": nullable("groupId", target_group_id, "target_group_id"),
        "description": _overlay_or_clear(
            _field(current, "description", of="the schedule"), description, "description",
            to_clear, when_cleared="",
        ),
        "timeInterval": nullable("timeInterval", time_interval, "time_interval"),
        "timeUnit": nullable("timeUnit", time_unit, "time_unit"),
        "timeBasis": _overlay(_field(current, "timeBasis", of="the schedule"), time_basis),
        "anchorOn": nullable("anchorOn", anchor_on, "anchor_on"),
        "leadDays": _overlay(_field(current, "leadDays", of="the schedule"), lead_days),
        "meterDefinitionId": nullable("meterDefinitionId", meter_definition_id, "meter_definition_id"),
        "meterInterval": nullable("meterInterval", meter_interval, "meter_interval"),
        "anchorMeter": nullable("anchorMeter", anchor_meter, "anchor_meter"),
        "meterLead": nullable("meterLead", meter_lead, "meter_lead"),
        "seasonBehavior": _overlay(
            _field(current, "seasonBehavior", of="the schedule"), season_behavior,
        ),
        "seasonReentry": nullable("seasonReentry", season_reentry, "season_reentry"),
        "seasonReentryOffsetDays": nullable(
            "seasonReentryOffsetDays", season_reentry_offset_days, "season_reentry_offset_days",
        ),
        "completionMode": _overlay(
            _field(current, "completionMode", of="the schedule"), completion_mode,
        ),
        "profileId": nullable("profileId", profile_id, "profile_id"),
        "remindersEnabled": _overlay(
            _field(current, "remindersEnabled", of="the schedule"), reminders_enabled,
        ),
        "providers": [] if "providers" in to_clear else (
            kept_providers() if providers is None else providers
        ),
    }
    return _call("PATCH", path, json_body=body, content_type="application/json")


@mcp.tool()
def pause_schedule(schedule_id: str, paused: bool = True) -> dict[str, Any]:
    """Pause or resume a schedule. A paused one never notifies and never counts as due; it keeps its
    history and its rule, and resuming picks the rule back up where it stands."""
    return _call(
        "POST",
        f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}/pause",
        json_body={"paused": paused},
        content_type="application/json",
    )


@mcp.tool()
def archive_schedule(schedule_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a schedule. Archive is not delete: it disappears from every due list and
    its completions and closures are kept. There is deliberately no tool that deletes one."""
    return _call(
        "POST",
        f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


_POSTPONE_CLEARABLE_FIELDS: frozenset[str] = frozenset({"postponed_due_on"})


@mcp.tool()
def postpone_schedule(
    schedule_id: str,
    postponed_due_on: str | None = None,
    clear_fields: list[str] | None = None,
) -> dict[str, Any]:
    """Move **this one occurrence** to a later date. Changes no rule and creates no event.

    The next occurrence still comes from the rule, so a postponement is a one-off agreement and
    never a reschedule. Completing or closing the round clears it, and so does a rule edit.

    **Clearing a postponement is `clear_fields=["postponed_due_on"]`**, by name, and this is the
    sharpest case of the convention on this server: an omitted argument and an explicit `null` both
    mean "leave alone", so `postponed_due_on=None` **cannot** put the occurrence back where the rule
    says — even though the wire route itself does accept a literal `null` for exactly that. Passing
    a date and naming the field to clear in one call is refused rather than guessed at.

    A **meter-only** schedule has no occurrence date to move and the app refuses a postponement on
    one; clearing is always allowed, so a row that arrived with one set can still be cleaned up.
    """
    to_clear = _validate_clear_fields(
        clear_fields, _POSTPONE_CLEARABLE_FIELDS, {"postponed_due_on": postponed_due_on},
    )
    path = f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}"
    if "postponed_due_on" in to_clear:
        value: str | None = None
    elif postponed_due_on is not None:
        value = postponed_due_on
    else:
        current = _field(_call("GET", path), "schedule", of="the schedule lookup")
        value = _field(current, "postponedDueOn", of="the schedule")
    return _call(
        "POST", f"{path}/postpone",
        json_body={"postponedDueOn": value},
        content_type="application/json",
    )


@mcp.tool()
def complete_schedule(
    schedule_id: str,
    occurred_on: str,
    tz_id: str,
    asset_id: str | None,
    occurred_time: str | None,
    notes: str,
    values: dict[str, str],
    consumables: list[dict[str, str]],
) -> dict[str, Any]:
    """Record that the current occurrence was done. **The only way to log a completion.**

    Like `update_event`, this tool has **no overlay and no defaults**: a completion is a new fact,
    and there is nothing about it to inherit from a row. Every argument is required — pass
    `notes=""`, `values={}`, `consumables=[]` and `occurred_time=None` for none of those, and
    `asset_id=None` when the schedule targets a single asset.

    **A group-targeted schedule is completed one member at a time**, and `asset_id` says which
    member did the work: completing a five-member round is five calls, and the round only advances
    once every member it obliges is done. `asset_id` is **required** there and is checked against
    the members this round actually obliges. For an asset-targeted schedule it must be `None` or
    that schedule's own asset.

    `occurred_on` is ISO `YYYY-MM-DD` and may be any past date — the date you send is the date
    stored, so a job done last Tuesday is logged as last Tuesday. `occurred_time` is `HH:MM`.
    `values` is keyed by definition id, the text a person would type; `consumables` is
    `{"name", "quantity", "unit"}`. You never send the occurrence key: the app stamps it from the
    schedule's own computed due date, which is what makes a repeat harmless rather than a duplicate.

    A `FORM` schedule completed with no `values` and no `consumables` is accepted and recorded with
    `detailsPending: true` — the quick path exists, and the row still says the form is owed.

    Two refusals worth knowing: a member already recorded for this round answers
    `SCHEDULE_OCCURRENCE_TAKEN`, and a round that was **closed** answers `OCCURRENCE_CLOSED` — work
    done after a round is closed is logged with `log_event`, with no schedule link, and that still
    succeeds.
    """
    return _call(
        "POST",
        f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}/complete",
        json_body={
            "occurredOn": occurred_on,
            "occurredTime": occurred_time,
            "tzId": tz_id,
            "notes": notes,
            "values": values,
            "consumables": consumables,
            "assetId": asset_id,
        },
        content_type="application/json",
    )


@mcp.tool()
def close_round(schedule_id: str, closed_on: str | None) -> dict[str, Any]:
    """End a round that nobody finished, **without claiming the outstanding members were serviced**.

    That sentence is the whole point of this tool. It writes one immutable `occurrence_closure` row
    and nothing else: **no event on any asset**, so it never says work was done, and no column on
    the schedule. The recurrence advances from the closing date exactly as it would from a
    completion, and the member completions that *were* recorded stay as truthful history.

    **The row can never be amended or deleted.** There is no tool and no route that edits or removes
    a closure — it is exported history, and a closure that could be rewritten could rewrite a
    schedule's past. Closing the same round twice is refused and the first closure stands.

    `closed_on` is required, and `None` means today. Any date from the round's **open date** through
    today is accepted; a future one, or one before the round opened, is refused — the row is
    permanent and an unbounded date would move the schedule's future for good.

    Offered on **group-targeted** schedules only, and only on a round that obliges somebody: a round
    with no required members is not a round, and closing one is refused rather than recorded.
    """
    return _call(
        "POST",
        f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}/close-round",
        json_body={"closedOn": closed_on},
        content_type="application/json",
    )


@mcp.tool()
def list_closures(schedule_id: str) -> dict[str, Any]:
    """One schedule's closed rounds, oldest first. **Read-only, and sparse by construction**: a round
    that was finished normally leaves no row here, so a schedule always done on time carries none at
    all for its whole life."""
    return _call("GET", f"/v1/schedules/{_path_id(schedule_id, field='schedule_id')}/closures")


@mcp.tool()
def list_due() -> dict[str, Any]:
    """Due work, in the order the phone's own dashboard shows it.

    Each item carries the schedule's status, its due date and due meter, its last completion, and —
    for a group-targeted schedule — `membersRequired` and `membersComplete`. A group is **one** item
    counted **once**, however many members are outstanding. `rank` is its 0-based position in that
    order, so the app's ordering can be reproduced without re-deriving it. Archived schedules never
    appear.
    """
    return _call("GET", "/v1/due")


_GUARD_PROBE_KEY = "__servicetag_guard_probe__"
"""A key no real tool could ever have — `func_metadata.py` raises `InvalidSignature` for any
parameter starting with `_` (see `test_argument_guard.py`'s G6 note) — used only to *validate* that
`extra="forbid"` actually took effect (G3), never sent to a tool or logged anywhere a value would be."""


def _forbid_unknown_arguments(tools: list[Any], *, expected_count: int | None = None) -> None:
    """Applied once, right below, after every `@mcp.tool()` registration above — to all of them at
    once, not as a per-tool check. Two things, per tool:

    1. The per-tool pydantic argument model (`Tool.fn_metadata.arg_model`) is switched from `mcp`
       2.2.0's default — silently ignoring an extra key, since `ArgModelBase.model_config =
       ConfigDict(arbitrary_types_allowed=True)` sets no `extra=`
       (`mcp/server/mcpserver/utilities/func_metadata.py:96`-ish) — to `extra="forbid"`, then
       rebuilt so the new config takes effect on the model's next validation. (G3: the rebuild's
       *effect on validation* is then verified by actually validating a probe payload — see below.
       A first attempt at this checked `model_json_schema()` instead, which is unsound: pydantic's
       schema generator reads `model_config` live regardless of whether `model_rebuild` ever ran, so
       that check reports `additionalProperties: false` even in the exact silent-degradation mode
       it was meant to catch — confirmed by mutating `model_config` on a plain model with no rebuild
       at all and reading its schema. Kept below anyway, since it is still what makes the
       *published* schema say `additionalProperties: false` — see point 2 — but it proves nothing
       about condition 6 on its own now.)
    2. The already-computed, published JSON schema (`Tool.parameters`, a plain dict frozen at
       registration time and never auto-refreshed by a later `model_config` change) is given
       `additionalProperties: False` directly, so a client reading `tools/list` sees the same
       contract the server enforces — not just the ones who reach `_StrictMCPServer.call_tool`.

    This is the *backstop*: `_StrictMCPServer.call_tool` (above `mcp`'s construction) intercepts
    first on every real request path, with a message that names the tool's real argument names and
    never the value supplied. This function is what still refuses the key if that interception were
    ever bypassed — e.g. a future direct call into `mcp._tool_manager.call_tool(...)` — and it is
    also the only thing that makes the *published schema* say `additionalProperties: false`,
    which the interception above does nothing to change.

    `expected_count`, when given, is checked before anything else (G5): a tool registered — or
    dropped — around this function's call site changes `len(tools)` without changing any of the
    internals the loop below inspects, so nothing else here would notice either way. The real call
    site passes `len(TOOL_NAMES)`; a differently-shaped-tool test omits it, since it is exercising
    the per-tool loop on a deliberately small fake list.

    Leans on `mcp` 2.2.0 internals that are not part of its public contract: `Tool.fn_metadata`,
    `FuncMetadata.arg_model` as a pydantic model *class* with a mutable `model_config` dict, a
    `model_rebuild` classmethod, a `model_json_schema` classmethod and a `model_validate` classmethod
    whose failure is a pydantic `ValidationError` with an `errors()` list, and `Tool.parameters` as a
    plain, later-mutable dict. `uv.lock` pins the resolved version, so this is stable until the next
    deliberate dependency bump. If any of that shape is gone — or present but inert, the
    silent-degradation mode G3 closes, where `model_rebuild` runs without error but validation
    against the rebuilt model keeps accepting an extra key anyway — this raises here, loudly, at
    *import* time, since the call below runs at module load, rather than silently registering an
    unguarded tool: a guard that quietly stops guarding after a dependency bump is worse than none
    (owner ruling, 2026-09-21).
    """
    if not tools:
        raise RuntimeError("_forbid_unknown_arguments: no tools were registered — call it last")
    if expected_count is not None and len(tools) != expected_count:
        raise RuntimeError(
            f"_forbid_unknown_arguments: {len(tools)} tools were registered but {expected_count} "
            "were expected — a tool must have been added or removed around the guard's call site"
        )
    for tool in tools:
        try:
            arg_model = tool.fn_metadata.arg_model
            arg_model.model_config["extra"] = "forbid"
            arg_model.model_rebuild(force=True)
            tool.parameters["additionalProperties"] = False
            # This is necessary but not sufficient for condition 6 (see the docstring above): the
            # schema generator reads `model_config` live, so it can say `false` even when the
            # *validator* was never rebuilt. It still matters, because it is what a client reading
            # `tools/list` actually sees.
            if arg_model.model_json_schema().get("additionalProperties") is not False:
                raise TypeError("extra=forbid is not reflected in the published schema")
            # G3, the effect check: validate an inert probe payload and require pydantic to refuse
            # the extra key *specifically* — a missing-required error alone (which a degraded model
            # would also raise, if the tool has any required field) does not count, and neither does
            # no error at all (which a degraded, all-optional tool would produce).
            try:
                arg_model.model_validate({_GUARD_PROBE_KEY: None})
            except ValidationError as validation_error:
                refused_as_extra = any(
                    error.get("type") == "extra_forbidden" and error.get("loc") == (_GUARD_PROBE_KEY,)
                    for error in validation_error.errors()
                )
                if not refused_as_extra:
                    raise TypeError("extra=forbid rejected the probe but not as an extra field") from None
            else:
                raise TypeError("extra=forbid did not take effect on validation")
        except (AttributeError, TypeError, KeyError) as exc:
            raise RuntimeError(
                f"the guard's assumptions about mcp's internals do not hold for tool "
                f"{getattr(tool, 'name', '?')!r} — see _forbid_unknown_arguments's docstring for "
                "exactly which internals it leans on, and check the resolved mcp version in uv.lock"
            ) from exc


_forbid_unknown_arguments(mcp._tool_manager.list_tools(), expected_count=len(TOOL_NAMES))


def main() -> None:
    """The console script: serve over stdio, which is what an editor connects to."""
    mcp.run()
