"""A plain snapshot of the phone's assets, profiles, groups and schedules, read through the MCP
client `servicetag_mcp` exposes — `mcp.Client(servicetag_mcp.server.mcp)` in-process, the same
client the 1.2.0 release proofs use.

`call_tool` is the one seam every wire call goes through, here and in `apply.py`: it unwraps a
`CallToolResult` the way the release proofs' own `payload`/`is_err` helpers do (a client library
detail, not a phone concern), and turns an `is_error` result into `PhoneError` naming the tool —
never swallowed. On a write `apply.py` makes on a manifest entry's behalf, it passes `entry` (the
entry's kind and key, e.g. `"schedule 's31'"`) so the raised message leads with *which manifest
entry* the phone refused, not just which tool — the brief's "every MCP error surfaces naming the
entry key".
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol


class ToolClient(Protocol):
    """What `call_tool` needs from its `client` argument — exactly `mcp.Client`'s own shape, so a
    real in-process client and a test fake are interchangeable."""

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> Any: ...


class PhoneError(RuntimeError):
    """An MCP tool call answered `is_error` — the phone's own message, prefixed with the tool
    name and, when the call was made on a manifest entry's behalf, that entry's kind and key
    (`call_tool`'s `entry` argument), so a caller never has to guess which call, or which entry,
    failed."""


def _is_error(result: Any) -> bool:
    return bool(getattr(result, "is_error", getattr(result, "isError", False)))


def _text(result: Any) -> str:
    return "".join(getattr(c, "text", "") for c in getattr(result, "content", []) or [])


def _payload(result: Any) -> Any:
    sc = getattr(result, "structured_content", None) or getattr(result, "structuredContent", None)
    if sc is not None:
        return sc.get("result", sc) if isinstance(sc, dict) and set(sc) == {"result"} else sc
    txt = _text(result)
    try:
        return json.loads(txt)
    except (json.JSONDecodeError, TypeError):
        return txt


async def call_tool(
    client: ToolClient, name: str, arguments: dict[str, Any], *, entry: str | None = None,
) -> Any:
    """Call one MCP tool and return its payload, or raise `PhoneError` naming `name` — and, when
    `entry` is given (a manifest entry's kind and key, e.g. `"group 'g1'"`), leading with it, so a
    write made on that entry's behalf never surfaces as an anonymous tool failure."""
    result = await client.call_tool(name, arguments)
    if _is_error(result):
        message = f"{name}: {_text(result)}"
        raise PhoneError(f"{entry}: {message}" if entry else message)
    return _payload(result)


# ---- the snapshot ------------------------------------------------------------------------------

@dataclass(frozen=True)
class Asset:
    id: str
    name: str
    archived: bool
    retired: bool
    parent_id: str | None


@dataclass(frozen=True)
class Profile:
    id: str
    asset_id: str
    name: str
    archived: bool


@dataclass(frozen=True)
class Group:
    id: str
    name: str
    archived: bool
    open_member_asset_ids: frozenset[str]


@dataclass(frozen=True)
class Schedule:
    id: str
    title: str
    target_asset_id: str | None
    target_group_id: str | None
    time_interval: int | None
    time_unit: str | None
    time_basis: str | None
    anchor_on: str | None
    lead_days: int | None
    completion_mode: str | None
    profile_id: str | None
    service_policy: str | None
    """The row's own `servicePolicy` (ServiceTag 1.4). 1.3's `seasonBehavior` is only a derived
    projection of it on a 1.4 row and is deliberately not read: `FOLLOW_ASSET` names three policies
    and `null` a fourth, so it cannot say what the phone holds."""
    policy_offset_days: int | None
    archived: bool


@dataclass(frozen=True)
class Inventory:
    """A plain snapshot: assets (id, name, archived/retired, parent), profiles by asset id, groups
    (id, name, archived, open member asset ids), schedules (id, title, target, rule fields, service
    policy and offset, archived). `plan.plan` reads this and nothing else of the phone."""

    assets: tuple[Asset, ...] = ()
    profiles: tuple[Profile, ...] = ()
    groups: tuple[Group, ...] = ()
    schedules: tuple[Schedule, ...] = ()


def _asset_from(row: dict[str, Any], parent_id: str | None) -> Asset:
    return Asset(
        id=row["id"],
        name=row["name"],
        archived=row.get("status") == "ARCHIVED",
        retired=row.get("retiredOn") is not None,
        parent_id=parent_id,
    )


def _profile_from(row: dict[str, Any], asset_id: str) -> Profile:
    return Profile(
        id=row["id"],
        asset_id=row.get("assetId") or asset_id,
        name=row["name"],
        archived=row.get("archivedAt") is not None,
    )


def _group_from(row: dict[str, Any]) -> Group:
    open_ids = frozenset(
        m["assetId"] for m in row.get("members", []) if m.get("removedAt") is None
    )
    return Group(
        id=row["id"],
        name=row["name"],
        archived=row.get("archivedAt") is not None,
        open_member_asset_ids=open_ids,
    )


def _schedule_from(row: dict[str, Any]) -> Schedule:
    return Schedule(
        id=row["id"],
        title=row["title"],
        target_asset_id=row.get("assetId"),
        target_group_id=row.get("groupId"),
        time_interval=row.get("timeInterval"),
        time_unit=row.get("timeUnit"),
        time_basis=row.get("timeBasis"),
        anchor_on=row.get("anchorOn"),
        lead_days=row.get("leadDays"),
        completion_mode=row.get("completionMode"),
        profile_id=row.get("profileId"),
        service_policy=row.get("servicePolicy"),
        policy_offset_days=row.get("policyOffsetDays"),
        archived=row.get("status") == "ARCHIVED",
    )


async def snapshot(client: ToolClient) -> Inventory:
    """Fill an `Inventory` from the phone through `client`'s MCP tools: `list_assets`,
    `list_profiles` (one call per asset), `list_groups`, `list_schedules`. Never `pair` — a caller
    pairs once, before taking any snapshot."""
    assets_payload = await call_tool(client, "list_assets", {})
    assets: list[Asset] = [_asset_from(row, None) for row in assets_payload.get("topLevel", [])]
    for parent_id, rows in (assets_payload.get("components") or {}).items():
        assets.extend(_asset_from(row, parent_id) for row in rows)

    profiles: list[Profile] = []
    for asset in assets:
        profiles_payload = await call_tool(client, "list_profiles", {"asset_id": asset.id})
        profiles.extend(_profile_from(row, asset.id) for row in profiles_payload.get("profiles", []))

    groups_payload = await call_tool(client, "list_groups", {})
    groups = tuple(_group_from(row) for row in groups_payload.get("groups", []))

    schedules_payload = await call_tool(client, "list_schedules", {})
    schedules = tuple(_schedule_from(row) for row in schedules_payload.get("schedules", []))

    return Inventory(tuple(assets), tuple(profiles), groups, schedules)
