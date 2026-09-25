"""Spec §4.1's legacy mapping — 1.3's season fields onto 1.4's service policy — for the loader's
**comparison**, and for nothing else.

The loader keeps writing a manifest's `seasonBehavior` through the MCP's deprecated
`season_behavior` argument, and the app translates it (`apply.py` never maps anything). What changed
in 1.4 is the **read**: a schedule row now reports `servicePolicy` and `policyOffsetDays`, and 1.3's
`seasonBehavior` only as a derived projection. So `plan.py` maps the manifest's value through this
table and compares the result with the row's own policy and offset — which keeps a loaded manifest
re-planning IDENTICAL, and makes a row whose policy the triple cannot describe (`PRE_SERVICE`, a
non-zero AT_START offset, RESUME_CLAMPED) a CONFLICT rather than a false match.

The table normalises and never refuses a value (none of 1.3's values ever had behaviour); an unknown
behaviour *name* was never a value, so it is refused as 1.3 refused it. `tests/test_legacy_mapping.py`
holds this function to the repository's golden `docs/api/legacy-season-mapping.json`, the same cases
the app's migration, decoder and API legacy form are held to.
"""

from __future__ import annotations

_AT_START_OFFSET_RANGE = range(0, 366)
"""An old AT_START offset carries over only when it is 0–365 and a time rule exists; else it is 0."""


def to_policy(
    season_behavior: str | None,
    season_reentry: str | None = None,
    season_reentry_offset_days: int | None = None,
    has_time_rule: bool = True,
) -> tuple[str, int | None]:
    """`(servicePolicy, policyOffsetDays)` for 1.3's triple.

    | `season_behavior` | `season_reentry` | policy | offset |
    |---|---|---|---|
    | `IGNORE`, or absent | anything | `CONTINUOUS` | `None` |
    | `FOLLOW_ASSET` | `None`, `AT_START`, or anything else (an `MM-DD`) | `IN_SERVICE_AT_START` | the old offset if 0–365 and a time rule exists, else 0 |
    | `FOLLOW_ASSET` | `RESUME_CLAMPED` | `IN_SERVICE_RESUME_CLAMPED` | `None` |
    """
    if season_behavior is None or season_behavior == "IGNORE":
        return "CONTINUOUS", None
    if season_behavior != "FOLLOW_ASSET":
        raise ValueError(f"unknown seasonBehavior {season_behavior!r}: not in the legacy mapping")
    if season_reentry == "RESUME_CLAMPED":
        return "IN_SERVICE_RESUME_CLAMPED", None
    offset = season_reentry_offset_days
    if has_time_rule and offset is not None and offset in _AT_START_OFFSET_RANGE:
        return "IN_SERVICE_AT_START", offset
    return "IN_SERVICE_AT_START", 0
