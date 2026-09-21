"""The MCP surface: one tool per `/v1` endpoint, and `pair` for the code on the phone's screen.

Nothing here reaches past the API. Every tool is a method, a path and a body — the contract is
`docs/api/v1.md`, and the app's own router is what enforces it.

`@mcp.tool()` is called with parentheses on purpose: the SDK raises a `TypeError` at *import* time
for a bare `@mcp.tool`, which is a server that never starts.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from mcp.server import MCPServer

from .client import Device

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


def _body(**fields: Any) -> dict[str, Any]:
    """Only what the caller actually gave. The app's decoder is strict, and its defaults are right."""
    return {k: v for k, v in fields.items() if v is not None}


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
    return device.request("GET", "/v1/status")


@mcp.tool()
def list_assets() -> dict[str, Any]:
    """Every asset: the top-level systems, and each system's components under its own id."""
    return device.request("GET", "/v1/assets")


@mcp.tool()
def get_asset(asset_id: str) -> dict[str, Any]:
    """One asset, in the same shape a backup archive carries it."""
    return device.request("GET", f"/v1/assets/{asset_id}")


@mcp.tool()
def create_asset(
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    location: str | None = None,
    parent_asset_id: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset. `template_key` seeds its readings and quick actions, in the same write."""
    return device.request(
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
            location=location,
            parentAssetId=parent_asset_id,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


@mcp.tool()
def update_asset(
    asset_id: str,
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    manufacturer: str | None = None,
    model: str | None = None,
    serial_number: str | None = None,
    location: str | None = None,
    parent_asset_id: str | None = None,
) -> dict[str, Any]:
    """Edit an asset's fields. Identity, status and retirement are not editable here, by design."""
    return device.request(
        "PATCH",
        f"/v1/assets/{asset_id}",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            manufacturer=manufacturer,
            model=model,
            serialNumber=serial_number,
            location=location,
            parentAssetId=parent_asset_id,
        ),
        content_type="application/json",
    )


@mcp.tool()
def create_component(
    parent_asset_id: str,
    name: str,
    category: str | None = None,
    description: str | None = None,
    notes: str | None = None,
    template_key: str | None = None,
) -> dict[str, Any]:
    """Create an asset as a component of another. The path decides the parent, not the body."""
    return device.request(
        "POST",
        f"/v1/assets/{parent_asset_id}/components",
        json_body=_body(
            name=name,
            category=category,
            description=description,
            notes=notes,
            templateKey=template_key,
        ),
        content_type="application/json",
    )


@mcp.tool()
def retire_asset(asset_id: str, retired_on: str | None = None) -> dict[str, Any]:
    """Retire an asset on a date (`YYYY-MM-DD`), or un-retire it with `retired_on=None`."""
    return device.request(
        "POST",
        f"/v1/assets/{asset_id}/retire",
        json_body={"retiredOn": retired_on},
        content_type="application/json",
    )


@mcp.tool()
def archive_asset(asset_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive an asset. Archive is not delete: tags bound to it still resolve."""
    return device.request(
        "POST",
        f"/v1/assets/{asset_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_definitions(asset_id: str) -> dict[str, Any]:
    """The readings defined on one asset, archived ones included."""
    return device.request("GET", f"/v1/assets/{asset_id}/definitions")


@mcp.tool()
def save_definition(
    asset_id: str,
    label: str,
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
) -> dict[str, Any]:
    """Create a reading, or edit one by passing `definition_id`.

    `kind` is `ENTERED` or `DERIVED`; a derived reading needs `formula` (`PERCENT_DROP`) and both
    sources. `value_type` is `NUMBER`, `TEXT` or `BOOLEAN`. Once measurements exist, the app
    refuses a change to `value_type`, `kind` or `key`.
    """
    return device.request(
        "POST",
        "/v1/definitions",
        json_body=_body(
            id=definition_id,
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
        ),
        content_type="application/json",
    )


@mcp.tool()
def archive_definition(definition_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a reading. Archived readings leave the forms and keep their history."""
    return device.request(
        "POST",
        f"/v1/definitions/{definition_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_profiles(asset_id: str) -> dict[str, Any]:
    """The quick actions defined on one asset, archived ones included."""
    return device.request("GET", f"/v1/assets/{asset_id}/profiles")


@mcp.tool()
def save_profile(
    asset_id: str,
    name: str,
    event_kind: str,
    profile_id: str | None = None,
    default_title: str | None = None,
    fields: list[dict[str, Any]] | None = None,
    consumables: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Create a quick action, or edit one by passing `profile_id`.

    `fields` is a list of `{"definitionId": ..., "required": bool}`; each must name an ENTERED
    reading of the same asset. `consumables` is `{"name", "defaultQuantity", "unit"}`.
    """
    return device.request(
        "POST",
        "/v1/profiles",
        json_body=_body(
            id=profile_id,
            assetId=asset_id,
            name=name,
            eventKind=event_kind,
            defaultTitle=default_title,
            fields=fields,
            consumables=consumables,
        ),
        content_type="application/json",
    )


@mcp.tool()
def archive_profile(profile_id: str, archived: bool = True) -> dict[str, Any]:
    """Archive or unarchive a quick action. Events logged through it keep pointing at it."""
    return device.request(
        "POST",
        f"/v1/profiles/{profile_id}/archive",
        json_body={"archived": archived},
        content_type="application/json",
    )


@mcp.tool()
def list_events(asset_id: str) -> dict[str, Any]:
    """One asset's service record, newest first."""
    return device.request("GET", f"/v1/assets/{asset_id}/events")


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
    return device.request(
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
    title: str | None = None,
    profile_id: str | None = None,
    occurred_time: str | None = None,
    notes: str | None = None,
    values: dict[str, str] | None = None,
    consumables: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Edit a logged event. `asset_id` must be the asset it already belongs to — an edit never re-parents."""
    return device.request(
        "PATCH",
        f"/v1/events/{event_id}",
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
def delete_event(event_id: str) -> dict[str, Any]:
    """Delete a logged event and its readings. This one is destructive and has no undo."""
    return device.request("DELETE", f"/v1/events/{event_id}")


@mcp.tool()
def list_tag_bindings() -> dict[str, Any]:
    """Every NFC tag binding this phone holds. Read-only: the API cannot write or bind a tag."""
    return device.request("GET", "/v1/tags")


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
    """
    body = Path(archive_path).read_bytes()
    plan = device.request(
        "POST", "/v1/import-merge/plan", content=body, content_type="application/zip"
    )
    if plan_only or not plan.get("applicable", False):
        return plan
    # A 409 from the apply is the report, not an error: the phone re-planned inside its own
    # transaction, found a conflict (or found the destination had moved), and wrote nothing. Read
    # `applicable` — it is false — and `conflicts`.
    return device.request(
        "POST",
        "/v1/import-merge/apply",
        content=body,
        content_type="application/zip",
        report_statuses=(409,),
    )


def main() -> None:
    """The console script: serve over stdio, which is what an editor connects to."""
    mcp.run()
