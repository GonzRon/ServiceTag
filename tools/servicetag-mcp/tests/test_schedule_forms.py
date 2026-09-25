"""The schedule tools' two forms (spec §9.3, RS-1; master plan §12).

`create_schedule` and `update_schedule` keep 1.3's `season_behavior`, `season_reentry` and
`season_reentry_offset_days` as **deprecated arguments**. One of them makes the body the **legacy
form** — those keys only, sent exactly as given — and the app, never this server, translates it
through its one legacy mapping and refuses with its own codes. Without one, the body is the **1.4
form**. A deprecated argument and a 1.4 one together is refused here, before any request, with the
code the app would give (`LEGACY_AND_CURRENT_FIELDS_MIXED`). `update_schedule` and
`archive_schedule` carry the action flag `unlinkHealthSubject` when asked.
"""

from __future__ import annotations

import asyncio
import json

import mcp.types
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from servicetag_mcp import server as server_module

LEGACY_KEYS = ("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays")
CURRENT_KEYS = ("servicePolicy", "policyOffsetDays")


def body_of(recorded) -> dict:
    return json.loads(recorded.body.decode())


def schedule_row(**overrides) -> dict:
    """A 1.4 schedule row: the format-8 row plus the derived 1.3 triple."""
    row = {
        "id": "s1", "assetId": "a1", "groupId": None, "title": "Tune-up", "description": "",
        "timeInterval": 1, "timeUnit": "YEAR", "timeBasis": "FIXED", "anchorOn": "2026-03-01",
        "leadDays": 14, "meterDefinitionId": None, "meterInterval": None, "anchorMeter": None,
        "meterLead": None, "servicePolicy": "CONTINUOUS", "policyOffsetDays": None,
        "completionMode": "QUICK", "profileId": None, "remindersEnabled": True, "status": "ACTIVE",
        "postponedDueOn": None, "createdAt": 1, "updatedAt": 1, "ruleChangedAt": 1,
        "providers": [{"provider": "LOCAL", "enabled": True}],
        "seasonBehavior": "IGNORE", "seasonReentry": None, "seasonReentryOffsetDays": None,
    }
    row.update(overrides)
    return {"schedule": row, "state": {}, "status": "OK", "computedForOn": "2026-02-01"}


AT_START_5 = dict(
    servicePolicy="IN_SERVICE_AT_START", policyOffsetDays=5,
    seasonBehavior="FOLLOW_ASSET", seasonReentry="AT_START", seasonReentryOffsetDays=5,
)
PRE_SERVICE_14 = dict(
    servicePolicy="PRE_SERVICE", policyOffsetDays=-14,
    seasonBehavior=None, seasonReentry=None, seasonReentryOffsetDays=None,
)


def a_time_ruled_create(**policy) -> None:
    server_module.create_schedule(
        title="Tune-up", target_asset_id="a1", time_interval=1, time_unit="YEAR",
        anchor_on="2026-03-01", **policy,
    )


def _refusal(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message, "problems": []}}


# --- a deprecated argument: the legacy form, sent verbatim -----------------------------------------


def test_a_deprecated_argument_sends_a_legacy_body_verbatim(paired) -> None:
    """No `servicePolicy` or `policyOffsetDays` key, and every value exactly as given — an `MM-DD`
    re-entry and an out-of-range offset included, both of which the app's mapping normalises. This
    server translates nothing."""
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())
    server_module.update_schedule(
        schedule_id="s1", season_behavior="FOLLOW_ASSET", season_reentry="04-01",
    )
    body = body_of(paired.last())
    assert body["seasonBehavior"] == "FOLLOW_ASSET"
    assert body["seasonReentry"] == "04-01"
    assert body["seasonReentryOffsetDays"] is None, "the rest of the triple is the row's own"
    for key in CURRENT_KEYS:
        assert key not in body, key

    # Overlaid on the row's derived triple: only the offset changes, out of range and untranslated.
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**AT_START_5))
    server_module.update_schedule(schedule_id="s1", season_reentry_offset_days=400)
    body = body_of(paired.last())
    assert [body[k] for k in LEGACY_KEYS] == ["FOLLOW_ASSET", "AT_START", 400]
    for key in CURRENT_KEYS:
        assert key not in body, key

    # Clearing a deprecated field by name is a deprecated argument too.
    server_module.update_schedule(schedule_id="s1", clear_fields=["season_reentry"])
    body = body_of(paired.last())
    assert "seasonReentry" in body and body["seasonReentry"] is None
    assert body["seasonBehavior"] == "FOLLOW_ASSET"
    for key in CURRENT_KEYS:
        assert key not in body, key

    # And on a create: the deprecated keys only, nothing added.
    a_time_ruled_create(season_behavior="FOLLOW_ASSET", season_reentry="RESUME_CLAMPED")
    body = body_of(paired.last())
    assert paired.last().path == "/v1/schedules"
    assert body["seasonBehavior"] == "FOLLOW_ASSET"
    assert body["seasonReentry"] == "RESUME_CLAMPED"
    for key in CURRENT_KEYS + ("seasonReentryOffsetDays",):
        assert key not in body, key


def test_new_arguments_send_the_14_form(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())
    server_module.update_schedule(schedule_id="s1", service_policy="PRE_SERVICE", policy_offset_days=-14)
    body = body_of(paired.last())
    assert body["servicePolicy"] == "PRE_SERVICE"
    assert body["policyOffsetDays"] == -14
    for key in LEGACY_KEYS:
        assert key not in body, key

    # One of the two alone overlays the other from the row.
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**AT_START_5))
    server_module.update_schedule(schedule_id="s1", policy_offset_days=30)
    body = body_of(paired.last())
    assert (body["servicePolicy"], body["policyOffsetDays"]) == ("IN_SERVICE_AT_START", 30)

    # No argument at all: the 1.4 form with every value the row already has — an idempotent PATCH.
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**PRE_SERVICE_14))
    server_module.update_schedule(schedule_id="s1")
    body = body_of(paired.last())
    row = schedule_row(**PRE_SERVICE_14)["schedule"]
    assert (body["servicePolicy"], body["policyOffsetDays"]) == ("PRE_SERVICE", -14)
    assert body["targetAssetId"] == row["assetId"] and body["targetGroupId"] is None
    for key in ("title", "timeInterval", "timeUnit", "anchorOn", "leadDays", "providers"):
        assert body[key] == row[key], key
    for key in LEGACY_KEYS:
        assert key not in body, key

    # A create: the policy keys it was given, and no legacy key.
    a_time_ruled_create(service_policy="IN_SERVICE_AT_START")
    body = body_of(paired.last())
    assert body["servicePolicy"] == "IN_SERVICE_AT_START"
    assert "policyOffsetDays" not in body
    for key in LEGACY_KEYS:
        assert key not in body, key


def test_a_create_with_neither_form_sends_neither_key(paired) -> None:
    """Which the app reads as the legacy form with `seasonBehavior` IGNORE → `CONTINUOUS`, the same
    answer as the 1.4 default."""
    a_time_ruled_create()
    body = body_of(paired.last())
    for key in CURRENT_KEYS + LEGACY_KEYS:
        assert key not in body, key


def test_a_422_from_a_legacy_body_is_a_tool_error_with_its_code(paired) -> None:
    """The app refuses a legacy edit of a `PRE_SERVICE` schedule. This server does not pre-check
    it: it sends the legacy body and surfaces the app's own code."""
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row(**PRE_SERVICE_14))
    paired.reply(
        "PATCH", "/v1/schedules/s1", 422,
        _refusal("LEGACY_WRITE_CANNOT_REPRESENT", "the legacy form cannot describe this schedule"),
    )
    with pytest.raises(ToolError, match="LEGACY_WRITE_CANNOT_REPRESENT") as raised:
        server_module.update_schedule(schedule_id="s1", season_behavior="IGNORE")
    assert "422" in str(raised.value)
    sent = body_of(paired.last())
    assert paired.last().method == "PATCH"
    assert sent["seasonBehavior"] == "IGNORE"
    assert "servicePolicy" not in sent

    paired.reply(
        "POST", "/v1/schedules", 422,
        _refusal("SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET", "a group target is CONTINUOUS only"),
    )
    with pytest.raises(ToolError, match="SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET"):
        server_module.create_schedule(
            title="Rinse", target_group_id="g1", time_interval=1, time_unit="MONTH",
            anchor_on="2026-03-01", season_behavior="FOLLOW_ASSET",
        )


# --- mixing is refused here, before any request ------------------------------------------------


@pytest.mark.parametrize(
    "arguments",
    [
        {"season_behavior": "IGNORE", "service_policy": "CONTINUOUS"},
        {"season_reentry_offset_days": 3, "policy_offset_days": 3},
        {"season_reentry": "AT_START", "service_policy": "IN_SERVICE_AT_START"},
        {"clear_fields": ["season_reentry"], "service_policy": "PRE_SERVICE"},
        {"clear_fields": ["policy_offset_days"], "season_behavior": "FOLLOW_ASSET"},
        {"clear_fields": ["season_reentry_offset_days", "policy_offset_days"]},
    ],
    ids=["behaviour+policy", "both-offsets", "reentry+policy", "clear-reentry+policy",
         "clear-offset+behaviour", "clear-both"],
)
def test_mixing_is_refused_before_any_http(paired, arguments) -> None:
    with pytest.raises(ToolError, match="LEGACY_AND_CURRENT_FIELDS_MIXED"):
        server_module.update_schedule(schedule_id="s1", **arguments)
    assert paired.requests == [], "no schema check, no read, no write"


def test_mixing_on_a_create_is_refused_before_any_http(paired) -> None:
    with pytest.raises(ToolError, match="LEGACY_AND_CURRENT_FIELDS_MIXED"):
        a_time_ruled_create(season_behavior="FOLLOW_ASSET", policy_offset_days=0)
    assert paired.requests == []


def test_the_mixed_refusal_reaches_a_client_through_the_sdk(paired) -> None:
    params = mcp.types.CallToolRequestParams.model_validate(
        {"name": "update_schedule",
         "arguments": {"schedule_id": "s1", "season_behavior": "IGNORE",
                       "service_policy": "CONTINUOUS"}}
    )
    result = asyncio.run(server_module.mcp._handle_call_tool(None, params))
    assert result.is_error is True
    assert "LEGACY_AND_CURRENT_FIELDS_MIXED" in result.content[0].text
    assert paired.requests == []


# --- the action flag ----------------------------------------------------------------------------


def test_unlink_health_subject_on_update_and_archive(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())

    server_module.update_schedule(
        schedule_id="s1", target_group_id="g1", clear_fields=["target_asset_id"],
        unlink_health_subject=True,
    )
    assert body_of(paired.last())["unlinkHealthSubject"] is True

    for unset in (None, False):
        server_module.update_schedule(schedule_id="s1", title="x", unlink_health_subject=unset)
        assert "unlinkHealthSubject" not in body_of(paired.last()), unset

    server_module.archive_schedule(schedule_id="s1", unlink_health_subject=True)
    assert paired.last().path == "/v1/schedules/s1/archive"
    assert body_of(paired.last()) == {"archived": True, "unlinkHealthSubject": True}

    server_module.archive_schedule(schedule_id="s1")
    assert body_of(paired.last()) == {"archived": True}


def test_without_the_flag_the_apps_link_guard_arrives_with_its_code(paired) -> None:
    paired.reply("GET", "/v1/schedules/s1", 200, schedule_row())
    paired.reply(
        "POST", "/v1/schedules/s1/archive", 422,
        _refusal("SCHEDULE_DRIVES_HEALTH_SUBJECT", "this schedule drives a live health subject"),
    )
    with pytest.raises(ToolError, match="SCHEDULE_DRIVES_HEALTH_SUBJECT"):
        server_module.archive_schedule(schedule_id="s1")


def test_create_schedule_takes_no_action_flag() -> None:
    """The flag is an action on an existing schedule; the app refuses it on a create."""
    tool = server_module.mcp._tool_manager.get_tool("create_schedule")
    assert "unlink_health_subject" not in tool.parameters.get("properties", {})
