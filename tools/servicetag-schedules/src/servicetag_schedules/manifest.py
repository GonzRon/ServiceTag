"""The validated manifest model: the v1 contract in
`docs/superpowers/plans/2026-09-23-servicetag-stage-b/plan.md` decoded into a tree of frozen
dataclasses.

This module validates *shape* only — types, enum membership, well-formed dates, unknown keys — the
same way `servicetag_bundle.source` validates a backup source. What each entry *resolves to* on the
phone (an asset, a profile, a group, an existing schedule) and whether two entries collide with each
other are `plan.plan`'s job, not this module's: those checks need either an `Inventory` or a look
across sibling entries, and a `ManifestError` here would abort before a plan could even say *which*
entries are the problem. So a manifest with two groups sharing one `key`, for instance, loads fine —
`plan.plan` reports `ERROR` on both.

No clock, no environment, no filesystem access beyond the one path `load` is given.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

TIME_UNITS = ("DAY", "WEEK", "MONTH", "YEAR")
TIME_BASES = ("FIXED", "COMPLETION")
COMPLETION_MODES = ("QUICK", "FORM")
SEASON_BEHAVIORS = ("IGNORE", "FOLLOW_ASSET")

_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

_TOP_KEYS = {"manifestVersion", "asOf", "groups", "schedules"}
_GROUP_KEYS = {"key", "name", "description", "members"}
_MEMBER_KEYS = {"asset"}
_SCHEDULE_KEYS = {
    "key", "title", "target", "description", "time", "leadDays", "completionMode", "profile",
    "seasonBehavior", "remindersEnabled", "tags", "source",
}
_TARGET_KEYS = {"asset", "group"}
_TIME_KEYS = {"interval", "unit", "basis", "anchorOn"}
_SOURCE_KEYS = {"todoistId", "cadence"}


class ManifestError(ValueError):
    """A manifest document failed shape validation, or (raised by `load` itself) could not even be
    read or parsed. `path` is the JSON path of the offending value, dotted-and-bracketed like
    `schedules[1].time.anchorOn` (`""` for the document itself) — except when the failure is in
    reading or parsing the file rather than in its contents, in which case `path` is the manifest
    file's own filesystem path instead."""

    def __init__(self, path: str, message: str) -> None:
        super().__init__(f"{path}: {message}" if path else message)
        self.path = path
        self.message = message


# ---- the model -----------------------------------------------------------------------------

@dataclass(frozen=True)
class Member:
    asset: str


@dataclass(frozen=True)
class Group:
    key: str
    name: str
    description: str | None
    members: tuple[Member, ...] = ()


@dataclass(frozen=True)
class Time:
    interval: int
    unit: str
    basis: str
    anchor_on: str


@dataclass(frozen=True)
class Source:
    todoist_id: str | None = None
    cadence: str | None = None


@dataclass(frozen=True)
class Schedule:
    key: str
    title: str
    target_asset: str | None
    target_group: str | None
    description: str | None
    time: Time
    lead_days: int
    completion_mode: str
    profile: str | None
    season_behavior: str
    reminders_enabled: bool
    tags: tuple[str, ...] = ()
    source: Source = field(default_factory=Source)

    @property
    def is_group_target(self) -> bool:
        return self.target_group is not None


@dataclass(frozen=True)
class Manifest:
    manifest_version: int
    as_of: str
    groups: tuple[Group, ...] = ()
    schedules: tuple[Schedule, ...] = ()


# ---- small parsing helpers -------------------------------------------------------------------

def _join(path: str, key: str) -> str:
    return f"{path}.{key}" if path else key


def _check_keys(obj: Any, allowed: set[str], required: set[str], path: str) -> None:
    if not isinstance(obj, dict):
        raise ManifestError(path, "must be an object")
    unknown = set(obj) - allowed
    if unknown:
        raise ManifestError(_join(path, sorted(unknown)[0]), "unknown key")
    missing = required - set(obj)
    if missing:
        raise ManifestError(_join(path, sorted(missing)[0]), "missing required key")


def _require_str(obj: dict, key: str, path: str) -> str:
    value = obj.get(key)
    p = _join(path, key)
    if not isinstance(value, str):
        raise ManifestError(p, "must be a string")
    if not value.strip():
        raise ManifestError(p, "must not be blank")
    return value


def _optional_str(obj: dict, key: str, path: str) -> str | None:
    if key not in obj or obj[key] is None:
        return None
    value = obj[key]
    if not isinstance(value, str):
        raise ManifestError(_join(path, key), "must be a string or null")
    return value


def _require_bool(obj: dict, key: str, path: str) -> bool:
    value = obj.get(key)
    if not isinstance(value, bool):
        raise ManifestError(_join(path, key), "must be a boolean")
    return value


def _require_int(obj: dict, key: str, path: str, *, minimum: int) -> int:
    value = obj.get(key)
    p = _join(path, key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ManifestError(p, "must be an integer")
    if value < minimum:
        raise ManifestError(p, f"must be at least {minimum}")
    return value


def _require_enum(obj: dict, key: str, path: str, choices: tuple[str, ...]) -> str:
    value = obj.get(key)
    p = _join(path, key)
    if not isinstance(value, str) or value not in choices:
        raise ManifestError(p, f"must be one of {choices}")
    return value


def _require_date(obj: dict, key: str, path: str) -> str:
    value = obj.get(key)
    p = _join(path, key)
    if not isinstance(value, str) or not _ISO_DATE.match(value):
        raise ManifestError(p, "must be an ISO calendar date YYYY-MM-DD")
    year, month, day = int(value[0:4]), int(value[5:7]), int(value[8:10])
    try:
        date(year, month, day)
    except ValueError:
        raise ManifestError(p, "must be a real calendar date") from None
    return value


def _string_list(obj: dict, key: str, path: str) -> tuple[str, ...]:
    if key not in obj:
        return ()
    value = obj[key]
    p = _join(path, key)
    if not isinstance(value, list):
        raise ManifestError(p, "must be a list of strings")
    out = []
    for i, item in enumerate(value):
        if not isinstance(item, str) or not item.strip():
            raise ManifestError(f"{p}[{i}]", "must be a non-blank string")
        out.append(item)
    return tuple(out)


# ---- entry parsers ----------------------------------------------------------------------------

def _parse_member(obj: Any, path: str) -> Member:
    _check_keys(obj, _MEMBER_KEYS, _MEMBER_KEYS, path)
    return Member(asset=_require_str(obj, "asset", path))


def _parse_group(obj: Any, path: str) -> Group:
    _check_keys(obj, _GROUP_KEYS, {"key", "name", "members"}, path)
    members_raw = obj["members"]
    if not isinstance(members_raw, list):
        raise ManifestError(_join(path, "members"), "must be a list")
    members = tuple(
        _parse_member(m, f"{path}.members[{i}]") for i, m in enumerate(members_raw)
    )
    return Group(
        key=_require_str(obj, "key", path),
        name=_require_str(obj, "name", path),
        description=_optional_str(obj, "description", path),
        members=members,
    )


def _parse_target(obj: Any, path: str) -> tuple[str | None, str | None]:
    if not isinstance(obj, dict):
        raise ManifestError(path, "must be an object")
    unknown = set(obj) - _TARGET_KEYS
    if unknown:
        raise ManifestError(_join(path, sorted(unknown)[0]), "unknown key")
    present = _TARGET_KEYS & set(obj)
    if len(present) != 1:
        raise ManifestError(path, "must name exactly one of asset or group")
    if "asset" in present:
        return _require_str(obj, "asset", path), None
    return None, _require_str(obj, "group", path)


def _parse_time(obj: Any, path: str) -> Time:
    _check_keys(obj, _TIME_KEYS, _TIME_KEYS, path)
    return Time(
        interval=_require_int(obj, "interval", path, minimum=1),
        unit=_require_enum(obj, "unit", path, TIME_UNITS),
        basis=_require_enum(obj, "basis", path, TIME_BASES),
        anchor_on=_require_date(obj, "anchorOn", path),
    )


def _parse_source(obj: Any, path: str) -> Source:
    if obj is None:
        return Source()
    _check_keys(obj, _SOURCE_KEYS, set(), path)
    return Source(
        todoist_id=_optional_str(obj, "todoistId", path),
        cadence=_optional_str(obj, "cadence", path),
    )


def _parse_schedule(obj: Any, path: str) -> Schedule:
    _check_keys(
        obj, _SCHEDULE_KEYS,
        {"key", "title", "target", "time", "leadDays", "completionMode", "seasonBehavior", "remindersEnabled"},
        path,
    )
    target_asset, target_group = _parse_target(obj["target"], _join(path, "target"))
    return Schedule(
        key=_require_str(obj, "key", path),
        title=_require_str(obj, "title", path),
        target_asset=target_asset,
        target_group=target_group,
        description=_optional_str(obj, "description", path),
        time=_parse_time(obj["time"], _join(path, "time")),
        lead_days=_require_int(obj, "leadDays", path, minimum=0),
        completion_mode=_require_enum(obj, "completionMode", path, COMPLETION_MODES),
        profile=_optional_str(obj, "profile", path),
        season_behavior=_require_enum(obj, "seasonBehavior", path, SEASON_BEHAVIORS),
        reminders_enabled=_require_bool(obj, "remindersEnabled", path),
        tags=_string_list(obj, "tags", path),
        source=_parse_source(obj.get("source"), _join(path, "source")),
    )


# ---- public entry points -----------------------------------------------------------------------

def parse(obj: Any) -> Manifest:
    """Validate a decoded JSON document and return its `Manifest` tree, or raise `ManifestError`."""
    _check_keys(obj, _TOP_KEYS, _TOP_KEYS, "")

    version = obj["manifestVersion"]
    if version != 1:
        raise ManifestError("manifestVersion", "must be 1")

    as_of = _require_date(obj, "asOf", "")

    groups_raw = obj["groups"]
    if not isinstance(groups_raw, list):
        raise ManifestError("groups", "must be a list")
    groups = tuple(_parse_group(g, f"groups[{i}]") for i, g in enumerate(groups_raw))

    schedules_raw = obj["schedules"]
    if not isinstance(schedules_raw, list):
        raise ManifestError("schedules", "must be a list")
    schedules = tuple(_parse_schedule(s, f"schedules[{i}]") for i, s in enumerate(schedules_raw))

    return Manifest(manifest_version=version, as_of=as_of, groups=groups, schedules=schedules)


def load(path: Path | str) -> Manifest:
    """Read and validate a manifest document from `path`. A missing/unreadable file or malformed
    JSON is reported the same way a validation failure is — as a `ManifestError` naming `path` — so
    a caller (the CLI) needs only one `except ManifestError` to report every way this can fail."""
    path = Path(path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise ManifestError(str(path), e.strerror or str(e)) from e
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise ManifestError(str(path), f"not valid JSON: {e}") from e
    return parse(obj)
