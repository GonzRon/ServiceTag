"""A `FakeClient`: an in-memory phone answering exactly the tools `servicetag_schedules` calls
(`pair`, `list_assets`, `list_profiles`, `list_groups`, `list_schedules`, `create_group`,
`create_schedule`), shaped like the real ones' JSON (`docs/api/v1.md`). It implements the same
`call_tool(name, arguments)` coroutine `mcp.Client` offers, so `phone.call_tool` and everything
built on it treat a `FakeClient` exactly like a real in-process client. `calls` records every
`(name, arguments)` in call order, which is what the apply tests check (groups before schedules,
the right ids on the wire).
"""

from __future__ import annotations

import itertools
from dataclasses import dataclass, field
from typing import Any

import pytest


@dataclass
class _TextContent:
    text: str


@dataclass
class _Result:
    structured_content: Any = None
    is_error: bool = False
    content: list[_TextContent] = field(default_factory=list)


def _ok(payload: Any) -> _Result:
    return _Result(structured_content=payload, is_error=False)


def _err(message: str) -> _Result:
    return _Result(structured_content=None, is_error=True, content=[_TextContent(message)])


class FakeClient:
    def __init__(self) -> None:
        self.top_level: list[dict[str, Any]] = []
        self.components: dict[str, list[dict[str, Any]]] = {}
        self.profiles: dict[str, list[dict[str, Any]]] = {}
        self.groups: list[dict[str, Any]] = []
        self.schedules: list[dict[str, Any]] = []
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self._ids = itertools.count(1)

    def _new_id(self, prefix: str) -> str:
        return f"{prefix}-{next(self._ids)}"

    # ---- the mcp.Client shape ---------------------------------------------------------------

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> _Result:
        self.calls.append((name, dict(arguments)))
        handler = getattr(self, f"_tool_{name}", None)
        if handler is None:
            return _err(f"FakeClient has no handler for {name!r}")
        try:
            return handler(**arguments)
        except TypeError as e:
            return _err(str(e))

    def _tool_pair(self, code: str) -> _Result:
        return _ok("paired")

    def _tool_list_assets(self) -> _Result:
        return _ok({"topLevel": list(self.top_level), "components": dict(self.components)})

    def _tool_list_profiles(self, asset_id: str) -> _Result:
        return _ok({"profiles": list(self.profiles.get(asset_id, []))})

    def _tool_list_groups(self) -> _Result:
        return _ok({"groups": list(self.groups)})

    def _tool_list_schedules(
        self, asset_id: str | None = None, group_id: str | None = None,
    ) -> _Result:
        return _ok({"schedules": list(self.schedules)})

    def _tool_create_group(
        self,
        name: str,
        description: str | None = None,
        members: list[dict[str, Any]] | None = None,
    ) -> _Result:
        rows = [
            {
                "id": self._new_id("member"),
                "assetId": m["assetId"],
                "sortOrder": m.get("sortOrder", 0),
                "addedAt": 1,
                "removedAt": None,
            }
            for m in (members or [])
        ]
        row = {
            "id": self._new_id("group"),
            "name": name,
            "description": description or "",
            "archivedAt": None,
            "createdAt": 1,
            "updatedAt": 1,
            "members": rows,
        }
        self.groups.append(row)
        return _ok({"group": row})

    def _tool_create_schedule(self, title: str, **fields: Any) -> _Result:
        row = {
            "id": self._new_id("schedule"),
            "title": title,
            "assetId": fields.get("target_asset_id"),
            "groupId": fields.get("target_group_id"),
            "description": fields.get("description") or "",
            "timeInterval": fields.get("time_interval"),
            "timeUnit": fields.get("time_unit"),
            "timeBasis": fields.get("time_basis") or "FIXED",
            "anchorOn": fields.get("anchor_on"),
            "leadDays": fields.get("lead_days") or 0,
            "completionMode": fields.get("completion_mode") or "QUICK",
            "profileId": fields.get("profile_id"),
            "seasonBehavior": fields.get("season_behavior") or "IGNORE",
            "remindersEnabled": bool(fields.get("reminders_enabled") or False),
            "status": "ACTIVE",
        }
        self.schedules.append(row)
        return _ok({"schedule": row})

    # ---- test-side seeding --------------------------------------------------------------------

    def add_asset(
        self,
        *,
        name: str,
        archived: bool = False,
        retired: bool = False,
        parent_id: str | None = None,
    ) -> str:
        asset_id = self._new_id("asset")
        row = {
            "id": asset_id,
            "name": name,
            "status": "ARCHIVED" if archived else "ACTIVE",
            "retiredOn": "2020-01-01" if retired else None,
        }
        if parent_id is None:
            self.top_level.append(row)
        else:
            self.components.setdefault(parent_id, []).append(row)
        return asset_id

    def add_profile(self, *, asset_id: str, name: str, archived: bool = False) -> str:
        profile_id = self._new_id("profile")
        self.profiles.setdefault(asset_id, []).append(
            {"id": profile_id, "assetId": asset_id, "name": name, "archivedAt": 1 if archived else None}
        )
        return profile_id

    def add_group(self, *, name: str, member_asset_ids: list[str], archived: bool = False) -> str:
        group_id = self._new_id("group")
        members = [
            {"id": self._new_id("member"), "assetId": aid, "sortOrder": i, "addedAt": 1, "removedAt": None}
            for i, aid in enumerate(member_asset_ids)
        ]
        self.groups.append(
            {
                "id": group_id,
                "name": name,
                "description": "",
                "archivedAt": 1 if archived else None,
                "createdAt": 1,
                "updatedAt": 1,
                "members": members,
            }
        )
        return group_id

    def add_schedule(
        self,
        *,
        title: str,
        target_asset_id: str | None = None,
        target_group_id: str | None = None,
        time_interval: int = 30,
        time_unit: str = "DAY",
        time_basis: str = "FIXED",
        anchor_on: str = "2026-01-01",
        lead_days: int = 0,
        completion_mode: str = "QUICK",
        profile_id: str | None = None,
        season_behavior: str = "IGNORE",
        archived: bool = False,
    ) -> str:
        schedule_id = self._new_id("schedule")
        self.schedules.append(
            {
                "id": schedule_id,
                "title": title,
                "assetId": target_asset_id,
                "groupId": target_group_id,
                "description": "",
                "timeInterval": time_interval,
                "timeUnit": time_unit,
                "timeBasis": time_basis,
                "anchorOn": anchor_on,
                "leadDays": lead_days,
                "completionMode": completion_mode,
                "profileId": profile_id,
                "seasonBehavior": season_behavior,
                "remindersEnabled": False,
                "status": "ARCHIVED" if archived else "ACTIVE",
            }
        )
        return schedule_id


@pytest.fixture
def fake_client() -> FakeClient:
    return FakeClient()
