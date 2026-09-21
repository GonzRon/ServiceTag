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

        rows = _field(
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
        kept_fields = [
            {
                "definitionId": _field(f, "definitionId", of="a field entry"),
                "required": _field(f, "required", of="a field entry"),
            }
            for f in _field(current, "fields", of="the quick action")
        ]
        kept_consumables = [
            {
                "id": _field(c, "id", of="a consumable entry"),
                "name": _field(c, "name", of="a consumable entry"),
                "defaultQuantity": _field(c, "defaultQuantity", of="a consumable entry"),
                "unit": _field(c, "unit", of="a consumable entry"),
            }
            for c in _field(current, "consumables", of="the quick action")
        ]
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
