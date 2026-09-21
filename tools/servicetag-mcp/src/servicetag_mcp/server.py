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

from .client import ApiError, Device, MAX_IMPORT_BYTES, NotPaired

mcp = MCPServer("servicetag")

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

CLEAR_FIELD: str = "__CLEAR__"
"""Pass this literal string to a *nullable* field on an edit tool (`update_asset`, `save_definition`)
to set that field to null. Omitting the argument (leaving it the Python default `None`) means
"leave it as it already is" instead — that is the whole overlay convention these edit tools use,
because the app's own write endpoints are a full replace and an omitted field must not become a
silently lost one. A *text* field (one whose DTO default is `""`, never `null`) does not need the
sentinel: pass an explicit `""` to clear it, since `""` and "omitted" are different arguments there."""

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


def _text_overlay(current: str, given: str | None) -> str:
    """The clearing convention for a *text* field on an edit: omitted (`None`) keeps the row's
    current value; any given string, including `""`, replaces it — `""` and omitted are different
    arguments here on purpose."""
    return current if given is None else given


def _nullable_overlay(current: Any, given: Any) -> Any:
    """The clearing convention for a *nullable* field on an edit: omitted (`None`) keeps the row's
    current value; the literal [CLEAR_FIELD] sets it to null; anything else replaces it."""
    if given is None:
        return current
    if given == CLEAR_FIELD:
        return None
    return given


def _find_by_id(rows: list[dict[str, Any]], row_id: str, *, field: str) -> dict[str, Any]:
    """The row an edit tool needs to overlay onto, from a list read the API already offers."""
    for row in rows:
        if row["id"] == row_id:
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
    or no value at all) for a brand-new row, not something being cleared."""
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
    purchase_price_minor: int | str | None = None,
    currency: str | None = None,
    vendor: str | None = None,
    location: str | None = None,
    warranty_expires_on: str | None = None,
    warranty_notes: str | None = None,
    parent_asset_id: str | None = None,
    season_start_mmdd: str | None = None,
    season_end_mmdd: str | None = None,
) -> dict[str, Any]:
    """Edit an asset. **The app's `PATCH` replaces the whole row** — every field on the wire is
    what the asset ends up with — so this tool reads the asset first and sends back its current
    fields, overlaid with only the arguments you actually passed. An omitted argument therefore
    keeps whatever the asset already has.

    Clearing is explicit and looks different depending on the field:
    - a **text** field (`name`, `category`, `description`, `notes`, `manufacturer`, `model`,
      `serial_number`, `vendor`, `location`, `warranty_notes`) is cleared by passing `""`; omitted
      and `""` are different arguments here.
    - a **nullable** field (`purchase_on`, `in_service_on`, `purchase_price_minor`, `currency`,
      `warranty_expires_on`, `parent_asset_id`, `season_start_mmdd`, `season_end_mmdd`) is cleared
      by passing the literal string in [CLEAR_FIELD]; omitted keeps the current value. Clearing
      `parent_asset_id` promotes a component to a top-level asset.

    `template_key` is not a parameter here because the app ignores it on an edit — it only seeds a
    *new* asset (`create_asset`, `create_component`).
    """
    path = f"/v1/assets/{_path_id(asset_id, field='asset_id')}"
    current = _call("GET", path)["asset"]
    body = {
        "name": _text_overlay(current["name"], name),
        "category": _text_overlay(current["category"], category),
        "description": _text_overlay(current["description"], description),
        "notes": _text_overlay(current["notes"], notes),
        "manufacturer": _text_overlay(current["manufacturer"], manufacturer),
        "model": _text_overlay(current["model"], model),
        "serialNumber": _text_overlay(current["serialNumber"], serial_number),
        "purchaseOn": _nullable_overlay(current["purchaseOn"], purchase_on),
        "inServiceOn": _nullable_overlay(current["inServiceOn"], in_service_on),
        "purchasePriceMinor": _nullable_overlay(current["purchasePriceMinor"], purchase_price_minor),
        "currency": _nullable_overlay(current["currency"], currency),
        "vendor": _text_overlay(current["vendor"], vendor),
        "location": _text_overlay(current["location"], location),
        "warrantyExpiresOn": _nullable_overlay(current["warrantyExpiresOn"], warranty_expires_on),
        "warrantyNotes": _text_overlay(current["warrantyNotes"], warranty_notes),
        "parentAssetId": _nullable_overlay(current["parentAssetId"], parent_asset_id),
        "seasonStartMmdd": _nullable_overlay(current["seasonStartMmdd"], season_start_mmdd),
        "seasonEndMmdd": _nullable_overlay(current["seasonEndMmdd"], season_end_mmdd),
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
    to pass here beyond the one naming which asset this is a component of."""
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
def retire_asset(asset_id: str, retired_on: str | None) -> dict[str, Any]:
    """Pass a date (`YYYY-MM-DD`) to retire the asset; pass `retired_on=None` explicitly to
    un-retire it. There is no default, on purpose: a default that un-retires on a bare
    `retire_asset(asset_id=...)` call would invert what the tool's own name says it does."""
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
    range_low: float | str | None = None,
    range_high: float | str | None = None,
    is_meter: bool | None = None,
    formula: str | None = None,
    source_a_id: str | None = None,
    source_b_id: str | None = None,
) -> dict[str, Any]:
    """Create a reading, or edit one by passing `definition_id`.

    **Create** (no `definition_id`): `label` is required; every other field starts from the API's
    own default (`key=""` generates one from the label, `kind="ENTERED"`, `value_type="NUMBER"`,
    `decimals=0`, no range, not a meter). `key` blank is always "leave it alone" to the app itself,
    on a create as well as an edit, so passing `key=""` never sets it blank.

    **Edit** (`definition_id` given): this reads the asset's current readings (`list_definitions`)
    and finds `definition_id` among them first, then sends the whole row back with only the fields
    you passed changed — an omitted argument keeps what the reading already has. `range_low`,
    `range_high`, `formula`, `source_a_id` and `source_b_id` are nullable: pass the literal string
    in [CLEAR_FIELD] to clear one. Once measurements exist, the app refuses a change to
    `value_type`, `kind` or `key` either way.
    """
    if definition_id is None:
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
        rows = _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/definitions")
        current = _find_by_id(rows["definitions"], definition_id, field="definition_id")
        body = {
            "id": definition_id,
            "assetId": asset_id,
            "key": _text_overlay(current["key"], key),
            "label": _text_overlay(current["label"], label),
            "unit": _text_overlay(current["unit"], unit),
            "kind": _text_overlay(current["kind"], kind),
            "valueType": _text_overlay(current["valueType"], value_type),
            "decimals": current["decimals"] if decimals is None else decimals,
            "rangeLow": _nullable_overlay(current["rangeLow"], range_low),
            "rangeHigh": _nullable_overlay(current["rangeHigh"], range_high),
            "isMeter": current["isMeter"] if is_meter is None else is_meter,
            "formula": _nullable_overlay(current["formula"], formula),
            "sourceAId": _nullable_overlay(current["sourceAId"], source_a_id),
            "sourceBId": _nullable_overlay(current["sourceBId"], source_b_id),
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

    **Edit** (`profile_id` given): this reads the asset's current quick actions (`list_profiles`)
    and finds `profile_id` among them first, then sends the whole row back with only the fields you
    passed changed — an omitted argument, `fields`/`consumables` included, keeps what the quick
    action already has. Pass `fields`/`consumables` only when you mean to replace the whole list.
    Each existing consumable's `id` travels with it when kept, which is what keeps its identity
    across the edit; a `consumables` list you pass yourself may include `id` the same way.
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
        rows = _call("GET", f"/v1/assets/{_path_id(asset_id, field='asset_id')}/profiles")
        current = _find_by_id(rows["profiles"], profile_id, field="profile_id")
        kept_fields = [
            {"definitionId": f["definitionId"], "required": f["required"]}
            for f in current["fields"]
        ]
        kept_consumables = [
            {
                "id": c["id"],
                "name": c["name"],
                "defaultQuantity": c["defaultQuantity"],
                "unit": c["unit"],
            }
            for c in current["consumables"]
        ]
        body = {
            "id": profile_id,
            "assetId": asset_id,
            "name": _text_overlay(current["name"], name),
            "eventKind": _text_overlay(current["eventKind"], event_kind),
            "defaultTitle": _text_overlay(current["defaultTitle"], default_title),
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


def main() -> None:
    """The console script: serve over stdio, which is what an editor connects to."""
    mcp.run()
