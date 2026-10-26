"""#124 (C44; row 153) — `IN_SERVICE_AFTER_OPENING_SERVICE` through `create_schedule` and `update_schedule`.

The value and its offset travel unchanged in the 1.4 form; this server keeps no list of policies, so a name it has
never heard of goes to the app as given and the app's refusal comes back with its code. The two docstrings name the
value, its offset, its two `policyReason`s and the refusals; `import_merge` and the README name the merge reason
`SEASON_START_SERVICE_MISSING` (N2b review N-5). Every name is fictional.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from conftest import schedule_row
from servicetag_mcp import server as server_module

OPENING = "IN_SERVICE_AFTER_OPENING_SERVICE"
LEGACY_KEYS = ("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays")
OPENING_7 = dict(
    servicePolicy=OPENING, policyOffsetDays=7, seasonBehavior=None, seasonReentry=None, seasonReentryOffsetDays=None,
)
README = Path(__file__).resolve().parent.parent / "README.md"


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def flat(text: str | None) -> str:
    return " ".join((text or "").split())


def a_create(**policy) -> None:
    server_module.create_schedule(
        title="Example filter check", target_asset_id="example-heater", time_interval=1, time_unit="MONTH",
        anchor_on="2026-04-01", **policy,
    )


def test_create_schedule_sends_the_value_and_its_offset_unchanged(paired) -> None:
    a_create(service_policy=OPENING, policy_offset_days=7)
    body = body_of(paired.last())
    assert (body["servicePolicy"], body["policyOffsetDays"]) == (OPENING, 7)
    for key in LEGACY_KEYS:
        assert key not in body, key

    # Omitted, the offset is not sent: the app reads it as 0.
    a_create(service_policy=OPENING)
    body = body_of(paired.last())
    assert body["servicePolicy"] == OPENING and "policyOffsetDays" not in body


def test_update_schedule_keeps_and_sets_the_value_and_its_offset(paired) -> None:
    # An unrelated edit of a dependent sends its value and offset back as the row has them.
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**OPENING_7))
    server_module.update_schedule(schedule_id="s1", description="Clean the burn pot first")
    body = body_of(paired.last())
    assert (body["servicePolicy"], body["policyOffsetDays"]) == (OPENING, 7)
    for key in LEGACY_KEYS:
        assert key not in body, key

    # A new offset alone is laid over the row's value; and the value can be chosen on any schedule.
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**OPENING_7))
    server_module.update_schedule(schedule_id="s1", policy_offset_days=14)
    assert (body_of(paired.last())["servicePolicy"], body_of(paired.last())["policyOffsetDays"]) == (OPENING, 14)

    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())
    server_module.update_schedule(schedule_id="s1", service_policy=OPENING, policy_offset_days=0)
    assert (body_of(paired.last())["servicePolicy"], body_of(paired.last())["policyOffsetDays"]) == (OPENING, 0)


def test_no_client_side_list_the_app_validates_and_refuses_with_its_code(paired) -> None:
    a_create(service_policy="IN_SERVICE_AFTER_SOME_LATER_EVENT", policy_offset_days=3)
    assert body_of(paired.last())["servicePolicy"] == "IN_SERVICE_AFTER_SOME_LATER_EVENT"

    paired.reply(
        "POST", "/v1/schedules", 409,
        {"error": {
            "code": "SEASON_START_REFUSED",
            "message": "Example Heater has no Season Start service to count from",
            "problems": ["SeasonStartRefused(reason=NO_SEASON_START_SERVICE, assetId=example-heater)"],
        }},
    )
    with pytest.raises(ToolError, match="SEASON_START_REFUSED"):
        a_create(service_policy=OPENING, policy_offset_days=7)


def test_the_docstrings_name_the_value_its_reasons_and_its_refusals() -> None:
    for tool in (server_module.create_schedule, server_module.update_schedule):
        doc = flat(tool.__doc__)
        for word in (
            f"`{OPENING}`", "`AWAITING_OPENING_SERVICE`", "`OPENING_SERVICE`", "`SEASON_START_REFUSED`",
            "`NO_SEASON_START_SERVICE`", "`COUNTS_FROM_ITSELF`", "`YEAR_ROUND`", "`SEASON_START_STRANDED`",
            "`HAS_DEPENDENT_SCHEDULES`",
        ):
            assert word in doc, (tool.__name__, word)


def test_import_merge_and_the_readme_name_the_missing_season_start_service() -> None:
    assert "`SEASON_START_SERVICE_MISSING`" in flat(server_module.import_merge.__doc__)
    readme = README.read_text(encoding="utf-8")
    merge = flat(readme.split("### `import_merge`", 1)[1].split("\n## ", 1)[0])
    assert "`SEASON_START_SERVICE_MISSING`" in merge
    forms = flat(readme.split("### The schedule's two forms", 1)[1].split("\n### ", 1)[0])
    for word in (f"`{OPENING}`", "`SEASON_START_REFUSED`", "`NO_SEASON_START_SERVICE`", "`COUNTS_FROM_ITSELF`"):
        assert word in forms, word
