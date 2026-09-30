"""A `FakeClient`: an in-memory phone answering exactly the tools `servicetag_schedules` calls
(`pair`, `status`, `list_assets`, `list_profiles`, `list_groups`, `list_schedules`, `create_group`,
`create_schedule`), shaped like the real ones' JSON (`docs/api/v1.md`). `status` answers a
ServiceTag 1.4.0 app (schema 8) unless a test edits `status_answer`. It implements the same
`call_tool(name, arguments)` coroutine `mcp.Client` offers, so `phone.call_tool` and everything
built on it treat a `FakeClient` exactly like a real in-process client. `calls` records every
`(name, arguments)` in call order, which is what the apply tests check (groups before schedules,
the right ids on the wire).

Its schedule rows are **1.4 rows** (ServiceTag 1.4.0's `/v1`): `servicePolicy` and
`policyOffsetDays`, plus 1.3's `seasonBehavior`/`seasonReentry`/`seasonReentryOffsetDays` as the
**derived** compatibility triple. A `create_schedule` sent with the deprecated `season_behavior`
argument is translated the way the app translates the legacy form — by this fixture's own two-line
table (`_PHONE_LEGACY_FORM`), never by the loader's `legacy_mapping`, so a re-plan proof here never
grades the code under test against itself.
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


_PHONE_LEGACY_FORM: dict[str, tuple[str, int | None]] = {
    "IGNORE": ("CONTINUOUS", None),
    "FOLLOW_ASSET": ("IN_SERVICE_AT_START", 0),
}
"""What the app makes of the only legacy bodies the loader sends: a `seasonBehavior` with no
re-entry and no offset, on a schedule with a time rule (spec §4.1)."""


_SUCCESSOR_WIRE: dict[str, str] = {
    "category": "category", "manufacturer": "manufacturer", "model": "model", "serial_number": "serialNumber",
    "purchase_on": "purchaseOn", "in_service_on": "inServiceOn", "purchase_price_minor": "purchasePriceMinor",
    "currency": "currency", "vendor": "vendor", "location": "location", "warranty_expires_on": "warrantyExpiresOn",
    "warranty_notes": "warrantyNotes", "parent_asset_id": "parentAssetId",
}
"""`replace_asset`'s successor arguments as the new asset's row names them — this fixture's own table, never the
loader's."""


def _as_stored(argument: str, value: Any, labels: list[str]) -> Any:
    """What the phone stores for a successor argument, by this fixture's own rule (never the loader's): text is
    trimmed (`AssetCommands.trimmed`); a blank `currency` is null (`blankToNull`); a category has its whitespace
    runs collapsed and, when its case-insensitive spelling matches a built-in, takes the built-in's label
    (`PromoteCategory`)."""
    if not isinstance(value, str):
        return value
    text = value.strip()
    if argument == "currency":
        return text or None
    if argument == "category":
        text = " ".join(text.split())
        return next((label for label in labels if label.lower() == text.lower()), text)
    return text


def _derived_triple(policy: str, offset: int | None) -> dict[str, Any]:
    """Spec §9.1's reverse projection, as a 1.4 row reports it (all null for `PRE_SERVICE`)."""
    behavior, reentry, reentry_offset = {
        "CONTINUOUS": ("IGNORE", None, None),
        "IN_SERVICE_AT_START": ("FOLLOW_ASSET", "AT_START", offset),
        "IN_SERVICE_RESUME_CLAMPED": ("FOLLOW_ASSET", "RESUME_CLAMPED", None),
        "PRE_SERVICE": (None, None, None),
    }[policy]
    return {
        "servicePolicy": policy,
        "policyOffsetDays": offset,
        "seasonBehavior": behavior,
        "seasonReentry": reentry,
        "seasonReentryOffsetDays": reentry_offset,
    }


class FakeClient:
    def __init__(self) -> None:
        self.top_level: list[dict[str, Any]] = []
        self.components: dict[str, list[dict[str, Any]]] = {}
        self.profiles: dict[str, list[dict[str, Any]]] = {}
        self.groups: list[dict[str, Any]] = []
        self.schedules: list[dict[str, Any]] = []
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.status_answer: dict[str, Any] = {
            "appVersion": "1.4.0",
            "apiVersion": 1,
            "schemaVersion": 8,
            "backupFormatVersion": 8,
            "counts": {},
        }
        self._ids = itertools.count(1)
        self._fail_next: dict[str, str] = {}
        # #92: the replace tools (`get_asset_succession`, `get_asset`, `get_replace_offer`, `replace_asset`).
        self.successions: list[dict[str, Any]] = []
        self.tags: dict[str, list[dict[str, Any]]] = {}
        self.held: set[str] = set()
        self.replace_problems: dict[str, list[dict[str, Any]]] = {}
        self.replace_apply_error: str | None = None  # answered to the next apply, after its plan
        self.replace_apply_unknown = False  # the next apply replaces, then answers UNKNOWN
        self.before_replace_apply: Any = None  # a callable run once as the next apply arrives (a race)
        self.category_labels: list[str] = ["Pump", "Hot tub", "HVAC"]  # built-ins the phone promotes to

    def _new_id(self, prefix: str) -> str:
        return f"{prefix}-{next(self._ids)}"

    def fail_next(self, name: str, message: str) -> None:
        """The next call to tool `name` answers `is_error` with `message` instead of running its
        handler -- for pinning what a phone-side refusal on a write looks like to a caller."""
        self._fail_next[name] = message

    # ---- the mcp.Client shape ---------------------------------------------------------------

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> _Result:
        self.calls.append((name, dict(arguments)))
        if name in self._fail_next:
            return _err(self._fail_next.pop(name))
        handler = getattr(self, f"_tool_{name}", None)
        if handler is None:
            return _err(f"FakeClient has no handler for {name!r}")
        try:
            return handler(**arguments)
        except TypeError as e:
            return _err(str(e))

    def _tool_pair(self, code: str) -> _Result:
        return _ok("paired")

    def _tool_status(self) -> _Result:
        return _ok(dict(self.status_answer))

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
        if fields.get("service_policy") is not None or fields.get("policy_offset_days") is not None:
            policy = fields.get("service_policy") or "CONTINUOUS"
            offset = fields.get("policy_offset_days")
            if policy == "IN_SERVICE_AT_START" and offset is None:
                offset = 0
        else:
            policy, offset = _PHONE_LEGACY_FORM[fields.get("season_behavior") or "IGNORE"]
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
            "remindersEnabled": bool(fields.get("reminders_enabled") or False),
            "status": "ACTIVE",
            # Stored exactly as sent, and an absent list as none — what a 1.4.0 app did (#80). The
            # loader must never lean on the app's default, so this fake does not supply one.
            "providers": list(fields.get("providers") or []),
            **_derived_triple(policy, offset),
        }
        self.schedules.append(row)
        return _ok({"schedule": row})

    # ---- #92: the replace tools, shaped like servicetag-mcp's ------------------------------------

    def _asset_row(self, asset_id: str) -> dict[str, Any] | None:
        rows = self.top_level + [row for rows in self.components.values() for row in rows]
        return next((row for row in rows if row["id"] == asset_id), None)

    def _succession(self, side: str, asset_id: str) -> dict[str, Any] | None:
        return next((row for row in self.successions if row[side] == asset_id), None)

    def _tool_get_asset_succession(self, asset_id: str) -> _Result:
        return _ok({"replaces": self._succession("successorAssetId", asset_id),
                    "replacedBy": self._succession("predecessorAssetId", asset_id)})

    def _tool_get_asset(self, asset_id: str) -> _Result:
        row = self._asset_row(asset_id)
        return _ok({"asset": dict(row)}) if row is not None else _err("404 no_such_asset")

    def _offer(self, asset_id: str) -> dict[str, Any]:
        replaced_by, held = self._succession("predecessorAssetId", asset_id), asset_id in self.held
        return {
            "eligible": not held and replaced_by is None, "held": held, "replacedBy": replaced_by,
            "predecessor": self._asset_row(asset_id),
            "schedules": [row for row in self.schedules if row["assetId"] == asset_id and row["status"] != "ARCHIVED"],
            "groups": [g for g in self.groups if g["archivedAt"] is None
                       and any(m["assetId"] == asset_id and m["removedAt"] is None for m in g["members"])],
            "tags": list(self.tags.get(asset_id, [])),
        }

    def _tool_get_replace_offer(self, asset_id: str) -> _Result:
        return _ok(self._offer(asset_id))

    def _tool_replace_asset(self, asset_id: str, name: str, plan_only: bool = True, **draft: Any) -> _Result:
        if plan_only is False and self.before_replace_apply is not None:
            race, self.before_replace_apply = self.before_replace_apply, None
            race()
        offer = self._offer(asset_id)
        problems = list(self.replace_problems.get(asset_id, []))
        offered = {tag["id"] for tag in offer["tags"]}
        problems += [{"code": "REPLACE_NOT_OFFERED", "field": "movedTagIds", "problem": "not offered"}
                     for tag_id in draft.get("moved_tag_ids") or [] if tag_id not in offered]
        blocked = "ASSET_ALREADY_REPLACED" if offer["replacedBy"] else "asset_transferred_out" if offer["held"] else None
        plan = {"eligible": offer["eligible"], "blockedBy": blocked, "replacedOn": "2026-09-30",
                "problems": problems, "sourcesDigest": "d" * 64}
        if plan_only is not False:
            return _ok(plan)
        if offer["replacedBy"] is not None:  # the tool's own (name-only) IDENTICAL rule
            successor = self._asset_row(offer["replacedBy"]["successorAssetId"]) or {}
            if str(successor.get("name", "")).strip() == name.strip():
                return _ok({"decision": "IDENTICAL", "successor": successor})
            return _err("409 ASSET_ALREADY_REPLACED: this asset was already replaced by "
                        f"{successor.get('id')!r}, which carries another name, so nothing was replaced")
        if blocked is not None or problems:
            return _err("the phone's replace plan is not clean, so nothing was applied")
        if self.replace_apply_error is not None:
            message, self.replace_apply_error = self.replace_apply_error, None
            return _err(message)
        successor_id = self.add_asset(name=name)
        successor = self._asset_row(successor_id)
        assert successor is not None
        successor.update({_SUCCESSOR_WIRE[k]: _as_stored(k, v, self.category_labels)
                          for k, v in draft.items() if k in _SUCCESSOR_WIRE})
        predecessor = self._asset_row(asset_id)
        assert predecessor is not None
        predecessor["retiredOn"] = predecessor["retiredOn"] or draft.get("retired_on")
        succession = {"id": self._new_id("succession"), "predecessorAssetId": asset_id,
                      "successorAssetId": successor_id, "replacedOn": "2026-09-30", "createdAt": 1}
        self.successions.append(succession)
        moved = set(draft.get("moved_tag_ids") or [])
        kept = [tag for tag in self.tags.get(asset_id, []) if tag["id"] not in moved]
        self.tags[successor_id] = [tag for tag in self.tags.get(asset_id, []) if tag["id"] in moved]
        self.tags[asset_id] = kept
        if self.replace_apply_unknown:
            self.replace_apply_unknown = False
            return _ok({"decision": "UNKNOWN", "next": "read get_asset_succession"})
        return _ok({"decision": "CREATED", "predecessor": predecessor, "successor": successor,
                    "succession": succession})

    def add_tag(self, *, asset_id: str, label: str) -> str:
        tag_id = self._new_id("tag")
        self.tags.setdefault(asset_id, []).append({"id": tag_id, "label": label, "assetId": asset_id})
        return tag_id

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
        season_behavior: str | None = None,
        service_policy: str | None = None,
        policy_offset_days: int | None = None,
        archived: bool = False,
    ) -> str:
        """Seed a 1.4 row. `service_policy`/`policy_offset_days` set the policy directly;
        `season_behavior` alone (the shipped seeding) is translated as the app translates a legacy
        body; neither is `CONTINUOUS`."""
        if service_policy is None:
            service_policy, policy_offset_days = _PHONE_LEGACY_FORM[season_behavior or "IGNORE"]
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
                "remindersEnabled": False,
                "status": "ARCHIVED" if archived else "ACTIVE",
                **_derived_triple(service_policy, policy_offset_days),
            }
        )
        return schedule_id


@pytest.fixture
def fake_client() -> FakeClient:
    return FakeClient()
